package com.termux.app.filemanager.ui;

import android.app.Activity;
import android.app.Application;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.ViewConfiguration;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.termux.R;
import com.termux.app.editor.EditorController;
import com.termux.app.session.WorkspaceSessionManager;
import com.termux.app.filemanager.storage.StorageRegistry;
import com.termux.app.filemanager.storage.StorageTreePicker;
import com.termux.app.filemanager.storage.model.StorageCapability;
import com.termux.app.filemanager.storage.model.StorageEntry;
import com.termux.app.filemanager.storage.model.StorageProvider;
import com.termux.app.filemanager.storage.operations.FileOperationOptions;
import com.termux.app.filemanager.storage.operations.FileOperationResult;
import com.termux.app.filemanager.storage.operations.FileOperationService;
import com.termux.app.filemanager.storage.provider.SafTreeStorageProvider;
import com.termux.app.filemanager.storage.selection.StorageSelection;
import com.termux.app.filemanager.storage.transfer.StorageTransferService;
import com.termux.app.filemanager.storage.transfer.TransferOptions;
import com.termux.app.filemanager.storage.transfer.TransferResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sidebar File Explorer for Yummy. The storage rail selects a provider while the
 * adjacent directory tree lazily loads child directories through StorageProvider.
 * The UI never converts SAF document IDs into File paths.
 */
public final class FileManagerController {

    public static final int REQUEST_ADD_STORAGE = 4284;
    private static final String PREFS_NAME = "yummy_file_manager";
    private static final String KEY_FAVORITES = "favorites_v1";
    private static final String KEY_RECENTS = "recents_v1";
    private static final String KEY_SHOW_HIDDEN = "show_hidden_v1";
    private static final int MAX_OPEN_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SEARCH_ENTRIES = 8_000;
    private static final int MAX_RECENTS = 16;

    private Typeface nunitoRegular;
    private Typeface nunitoBold;

    private Typeface nunitoTypeface(boolean bold) {
        try {
            if (bold) {
                if (nunitoBold == null) {
                    nunitoBold = Typeface.createFromAsset(activity.getAssets(), "fonts/Nunito-Bold.ttf");
                }
                return nunitoBold;
            }
            if (nunitoRegular == null) {
                nunitoRegular = Typeface.createFromAsset(activity.getAssets(), "fonts/Nunito-Regular.ttf");
            }
            return nunitoRegular;
        } catch (RuntimeException ignored) {
            return Typeface.DEFAULT;
        }
    }

    private final Activity activity;
    private final EditorController editorController;
    private final StorageRegistry registry;
    private final FileOperationService fileOperations;
    private final StorageTransferService transferService = new StorageTransferService();
    private final SharedPreferences preferences;
    private final WorkspaceSessionManager sessionManager;
    private JSONObject savedSession;
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "YummyFileManagerIO");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private StorageProvider activeFileProvider;
    private StorageEntry activeFileEntry;
    // Dokumen yang terbuka dipetakan ke Storage Provider asalnya agar setiap tab
    // dapat disimpan tanpa bergantung pada tab yang sedang aktif.
    private final Map<String, OpenFileReference> openFiles = new HashMap<>();
    private ManagerDialog managerDialog;
    private boolean preserveUnavailableSession;
    private boolean destroyed;
    private boolean restoreInProgress;
    private final Application.ActivityLifecycleCallbacks lifecycleCallbacks;

    private static final class OpenFileReference {
        final StorageProvider provider;
        final StorageEntry entry;
        OpenFileReference(StorageProvider provider, StorageEntry entry) {
            this.provider = provider;
            this.entry = entry;
        }
    }

    public FileManagerController(@NonNull Activity activity,
                                 @NonNull EditorController editorController) {
        this.activity = activity;
        this.editorController = editorController;
        this.registry = new StorageRegistry(activity.getApplicationContext());
        this.fileOperations = new FileOperationService(registry);
        this.preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.sessionManager = editorController.getWorkspaceSessionManager();
        this.savedSession = sessionManager.loadFileManagerSession();
        restoreOpenFileReferences(savedSession);
        this.editorController.setDocumentActionListener(new EditorController.DocumentActionListener() {
            @Override public void onActiveDocumentChanged(@Nullable String documentId) {
                syncActiveFile(documentId);
            }
            @Override public void onDocumentClosed(@NonNull String documentId) {
                openFiles.remove(documentId);
                persistFileManagerSession();
            }
            @Override public void onSaveRequested(@NonNull String documentId, @NonNull Runnable onSaved) {
                if (!editorController.activateDocument(documentId)) return;
                if (openFiles.containsKey(documentId)) {
                    saveActiveFile(onSaved);
                } else {
                    show(true);
                    if (managerDialog != null) managerDialog.pendingOpenAfterSave = onSaved;
                }
            }
        });
        restoreDocumentsFromStorage();

        lifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(@NonNull Activity activity, @Nullable android.os.Bundle state) { }
            @Override public void onActivityStarted(@NonNull Activity activity) { }
            @Override public void onActivityResumed(@NonNull Activity activity) {
                if (activity == FileManagerController.this.activity) restoreDocumentsFromStorage();
            }
            @Override public void onActivityPaused(@NonNull Activity activity) { }
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull android.os.Bundle state) {
                if (activity == FileManagerController.this.activity) persistFileManagerSessionBlocking();
            }
            @Override public void onActivityStopped(@NonNull Activity activity) {
                if (activity == FileManagerController.this.activity) persistFileManagerSessionBlocking();
            }
            @Override public void onActivityDestroyed(@NonNull Activity activity) {
                if (activity == FileManagerController.this.activity) {
                    persistFileManagerSessionBlocking();
                    activity.getApplication().unregisterActivityLifecycleCallbacks(this);
                }
            }
        };
        activity.getApplication().registerActivityLifecycleCallbacks(lifecycleCallbacks);
    }

    private void restoreOpenFileReferences(@Nullable JSONObject session) {
        if (session == null) return;
        JSONArray open = session.optJSONArray("openFiles");
        if (open == null) return;
        for (int i = 0; i < open.length(); i++) {
            JSONObject item = open.optJSONObject(i);
            if (item == null) continue;
            String documentId = item.optString("documentId", "");
            String providerId = item.optString("providerId", "");
            StorageProvider provider = registry.getProvider(providerId);
            StorageEntry entry = entryFromJson(item.optJSONObject("entry"));
            if (documentId.isEmpty() || provider == null || entry == null) continue;
            // Keep the reference even when a removable/SAF provider is temporarily
            // unavailable; save will report the access problem without losing the tab.
            openFiles.put(documentId, new OpenFileReference(provider, entry));
        }
    }

    private static final class TextFileSnapshot {
        final String text;
        final String sha256;
        TextFileSnapshot(String text, String sha256) {
            this.text = text;
            this.sha256 = sha256;
        }
    }

    private static final class RestoredDocumentSnapshot {
        final String documentId;
        final TextFileSnapshot snapshot;
        RestoredDocumentSnapshot(String documentId, TextFileSnapshot snapshot) {
            this.documentId = documentId;
            this.snapshot = snapshot;
        }
    }

    /**
     * Reopens every persisted source file, fingerprints its raw bytes, and only then lets
     * EditorController decide whether a saved dirty patch is safe to replay.
     */
    private void restoreDocumentsFromStorage() {
        if (destroyed || restoreInProgress || openFiles.isEmpty()) return;
        final List<Map.Entry<String, OpenFileReference>> references = new ArrayList<>();
        for (Map.Entry<String, OpenFileReference> item : openFiles.entrySet()) {
            if (editorController.isAwaitingStorageRestore(item.getKey())) references.add(item);
        }
        if (references.isEmpty()) return;
        restoreInProgress = true;
        ioExecutor.execute(() -> {
            List<RestoredDocumentSnapshot> restored = new ArrayList<>();
            for (Map.Entry<String, OpenFileReference> item : references) {
                if (Thread.currentThread().isInterrupted()) return;
                String documentId = item.getKey();
                OpenFileReference reference = item.getValue();
                if (reference.provider == null || !reference.provider.isAvailable()) continue;
                try {
                    TextFileSnapshot file = readUtf8TextSnapshot(reference.provider, reference.entry);
                    restored.add(new RestoredDocumentSnapshot(documentId, file));
                } catch (IOException | RuntimeException error) {
                    android.util.Log.w("YummySession",
                        "Could not restore source file for tab " + documentId, error);
                }
            }
            mainHandler.post(() -> {
                restoreInProgress = false;
                if (destroyed) return;
                int conflicts = 0;
                for (RestoredDocumentSnapshot item : restored) {
                    if (editorController.restoreDocumentFromStorage(
                        item.documentId, item.snapshot.text, item.snapshot.sha256
                    )) conflicts++;
                }
                if (conflicts > 0) {
                    toast(conflicts == 1
                        ? "File berubah sejak terakhir diedit. Recovery lama tidak diterapkan."
                        : conflicts + " file berubah. Recovery lama tidak diterapkan.");
                }
                syncActiveFile(editorController.getActiveDocumentId());
            });
        });
    }

    private JSONArray buildOpenFilesSnapshot() {
        JSONArray result = new JSONArray();
        for (Map.Entry<String, OpenFileReference> item : openFiles.entrySet()) {
            OpenFileReference reference = item.getValue();
            JSONObject value = new JSONObject();
            try {
                value.put("documentId", item.getKey());
                value.put("providerId", reference.provider.getId());
                value.put("entry", entryToJson(reference.entry));
                result.put(value);
            } catch (JSONException error) {
                android.util.Log.e("YummySession", "Could not snapshot open file reference", error);
            }
        }
        return result;
    }

    private void persistFileManagerSession() {
        if (sessionManager == null) return;
        if (managerDialog == null && savedSession == null && openFiles.isEmpty()) return;
        if (managerDialog != null && managerDialog.initialized) {
            managerDialog.persistSession();
            return;
        }
        JSONObject snapshot = new JSONObject();
        if (savedSession != null) {
            try { snapshot = new JSONObject(savedSession.toString()); }
            catch (JSONException ignored) { snapshot = new JSONObject(); }
        }
        try {
            snapshot.put("openFiles", buildOpenFilesSnapshot());
            savedSession = new JSONObject(snapshot.toString());
            sessionManager.saveFileManagerSession(snapshot);
        } catch (JSONException error) {
            android.util.Log.e("YummySession", "Could not persist open file references", error);
        }
    }

    /** Final synchronous checkpoint for lifecycle stop/destroy callbacks. */
    private void persistFileManagerSessionBlocking() {
        if (sessionManager == null) return;
        persistFileManagerSession();
        JSONObject snapshot = new JSONObject();
        if (savedSession != null) {
            try { snapshot = new JSONObject(savedSession.toString()); }
            catch (JSONException ignored) { snapshot = new JSONObject(); }
        }
        try {
            snapshot.put("openFiles", buildOpenFilesSnapshot());
            savedSession = new JSONObject(snapshot.toString());
            sessionManager.saveFileManagerSessionBlocking(snapshot);
        } catch (JSONException error) {
            android.util.Log.e("YummySession", "Could not create final File Manager checkpoint", error);
        }
    }

    private void syncActiveFile(@Nullable String documentId) {
        OpenFileReference reference = documentId == null ? null : openFiles.get(documentId);
        activeFileProvider = reference == null ? null : reference.provider;
        activeFileEntry = reference == null ? null : reference.entry;
    }

    @Nullable
    private String findOpenDocumentId(StorageProvider provider, StorageEntry entry) {
        for (Map.Entry<String, OpenFileReference> item : openFiles.entrySet()) {
            OpenFileReference reference = item.getValue();
            if (reference.provider.getId().equals(provider.getId()) && sameEntry(reference.entry, entry)) {
                return item.getKey();
            }
        }
        return null;
    }

    @Nullable
    private String findDirtyOpenDocumentId(StorageEntry entry) {
        for (Map.Entry<String, OpenFileReference> item : openFiles.entrySet()) {
            if (sameEntry(item.getValue().entry, entry) && editorController.isDocumentDirty(item.getKey())) {
                return item.getKey();
            }
        }
        return null;
    }

    private void updateOpenFileEntry(StorageEntry oldEntry, StorageEntry newEntry, StorageProvider provider) {
        List<String> ids = new ArrayList<>(openFiles.keySet());
        for (String id : ids) {
            OpenFileReference reference = openFiles.get(id);
            if (reference != null && sameEntry(reference.entry, oldEntry)) {
                openFiles.put(id, new OpenFileReference(provider, newEntry));
                editorController.setDocumentName(id, newEntry.getName());
            }
        }
        syncActiveFile(editorController.getActiveDocumentId());
        persistFileManagerSession();
    }

    private void closeOpenTabsForEntry(StorageEntry entry) {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, OpenFileReference> item : openFiles.entrySet()) {
            if (sameEntry(item.getValue().entry, entry)) ids.add(item.getKey());
        }
        for (String id : ids) editorController.closeDocumentWithoutPrompt(id);
    }

    /** Bind the folder button and editor save button after setContentView(). */
    public void setup() {
        View fileButton = activity.findViewById(R.id.yummy_btn_menu);
        if (fileButton != null) fileButton.setOnClickListener(v -> show());

        View saveButton = activity.findViewById(R.id.yummy_btn_save);
        if (saveButton != null) saveButton.setOnClickListener(v -> {
            // Editor tanpa tab tetap dapat diketik. Buat draft saat pengguna menekan Save.
            editorController.ensureDraftDocumentForSave();
            if (activeFileProvider == null || activeFileEntry == null) {
                show(true);
            } else {
                saveActiveFile(null);
            }
        });
    }

    public void show() {
        show(false);
    }

    private void show(boolean saveAsMode) {
        if (destroyed || activity.isFinishing()) return;

        if (managerDialog == null) managerDialog = new ManagerDialog(saveAsMode);
        else managerDialog.setSaveAsMode(saveAsMode);
        managerDialog.show();
    }

//    private void show(boolean saveAsMode) {
//        if (destroyed || activity.isFinishing()) return;
//        if (managerDialog != null && managerDialog.dialog.isShowing()) {
//            managerDialog.dialog.dismiss();
//        }
//        managerDialog = new ManagerDialog(saveAsMode);
//        managerDialog.show();
//    }

    /** Delegate from TermuxActivity.onActivityResult(). */
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_ADD_STORAGE || destroyed) return;
        try {
            StorageProvider provider = StorageTreePicker.acceptResult(registry, resultCode, data);
            if (provider != null) {
                toast("Storage ditambahkan: " + provider.getLocation().getName());
                if (managerDialog != null) managerDialog.onStorageAdded(provider);
            }
        } catch (IOException | RuntimeException error) {
            showError("Tidak dapat menambahkan storage", error);
        }
    }

    public void destroy() {
        persistFileManagerSessionBlocking();
        editorController.persistEditorSessionNow();
        destroyed = true;
        activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
        if (managerDialog != null) {
            managerDialog.dismiss();
            managerDialog = null;
        }
        editorController.setDocumentActionListener(null);
        openFiles.clear();
        ioExecutor.shutdownNow();
    }

    private void openFile(StorageProvider provider, StorageEntry entry, @Nullable Runnable afterOpen) {
        if (entry.isDirectory()) return;
        FileTypeResolver.Descriptor descriptor = FileTypeResolver.resolve(
            entry.getName(), false, entry.getMimeType()
        );
        if (!descriptor.isTextEditable()) {
            toast("Format " + descriptor.getLabel() + " belum bisa dibuka di editor teks.");
            return;
        }

        String existingDocumentId = findOpenDocumentId(provider, entry);
        if (existingDocumentId != null && editorController.activateDocument(existingDocumentId)) {
            if (managerDialog != null) managerDialog.dismiss();
            if (afterOpen != null) afterOpen.run();
            toast("Tab aktif: " + entry.getName());
            return;
        }
        // Dokumen yang sedang diedit tetap berada di tab sebelumnya; membuka file lain
        // tidak memaksa penyimpanan atau membuang perubahan.
        loadFileNow(provider, entry, afterOpen);
    }

    private void loadFileNow(StorageProvider provider, StorageEntry entry, @Nullable Runnable afterOpen) {
        toast("Membuka " + entry.getName() + "…");
        ioExecutor.execute(() -> {
            try {
                TextFileSnapshot fileSnapshot = readUtf8TextSnapshot(provider, entry);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    String documentId = provider.getId() + "::" + entry.getDocumentId();
                    openFiles.put(documentId, new OpenFileReference(provider, entry));
                    persistFileManagerSession();
                    editorController.openStorageDocument(
                        documentId, fileSnapshot.text, entry.getName(), fileSnapshot.sha256
                    );
                    rememberRecent(entry);
                    syncActiveFile(documentId);
                    if (managerDialog != null) managerDialog.dismiss();
                    if (afterOpen != null) afterOpen.run();
                    toast("Dibuka: " + entry.getName());
                });
            } catch (IOException | RuntimeException error) {
                mainHandler.post(() -> showError("Tidak dapat membuka " + entry.getName(), error));
            }
        });
    }

    private static TextFileSnapshot readUtf8TextSnapshot(StorageProvider provider,
                                                             StorageEntry entry) throws IOException {
        if (!provider.isAvailable()) throw new IOException("Storage sedang tidak tersedia");
        try (InputStream input = provider.openRead(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_OPEN_BYTES) {
                    throw new IOException("File terlalu besar untuk editor (maksimum 8 MiB)");
                }
                output.write(buffer, 0, read);
            }
            byte[] bytes = output.toByteArray();
            for (byte value : bytes) {
                if (value == 0) throw new IOException("File tampaknya berisi data biner");
            }
            String fingerprint = WorkspaceSessionManager.sha256(bytes);
            int offset = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
            String text = new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
            return new TextFileSnapshot(text, fingerprint);
        }
    }

    private void saveActiveFile(@Nullable Runnable afterSave) {
        String documentId = editorController.getActiveDocumentId();
        OpenFileReference reference = documentId == null ? null : openFiles.get(documentId);
        if (reference == null) {
            show(true);
            if (afterSave != null && managerDialog != null) managerDialog.pendingOpenAfterSave = afterSave;
            return;
        }
        final String targetDocumentId = documentId;
        final long savedRevision = editorController.getDocumentRevision(targetDocumentId);
        final String content = editorController.getDocumentText(targetDocumentId);
        final Charset documentCharset = Charset.forName(editorController.getDocumentEncoding(targetDocumentId));
        final byte[] encodedContent = content.getBytes(documentCharset);
        final StorageProvider provider = reference.provider;
        final StorageEntry entry = reference.entry;
        ioExecutor.execute(() -> {
            try (OutputStream output = provider.openWrite(entry, false)) {
                output.write(encodedContent);
                output.flush();
                mainHandler.post(() -> {
                    if (destroyed) return;
                    editorController.markDocumentSaved(
                        targetDocumentId, savedRevision, content,
                        WorkspaceSessionManager.sha256(encodedContent)
                    );
                    toast("Disimpan: " + entry.getName());
                    if (afterSave != null) {
                        if (editorController.isDocumentDirty(targetDocumentId)) {
                            showError("Dokumen berubah saat disimpan",
                                new IOException("Perubahan terbaru belum tersimpan. Simpan lagi sebelum menutup tab."));
                        } else afterSave.run();
                    }
                });
            } catch (IOException | RuntimeException error) {
                mainHandler.post(() -> showError("Gagal menyimpan " + entry.getName(), error));
            }
        });
    }

    private void saveAs(StorageProvider provider, StorageEntry parent, String name,
                        @Nullable Runnable afterSave) {
        // Jaga alur Save As tetap aman meskipun dipanggil tanpa tab aktif.
        final String targetDocumentId = editorController.ensureDraftDocumentForSave();
        final long savedRevision = editorController.getDocumentRevision(targetDocumentId);
        final String content = editorController.getDocumentText(targetDocumentId);
        final Charset documentCharset = Charset.forName(editorController.getDocumentEncoding(targetDocumentId));
        final byte[] encodedContent = content.getBytes(documentCharset);
        ioExecutor.execute(() -> {
            StorageEntry created = null;
            try {
                if (!provider.isAvailable()) throw new IOException("Storage sedang tidak tersedia");
                created = fileOperations.createFile(provider.getId(), parent, name,
                    FileOperationService.inferMimeType(name));
                try (OutputStream output = provider.openWrite(created, false)) {
                    output.write(encodedContent);
                    output.flush();
                }
                StorageEntry savedEntry = created;
                mainHandler.post(() -> {
                    if (destroyed) return;
                    openFiles.put(targetDocumentId, new OpenFileReference(provider, savedEntry));
                    persistFileManagerSession();
                    editorController.setDocumentName(targetDocumentId, savedEntry.getName());
                    editorController.markDocumentSaved(
                        targetDocumentId, savedRevision, content,
                        WorkspaceSessionManager.sha256(encodedContent)
                    );
                    if (targetDocumentId.equals(editorController.getActiveDocumentId())) syncActiveFile(targetDocumentId);
                    toast("Disimpan sebagai " + savedEntry.getName());
                    if (managerDialog != null) managerDialog.dismiss();
                    if (afterSave != null) {
                        if (editorController.isDocumentDirty(targetDocumentId)) {
                            showError("Dokumen berubah saat disimpan",
                                new IOException("Perubahan terbaru belum tersimpan. Simpan lagi sebelum menutup tab."));
                        } else afterSave.run();
                    }
                });
            } catch (IOException | RuntimeException error) {
                if (created != null) {
                    try { provider.delete(created); } catch (Exception ignored) { }
                }
                mainHandler.post(() -> showError("Gagal menyimpan salinan", error));
            }
        });
    }

    private void shareOrOpenExternally(StorageProvider provider, StorageEntry entry, boolean open) {
        if (entry.isDirectory()) return;
        if (provider == null || !provider.isAvailable()) {
            toast("Storage tidak tersedia");
            return;
        }
        toast((open ? "Menyiapkan file untuk dibuka…" : "Menyiapkan file untuk dibagikan…"));
        ioExecutor.execute(() -> {
            File exportDirectory = new File(activity.getCacheDir(), "file-manager-share");
            File exported = null;
            try {
                if (!exportDirectory.isDirectory() && !exportDirectory.mkdirs()) {
                    throw new IOException("Tidak dapat membuat cache sementara");
                }
                String safeName = entry.getName().replaceAll("[^A-Za-z0-9._-]", "_");
                if (safeName.isEmpty()) safeName = "shared-file";
                exported = new File(exportDirectory, UUID.randomUUID().toString() + "-" + safeName);
                try (InputStream input = provider.openRead(entry);
                     OutputStream output = new FileOutputStream(exported)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    output.flush();
                }
                File finalFile = exported;
                String mime = entry.getMimeType();
                if (mime == null || mime.trim().isEmpty() || "application/octet-stream".equals(mime)) {
                    mime = FileTypeResolver.resolve(entry.getName(), false, mime).getMimeType();
                }
                Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", finalFile);
                Intent intent;
                if (open) {
                    intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, mime);
                } else {
                    intent = new Intent(Intent.ACTION_SEND);
                    intent.setType(mime);
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newUri(activity.getContentResolver(), entry.getName(), uri));
                Intent chooser = Intent.createChooser(intent, open ? "Open with" : "Share file");
                mainHandler.post(() -> {
                    if (destroyed) return;
                    try { activity.startActivity(chooser); }
                    catch (RuntimeException error) { showError("Tidak ada aplikasi yang dapat membuka file ini", error); }
                });
            } catch (IOException | RuntimeException error) {
                if (exported != null) { try { exported.delete(); } catch (RuntimeException ignored) { } }
                mainHandler.post(() -> showError("Tidak dapat menyiapkan file", error));
            }
        });
    }

    private void rememberRecent(StorageEntry entry) {
        List<StorageEntry> recents = readEntryList(KEY_RECENTS);
        recents.removeIf(item -> sameEntry(item, entry));
        recents.add(0, entry);
        while (recents.size() > MAX_RECENTS) recents.remove(recents.size() - 1);
        writeEntryList(KEY_RECENTS, recents);
    }

    private List<StorageEntry> readEntryList(String key) {
        List<StorageEntry> result = new ArrayList<>();
        String raw = preferences.getString(key, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                StorageEntry entry = entryFromJson(object);
                if (entry != null) result.add(entry);
            }
        } catch (JSONException ignored) { }
        return result;
    }

    private void writeEntryList(String key, List<StorageEntry> entries) {
        JSONArray array = new JSONArray();
        for (StorageEntry entry : entries) {
            JSONObject object = entryToJson(entry);
            if (object != null) array.put(object);
        }
        preferences.edit().putString(key, array.toString()).apply();
    }

    @Nullable
    private static JSONObject entryToJson(@Nullable StorageEntry entry) {
        if (entry == null) return null;
        try {
            JSONObject object = new JSONObject();
            object.put("provider", entry.getProviderId());
            object.put("id", entry.getDocumentId());
            object.put("parent", entry.getParentDocumentId());
            object.put("name", entry.getName());
            object.put("mime", entry.getMimeType());
            object.put("directory", entry.isDirectory());
            object.put("size", entry.getSize());
            object.put("modified", entry.getLastModified());
            object.put("capabilities", entry.getCapabilities());
            return object;
        } catch (JSONException e) { return null; }
    }

    @Nullable
    private static StorageEntry entryFromJson(@Nullable JSONObject object) {
        if (object == null) return null;
        try {
            String parent = object.isNull("parent") ? null : object.getString("parent");
            return new StorageEntry(
                object.getString("provider"), object.getString("id"), parent,
                object.getString("name"), object.optString("mime", "application/octet-stream"),
                object.optBoolean("directory", false), object.optLong("size", 0),
                object.optLong("modified", 0), object.optInt("capabilities", StorageCapability.READ)
            );
        } catch (JSONException e) { return null; }
    }

    private static boolean sameEntry(StorageEntry first, StorageEntry second) {
        return first.getProviderId().equals(second.getProviderId())
            && first.getDocumentId().equals(second.getDocumentId());
    }

    private void toggleFavorite(StorageEntry entry) {
        List<StorageEntry> favorites = readEntryList(KEY_FAVORITES);
        StorageEntry found = null;
        for (StorageEntry item : favorites) {
            if (sameEntry(item, entry)) { found = item; break; }
        }
        if (found != null) {
            favorites.remove(found);
            toast("Dihapus dari Favorites");
        } else {
            favorites.add(0, entry);
            toast("Ditambahkan ke Favorites");
        }
        writeEntryList(KEY_FAVORITES, favorites);
        if (managerDialog != null) managerDialog.refreshVisible();
    }

    private void toast(String message) {
        if (activity.isFinishing() || destroyed) return;
        mainHandler.post(() -> Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
    }

    private AlertDialog.Builder nunitoDialogBuilder() {
        return new AlertDialog.Builder(new ContextThemeWrapper(activity, android.R.style.Theme_Material_Dialog_Alert)) {
            @Override
            public AlertDialog show() {
                AlertDialog dialog = super.show();
                applyNunitoToDialog(dialog);
                return dialog;
            }
        };
    }

    private void applyNunitoToDialog(@NonNull AlertDialog dialog) {
        Typeface typeface = nunitoTypeface(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(roundRect(0xFF171A21, dp(14)));
        }

        int titleId = activity.getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = titleId != 0 ? dialog.findViewById(titleId) : null;
        TextView message = dialog.findViewById(android.R.id.message);
        if (title != null) {
            title.setTypeface(typeface);
            title.setTextColor(0xFFF4F5F7);
        }
        if (message != null) {
            message.setTypeface(nunitoTypeface(false));
            message.setTextColor(0xFFD0D4DA);
        }

        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (positive != null) {
            positive.setTypeface(typeface);
            positive.setTextColor(0xFF3DDC84);
        }
        if (negative != null) {
            negative.setTypeface(typeface);
            negative.setTextColor(0xFFB8BEC8);
        }
        if (neutral != null) {
            neutral.setTypeface(typeface);
            neutral.setTextColor(0xFF3DDC84);
        }

        View content = dialog.findViewById(android.R.id.content);
        if (content != null) styleDialogInputs(content);
    }

    private void styleDialogInputs(View view) {
        if (view instanceof EditText) {
            EditText input = (EditText) view;
            input.setTypeface(nunitoTypeface(false));
            input.setTextColor(0xFFF4F5F7);
            input.setHintTextColor(0xFF969EA7);
            input.setBackground(roundRect(0xFF252A33, dp(8)));
            input.setPadding(dp(12), dp(9), dp(12), dp(9));
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                styleDialogInputs(group.getChildAt(i));
            }
        }
    }

    private void showError(String title, Throwable error) {
        if (activity.isFinishing() || destroyed) return;
        String message = error == null || error.getMessage() == null
            ? "Operasi tidak dapat diselesaikan" : error.getMessage();
        nunitoDialogBuilder().setTitle(title).setMessage(message)
            .setPositiveButton("OK", null).show();
    }

    private final class ManagerDialog {
        private enum SortMode { NAME, TYPE, SIZE, MODIFIED }
        private enum RowKind { SECTION, LOCATION, ENTRY, FAVORITE, RECENT }
        private enum PendingTransfer { NONE, COPY, MOVE }

        private boolean initialized;
        private Runnable pendingTreeWidthDebug;
        
        private final Dialog dialog;
        private boolean initialSaveAsMode;
        private final StorageSelection selection = new StorageSelection();
        private final Deque<StorageEntry> backStack = new ArrayDeque<>();
        private final List<Row> rows = new ArrayList<>();
        private final FileRowAdapter adapter = new FileRowAdapter();

        private FrameLayout root;
        private View scrim;
        private LinearLayout drawerPanel;
        private LinearLayout storageRail;
        private LinearLayout storageNavContainer;
        private LinearLayout rightPane;
        private TextView titleView;
        private TextView breadcrumbView;
        private TextView emptyView;
        private TextView selectionCountView;
        private LinearLayout actionBar;
        private LinearLayout selectionBar;
        private ListView listView;
        // Horizontal viewport and explicit wide content surface for deeply nested rows.
        private HorizontalScrollView treeHorizontalScroll;
        private FrameLayout treeContentContainer;
        private int treeViewportWidth;
        private EditText searchView;
        private Button useFolderButton;
        private ProgressBar progressBar;

        private StorageProvider currentProvider;
        private StorageEntry currentDirectory;
        private StorageEntry treeRootDirectory;
        private List<StorageEntry> currentEntries = new ArrayList<>();
        private final Set<String> expandedTreeKeys = new HashSet<>();
        private final Map<String, List<StorageEntry>> treeChildren = new HashMap<>();
        private final Map<String, StorageEntry> treeEntries = new HashMap<>();
        private boolean showingLocations;
        private SortMode sortMode = SortMode.NAME;
        private PendingTransfer pendingTransfer = PendingTransfer.NONE;
        private boolean selectionMode;
        private boolean showHidden;
        private boolean saveAsMode;
        private int loadGeneration;
        private String activeQuery = "";
        private Runnable pendingSearch;
        private Runnable pendingOpenAfterSave;

        ManagerDialog(boolean saveAsMode) {
            this.initialSaveAsMode = saveAsMode;
            this.saveAsMode = saveAsMode;
            this.showHidden = preferences.getBoolean(KEY_SHOW_HIDDEN, false);
            dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(ignored -> {
                if (initialized) persistSession();
                loadGeneration++;
                if (pendingSearch != null) {
                    mainHandler.removeCallbacks(pendingSearch);
                    pendingSearch = null;
                }
            });
            // Android Back should navigate up through folders before closing the manager.
            dialog.setOnKeyListener((ignored, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_BACK) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) goBack();
                return true;
            });
            buildView();
        }

        void setSaveAsMode(boolean enabled) {
            if (saveAsMode == enabled) return;
            saveAsMode = enabled;
            initialSaveAsMode = enabled;
            if (actionBar != null) rebuildActionBar();
            if (selectionBar != null) rebuildSelectionBar();
        }

        void show() {
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.dimAmount = saveAsMode ? 0.56f : 0.42f;
                window.setAttributes(attributes);

                if (saveAsMode) {
                    // Save picker is a compact centered popup. Keep system bars visible so
                    // it feels like a dialog over the editor, not another full-screen page.
                    window.getDecorView().setSystemUiVisibility(0);
                } else {
                    // Normal File Manager remains a full-height navigation drawer.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowInsetsController controller = window.getInsetsController();
                        if (controller != null) {
                            controller.hide(WindowInsets.Type.statusBars());
                            controller.setSystemBarsBehavior(
                                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            );
                        }
                    } else {
                        window.getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        );
                    }
                }
            }

            FrameLayout.LayoutParams panelParams =
                (FrameLayout.LayoutParams) drawerPanel.getLayoutParams();
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
            if (saveAsMode) {
                int popupWidth = Math.min(dp(480), Math.max(dp(280), screenWidth - dp(32)));
                int availableHeight = Math.max(dp(360), screenHeight - dp(112));
                int popupHeight = Math.min(dp(620), Math.round(availableHeight * 0.82f));
                panelParams.width = popupWidth;
                panelParams.height = popupHeight;
                panelParams.gravity = Gravity.CENTER;
                panelParams.setMargins(dp(16), dp(24), dp(16), dp(24));
                drawerPanel.setLayoutParams(panelParams);
                drawerPanel.setBackground(roundRect(0xFF13151B, dp(16)));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    drawerPanel.setClipToOutline(true);
                    drawerPanel.setElevation(dp(18));
                }
                drawerPanel.setTranslationX(dp(12));
                drawerPanel.setAlpha(0f);
                drawerPanel.setScaleX(0.97f);
                drawerPanel.setScaleY(0.97f);
                drawerPanel.animate().translationX(0f).alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(190L).start();
            } else {
                int panelWidth = Math.min(screenWidth,
                    Math.max(dp(280), Math.round(screenWidth * 0.88f)));
                panelParams.width = panelWidth;
                panelParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                panelParams.gravity = Gravity.START | Gravity.TOP;
                panelParams.setMargins(0, 0, 0, 0);
                drawerPanel.setLayoutParams(panelParams);
                drawerPanel.setBackgroundColor(0xFF13151B);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    drawerPanel.setClipToOutline(false);
                    drawerPanel.setElevation(0f);
                }
                drawerPanel.setAlpha(1f);
                drawerPanel.setScaleX(1f);
                drawerPanel.setScaleY(1f);
                drawerPanel.setTranslationX(-panelWidth);
                drawerPanel.animate().translationX(0f).setDuration(190L).start();
            }

//            StorageProvider initialProvider = findDefaultProvider();
            if (!initialized) {
                initialized = true;
                if (!restoreSavedSession()) {
                    StorageProvider initialProvider = findDefaultProvider();
                    if (initialProvider != null) openStorage(initialProvider);
                    else refreshHome();
                }
            }

            // Use the actual visible width (also correct for the compact Save As popup).
            rightPane.post(() -> {
                if (rightPane != null && treeHorizontalScroll != null) {
                    treeViewportWidth = Math.max(1, rightPane.getWidth());
                    updateTreeHorizontalWidth();
                }
            });

   //         if (initialProvider != null) openStorage(initialProvider);
//            else refreshHome();
        }

        private boolean restoreSavedSession() {
            JSONObject saved = savedSession;
            if (saved == null) return false;
            if (saved.optBoolean("showingLocations", false)) {
                preserveUnavailableSession = false;
                refreshHome(false);
                return true;
            }

            String providerId = saved.optString("providerId", "");
            StorageProvider provider = registry.getProvider(providerId);
            StorageEntry restoredRoot = entryFromJson(saved.optJSONObject("treeRoot"));
            StorageEntry restoredCurrent = entryFromJson(saved.optJSONObject("currentDirectory"));
            if (providerId.isEmpty() || restoredRoot == null) {
                preserveUnavailableSession = false;
                refreshHome(false);
                return true;
            }
            if (provider == null || !provider.isAvailable()) {
                // Show the locations screen, but keep the last selected provider/path
                // on disk so a temporarily unavailable OTG/SAF location is not forgotten.
                preserveUnavailableSession = true;
                refreshHome(false);
                return true;
            }
            if (restoredCurrent == null) restoredCurrent = restoredRoot;

            currentProvider = provider;
            treeRootDirectory = restoredRoot;
            currentDirectory = restoredCurrent;
            showingLocations = false;
            sortMode = parseSortMode(saved.optString("sortMode", "NAME"));
            showHidden = saved.optBoolean("showHidden", showHidden);
            activeQuery = "";
            backStack.clear();
            expandedTreeKeys.clear();
            treeChildren.clear();
            treeEntries.clear();
            treeEntries.put(treeKey(restoredRoot), restoredRoot);

            JSONArray cachedEntries = saved.optJSONArray("treeEntries");
            if (cachedEntries != null) {
                for (int i = 0; i < cachedEntries.length(); i++) {
                    StorageEntry entry = entryFromJson(cachedEntries.optJSONObject(i));
                    if (entry != null) treeEntries.put(treeKey(entry), entry);
                }
            }
            treeEntries.put(treeKey(restoredCurrent), restoredCurrent);

            JSONArray stack = saved.optJSONArray("backStack");
            if (stack != null) {
                for (int i = stack.length() - 1; i >= 0; i--) {
                    StorageEntry entry = entryFromJson(stack.optJSONObject(i));
                    if (entry != null) {
                        backStack.push(entry);
                        treeEntries.put(treeKey(entry), entry);
                    }
                }
            }

            List<StorageEntry> expandedEntries = new ArrayList<>();
            JSONArray expanded = saved.optJSONArray("expandedDirectories");
            if (expanded != null) {
                for (int i = 0; i < expanded.length(); i++) {
                    StorageEntry entry = entryFromJson(expanded.optJSONObject(i));
                    if (entry != null && entry.isDirectory()) {
                        expandedEntries.add(entry);
                        expandedTreeKeys.add(treeKey(entry));
                        treeEntries.put(treeKey(entry), entry);
                    }
                }
            }

            if (restoredCurrent.isDirectory()) expandedTreeKeys.add(treeKey(restoredCurrent));
            rebuildStorageRail();
            updateBreadcrumb();
            rebuildActionBar();
            rebuildSelectionBar();
            final int generation = ++loadGeneration;
            showLoading(true);
            final StorageEntry rootEntry = restoredRoot;
            final StorageEntry currentEntry = restoredCurrent;
            final boolean includeHidden = showHidden;
            final SortMode restoredSortMode = sortMode;
            final List<StorageEntry> directoriesToLoad = new ArrayList<>();
            directoriesToLoad.add(rootEntry);
            for (StorageEntry entry : expandedEntries) {
                if (!sameEntry(entry, rootEntry)) directoriesToLoad.add(entry);
            }
            ioExecutor.execute(() -> {
                Map<String, List<StorageEntry>> restoredChildren = new HashMap<>();
                boolean currentStillExists = true;
                try { currentStillExists = provider.exists(currentEntry); }
                catch (RuntimeException ignored) { currentStillExists = false; }
                for (StorageEntry directory : directoriesToLoad) {
                    try {
                        List<StorageEntry> children = provider.list(directory);
                        List<StorageEntry> visible = new ArrayList<>();
                        for (StorageEntry child : children) {
                            if (includeHidden || !child.getName().startsWith(".")) visible.add(child);
                        }
                        sortEntries(visible, restoredSortMode);
                        restoredChildren.put(treeKey(directory), visible);
                    } catch (IOException | RuntimeException error) {
                        android.util.Log.w("YummySession", "Could not restore expanded directory "
                            + directory.getName(), error);
                    }
                }
                final boolean finalCurrentExists = currentStillExists;
                mainHandler.post(() -> {
                    if (destroyed || !dialog.isShowing() || generation != loadGeneration) return;
                    treeChildren.clear();
                    treeChildren.putAll(restoredChildren);
                    currentEntries = restoredChildren.containsKey(treeKey(rootEntry))
                        ? restoredChildren.get(treeKey(rootEntry)) : new ArrayList<>();
                    for (List<StorageEntry> children : restoredChildren.values()) {
                        for (StorageEntry child : children) treeEntries.put(treeKey(child), child);
                    }
                    if (!finalCurrentExists) {
                        currentDirectory = treeRootDirectory;
                        backStack.clear();
                    }
                    updateBreadcrumb();
                    rebuildTreeRows();
                    rebuildSelectionBar();
                    showLoading(false);
                    persistSession();
                });
            });
            return true;
        }

        private SortMode parseSortMode(String value) {
            try { return SortMode.valueOf(value); }
            catch (RuntimeException ignored) { return SortMode.NAME; }
        }

        private void persistSession() {
            if (preserveUnavailableSession && currentProvider == null && savedSession != null) {
                try {
                    JSONObject preserved = new JSONObject(savedSession.toString());
                    preserved.put("openFiles", buildOpenFilesSnapshot());
                    savedSession = new JSONObject(preserved.toString());
                    sessionManager.saveFileManagerSession(preserved);
                } catch (JSONException error) {
                    android.util.Log.e("YummySession", "Could not preserve unavailable storage session", error);
                }
                return;
            }
            JSONObject snapshot = new JSONObject();
            try {
                snapshot.put("version", 1);
                snapshot.put("showingLocations", showingLocations);
                snapshot.put("providerId", currentProvider == null
                    ? JSONObject.NULL : currentProvider.getId());
                snapshot.put("treeRoot", entryToJson(treeRootDirectory));
                snapshot.put("currentDirectory", entryToJson(currentDirectory));
                snapshot.put("sortMode", sortMode.name());
                snapshot.put("showHidden", showHidden);

                JSONArray stack = new JSONArray();
                for (StorageEntry entry : backStack) {
                    JSONObject serialized = entryToJson(entry);
                    if (serialized != null) stack.put(serialized);
                }
                snapshot.put("backStack", stack);

                JSONArray expanded = new JSONArray();
                for (String key : expandedTreeKeys) {
                    StorageEntry entry = treeEntries.get(key);
                    if (entry == null && currentDirectory != null
                        && key.equals(treeKey(currentDirectory))) entry = currentDirectory;
                    JSONObject serialized = entryToJson(entry);
                    if (serialized != null && entry.isDirectory()) expanded.put(serialized);
                }
                snapshot.put("expandedDirectories", expanded);

                JSONArray entries = new JSONArray();
                for (StorageEntry entry : treeEntries.values()) {
                    JSONObject serialized = entryToJson(entry);
                    if (serialized != null) entries.put(serialized);
                }
                snapshot.put("treeEntries", entries);
                snapshot.put("openFiles", buildOpenFilesSnapshot());
                savedSession = new JSONObject(snapshot.toString());
                sessionManager.saveFileManagerSession(snapshot);
            } catch (JSONException error) {
                android.util.Log.e("YummySession", "Could not create File Manager session", error);
            }
        }

        private StorageProvider findDefaultProvider() {
            for (StorageProvider provider : registry.getProviders()) {
                if ("yummy.home".equals(provider.getId())) return provider;
            }
            List<StorageProvider> providers = registry.getProviders();
            return providers.isEmpty() ? null : providers.get(0);
        }

        void dismiss() {
            if (initialized) persistSession();
            loadGeneration++;
            if (pendingSearch != null) {
                mainHandler.removeCallbacks(pendingSearch);
                pendingSearch = null;
            }
            if (dialog.isShowing()) dialog.dismiss();
        }

        private void buildView() {
            root = new FrameLayout(activity);
            root.setBackgroundColor(Color.TRANSPARENT);

            // Tap outside the drawer to close it, like a standard navigation drawer.
            scrim = new View(activity);
            scrim.setBackgroundColor(0x66000000);
            scrim.setOnClickListener(v -> dismiss());
            root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

            drawerPanel = new LinearLayout(activity);
            drawerPanel.setOrientation(LinearLayout.HORIZONTAL);
            drawerPanel.setBackgroundColor(0xFF13151B);
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int panelWidth = Math.min(screenWidth, Math.max(dp(280), Math.round(screenWidth * 0.88f)));
            drawerPanel.setTranslationX(-panelWidth);
            FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                panelWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START | Gravity.TOP);
            root.addView(drawerPanel, panelParams);

            // Narrow storage rail on the left, matching the reference layout.
            storageRail = new LinearLayout(activity);
            storageRail.setOrientation(LinearLayout.VERTICAL);
            storageRail.setGravity(Gravity.CENTER_HORIZONTAL);
            storageRail.setBackgroundColor(0xFF13151B);
            drawerPanel.addView(storageRail, new LinearLayout.LayoutParams(dp(70), -1));

            rightPane = new LinearLayout(activity);
            rightPane.setOrientation(LinearLayout.VERTICAL);
            rightPane.setBackgroundColor(0xFF13151B);
            drawerPanel.addView(rightPane, new LinearLayout.LayoutParams(0, -1, 1));

            // Toolbar: current location + search + overflow.
            LinearLayout header = new LinearLayout(activity);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(12), 0, dp(8), 0);
            header.setBackgroundColor(0xFF13151B);
            rightPane.addView(header, new LinearLayout.LayoutParams(-1, dp(58)));

            titleView = text("Local", 18, 0xFFE8EAED, true);
            titleView.setGravity(Gravity.CENTER_VERTICAL);
            titleView.setSingleLine(true);
            titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            header.addView(titleView, new LinearLayout.LayoutParams(0, -1, 1));

            ImageButton searchButton = iconButton(R.drawable.ic_fm_search, "Search files and folders");
            header.addView(searchButton, new LinearLayout.LayoutParams(dp(44), -1));
            searchButton.setOnClickListener(v -> {
                searchView.setVisibility(searchView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                if (searchView.getVisibility() == View.VISIBLE) {
                    searchView.requestFocus();
                    searchView.post(() -> {
                        android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(searchView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    });
                } else {
                    searchView.setText("");
                }
            });

            ImageButton moreButton = iconButton(R.drawable.ic_fm_more, "More file manager actions");
            header.addView(moreButton, new LinearLayout.LayoutParams(dp(38), -1));
            moreButton.setOnClickListener(v -> showMoreMenu());

            searchView = new EditText(activity);
            searchView.setSingleLine(true);
            searchView.setTextColor(0xFFE8EAED);
            searchView.setHintTextColor(0xFF969EA7);
            searchView.setHint("Search files and folders");
            searchView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            searchView.setTypeface(nunitoTypeface(false));
            searchView.setPadding(dp(14), 0, dp(14), 0);
            searchView.setBackground(roundRect(0xFF292C35, dp(9)));
            searchView.setVisibility(View.GONE);
            LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(42));
            searchLp.setMargins(dp(12), dp(2), dp(12), dp(8));
            rightPane.addView(searchView, searchLp);
            searchView.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    activeQuery = s == null ? "" : s.toString().trim();
                    if (pendingSearch != null) mainHandler.removeCallbacks(pendingSearch);
                    pendingSearch = () -> {
                        if (destroyed || !dialog.isShowing()) return;
                        if (currentProvider != null && treeRootDirectory != null) loadDirectory();
                        else refreshHome();
                    };
                    mainHandler.postDelayed(pendingSearch, 250L);
                }
                @Override public void afterTextChanged(Editable s) { }
            });

            breadcrumbView = text("/", 12, 0xFF969EA7, false);
            breadcrumbView.setSingleLine(true);
            breadcrumbView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            breadcrumbView.setPadding(dp(14), 0, dp(12), 0);
            rightPane.addView(breadcrumbView, new LinearLayout.LayoutParams(-1, dp(32)));

            actionBar = new LinearLayout(activity);
            actionBar.setGravity(Gravity.CENTER_VERTICAL);
            actionBar.setPadding(dp(6), 0, dp(6), 0);
            actionBar.setBackgroundColor(0xFF1D1F26);
            HorizontalScrollView actionScroll = new HorizontalScrollView(activity);
            actionScroll.setHorizontalScrollBarEnabled(false);
            actionScroll.setFillViewport(false);
            actionScroll.addView(actionBar, new HorizontalScrollView.LayoutParams(-2, -1));
            rightPane.addView(actionScroll, new LinearLayout.LayoutParams(-1, dp(42)));

            FrameLayout listFrame = new FrameLayout(activity);
            rightPane.addView(listFrame, new LinearLayout.LayoutParams(-1, 0, 1));
            treeViewportWidth = Math.max(1, panelWidth - dp(70));
            listView = new ListView(activity);
            listView.setDivider(null);
            listView.setDividerHeight(0);
            listView.setCacheColorHint(Color.TRANSPARENT);
            listView.setBackgroundColor(0xFF13151B);
            listView.setAdapter(adapter);
            listView.setSelector(android.R.color.transparent);

            treeHorizontalScroll = new HorizontalScrollView(activity) {
                private float downX;
                private float downY;
                private final int touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();

                @Override
                public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
                    // Keep horizontal gestures available to this scroll container.
                    // onInterceptTouchEvent() still lets ListView handle vertical gestures.
                    super.requestDisallowInterceptTouchEvent(false);
                }

                @Override
                public boolean onInterceptTouchEvent(MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = event.getX();
                            downY = event.getY();
                            break;
                        case MotionEvent.ACTION_MOVE:
                            float deltaX = Math.abs(event.getX() - downX);
                            float deltaY = Math.abs(event.getY() - downY);
                            View content = getChildAt(0);
                            boolean contentCanScroll = content != null
                                && content.getWidth() > getWidth();
                            // ListView handles vertical scrolling. Claim only a clear
                            // horizontal swipe when the tree is wider than its viewport.
                            if (contentCanScroll && deltaX > touchSlop && deltaX > deltaY) {
                                return true;
                            }
                            break;
                    }
                    return super.onInterceptTouchEvent(event);
                }
            };
            treeHorizontalScroll.setHorizontalScrollBarEnabled(false);
            treeHorizontalScroll.setVerticalScrollBarEnabled(false);
            treeHorizontalScroll.setFillViewport(false);
            treeHorizontalScroll.setSmoothScrollingEnabled(true);
            treeHorizontalScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            treeContentContainer = new FrameLayout(activity);
            treeContentContainer.setLayoutParams(
                new HorizontalScrollView.LayoutParams(treeViewportWidth, -1));
            treeContentContainer.addView(listView,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            treeHorizontalScroll.addView(treeContentContainer);
            listFrame.addView(treeHorizontalScroll, new FrameLayout.LayoutParams(-1, -1));

            emptyView = text("No files", 14, 0xFF969EA7, false);
            emptyView.setGravity(Gravity.CENTER);
            emptyView.setVisibility(View.GONE);
            listFrame.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
            listView.setOnItemClickListener((parent, view, position, id) -> onRowClick(rows.get(position)));
            listView.setOnItemLongClickListener((parent, view, position, id) -> {
                Row row = rows.get(position);
                if (row.kind == RowKind.ENTRY || row.kind == RowKind.FAVORITE || row.kind == RowKind.RECENT) {
                    if (selectionMode) toggleSelection(row.entry); else showEntryMenu(view, row.entry);
                    return true;
                }
                if (row.kind == RowKind.LOCATION) {
                    showLocationMenu(view, row.provider);
                    return true;
                }
                return false;
            });

            selectionBar = new LinearLayout(activity);
            selectionBar.setGravity(Gravity.CENTER_VERTICAL);
            selectionBar.setPadding(dp(10), dp(6), dp(10), dp(6));
            selectionBar.setBackgroundColor(0xFF13151B);
            rightPane.addView(selectionBar, new LinearLayout.LayoutParams(-1, dp(58)));
            rebuildSelectionBar();
            rebuildActionBar();
            rebuildStorageRail();
            dialog.setContentView(root);
        }

        private void rebuildStorageRail() {
            storageRail.removeAllViews();

            ImageView brand = new ImageView(activity);
            brand.setImageResource(R.mipmap.ic_yummy_logo);
            brand.setScaleType(ImageView.ScaleType.FIT_CENTER);
            brand.setBackgroundColor(Color.TRANSPARENT);

            LinearLayout.LayoutParams brandLp =
            new LinearLayout.LayoutParams(dp(40), dp(40));

            brandLp.setMargins(0, dp(13), 0, dp(16));
    
            storageRail.addView(brand, brandLp);








            ScrollView storageScroll = new ScrollView(activity);
            storageScroll.setFillViewport(false);
            storageScroll.setVerticalScrollBarEnabled(false);
            storageNavContainer = new LinearLayout(activity);
            storageNavContainer.setOrientation(LinearLayout.VERTICAL);
            storageNavContainer.setGravity(Gravity.CENTER_HORIZONTAL);
            storageScroll.addView(storageNavContainer, new ScrollView.LayoutParams(-1, -2));
            storageRail.addView(storageScroll, new LinearLayout.LayoutParams(-1, 0, 1));

            List<StorageProvider> providers = new ArrayList<>(registry.getProviders());
            Collections.sort(providers, (a, b) -> Integer.compare(storageOrder(a), storageOrder(b)));
            for (StorageProvider provider : providers) {
                String id = provider.getId();
                String label = storageLabel(provider);
                addRailItem(FileManagerIconResolver.storage(provider), label,
                    currentProvider != null && id.equals(currentProvider.getId()), () -> openStorage(provider));
            }

            View spacer = new View(activity);
            storageRail.addView(spacer, new LinearLayout.LayoutParams(1, dp(4)));
            addRailItem(R.drawable.ic_fm_storage_all, "Storage", showingLocations, this::refreshHome);
            addRailItem(R.drawable.ic_fm_storage_add, "Add", false, this::requestAddStorage);
        }

        private int storageOrder(StorageProvider provider) {
            if ("yummy.home".equals(provider.getId())) return 0;
            if ("yummy.files".equals(provider.getId())) return 1;
            return 2;
        }

        private String storageLabel(StorageProvider provider) {
            if ("yummy.home".equals(provider.getId())) return "Local";
            if ("yummy.files".equals(provider.getId())) return "Private";
            return provider.getLocation().getName();
        }

        private void addRailItem(int iconResource, String label, boolean selected, Runnable action) {
            LinearLayout item = new LinearLayout(activity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(2), dp(5), dp(2), dp(5));
            item.setBackgroundColor(Color.TRANSPARENT);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(dp(64), dp(72));
            itemLp.setMargins(0, dp(3), 0, dp(4));
            storageNavContainer.addView(item, itemLp);
            ImageView icon = new ImageView(activity);
            icon.setImageResource(iconResource);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setContentDescription(label);
            icon.setColorFilter(0xFFE8EAED, android.graphics.PorterDuff.Mode.SRC_IN);
            icon.setBackground(selected ? roundRect(0xFF292C35, dp(12)) :
                new ColorDrawable(Color.TRANSPARENT));
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(38), dp(38));
            iconLp.gravity = Gravity.CENTER_HORIZONTAL;
            icon.setPadding(dp(7), dp(7), dp(7), dp(7));
            item.addView(icon, iconLp);
            TextView name = text(label, 10, selected ? 0xFFE8EAED : 0xFF969EA7, false);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.addView(name, new LinearLayout.LayoutParams(-1, dp(25)));
            item.setOnClickListener(v -> action.run());
            item.setContentDescription(label);
        }

        private void requestAddStorage() {
            try {
                activity.startActivityForResult(StorageTreePicker.createOpenTreeIntent(), REQUEST_ADD_STORAGE);
            } catch (RuntimeException error) {
                showError("Folder picker tidak tersedia", error);
            }
        }

        void onStorageAdded(StorageProvider provider) {
            showingLocations = false;
            rebuildStorageRail();
            openStorage(provider);
        }

        private void rebuildActionBar() {
            actionBar.removeAllViews();
            addAction("New file", R.drawable.ic_fm_add_file, () -> createNew(false));
            addAction("New folder", R.drawable.ic_fm_add_folder, () -> createNew(true));
            addAction("Sort", R.drawable.ic_fm_sort, this::showSortMenu);
            addAction(selectionMode ? "Done" : "Select",
                selectionMode ? R.drawable.ic_fm_done : R.drawable.ic_fm_select, () -> {
                selectionMode = !selectionMode;
                if (!selectionMode) selection.clear();
                rebuildActionBar();
                rebuildSelectionBar();
                adapter.notifyDataSetChanged();
            });
            addAction("More", R.drawable.ic_fm_more, this::showMoreMenu);
            if (saveAsMode) addAction("Save here", R.drawable.ic_fm_save, this::saveAsHere);
        }

        private void addAction(String label, int iconResource, Runnable action) {
            TextView button = textButton(label, 12, 0xFFD9E0E7);
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(iconResource, 0, 0, 0);
            setStartCompoundIconSize(button, 16);
            button.setCompoundDrawablePadding(dp(5));
            button.setPadding(dp(9), 0, dp(9), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
            actionBar.addView(button, lp);
            button.setOnClickListener(v -> action.run());
        }

        private void rebuildSelectionBar() {
            selectionBar.removeAllViews();
            if (!selectionMode && pendingTransfer == PendingTransfer.NONE && !saveAsMode) {
                selectionBar.setVisibility(View.GONE);
                return;
            }
            selectionBar.setVisibility(View.VISIBLE);
            if (pendingTransfer != PendingTransfer.NONE || saveAsMode) {
                TextView label = text(pendingTransfer != PendingTransfer.NONE
                    ? "Pilih folder tujuan" : "Simpan editor ke folder ini", 12, 0xFFDCE3EA, true);
                selectionBar.addView(label, new LinearLayout.LayoutParams(0, -1, 1));
                useFolderButton = button(pendingTransfer != PendingTransfer.NONE ? "Gunakan folder ini" : "Simpan di sini");
                selectionBar.addView(useFolderButton, new LinearLayout.LayoutParams(-2, dp(40)));
                useFolderButton.setEnabled(currentProvider != null && currentDirectory != null);
                useFolderButton.setOnClickListener(v -> {
                    if (pendingTransfer != PendingTransfer.NONE) executeTransfer();
                    else saveAsHere();
                });
                return;
            }
            selectionCountView = text(selection.size() + " selected", 12, 0xFFE8EAED, true);
            selectionBar.addView(selectionCountView, new LinearLayout.LayoutParams(0, -1, 1));
            addFooterAction("Copy", R.drawable.ic_fm_copy, () -> beginTransfer(PendingTransfer.COPY));
            addFooterAction("Move", R.drawable.ic_fm_move, () -> beginTransfer(PendingTransfer.MOVE));
            addFooterAction("Delete", R.drawable.ic_fm_delete, this::deleteSelection);
            addFooterAction("Cancel", R.drawable.ic_fm_cancel, () -> {
                selection.clear(); selectionMode = false;
                rebuildActionBar(); rebuildSelectionBar(); adapter.notifyDataSetChanged();
            });
        }

        private void addFooterAction(String label, int iconResource, Runnable action) {
            TextView button = textButton(label, 12, 0xFFB9C6D3);
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(iconResource, 0, 0, 0);
            setStartCompoundIconSize(button, 16);
            button.setCompoundDrawablePadding(dp(5));
            button.setPadding(dp(6), 0, dp(6), 0);
            selectionBar.addView(button, new LinearLayout.LayoutParams(-2, -1));
            button.setOnClickListener(v -> action.run());
        }

        private void refreshHome() {
            refreshHome(true);
        }

        private void refreshHome(boolean persist) {
            if (persist) preserveUnavailableSession = false;
            showingLocations = true;
            loadGeneration++;
            if (pendingSearch != null) {
                mainHandler.removeCallbacks(pendingSearch);
                pendingSearch = null;
            }
            currentProvider = null;
            currentDirectory = null;
            treeRootDirectory = null;
            backStack.clear();
            expandedTreeKeys.clear();
            treeChildren.clear();
            treeEntries.clear();
            titleView.setText("Storage");
            breadcrumbView.setText("Connected storage locations");
            rows.clear();
            rows.add(Row.section("STORAGE LOCATIONS"));
            for (StorageProvider provider : registry.getProviders()) rows.add(Row.location(provider));
            List<StorageEntry> favorites = readEntryList(KEY_FAVORITES);
            if (!favorites.isEmpty()) {
                rows.add(Row.section("FAVORITES"));
                for (StorageEntry entry : favorites) {
                    StorageProvider provider = registry.getProvider(entry.getProviderId());
                    if (provider != null) rows.add(Row.entry(RowKind.FAVORITE, provider, entry));
                }
            }
            List<StorageEntry> recents = readEntryList(KEY_RECENTS);
            if (!recents.isEmpty()) {
                rows.add(Row.section("RECENT FILES"));
                for (StorageEntry entry : recents) {
                    StorageProvider provider = registry.getProvider(entry.getProviderId());
                    if (provider != null) rows.add(Row.entry(RowKind.RECENT, provider, entry));
                }
            }
            filterHomeRows();
            rebuildStorageRail();
            rebuildActionBar();
            rebuildSelectionBar();
            updateTreeHorizontalWidth();
            adapter.notifyDataSetChanged();
            updateEmptyState();
            if (persist) persistSession();
        }

        private void filterHomeRows() {
            if (activeQuery.isEmpty()) return;
            String query = activeQuery.toLowerCase(Locale.ROOT);
            List<Row> filtered = new ArrayList<>();
            String currentSection = null;
            for (Row row : new ArrayList<>(rows)) {
                if (row.kind == RowKind.SECTION) { currentSection = row.label; continue; }
                String candidate = row.kind == RowKind.LOCATION
                    ? row.provider.getLocation().getName() : row.entry.getName();
                if (candidate.toLowerCase(Locale.ROOT).contains(query)) {
                    if (currentSection != null && (filtered.isEmpty()
                        || filtered.get(filtered.size() - 1).kind != RowKind.SECTION
                        || !currentSection.equals(filtered.get(filtered.size() - 1).label))) {
                        filtered.add(Row.section(currentSection));
                    }
                    filtered.add(row);
                }
            }
            rows.clear(); rows.addAll(filtered);
        }

        private void openStorage(StorageProvider provider) {
            preserveUnavailableSession = false;
            if (!provider.isAvailable()) {
                toast("Storage tidak tersedia: " + provider.getLocation().getName());
                return;
            }
            showingLocations = false;
            rebuildStorageRail();
            final int generation = ++loadGeneration;
            ioExecutor.execute(() -> {
                try {
                    StorageEntry rootEntry = provider.getRootEntry();
                    mainHandler.post(() -> {
                        if (destroyed || !dialog.isShowing() || generation != loadGeneration) return;
                        currentProvider = provider;
                        treeRootDirectory = rootEntry;
                        currentDirectory = rootEntry;
                        backStack.clear();
                        expandedTreeKeys.clear();
                        treeChildren.clear();
                        treeEntries.clear();
                        treeEntries.put(treeKey(rootEntry), rootEntry);
                        activeQuery = searchView.getText().toString().trim();
                        updateBreadcrumb();
                        rebuildStorageRail();
                        loadDirectory();
                        persistSession();
                    });
                } catch (IOException | RuntimeException error) {
                    mainHandler.post(() -> showError("Tidak dapat membuka storage", error));
                }
            });
        }

        private void navigateTo(StorageEntry directory) {
            if (currentProvider == null || !directory.isDirectory()) return;
            if (currentDirectory != null && !sameEntry(currentDirectory, directory)) backStack.push(currentDirectory);
            currentDirectory = directory;
            expandedTreeKeys.add(treeKey(directory));
            updateBreadcrumb();
            rebuildSelectionBar();
            persistSession();
        }

        private void goBack() {
            if (currentProvider != null && currentDirectory != null
                && treeRootDirectory != null && !sameEntry(currentDirectory, treeRootDirectory)
                && !backStack.isEmpty()) {
                expandedTreeKeys.remove(treeKey(currentDirectory));
                currentDirectory = backStack.pop();
                updateBreadcrumb();
                rebuildTreeRows();
                rebuildSelectionBar();
                persistSession();
                return;
            }
            if (saveAsMode) {
                // Back cancels the Save popup instead of leaving a hidden save-mode state.
                dismiss();
                return;
            }
            if (pendingTransfer != PendingTransfer.NONE) {
                pendingTransfer = PendingTransfer.NONE;
                selection.clear();
                rebuildActionBar();
                rebuildSelectionBar();
                return;
            }
            if (currentProvider != null) {
                refreshHome();
            } else {
                dismiss();
            }
        }

        private void updateBreadcrumb() {
            if (currentProvider == null || currentDirectory == null) {
                breadcrumbView.setText("Connected storage locations");
                titleView.setText("Storage");
                return;
            }
            List<String> pathParts = new ArrayList<>();
            StorageEntry cursor = currentDirectory;
            Set<String> pathGuard = new HashSet<>();
            while (cursor != null && pathGuard.add(treeKey(cursor))) {
                if (cursor.isRoot()) break;
                pathParts.add(cursor.getName());
                String parentId = cursor.getParentDocumentId();
                cursor = parentId == null ? null : treeEntries.get(
                    currentProvider.getId() + '\u0000' + parentId);
            }
            Collections.reverse(pathParts);
            String path = currentProvider.getLocation().getName() + "  /  /";
            if (!pathParts.isEmpty()) path += android.text.TextUtils.join(" / ", pathParts);
            breadcrumbView.setText(path);
            titleView.setText(storageLabel(currentProvider));
        }

        private String treeKey(StorageEntry entry) {
            return entry.getProviderId() + '\u0000' + entry.getDocumentId();
        }

        private void rebuildTreeRows() {
            rows.clear();
            if (treeRootDirectory == null || currentProvider == null) {
                updateTreeHorizontalWidth();
                adapter.notifyDataSetChanged();
                updateEmptyState();
                return;
            }
            if (!activeQuery.isEmpty()) {
                for (StorageEntry entry : currentEntries) rows.add(Row.tree(currentProvider, entry, 0, false));
            } else {
                rows.add(Row.tree(currentProvider, treeRootDirectory, 0, true));
                appendTreeRows(treeRootDirectory, 1, new HashSet<>());
            }
            adapter.notifyDataSetChanged();
            updateTreeHorizontalWidth();
            updateEmptyState();
        }

        /**
         * Size the horizontal content for both folder indentation and the widest visible
         * label. ListView keeps its normal vertical scrolling while the parent handles X.
         */
        private void updateTreeHorizontalWidth() {
            if (listView == null || treeHorizontalScroll == null) return;

            Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            namePaint.setTypeface(nunitoTypeface(false));
            namePaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 14f,
                activity.getResources().getDisplayMetrics()));

            Paint subtitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            subtitlePaint.setTypeface(nunitoTypeface(false));
            subtitlePaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 10f,
                activity.getResources().getDisplayMetrics()));

            // Row fixed parts: disclosure (16 + 4), file icon (16 + 6),
            // selection indicator (20), and right padding (8) dp.
            final int rowFixedWidth = dp(70);
            int requiredWidth = treeViewportWidth;
            for (Row row : rows) {
                if (row == null) continue;

                if ((row.kind == RowKind.ENTRY || row.kind == RowKind.FAVORITE
                    || row.kind == RowKind.RECENT) && row.entry != null) {
                    String label = row.entry.isRoot() ? "/" : row.entry.getName();
                    int indentWidth = Math.max(0, row.depth) * dp(18);
                    int textWidth = (int) Math.ceil(namePaint.measureText(
                        label == null ? "" : label));
                    requiredWidth = Math.max(requiredWidth,
                        indentWidth + rowFixedWidth + textWidth + dp(2));
                } else if (row.kind == RowKind.LOCATION && row.provider != null) {
                    String label = storageLabel(row.provider);
                    String description = row.provider.getLocation().getDescription();
                    if (description == null) {
                        description = row.provider.isAvailable() ? "Available" : "Unavailable";
                    }
                    int textWidth = (int) Math.ceil(Math.max(
                        namePaint.measureText(label == null ? "" : label),
                        subtitlePaint.measureText(description)));
                    requiredWidth = Math.max(requiredWidth,
                        dp(8) + rowFixedWidth + textWidth + dp(2));
                } else if (row.kind == RowKind.SECTION && row.label != null) {
                    Paint sectionPaint = new Paint(namePaint);
                    sectionPaint.setTypeface(nunitoTypeface(true));
                    sectionPaint.setTextSize(subtitlePaint.getTextSize());
                    int textWidth = (int) Math.ceil(sectionPaint.measureText(row.label));
                    requiredWidth = Math.max(requiredWidth, dp(14 + 12) + textWidth);
                }
            }

            if (treeContentContainer == null) return;
            ViewGroup.LayoutParams contentParams = treeContentContainer.getLayoutParams();
            if (contentParams == null) {
                contentParams = new HorizontalScrollView.LayoutParams(requiredWidth, -1);
            }
            if (contentParams.width != requiredWidth || contentParams.height != -1) {
                contentParams.width = requiredWidth;
                contentParams.height = -1;
                treeContentContainer.setLayoutParams(contentParams);
            }
            // The wrapper owns the overflow width. Keep ListView exactly as wide as
            // that wrapper so each row is laid out against the full horizontal canvas.
            ViewGroup.LayoutParams listParams = listView.getLayoutParams();
            if (listParams == null) {
                listParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            } else {
                listParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                listParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            }
            listView.setLayoutParams(listParams);
            listView.requestLayout();
            treeContentContainer.requestLayout();
            treeHorizontalScroll.requestLayout();
            treeHorizontalScroll.invalidate();
            scheduleTreeWidthDebug();
        }

        /**
         * Show the measurements after each File Tree rebuild/navigation, not just once
         * when the sidebar first opens. A short debounce lets ListView finish measuring
         * the newly loaded rows before the diagnostic is collected.
         */
        private void scheduleTreeWidthDebug() {
            if (treeContentContainer == null || treeHorizontalScroll == null || listView == null) return;
            if (pendingTreeWidthDebug != null) {
                treeContentContainer.removeCallbacks(pendingTreeWidthDebug);
            }
            pendingTreeWidthDebug = () -> {
                pendingTreeWidthDebug = null;
                if (destroyed || !dialog.isShowing()) return;
                showTreeWidthDebugNow();
            };
            treeContentContainer.postDelayed(pendingTreeWidthDebug, 300);
        }

        /** Collect actual laid-out widths after the current list contents are rendered. */
        private void showTreeWidthDebugNow() {
            if (treeContentContainer == null || treeHorizontalScroll == null || listView == null) return;

            StringBuilder info = new StringBuilder();
                info.append("TREE WIDTH DEBUG\n\n")
                    .append("Viewport: ").append(treeHorizontalScroll.getWidth()).append(" px\n")
                    .append("Content: ").append(treeContentContainer.getWidth()).append(" px\n")
                    .append("ListView: ").append(listView.getWidth()).append(" px\n")
                    .append("ScrollX: ").append(treeHorizontalScroll.getScrollX()).append(" px\n")
                    .append("Visible rows: ").append(listView.getChildCount()).append('\n');

                if (listView.getChildCount() > 0) {
                    View row = listView.getChildAt(0);
                    info.append("First row: ").append(row.getWidth()).append(" px\n");
                    if (row instanceof ViewGroup) {
                        ViewGroup rowGroup = (ViewGroup) row;
                        info.append("Row children: ").append(rowGroup.getChildCount()).append('\n');
                        for (int i = 0; i < rowGroup.getChildCount(); i++) {
                            View child = rowGroup.getChildAt(i);
                            info.append("  Child[").append(i).append("]: ")
                                .append(child.getClass().getSimpleName()).append(" = ")
                                .append(child.getWidth()).append(" px\n");
                            if (child instanceof ViewGroup) {
                                ViewGroup group = (ViewGroup) child;
                                for (int j = 0; j < group.getChildCount(); j++) {
                                    View nested = group.getChildAt(j);
                                    if (nested instanceof TextView) {
                                        TextView textView = (TextView) nested;
                                        info.append("    Text[").append(j).append("]: width=")
                                            .append(textView.getWidth()).append(" px, text=\"")
                                            .append(textView.getText()).append("\", ellipsize=")
                                            .append(textView.getEllipsize()).append('\n');
                                    }
                                }
                            }
                        }
                    }
                }

            new android.app.AlertDialog.Builder(activity)
                .setTitle("File Tree Width Debug")
                .setMessage(info.toString())
                .setPositiveButton("OK", null)
                .show();
        }

        private void appendTreeRows(StorageEntry parent, int depth, Set<String> visited) {
            String parentKey = treeKey(parent);
            if (!visited.add(parentKey)) return;
            List<StorageEntry> children = treeChildren.get(parentKey);
            if (children == null) return;
            for (StorageEntry entry : children) {
                String key = treeKey(entry);
                treeEntries.put(key, entry);
                boolean expanded = expandedTreeKeys.contains(key);
                rows.add(Row.tree(currentProvider, entry, depth, expanded));
                if (entry.isDirectory() && expanded) appendTreeRows(entry, depth + 1, visited);
            }
        }


        private boolean isWithinDirectory(StorageEntry candidate, StorageEntry ancestor) {
            StorageEntry cursor = candidate;
            Set<String> visited = new HashSet<>();
            while (cursor != null && visited.add(treeKey(cursor))) {
                if (sameEntry(cursor, ancestor)) return true;
                cursor = parentOf(cursor);
            }
            return false;
        }

        private StorageEntry parentOf(StorageEntry entry) {
            if (entry == null || entry.getParentDocumentId() == null) return null;
            return treeEntries.get(entry.getProviderId() + '\u0000' + entry.getParentDocumentId());
        }

        private void toggleTreeDirectory(StorageEntry directory) {
            if (currentProvider == null || !directory.isDirectory()) return;
            String key = treeKey(directory);
            if (expandedTreeKeys.contains(key)) {
                expandedTreeKeys.remove(key);
                if (currentDirectory != null && isWithinDirectory(currentDirectory, directory)) {
                    currentDirectory = parentOf(directory);
                    if (currentDirectory == null) currentDirectory = treeRootDirectory;
                    backStack.clear();
                }
                updateBreadcrumb();
                rebuildTreeRows();
                rebuildSelectionBar();
                persistSession();
                return;
            }

            expandedTreeKeys.add(key);
            if (currentDirectory != null && !sameEntry(currentDirectory, directory)) backStack.push(currentDirectory);
            currentDirectory = directory;
            updateBreadcrumb();
            rebuildSelectionBar();
            persistSession();
            if (treeChildren.containsKey(key)) {
                rebuildTreeRows();
                return;
            }

            final int generation = loadGeneration;
            final StorageProvider provider = currentProvider;
            showLoading(true);
            ioExecutor.execute(() -> {
                try {
                    List<StorageEntry> children = provider.list(directory);
                    List<StorageEntry> visible = new ArrayList<>();
                    for (StorageEntry child : children) {
                        if (showHidden || !child.getName().startsWith(".")) visible.add(child);
                    }
                    sortEntries(visible, sortMode);
                    mainHandler.post(() -> {
                        if (destroyed || !dialog.isShowing() || generation != loadGeneration
                            || currentProvider == null || !provider.getId().equals(currentProvider.getId())) return;
                        treeChildren.put(key, visible);
                        for (StorageEntry child : visible) treeEntries.put(treeKey(child), child);
                        showLoading(false);
                        rebuildTreeRows();
                        persistSession();
                    });
                } catch (IOException | RuntimeException error) {
                    mainHandler.post(() -> {
                        if (generation != loadGeneration) return;
                        showLoading(false);
                        expandedTreeKeys.remove(key);
                        showError("Tidak dapat membaca folder", error);
                        rebuildTreeRows();
                        persistSession();
                    });
                }
            });
        }

        private void loadDirectory() {
            if (currentProvider == null || treeRootDirectory == null) return;
            final int generation = ++loadGeneration;
            final StorageProvider provider = currentProvider;
            final StorageEntry directory = treeRootDirectory;
            final String query = activeQuery;
            final boolean includeHidden = showHidden;
            final SortMode requestedSort = sortMode;
            showLoading(true);
            ioExecutor.execute(() -> {
                try {
                    List<StorageEntry> entries = query.isEmpty()
                        ? provider.list(directory) : searchRecursively(provider, directory, query, includeHidden);
                    List<StorageEntry> visible = new ArrayList<>();
                    for (StorageEntry entry : entries) {
                        if (includeHidden || !entry.getName().startsWith(".")) visible.add(entry);
                    }
                    sortEntries(visible, requestedSort);
                    mainHandler.post(() -> {
                        if (destroyed || generation != loadGeneration) return;
                        currentEntries = visible;
                        treeChildren.put(treeKey(directory), visible);
                        for (StorageEntry entry : visible) treeEntries.put(treeKey(entry), entry);
                        rows.clear();
                        if (query.isEmpty()) {
                            rows.add(Row.tree(provider, directory, 0, true));
                            appendTreeRows(directory, 1, new HashSet<>());
                        } else {
                            for (StorageEntry entry : visible) rows.add(Row.tree(provider, entry, 0, false));
                        }
                        showLoading(false);
                        rebuildActionBar();
                        rebuildSelectionBar();
                        rebuildStorageRail();
                        adapter.notifyDataSetChanged();
                        updateTreeHorizontalWidth();
                        updateEmptyState();
                        persistSession();
                    });
                } catch (IOException | RuntimeException error) {
                    mainHandler.post(() -> {
                        if (generation != loadGeneration) return;
                        showLoading(false);
                        showError("Gagal membaca folder", error);
                    });
                }
            });
        }

        private List<StorageEntry> searchRecursively(StorageProvider provider,
                                                     StorageEntry start,
                                                     String query,
                                                     boolean includeHidden) throws IOException {
            List<StorageEntry> matches = new ArrayList<>();
            Deque<StorageEntry> pending = new ArrayDeque<>();
            Set<String> visited = new HashSet<>();
            pending.add(start);
            String needle = query.toLowerCase(Locale.ROOT);
            int visitedCount = 0;
            while (!pending.isEmpty()) {
                StorageEntry directory = pending.removeFirst();
                String key = directory.getProviderId() + '\u0000' + directory.getDocumentId();
                if (!visited.add(key)) continue;
                for (StorageEntry child : provider.list(directory)) {
                    if (++visitedCount > MAX_SEARCH_ENTRIES) return matches;
                    if (includeHidden || !child.getName().startsWith(".")) {
                        if (child.getName().toLowerCase(Locale.ROOT).contains(needle)) matches.add(child);
                        if (child.isDirectory()) pending.addLast(child);
                    }
                }
            }
            return matches;
        }

        private void sortEntries(List<StorageEntry> entries, SortMode mode) {
            Comparator<StorageEntry> comparator;
            switch (mode) {
                case TYPE:
                    comparator = Comparator.comparing((StorageEntry entry) -> entry.isDirectory() ? "0" : "1")
                        .thenComparing(entry -> FileTypeResolver.resolve(entry.getName(), false, entry.getMimeType()).getLabel(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(StorageEntry::getName, String.CASE_INSENSITIVE_ORDER);
                    break;
                case SIZE:
                    comparator = Comparator.comparing((StorageEntry entry) -> entry.isDirectory() ? "0" : "1")
                        .thenComparing(Comparator.comparingLong(StorageEntry::getSize).reversed())
                        .thenComparing(StorageEntry::getName, String.CASE_INSENSITIVE_ORDER);
                    break;
                case MODIFIED:
                    comparator = Comparator.comparing((StorageEntry entry) -> entry.isDirectory() ? "0" : "1")
                        .thenComparing(Comparator.comparingLong(StorageEntry::getLastModified).reversed())
                        .thenComparing(StorageEntry::getName, String.CASE_INSENSITIVE_ORDER);
                    break;
                case NAME:
                default:
                    comparator = Comparator.comparing((StorageEntry entry) -> entry.isDirectory() ? "0" : "1")
                        .thenComparing(StorageEntry::getName, String.CASE_INSENSITIVE_ORDER);
                    break;
            }
            entries.sort(comparator);
        }

        private void onRowClick(Row row) {
            if (row.kind == RowKind.SECTION) return;
            if (row.kind == RowKind.LOCATION) {
                openStorage(row.provider);
                return;
            }
            if (row.entry == null || row.provider == null) return;
            if (row.entry.isRoot() && treeRootDirectory != null
                && sameEntry(row.entry, treeRootDirectory)) return;
            if (selectionMode) {
                toggleSelection(row.entry);
                return;
            }
            if (pendingTransfer != PendingTransfer.NONE || saveAsMode) {
                if (row.entry.isDirectory()) {
                    if (currentProvider == null || !row.provider.getId().equals(currentProvider.getId())) {
                        openStoredDirectory(row.provider, row.entry);
                    } else if (!expandedTreeKeys.contains(treeKey(row.entry))) {
                        toggleTreeDirectory(row.entry);
                    } else {
                        if (currentDirectory != null && !sameEntry(currentDirectory, row.entry)) {
                            backStack.push(currentDirectory);
                        }
                        currentDirectory = row.entry;
                        updateBreadcrumb();
                        rebuildSelectionBar();
                        persistSession();
                    }
                }
                return;
            }
            if (row.entry.isDirectory()) {
                if (currentProvider == null || !currentProvider.getId().equals(row.provider.getId())) {
                    openStoredDirectory(row.provider, row.entry);
                } else {
                    toggleTreeDirectory(row.entry);
                }
            } else {
                openFile(row.provider, row.entry, null);
            }
        }

        private void openStoredDirectory(StorageProvider provider, StorageEntry entry) {
            final int generation = ++loadGeneration;
            ioExecutor.execute(() -> {
                try {
                    mainHandler.post(() -> {
                        if (destroyed || !dialog.isShowing() || generation != loadGeneration) return;
                        currentProvider = provider;
                        treeRootDirectory = entry;
                        currentDirectory = entry;
                        backStack.clear();
                        expandedTreeKeys.clear();
                        treeChildren.clear();
                        treeEntries.clear();
                        treeEntries.put(treeKey(entry), entry);
                        showingLocations = false;
                        rebuildStorageRail();
                        updateBreadcrumb();
                        loadDirectory();
                        persistSession();
                    });
                } catch (RuntimeException error) {
                    mainHandler.post(() -> showError("Folder tidak tersedia", error));
                }
            });
        }

        private void toggleSelection(StorageEntry entry) {
            selection.toggle(entry);
            rebuildSelectionBar();
            adapter.notifyDataSetChanged();
        }

        private void showEntryMenu(View anchor, StorageEntry entry) {

            PopupMenu menu = new PopupMenu(new ContextThemeWrapper(activity, android.R.style.Theme_Material), anchor);

            if (entry.isDirectory()) menu.getMenu().add("Open");
            menu.getMenu().add("Add to Favorites");
            if (!entry.isRoot() && entry.hasCapability(StorageCapability.RENAME)) menu.getMenu().add("Rename");
            if (!entry.isRoot() && entry.hasCapability(StorageCapability.DELETE)) menu.getMenu().add("Delete");
            menu.getMenu().add("Copy to…");
            menu.getMenu().add("Move to…");
            if (!entry.isDirectory()) {
                menu.getMenu().add("Share");
                menu.getMenu().add("Open externally");
            }
            menu.getMenu().add("Info");
            menu.setOnMenuItemClickListener(item -> {
                String action = item.getTitle().toString();
                if ("Open".equals(action) && entry.isDirectory()) {
                    currentProvider = registry.getProvider(entry.getProviderId());
                    if (currentProvider != null) openStoredDirectory(currentProvider, entry);
                } else if ("Add to Favorites".equals(action)) {
                    toggleFavorite(entry);
                } else if ("Rename".equals(action)) {
                    renameEntry(entry);
                } else if ("Delete".equals(action)) {
                    confirmDelete(entry);
                } else if ("Copy to…".equals(action)) {
                    selection.clear(); selection.add(entry); beginTransfer(PendingTransfer.COPY);
                } else if ("Move to…".equals(action)) {
                    selection.clear(); selection.add(entry); beginTransfer(PendingTransfer.MOVE);
                } else if ("Share".equals(action)) {
                    shareOrOpenExternally(registry.getProvider(entry.getProviderId()), entry, false);
                } else if ("Open externally".equals(action)) {
                    shareOrOpenExternally(registry.getProvider(entry.getProviderId()), entry, true);
                } else if ("Info".equals(action)) {
                    showInfo(entry);
                }
                return true;
            });
            menu.show();
        }

        private void showLocationMenu(View anchor, StorageProvider provider) {

            PopupMenu menu = new PopupMenu(new ContextThemeWrapper(activity, android.R.style.Theme_Material), anchor);

            menu.getMenu().add("Open");
            if (provider instanceof SafTreeStorageProvider) menu.getMenu().add("Remove storage");
            menu.setOnMenuItemClickListener(item -> {
                if ("Open".contentEquals(item.getTitle())) openStorage(provider);
                else if ("Remove storage".contentEquals(item.getTitle())) {
                    nunitoDialogBuilder().setTitle("Remove storage")
                        .setMessage("Lepaskan " + provider.getLocation().getName() + " dari Yummy? File tidak akan dihapus.")
                        .setPositiveButton("Lepaskan", (d, w) -> {
                            registry.removeSafLocation(provider.getId()); refreshHome();
                        }).setNegativeButton("Batal", null).show();
                }
                return true;
            });
            menu.show();
        }

        private void renameEntry(StorageEntry entry) {
            EditText input = new EditText(activity);
            input.setSingleLine(true);
            input.setText(entry.getName());
            input.setSelection(0, input.getText().length());
            nunitoDialogBuilder().setTitle("Rename") .setView(input)
                .setPositiveButton("Simpan", (dialog, which) -> {
                    StorageProvider provider = registry.getProvider(entry.getProviderId());
                    if (provider == null) return;
                    String newName = input.getText().toString().trim();
                    ioExecutor.execute(() -> {
                        try {
                            StorageEntry renamed = fileOperations.rename(provider.getId(), entry, newName);
                            mainHandler.post(() -> {
                                updateOpenFileEntry(entry, renamed, provider);
                                replaceStoredEntry(KEY_FAVORITES, entry, renamed);
                                replaceStoredEntry(KEY_RECENTS, entry, renamed);
                                refreshVisible();
                            });
                        } catch (IOException | RuntimeException error) {
                            mainHandler.post(() -> showError("Rename gagal", error));
                        }
                    });
                }).setNegativeButton("Batal", null).show();
        }

        private void replaceStoredEntry(String key, StorageEntry oldEntry, StorageEntry newEntry) {
            List<StorageEntry> values = readEntryList(key);
            boolean changed = false;
            for (int i = 0; i < values.size(); i++) {
                if (sameEntry(values.get(i), oldEntry)) { values.set(i, newEntry); changed = true; }
            }
            if (changed) writeEntryList(key, values);
        }

        private void confirmDelete(StorageEntry entry) {
            String message = entry.isDirectory()
                ? "Hapus folder " + entry.getName() + " beserta seluruh isinya?"
                : "Hapus file " + entry.getName() + "?";
            nunitoDialogBuilder().setTitle("Delete").setMessage(message)
                .setPositiveButton("Hapus", (dialog, which) -> {
                    if (findDirtyOpenDocumentId(entry) != null) {
                        toast("Simpan atau tutup tab yang masih memiliki perubahan sebelum menghapus file ini");
                        return;
                    }
                    final StorageProvider activeProviderSnapshot = activeFileProvider;
                    final StorageEntry activeEntrySnapshot = activeFileEntry;
                    ioExecutor.execute(() -> {
                        FileOperationResult result = fileOperations.delete(entry.getProviderId(), entry,
                            FileOperationOptions.builder().build());
                        final boolean activeStillExists = activeProviderSnapshot == null
                            || activeEntrySnapshot == null || activeProviderSnapshot.exists(activeEntrySnapshot);
                        mainHandler.post(() -> {
                            if (!activeStillExists) syncActiveFile(editorController.getActiveDocumentId());
                            if (result != null && result.getDeletedEntries() > 0 && !entry.isDirectory()) {
                                closeOpenTabsForEntry(entry);
                            }
                            showDeleteResult(result);
                            refreshVisible();
                        });
                    });
                }).setNegativeButton("Batal", null).show();
        }

        private void deleteSelection() {
            if (selection.isEmpty()) return;
            nunitoDialogBuilder().setTitle("Delete selected")
                .setMessage("Hapus " + selection.size() + " item yang dipilih beserta isi foldernya?")
                .setPositiveButton("Hapus", (dialog, which) -> {
                    StorageSelection snapshot = new StorageSelection();
                    for (StorageEntry entry : selection.getEntries()) snapshot.add(entry);
                    for (StorageEntry entry : snapshot.getEntries()) {
                        if (findDirtyOpenDocumentId(entry) != null) {
                            toast("Simpan atau tutup tab yang masih memiliki perubahan sebelum menghapusnya");
                            return;
                        }
                    }
                    final StorageProvider activeProviderSnapshot = activeFileProvider;
                    final StorageEntry activeEntrySnapshot = activeFileEntry;
                    ioExecutor.execute(() -> {
                        FileOperationResult result = fileOperations.deleteSelected(snapshot,
                            FileOperationOptions.builder().build());
                        final boolean activeStillExists = activeProviderSnapshot == null
                            || activeEntrySnapshot == null || activeProviderSnapshot.exists(activeEntrySnapshot);
                        mainHandler.post(() -> {
                            if (!activeStillExists) syncActiveFile(editorController.getActiveDocumentId());
                            if (result != null && result.getDeletedEntries() > 0) {
                                for (StorageEntry entry : snapshot.getEntries()) {
                                    if (!entry.isDirectory()) closeOpenTabsForEntry(entry);
                                }
                            }
                            selection.clear(); selectionMode = false;
                            rebuildActionBar(); rebuildSelectionBar();
                            showDeleteResult(result); refreshVisible();
                        });
                    });
                }).setNegativeButton("Batal", null).show();
        }

        private void showDeleteResult(FileOperationResult result) {
            if (result == null) return;
            String message = result.getDeletedEntries() + " item dihapus";
            if (!result.getFailures().isEmpty()) message += ", " + result.getFailures().size() + " gagal";
            if (result.getStatus() == FileOperationResult.Status.CANCELLED) message += " (dibatalkan)";
            toast(message);
        }

        private void showInfo(StorageEntry entry) {
            FileTypeResolver.Descriptor type = FileTypeResolver.resolve(entry.getName(), entry.isDirectory(), entry.getMimeType());
            String message = "Name: " + entry.getName()
                + "\nType: " + type.getLabel()
                + "\nMIME: " + entry.getMimeType()
                + "\nSize: " + (entry.isDirectory() ? "—" : humanSize(entry.getSize()))
                + "\nModified: " + (entry.getLastModified() > 0
                    ? DateFormat.getDateTimeInstance().format(new Date(entry.getLastModified())) : "Unknown")
                + "\nStorage: " + entry.getProviderId()
                + "\nCapabilities: " + capabilitySummary(entry);
            nunitoDialogBuilder().setTitle("File information").setMessage(message)
                .setPositiveButton("OK", null).show();
        }

        private String capabilitySummary(StorageEntry entry) {
            List<String> values = new ArrayList<>();
            if (entry.hasCapability(StorageCapability.READ)) values.add("Read");
            if (entry.hasCapability(StorageCapability.WRITE)) values.add("Write");
            if (entry.hasCapability(StorageCapability.LIST)) values.add("List");
            if (entry.hasCapability(StorageCapability.CREATE_CHILD)) values.add("Create");
            if (entry.hasCapability(StorageCapability.RENAME)) values.add("Rename");
            if (entry.hasCapability(StorageCapability.DELETE)) values.add("Delete");
            return values.isEmpty() ? "Read-only/unknown" : android.text.TextUtils.join(", ", values);
        }

        private void createNew(boolean directory) {
            if (currentProvider == null || currentDirectory == null) {
                toast("Pilih folder tujuan terlebih dahulu");
                return;
            }
            if (!currentDirectory.hasCapability(StorageCapability.CREATE_CHILD)) {
                toast("Folder ini tidak mengizinkan pembuatan item");
                return;
            }
            EditText input = new EditText(activity);
            input.setSingleLine(true);
            input.setHint(directory ? "Folder name" : "File name, e.g. main.py");
            nunitoDialogBuilder().setTitle(directory ? "New folder" : "New file")
                .setView(input).setPositiveButton("Create", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    final StorageProvider targetProvider = currentProvider;
                    final StorageEntry targetDirectory = currentDirectory;
                    ioExecutor.execute(() -> {
                        try {
                            StorageEntry created = directory
                                ? fileOperations.createDirectory(targetProvider.getId(), targetDirectory, name)
                                : fileOperations.createFile(targetProvider.getId(), targetDirectory, name);
                            mainHandler.post(() -> {
                                if (destroyed) return;
                                if (!directory) {
                                    String documentId = targetProvider.getId() + "::" + created.getDocumentId();
                                    openFiles.put(documentId, new OpenFileReference(targetProvider, created));
                                    editorController.openStorageDocument(documentId, "", created.getName(),
                                        WorkspaceSessionManager.sha256(new byte[0]));
                                    syncActiveFile(documentId);
                                    dismiss();
                                } else refreshVisible();
                                toast((directory ? "Folder" : "File") + " dibuat: " + name);
                            });
                        } catch (IOException | RuntimeException error) {
                            mainHandler.post(() -> showError("Tidak dapat membuat item", error));
                        }
                    });
                }).setNegativeButton("Batal", null).show();
        }

        private void beginTransfer(PendingTransfer operation) {
            if (selection.isEmpty()) { toast("Belum ada item yang dipilih"); return; }
            pendingTransfer = operation;
            selectionMode = false;
            rebuildActionBar(); rebuildSelectionBar();
            refreshHome();
            pendingTransfer = operation;
            rebuildActionBar(); rebuildSelectionBar();
            toast(operation == PendingTransfer.COPY
                ? "Pilih storage dan folder tujuan untuk Copy" : "Pilih storage dan folder tujuan untuk Move");
        }

        private void executeTransfer() {
            if (pendingTransfer == PendingTransfer.NONE || currentProvider == null || currentDirectory == null) return;
            final PendingTransfer operation = pendingTransfer;
            final List<StorageEntry> selected = selection.getEntries();
            final StorageProvider destinationProvider = currentProvider;
            final StorageEntry destinationDirectory = currentDirectory;
            final StorageProvider activeProviderSnapshot = activeFileProvider;
            final StorageEntry activeEntrySnapshot = activeFileEntry;
            if (selected.isEmpty()) { toast("Tidak ada item yang dipilih"); return; }
            if (operation == PendingTransfer.MOVE) {
                for (StorageEntry source : selected) {
                    if (findDirtyOpenDocumentId(source) != null) {
                        toast("Simpan dokumen yang masih memiliki perubahan sebelum memindahkannya");
                        return;
                    }
                }
            }
            showLoading(true);
            ioExecutor.execute(() -> {
                int success = 0;
                List<String> failures = new ArrayList<>();
                StorageEntry movedActiveEntry = null;
                for (StorageEntry source : selected) {
                    StorageProvider sourceProvider = registry.getProvider(source.getProviderId());
                    if (sourceProvider == null) { failures.add(source.getName() + ": storage tidak ditemukan"); continue; }
                    try {
                        TransferOptions options = TransferOptions.builder(
                            operation == PendingTransfer.COPY
                                ? TransferOptions.Operation.COPY : TransferOptions.Operation.MOVE
                        ).conflictPolicy(TransferOptions.ConflictPolicy.KEEP_BOTH).build();
                        TransferResult result = transferService.transfer(sourceProvider, source,
                            destinationProvider, destinationDirectory, null, options);
                        if (result.getStatus() == TransferResult.Status.SUCCESS) {
                            success++;
                            if (operation == PendingTransfer.MOVE && activeEntrySnapshot != null
                                && sameEntry(source, activeEntrySnapshot)) {
                                movedActiveEntry = result.getDestinationEntry();
                            }
                        } else failures.add(source.getName() + ": " + result.getMessage());
                    } catch (IOException | RuntimeException error) {
                        failures.add(source.getName() + ": " + safeMessage(error));
                    }
                }
                final int copied = success;
                final StorageEntry movedActive = movedActiveEntry;
                final boolean activeSourceStillExists = activeProviderSnapshot == null
                    || activeEntrySnapshot == null || activeProviderSnapshot.exists(activeEntrySnapshot);
                mainHandler.post(() -> {
                    showLoading(false);
                    if (movedActive != null) {
                        String activeDocumentId = editorController.getActiveDocumentId();
                        if (activeDocumentId != null) {
                            openFiles.put(activeDocumentId, new OpenFileReference(destinationProvider, movedActive));
                            persistFileManagerSession();
                            syncActiveFile(activeDocumentId);
                        }
                    } else if (operation == PendingTransfer.MOVE && !activeSourceStillExists) {
                        syncActiveFile(editorController.getActiveDocumentId());
                    }
                    pendingTransfer = PendingTransfer.NONE;
                    selection.clear(); selectionMode = false;
                    rebuildActionBar(); rebuildSelectionBar();
                    toast((operation == PendingTransfer.COPY ? "Copy" : "Move") + " selesai: " + copied
                        + " berhasil, " + failures.size() + " gagal");
                    if (!failures.isEmpty()) {
                        nunitoDialogBuilder().setTitle("Sebagian transfer gagal")
                            .setMessage(android.text.TextUtils.join("\n", failures))
                            .setPositiveButton("OK", null).show();
                    }
                    refreshVisible();
                });
            });
        }

        private void saveAsHere() {
            // Pastikan teks editor sementara sudah menjadi dokumen yang dapat disimpan.
            editorController.ensureDraftDocumentForSave();
            if (currentProvider == null || currentDirectory == null) {
                toast("Pilih folder tujuan terlebih dahulu"); return;
            }
            if (!currentDirectory.hasCapability(StorageCapability.CREATE_CHILD)) {
                toast("Folder ini tidak mengizinkan pembuatan file"); return;
            }
            EditText input = new EditText(activity);
            input.setSingleLine(true);
            input.setTypeface(nunitoTypeface(false));
            input.setTextColor(0xFFE8EAED);
            input.setHintTextColor(0xFF969EA7);
            String defaultName = editorController.getDocumentName();
            input.setText(defaultName == null || defaultName.trim().isEmpty() ? "untitled.txt" : defaultName);
            input.setSelection(0, input.getText().length());
            AlertDialog saveNameDialog = nunitoDialogBuilder().setTitle("Simpan file")
                .setView(input)
                .setPositiveButton("Simpan", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    Runnable afterSave = pendingOpenAfterSave;
                    pendingOpenAfterSave = null;
                    saveAs(currentProvider, currentDirectory, name, afterSave);
                }).setNegativeButton("Batal", null).create();
            saveNameDialog.setOnShowListener(ignored -> applyNunitoToDialog(saveNameDialog));
            saveNameDialog.show();
        }

        private void showSortMenu() {
            PopupMenu menu = new PopupMenu(new ContextThemeWrapper(activity, android.R.style.Theme_Material), actionBar.getChildAt(2));
            menu.getMenu().add("Name"); menu.getMenu().add("Type");
            menu.getMenu().add("Size"); menu.getMenu().add("Modified");
            menu.setOnMenuItemClickListener(item -> {
                String value = item.getTitle().toString();
                sortMode = "Type".equals(value) ? SortMode.TYPE
                    : "Size".equals(value) ? SortMode.SIZE
                    : "Modified".equals(value) ? SortMode.MODIFIED : SortMode.NAME;
                if (currentProvider != null) {
                    for (List<StorageEntry> children : treeChildren.values()) sortEntries(children, sortMode);
                    rebuildTreeRows();
                }
                persistSession();
                return true;
            });
            menu.show();
        }

        private void showMoreMenu() {
            View anchor = actionBar.getChildAt(Math.max(0, actionBar.getChildCount() - 1));

            PopupMenu menu = new PopupMenu(new ContextThemeWrapper(activity, android.R.style.Theme_Material), anchor);

            menu.getMenu().add(showHidden ? "Hide hidden files" : "Show hidden files");
            menu.getMenu().add("Refresh");
            menu.getMenu().add("Locations");
            menu.getMenu().add("Save editor as…");
            if (currentProvider instanceof SafTreeStorageProvider) menu.getMenu().add("Remove current storage");
            menu.setOnMenuItemClickListener(item -> {
                String action = item.getTitle().toString();
                if (action.startsWith("Show hidden") || action.startsWith("Hide hidden")) {
                    showHidden = !showHidden;
                    preferences.edit().putBoolean(KEY_SHOW_HIDDEN, showHidden).apply();
                    persistSession();
                    refreshVisible();
                } else if ("Refresh".equals(action)) refreshVisible();
                else if ("Locations".equals(action)) refreshHome();
                else if ("Save editor as…".equals(action)) {
                    saveAsMode = true;
                    pendingTransfer = PendingTransfer.NONE;
                    selection.clear();
                    selectionMode = false;
                    refreshHome();
                    rebuildActionBar();
                    rebuildSelectionBar();
                }
                else if ("Remove current storage".equals(action) && currentProvider != null) {
                    StorageProvider provider = currentProvider;
                    nunitoDialogBuilder().setTitle("Remove storage")
                        .setMessage("Lepaskan " + provider.getLocation().getName() + "? File tidak akan dihapus.")
                        .setPositiveButton("Lepaskan", (d, w) -> {
                            registry.removeSafLocation(provider.getId()); refreshHome();
                        }).setNegativeButton("Batal", null).show();
                }
                return true;
            });
            menu.show();
        }

        void refreshVisible() {
            if (currentProvider != null) {
                treeChildren.clear();
                treeEntries.clear();
                expandedTreeKeys.clear();
                backStack.clear();
                currentDirectory = treeRootDirectory;
                if (treeRootDirectory != null) treeEntries.put(treeKey(treeRootDirectory), treeRootDirectory);
                loadDirectory();
            } else refreshHome();
        }

        private void updateEmptyState() {
            emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            if (currentProvider != null) {
                emptyView.setText(activeQuery.isEmpty() ? "Folder ini kosong" : "Tidak ada hasil pencarian");
            } else emptyView.setText("Belum ada storage yang terhubung");
        }

        private void showLoading(boolean visible) {
            if (progressBar == null) {
                progressBar = new ProgressBar(activity);
                progressBar.setIndeterminate(true);
                progressBar.setVisibility(View.GONE);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER);
                // listView is hosted by HorizontalScrollView, which only accepts one child.
                // Add the loading overlay to listFrame (the parent of the scroll container).
                ViewGroup overlayParent = (ViewGroup) treeHorizontalScroll.getParent();
                if (overlayParent != null) overlayParent.addView(progressBar, lp);
            }
            progressBar.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) emptyView.setVisibility(View.GONE);
        }

        private final class FileRowAdapter extends BaseAdapter {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int position) { return rows.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public boolean isEnabled(int position) { return rows.get(position).kind != RowKind.SECTION; }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Row row = rows.get(position);
                if (row.kind == RowKind.SECTION) {
                    TextView section = convertView instanceof TextView ? (TextView) convertView
                        : text("", 10, 0xFF969EA7, true);
                    section.setPadding(dp(14), dp(12), dp(12), dp(7));
                    section.setText(row.label);
                    section.setBackgroundColor(0xFF13151B);
                    return section;
                }

                LinearLayout container;
                ImageView disclosure;
                ImageView badge;
                LinearLayout texts;
                TextView name;
                TextView subtitle;
                ImageView check;
                if (convertView instanceof LinearLayout && "file_tree_row".equals(convertView.getTag())) {
                    container = (LinearLayout) convertView;
                    disclosure = (ImageView) container.getChildAt(0);
                    badge = (ImageView) container.getChildAt(1);
                    texts = (LinearLayout) container.getChildAt(2);
                    name = (TextView) texts.getChildAt(0);
                    subtitle = (TextView) texts.getChildAt(1);
                    check = (ImageView) container.getChildAt(3);
                } else {
                    container = new LinearLayout(activity);
                    container.setTag("file_tree_row");
                    container.setGravity(Gravity.CENTER_VERTICAL);
                    container.setPadding(0, 0, dp(8), 0);
                    disclosure = new ImageView(activity);
                    disclosure.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    LinearLayout.LayoutParams disclosureLp =
                        new LinearLayout.LayoutParams(dp(16), dp(38));
                    disclosureLp.setMargins(0, 0, dp(4), 0);
                    container.addView(disclosure, disclosureLp);
                    badge = new ImageView(activity);
                    badge.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    LinearLayout.LayoutParams badgeLp =
                        new LinearLayout.LayoutParams(dp(16), dp(38));
                    badgeLp.setMargins(0, 0, dp(6), 0);
                    container.addView(badge, badgeLp);
                    texts = new LinearLayout(activity);
                    texts.setOrientation(LinearLayout.VERTICAL);
                    texts.setGravity(Gravity.CENTER_VERTICAL);
                    container.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
                    name = text("", 14, 0xFFE8EAED, false);
                    name.setSingleLine(true);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    texts.addView(name, new LinearLayout.LayoutParams(-1, -2));
                    subtitle = text("", 10, 0xFF969EA7, false);
                    subtitle.setSingleLine(true);
                    subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    texts.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
                    check = new ImageView(activity);
                    check.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    container.addView(check, new LinearLayout.LayoutParams(dp(20), dp(38)));
                }

                if (row.kind == RowKind.LOCATION) {
                    disclosure.setImageResource(R.drawable.ic_fm_chevron_right);
                    disclosure.setVisibility(View.VISIBLE);
                    badge.setImageResource(FileManagerIconResolver.storage(row.provider));
                    badge.setVisibility(View.VISIBLE);
                    name.setText(storageLabel(row.provider));
                    subtitle.setVisibility(View.VISIBLE);
                    String description = row.provider.getLocation().getDescription();
                    subtitle.setText(description == null ?
                        (row.provider.isAvailable() ? "Available" : "Unavailable") : description);
                    check.setImageDrawable(null);
                    check.setVisibility(View.INVISIBLE);
                    container.setPadding(dp(8), dp(5), dp(8), dp(5));
                    container.setBackgroundColor(Color.TRANSPARENT);
                    return container;
                }

                StorageEntry entry = row.entry;
                if (entry == null) return container;
                int indent = Math.max(0, row.depth) * dp(18);
                container.setPadding(indent, 0, dp(8), 0);
                if (entry.isDirectory()) {
                    disclosure.setVisibility(View.VISIBLE);
                    disclosure.setImageResource(row.expanded
                        ? R.drawable.ic_fm_chevron_down : R.drawable.ic_fm_chevron_right);
                    badge.setImageDrawable(FileManagerIconResolver.file(activity, entry, row.expanded));
                    badge.setVisibility(View.VISIBLE);
                    subtitle.setText("");
                    subtitle.setVisibility(View.GONE);
                } else {
                    disclosure.setVisibility(View.INVISIBLE);
                    disclosure.setImageDrawable(null);
                    FileTypeResolver.Descriptor descriptor = FileTypeResolver.resolve(
                        entry.getName(), false, entry.getMimeType());
                    badge.setImageDrawable(FileManagerIconResolver.file(activity, entry, false));
                    badge.setVisibility(View.VISIBLE);
                    subtitle.setText("");
                    subtitle.setVisibility(View.GONE);
                }
                name.setText(entry.isRoot() ? "/" : entry.getName());
                boolean selected = selection.contains(entry);
                boolean current = currentDirectory != null && sameEntry(currentDirectory, entry);
                if (selected) {
                    check.setImageResource(R.drawable.ic_fm_check_circle);
                    check.setVisibility(View.VISIBLE);
                } else if (selectionMode) {
                    check.setImageResource(R.drawable.ic_fm_circle_outline);
                    check.setVisibility(View.VISIBLE);
                } else {
                    check.setImageDrawable(null);
                    check.setVisibility(View.INVISIBLE);
                }
                if (selected) container.setBackgroundColor(0xFF13151B);
                else if (current) container.setBackgroundColor(0xFF13151B);
                else container.setBackgroundColor(Color.TRANSPARENT);


                // Temporary click feedback
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    RippleDrawable ripple = new RippleDrawable(
                        ColorStateList.valueOf(0x33969EA7),
                        null,
                        new ColorDrawable(Color.WHITE)
                    );

                    container.setForeground(ripple);
                }

                return container;
            }
        }

        private String modified(StorageEntry entry) {
            return entry.getLastModified() > 0
                ? DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(entry.getLastModified()))
                : "Unknown date";
        }

        private static final class Row {
            final RowKind kind;
            final String label;
            final StorageProvider provider;
            final StorageEntry entry;
            final int depth;
            final boolean expanded;
            private Row(RowKind kind, String label, StorageProvider provider, StorageEntry entry,
                        int depth, boolean expanded) {
                this.kind = kind; this.label = label; this.provider = provider; this.entry = entry;
                this.depth = depth; this.expanded = expanded;
            }
            static Row section(String label) { return new Row(RowKind.SECTION, label, null, null, 0, false); }
            static Row location(StorageProvider provider) { return new Row(RowKind.LOCATION, null, provider, null, 0, false); }
            static Row entry(RowKind kind, StorageProvider provider, StorageEntry entry) {
                return new Row(kind, null, provider, entry, 0, false);
            }
            static Row tree(StorageProvider provider, StorageEntry entry, int depth, boolean expanded) {
                return new Row(RowKind.ENTRY, null, provider, entry, depth, expanded);
            }
        }
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setTypeface(nunitoTypeface(bold));
        return view;
    }

    private TextView textButton(String value, int sizeSp, int color) {
        TextView view = text(value, sizeSp, color, true);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundResource(android.R.drawable.list_selector_background);
        return view;
    }

    private void setStartCompoundIconSize(TextView view, int sizeDp) {
        Drawable[] drawables = view.getCompoundDrawablesRelative();
        Drawable start = drawables[0];
        if (start == null) return;
        start.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
        view.setCompoundDrawablesRelative(start, drawables[1], drawables[2], drawables[3]);
    }

    private ImageButton iconButton(int iconResource, String description) {
        ImageButton button = new ImageButton(activity);
        button.setImageResource(iconResource);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(13), dp(13), dp(13), dp(13)); // 18dp icon; touch target remains unchanged.
        button.setBackgroundResource(android.R.drawable.list_selector_background);
        button.setContentDescription(description);
        return button;
    }

    private Button button(String value) {
        Button button = new Button(activity);
        button.setText(value);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setTypeface(nunitoTypeface(true));
        button.setTextColor(0xFF07110C);
        button.setAllCaps(false);
        button.setBackground(roundRect(0xFF3DDC84, dp(9)));
        return button;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
            activity.getResources().getDisplayMetrics()));
    }

    private static String humanSize(long bytes) {
        if (bytes < 0) return "Unknown size";
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int index = 0;
        while (value >= 1024 && index < units.length - 1) { value /= 1024; index++; }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[index]);
    }

    private static String safeMessage(Throwable error) {
        return error == null || error.getMessage() == null ? "Unknown error" : error.getMessage();
    }
}
