package com.termux.app.editor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.drawable.GradientDrawable;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.Gravity;
import android.widget.PopupWindow;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import java.nio.charset.Charset;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.SelectionChangeEvent;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel;
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver;
import org.eclipse.tm4e.core.registry.IThemeSource;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import com.termux.app.session.WorkspaceSessionManager;
import com.termux.app.filemanager.ui.FileManagerIconResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bagian editor kode pada layar Yummy. Sengaja terpisah dari panel terminal
 * (EditorLayoutController): kelas ini tidak menyentuh terminal sama sekali.
 */
public class EditorController {
    private static boolean sTextMateInitialized;

    private final Activity mActivity;
    private CodeEditor mEditor;
    private TextView mStatusPos;
    private TextView mStatusCol;
    private TextView mStatusIndent;
    private TextView mStatusLang;
    private ImageView mStatusLangIcon;
    private TextView mStatusEncoding;
    private View mStatusEncodingGroup;
    private PopupWindow mEncodingPopup;
    private LinearLayout mTabsContainer;
    private HorizontalScrollView mTabsScroll;
    private View mEditorContainer;
    private View mEditorTopDivider;
    private View mEditorBottomDivider;
    private View mStatusBarDivider;
    private View mEditorStatusBar;
    private View mKeysRow;
    private View mSaveButton;
    private View mNewTabButton;
    private View mSessionSavingPill;
    private ImageView mSessionSavingIcon;
    private Animation mSessionSavingAnimation;
    private Typeface mNunitoRegular;
    private Typeface mNunitoSemiBold;

    private Typeface nunitoTypeface(boolean semiBold) {
        try {
            if (semiBold) {
                if (mNunitoSemiBold == null) {
                    mNunitoSemiBold = Typeface.createFromAsset(
                        mActivity.getAssets(), "fonts/Nunito-SemiBold.ttf");
                }
                return mNunitoSemiBold;
            }
            if (mNunitoRegular == null) {
                mNunitoRegular = Typeface.createFromAsset(
                    mActivity.getAssets(), "fonts/Nunito-Regular.ttf");
            }
            return mNunitoRegular;
        } catch (RuntimeException ignored) {
            return Typeface.DEFAULT;
        }
    }

    private final Handler mSessionHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPendingSessionSave = this::persistEditorSessionNow;
    private WorkspaceSessionManager mWorkspaceSessionManager;

    private final List<EditorDocument> mDocuments = new ArrayList<>();
    private EditorDocument mActiveDocument;
    private boolean mSuppressContentChanges;
    /** True only after a synchronous checkpoint has committed the latest editor state. */
    private boolean mFinalCheckpointCompleted;
    private boolean mTextMateReady;
    private DocumentActionListener mDocumentActionListener;

    /** Bridge ke FileManager agar aksi Save pada dialog close bisa menulis ke storage. */
    public interface DocumentActionListener {
        void onActiveDocumentChanged(@Nullable String documentId);
        void onDocumentClosed(@NonNull String documentId);
        void onSaveRequested(@NonNull String documentId, @NonNull Runnable onSaved);
    }

    private static final class EditorDocument {
        final String id;
        String name;
        String encoding = "UTF-8";
        String text;
        /** Live Sora buffer owned by this tab, including its native UndoManager. */
        Content content;
        /** Last durable history snapshot; kept until a fresh snapshot supersedes it. */
        JSONObject undoHistory;
        boolean undoHistoryRestoreAttempted;
        String baseText;
        String baseSha256 = "";
        boolean storageBacked;
        boolean restorePending;
        JSONObject recoveryPatch;
        String legacyRecoveryText;
        long revision;
        long savedRevision;
        boolean closePending;

        EditorDocument(String id, String name, String text) {
            this.id = id;
            this.name = name;
            this.text = text == null ? "" : text;
            this.baseText = this.text;
        }

        boolean isDirty() { return revision != savedRevision; }
    }

    public EditorController(@NonNull Activity activity) {
        mActivity = activity;
    }

    /** Panggil sekali setelah setContentView(R.layout.activity_termux). */
    public void setup() {
        mEditor = mActivity.findViewById(R.id.yummy_code_editor);
        if (mEditor == null) return;

        mStatusPos = mActivity.findViewById(R.id.yummy_status_pos);
        mStatusCol = mActivity.findViewById(R.id.yummy_status_col);
        mStatusIndent = mActivity.findViewById(R.id.yummy_status_indent);
        mStatusLang = mActivity.findViewById(R.id.yummy_status_lang);
        mStatusLangIcon = mActivity.findViewById(R.id.yummy_status_lang_icon);
        mStatusEncoding = mActivity.findViewById(R.id.yummy_status_enc);
        mStatusEncodingGroup = mActivity.findViewById(R.id.yummy_status_encoding_button);
        if (mStatusEncodingGroup != null) mStatusEncodingGroup.setOnClickListener(v -> showEncodingPopup());
        mTabsContainer = mActivity.findViewById(R.id.yummy_tabs_container);
        mTabsScroll = mActivity.findViewById(R.id.yummy_tabs_scroll);
        mNewTabButton = mActivity.findViewById(R.id.yummy_btn_new_tab);
        if (mNewTabButton != null) mNewTabButton.setOnClickListener(v -> createUntitledDocument());
        mEditorContainer = mActivity.findViewById(R.id.yummy_editor_container);
        mEditorTopDivider = mActivity.findViewById(R.id.yummy_editor_top_divider);
        mEditorBottomDivider = mActivity.findViewById(R.id.yummy_editor_bottom_divider);
        mStatusBarDivider = mActivity.findViewById(R.id.yummy_editor_status_divider);
        mEditorStatusBar = mActivity.findViewById(R.id.yummy_editor_status_bar);
        mKeysRow = mActivity.findViewById(R.id.yummy_keys_row);
        mSaveButton = mActivity.findViewById(R.id.yummy_btn_save);
        mSessionSavingPill = mActivity.findViewById(R.id.yummy_session_save_pill);
        mSessionSavingIcon = mActivity.findViewById(R.id.yummy_session_save_icon);

        mWorkspaceSessionManager = new WorkspaceSessionManager(
            mActivity.getApplicationContext(),
            (saving, failed) -> updateSessionSavingIndicator(saving, failed)
        );
        updateSessionSavingIndicator(false, false);

        configureEditor();
        initializeTextMateSupport();
        bindKeys();
        bindEditorActionButtons();

        mEditor.subscribeEvent(SelectionChangeEvent.class, (event, unsubscribe) -> updateStatus());
        mEditor.subscribeEvent(ContentChangeEvent.class, (event, unsubscribe) -> {
            if (!mSuppressContentChanges && mActiveDocument != null) {
                mActiveDocument.revision++;
                // A previous snapshot is no longer authoritative after any content mutation,
                // even when Undo/Redo returns to text that happens to have the same hash.
                mActiveDocument.undoHistory = null;
                captureActiveDocumentContent();
                renderTabs();
                scheduleEditorSessionSave();
            }
            updateStatus();
        });

        boolean restored = restoreEditorSession();
        if (restored && mActiveDocument != null) {
            activateDocumentInternal(mActiveDocument, false);
        } else {
            // Workspace baru atau sesi tanpa tab: jangan tampilkan editor kosong.
            mActiveDocument = null;
            applyActiveDocumentText();
            applyLanguageForActiveDocument();
            updateTitle();
            updateStatus();
            updateEditorVisibility();
        }
        renderTabs();
        updateStatus();
        scheduleEditorSessionSave();

        mActivity.getApplication().registerActivityLifecycleCallbacks(mLifecycle);
    }


    @NonNull
    public WorkspaceSessionManager getWorkspaceSessionManager() {
        if (mWorkspaceSessionManager == null) {
            mWorkspaceSessionManager = new WorkspaceSessionManager(
                mActivity.getApplicationContext(),
                (saving, failed) -> updateSessionSavingIndicator(saving, failed)
            );
        }
        return mWorkspaceSessionManager;
    }

    private boolean restoreEditorSession() {
        JSONObject session = getWorkspaceSessionManager().loadEditorSession();
        if (session == null || !session.has("documents")) return false;
        JSONArray documents = session.optJSONArray("documents");
        if (documents != null) {
            for (int i = 0; i < documents.length(); i++) {
                JSONObject item = documents.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (id.isEmpty()) continue;
                boolean storageBacked = item.optBoolean("storageBacked", id.contains("::"));
                String baseText = item.optString("baseText", item.optString("text", ""));
                boolean hasUntitledDraftText = !storageBacked
                    && id.startsWith("yummy:untitled:") && item.has("draftText");
                String restoredText = storageBacked ? ""
                    : (hasUntitledDraftText ? item.optString("draftText", baseText) : baseText);
                EditorDocument document = new EditorDocument(
                    id, item.optString("name", "Untitled"), restoredText
                );
                document.storageBacked = storageBacked;
                document.encoding = normalizeEncoding(item.optString("encoding", "UTF-8"));
                document.baseText = storageBacked ? "" : baseText;
                document.recoveryPatch = item.optJSONObject("recoveryPatch");
                document.baseSha256 = item.optString("baseSha256",
                    document.recoveryPatch == null ? ""
                        : document.recoveryPatch.optString("baseSha256", ""));
                document.legacyRecoveryText = item.optString("legacyRecoveryText", null);
                document.revision = item.optLong("revision", 0L);
                document.savedRevision = item.optLong("savedRevision", document.revision);
                document.undoHistory = item.optJSONObject("undoHistory");

                if (storageBacked) {
                    // File Manager must reload and fingerprint the real source before
                    // a dirty patch can be applied. Until then, never guess the base.
                    document.restorePending = true;
                } else if (!hasUntitledDraftText && document.recoveryPatch != null) {
                    // Older sessions do not contain draftText, so keep their patch-based
                    // recovery path as a compatibility fallback.
                    String patched = WorkspaceSessionManager.applyTextPatch(
                        document.baseText, document.recoveryPatch
                    );
                    if (patched != null) document.text = patched;
                    else {
                        document.text = document.baseText;
                        document.revision = document.savedRevision;
                        document.recoveryPatch = null;
                    }
                }
                mDocuments.add(document);
            }
        }
        String activeId = session.optString("activeDocumentId", "");
        mActiveDocument = findDocument(activeId);
        if (mActiveDocument == null && !mDocuments.isEmpty()) {
            mActiveDocument = mDocuments.get(0);
        }
        return true;
    }

    private void scheduleEditorSessionSave() {
        if (mWorkspaceSessionManager == null) return;
        mFinalCheckpointCompleted = false;
        mSessionHandler.removeCallbacks(mPendingSessionSave);
        mSessionHandler.postDelayed(mPendingSessionSave, 350L);
    }

    /** Capture and enqueue the complete in-memory editor state immediately. */
    public void persistEditorSessionNow() {
        mFinalCheckpointCompleted = false;
        mSessionHandler.removeCallbacks(mPendingSessionSave);
        JSONObject session = createEditorSessionSnapshot();
        if (session != null && mWorkspaceSessionManager != null) {
            mWorkspaceSessionManager.saveEditorSession(session);
        }
    }

    /**
     * Commit the current editor snapshot before Activity leaves the foreground.
     * This is called directly by TermuxActivity rather than relying only on
     * Application.ActivityLifecycleCallbacks, whose dispatch happens outside
     * the Activity override itself.
     */
    public boolean persistEditorSessionBlocking() {
        mFinalCheckpointCompleted = false;
        mSessionHandler.removeCallbacks(mPendingSessionSave);
        JSONObject session = createEditorSessionSnapshot();
        if (session == null || mWorkspaceSessionManager == null) return false;
        boolean saved = mWorkspaceSessionManager.saveEditorSessionBlocking(session);
        mFinalCheckpointCompleted = saved;
        if (!saved) {
            android.util.Log.e("YummyEditor", "Could not commit editor session during Activity lifecycle");
        }
        return saved;
    }

    @Nullable
    private JSONObject createEditorSessionSnapshot() {
        if (mWorkspaceSessionManager == null) return null;
        if (mEditor != null && mActiveDocument != null) {
            captureActiveDocumentContent();
        }
        JSONObject session = new JSONObject();
        JSONArray documents = new JSONArray();
        try {
            session.put("version", 1);
            session.put("activeDocumentId", mActiveDocument == null ? JSONObject.NULL : mActiveDocument.id);
            for (EditorDocument document : mDocuments) {
                if (document.content != null) {
                    try {
                        document.undoHistory = SoraUndoHistoryCodec.encode(document.content);
                    } catch (java.io.IOException error) {
                        // A transient Sora edit must not fail the whole workspace checkpoint.
                        // Never keep a stale snapshot after the buffer has changed; it may have
                        // the same text hash after an Undo/Redo but a different stack pointer.
                        if (!SoraUndoHistoryCodec.matchesContent(
                                document.content, document.undoHistory)) {
                            document.undoHistory = null;
                        }
                        android.util.Log.w("YummyEditor",
                            "Could not encode undo history for " + document.id, error);
                    }
                }

                JSONObject item = new JSONObject();
                item.put("id", document.id);
                item.put("name", document.name);
                item.put("encoding", document.encoding);
                item.put("text", document.text == null ? "" : document.text);
                item.put("baseText", document.baseText == null ? "" : document.baseText);
                item.put("baseSha256", document.baseSha256 == null ? "" : document.baseSha256);
                item.put("storageBacked", document.storageBacked);
                item.put("revision", document.revision);
                item.put("savedRevision", document.savedRevision);
                if (document.undoHistory != null) {
                    item.put("undoHistory", document.undoHistory);
                }
                if (document.restorePending && document.recoveryPatch != null) {
                    item.put("preserveRecoveryPatch", true);
                    item.put("recoveryPatch", document.recoveryPatch);
                }
                documents.put(item);
            }
            session.put("documents", documents);
            return session;
        } catch (JSONException error) {
            android.util.Log.e("YummyEditor", "Could not create editor session snapshot", error);
            return null;
        }
    }

    private void updateSessionSavingIndicator(boolean saving, boolean failed) {
        if (mSessionSavingPill == null || mSessionSavingIcon == null) return;
        if (mSessionSavingAnimation != null) {
            mSessionSavingIcon.clearAnimation();
            mSessionSavingAnimation = null;
        }
        int backgroundColor = saving ? 0xFF16A34A : (failed ? 0xFFDC2626 : Color.TRANSPARENT);
        int iconColor = (saving || failed) ? Color.WHITE : 0xFF89919B;
        GradientDrawable background = new GradientDrawable();
        background.setColor(backgroundColor);
        background.setCornerRadius(dpInt(999));
        mSessionSavingPill.setBackground(background);
        mSessionSavingIcon.setColorFilter(iconColor);
        mSessionSavingIcon.setContentDescription(
            saving ? "Menyimpan sesi" : (failed ? "Gagal menyimpan sesi" : "Sesi tersimpan")
        );
        if (saving) {
            RotateAnimation rotation = new RotateAnimation(
                0f, 360f, Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            );
            rotation.setDuration(850L);
            rotation.setInterpolator(new LinearInterpolator());
            rotation.setRepeatCount(Animation.INFINITE);
            rotation.setFillAfter(false);
            mSessionSavingAnimation = rotation;
            mSessionSavingIcon.startAnimation(rotation);
        }
    }

    public void setDocumentActionListener(@Nullable DocumentActionListener listener) {
        mDocumentActionListener = listener;
        notifyActiveDocumentChanged();
    }

    /** Membuat tab Untitled baru hanya ketika pengguna menekan tombol +. */
    private void createUntitledDocument() {
        EditorDocument document = new EditorDocument(
            "yummy:untitled:" + UUID.randomUUID(), "Untitled", ""
        );
        document.baseText = "";
        document.baseSha256 = WorkspaceSessionManager.sha256(new byte[0]);
        document.storageBacked = false;
        document.restorePending = false;
        document.revision = 0L;
        document.savedRevision = 0L;
        mDocuments.add(document);
        activateDocumentInternal(document, true);
        scheduleEditorSessionSave();
        if (mEditor != null) mEditor.requestFocus();
        if (mTabsScroll != null) {
            mTabsScroll.post(() -> mTabsScroll.fullScroll(View.FOCUS_RIGHT));
        }
    }

    /** Membuka dokumen non-storage sebagai tab baru. */
    public void openDocument(@NonNull String documentId, String content, @NonNull String name) {
        EditorDocument document = findDocument(documentId);
        if (document != null) {
            activateDocumentInternal(document, true);
            return;
        }
        document = new EditorDocument(documentId, name, content);
        document.baseText = content == null ? "" : content;
        document.baseSha256 = WorkspaceSessionManager.sha256(
            document.baseText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        document.storageBacked = false;
        document.revision = 1;
        document.savedRevision = 1;
        mDocuments.add(document);
        activateDocumentInternal(document, true);
        scheduleEditorSessionSave();
    }

    /** Membuka file storage dengan fingerprint byte sumber sebelum ada perubahan editor. */
    public void openStorageDocument(@NonNull String documentId, String content,
                                    @NonNull String name, @NonNull String sourceSha256) {
        EditorDocument document = findDocument(documentId);
        if (document != null) {
            activateDocumentInternal(document, true);
            return;
        }
        document = new EditorDocument(documentId, name, content);
        document.baseText = content == null ? "" : content;
        document.baseSha256 = sourceSha256;
        document.storageBacked = true;
        document.revision = 1;
        document.savedRevision = 1;
        mDocuments.add(document);
        activateDocumentInternal(document, true);
        scheduleEditorSessionSave();
    }

    /**
     * Completes startup restoration after File Manager has read the real source file.
     * A dirty patch is replayed only when the source fingerprint exactly matches.
     * Returns true when a stale/unverifiable recovery was deliberately not applied.
     */
    public boolean restoreDocumentFromStorage(@NonNull String documentId,
                                               @NonNull String sourceText,
                                               @NonNull String sourceSha256) {
        EditorDocument document = findDocument(documentId);
        if (document == null || !document.storageBacked || !document.restorePending) return false;

        boolean conflict = false;
        boolean patchApplied = false;
        String restoredText = sourceText;
        if (document.recoveryPatch != null) {
            String patchBaseSha256 = document.recoveryPatch.optString("baseSha256", "");
            boolean patchFingerprintConsistent = patchBaseSha256.isEmpty()
                || patchBaseSha256.equalsIgnoreCase(document.baseSha256);
            if (patchFingerprintConsistent && !document.baseSha256.isEmpty()
                && document.baseSha256.equalsIgnoreCase(sourceSha256)) {
                String patched = WorkspaceSessionManager.applyTextPatch(
                    sourceText, document.recoveryPatch
                );
                if (patched != null) {
                    restoredText = patched;
                    patchApplied = true;
                } else {
                    conflict = true;
                }
            } else {
                conflict = true;
            }
        } else if (document.isDirty()) {
            // This includes legacy sessions which stored a full text but no source hash.
            // They cannot be safely replayed, so the latest source is shown instead.
            conflict = true;
        }

        document.baseText = sourceText;
        document.baseSha256 = sourceSha256;
        document.text = restoredText;
        document.content = null; // Rebuild Content from the verified recovered source.
        document.undoHistoryRestoreAttempted = false;
        document.restorePending = false;
        document.recoveryPatch = null;
        document.legacyRecoveryText = null;
        if (conflict && !patchApplied) {
            document.revision = document.savedRevision;
        }
        if (document == mActiveDocument) {
            if (mEditor != null) mEditor.setEnabled(true);
            applyActiveDocumentText();
            applyLanguageForActiveDocument();
            updateTitle();
            updateStatus();
            updateEditorVisibility();
        }
        renderTabs();
        scheduleEditorSessionSave();
        return conflict;
    }

    /** Mengaktifkan tab yang sudah ada. Mengembalikan false bila ID tidak ditemukan. */
    public boolean activateDocument(@NonNull String documentId) {
        EditorDocument document = findDocument(documentId);
        if (document == null) return false;
        if (document != mActiveDocument) {
            activateDocumentInternal(document, true);
            scheduleEditorSessionSave();
        }
        return true;
    }

    public boolean hasDocument(@NonNull String documentId) {
        return findDocument(documentId) != null;
    }

    public boolean isAwaitingStorageRestore(@NonNull String documentId) {
        EditorDocument document = findDocument(documentId);
        return document != null && document.storageBacked && document.restorePending;
    }

    @Nullable
    public String getActiveDocumentId() {
        return mActiveDocument == null ? null : mActiveDocument.id;
    }

    /**
     * Safety net untuk pemanggil Save lama: bila belum ada dokumen aktif,
     * buat dokumen sementara agar alur penyimpanan tidak menerima ID null.
     * Pada UI normal, tab Untitled dibuat melalui tombol + terlebih dahulu.
     */
    @NonNull
    public String ensureDraftDocumentForSave() {
        if (mActiveDocument != null) {
            captureActiveDocumentContent();
            return mActiveDocument.id;
        }

        String currentText = mEditor == null ? "" : mEditor.getText().toString();
        EditorDocument draft = new EditorDocument(
            "yummy:untitled:" + UUID.randomUUID(), "Untitled", currentText
        );
        draft.baseText = "";
        draft.baseSha256 = WorkspaceSessionManager.sha256(
            new byte[0]
        );
        draft.storageBacked = false;
        draft.restorePending = false;
        draft.revision = 1L;
        draft.savedRevision = 0L;
        mDocuments.add(draft);
        activateDocumentInternal(draft, true);
        renderTabs();
        scheduleEditorSessionSave();
        return draft.id;
    }

    /** Kompatibilitas dengan pemanggil lama: ganti isi tab aktif, bukan membuat tab baru. */
    public void setDocumentContent(String content, String name) {
        if (mActiveDocument == null) {
            EditorDocument document = new EditorDocument(
                "yummy:untitled:" + UUID.randomUUID(),
                name == null || name.trim().isEmpty() ? "Untitled" : name,
                content
            );
            mDocuments.add(document);
            activateDocumentInternal(document, false);
        } else {
            if (name != null && !name.trim().isEmpty()) mActiveDocument.name = name;
            mActiveDocument.content = null; // Explicit content replacement starts a fresh history.
            mActiveDocument.undoHistory = null;
            mActiveDocument.undoHistoryRestoreAttempted = true;
            mActiveDocument.text = content == null ? "" : content;
            mActiveDocument.baseText = mActiveDocument.text;
            mActiveDocument.baseSha256 = WorkspaceSessionManager.sha256(
                mActiveDocument.text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            mActiveDocument.storageBacked = false;
            mActiveDocument.restorePending = false;
            mActiveDocument.recoveryPatch = null;
            mActiveDocument.revision++;
            mActiveDocument.savedRevision = mActiveDocument.revision;
            applyActiveDocumentText();
            applyLanguageForActiveDocument();
        }
        renderTabs();
        updateStatus();
        scheduleEditorSessionSave();
    }

    @NonNull
    public String getDocumentText() {
        if (mActiveDocument == null) return "";
        if (mEditor != null) mActiveDocument.text = mEditor.getText().toString();
        return mActiveDocument.text;
    }

    @NonNull
    public String getDocumentText(@NonNull String documentId) {
        EditorDocument document = findDocument(documentId);
        if (document == null) return "";
        if (document == mActiveDocument && mEditor != null) {
            document.text = mEditor.getText().toString();
        }
        return document.text;
    }

    @NonNull
    public String getDocumentName() {
        return mActiveDocument == null ? "Untitled" : mActiveDocument.name;
    }

    public void setDocumentName(String name) {
        if (mActiveDocument == null || name == null || name.trim().isEmpty()) return;
        setDocumentName(mActiveDocument.id, name);
    }

    public void setDocumentName(@NonNull String documentId, String name) {
        EditorDocument document = findDocument(documentId);
        if (document == null || name == null || name.trim().isEmpty()) return;
        document.name = name;
        if (document == mActiveDocument) {
            updateTitle();
            applyLanguageForActiveDocument();
            updateStatus();
        }
        renderTabs();
        scheduleEditorSessionSave();
    }

    public long getDocumentRevision() {
        return mActiveDocument == null ? 0 : mActiveDocument.revision;
    }

    public long getDocumentRevision(@NonNull String documentId) {
        EditorDocument document = findDocument(documentId);
        return document == null ? 0 : document.revision;
    }

    public void markDocumentSaved(long revision) {
        if (mActiveDocument != null) markDocumentSaved(mActiveDocument.id, revision);
    }

    public void markDocumentSaved(@NonNull String documentId, long revision) {
        EditorDocument document = findDocument(documentId);
        if (document == null) return;
        document.savedRevision = revision;
        renderTabs();
        scheduleEditorSessionSave();
    }

    /** Advances the recovery baseline to the exact content successfully written to storage. */
    public void markDocumentSaved(@NonNull String documentId, long revision,
                                  @NonNull String savedText, @NonNull String sourceSha256) {
        EditorDocument document = findDocument(documentId);
        if (document == null) return;
        document.savedRevision = revision;
        document.baseText = savedText;
        document.baseSha256 = sourceSha256;
        document.storageBacked = true;
        document.restorePending = false;
        document.recoveryPatch = null;
        if (document.revision == revision) document.text = savedText;
        renderTabs();
        scheduleEditorSessionSave();
    }

    public boolean isDocumentDirty() {
        return mActiveDocument != null && mActiveDocument.isDirty();
    }

    public boolean isDocumentDirty(@NonNull String documentId) {
        EditorDocument document = findDocument(documentId);
        return document != null && document.isDirty();
    }

    /** Meminta tutup tab; dokumen kotor menawarkan Simpan, Jangan Simpan, atau Batal. */
    public void closeDocument(@NonNull String documentId) {
        EditorDocument document = findDocument(documentId);
        if (document == null || document.closePending) return;
        if (document != mActiveDocument) activateDocumentInternal(document, true);
        if (!document.isDirty()) {
            closeDocumentNow(documentId);
            return;
        }
        document.closePending = true;
        AlertDialog closeDialog = new AlertDialog.Builder(mActivity)
            .setTitle("Perubahan belum disimpan")
            .setMessage("Simpan perubahan pada " + document.name + " sebelum menutup tab?")
            .setPositiveButton("Simpan", (dialog, which) -> {
                document.closePending = false;
                if (mDocumentActionListener == null) return;
                mDocumentActionListener.onSaveRequested(documentId, () -> closeDocumentNow(documentId));
            })
            .setNegativeButton("Jangan Simpan", (dialog, which) -> {
                document.closePending = false;
                closeDocumentNow(documentId);
            })
            .setNeutralButton("Batal", (dialog, which) -> document.closePending = false)
            .setOnCancelListener(dialog -> document.closePending = false)
            .create();
        closeDialog.setOnShowListener(ignored -> {
            int titleId = mActivity.getResources().getIdentifier("alertTitle", "id", "android");
            TextView title = titleId != 0 ? closeDialog.findViewById(titleId) : null;
            TextView message = closeDialog.findViewById(android.R.id.message);
            if (title != null) title.setTypeface(nunitoTypeface(true));
            if (message != null) message.setTypeface(nunitoTypeface(false));
            for (int which : new int[] { AlertDialog.BUTTON_POSITIVE,
                    AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL }) {
                android.widget.Button button = closeDialog.getButton(which);
                if (button != null) button.setTypeface(nunitoTypeface(true));
            }
        });
        closeDialog.show();
    }

    /** Dipakai FileManager saat file dihapus; tidak menampilkan konfirmasi kedua. */
    public void closeDocumentWithoutPrompt(@NonNull String documentId) {
        closeDocumentNow(documentId);
    }

    private EditorDocument findDocument(String id) {
        for (EditorDocument document : mDocuments) {
            if (document.id.equals(id)) return document;
        }
        return null;
    }

    private void activateDocumentInternal(EditorDocument document, boolean notify) {
        // Only capture the editor buffer when switching away from a different document.
        // During startup restore, mActiveDocument is already set while the editor still
        // contains its initial empty buffer; copying it here would erase the restored draft.
        if (mActiveDocument != null && mActiveDocument != document && mEditor != null) {
            captureActiveDocumentContent();
        }
        mActiveDocument = document;
        applyActiveDocumentText();
        applyLanguageForActiveDocument();
        updateTitle();
        updateStatus();
        updateEditorVisibility();
        renderTabs();
        if (notify) notifyActiveDocumentChanged();
    }

    /** Keep the active document's exact Sora Content instance, not only its text. */
    private void captureActiveDocumentContent() {
        if (mActiveDocument == null || mEditor == null) return;
        Content content = mEditor.getText();
        mActiveDocument.content = content;
        mActiveDocument.text = content.toString();
    }

    private void applyActiveDocumentText() {
        if (mEditor == null) return;
        mSuppressContentChanges = true;
        try {
            if (mActiveDocument == null) {
                mEditor.setText(new Content(""), true, null);
                return;
            }

            String targetText = mActiveDocument.text == null ? "" : mActiveDocument.text;
            Content content = mActiveDocument.content;
            if (content == null || !targetText.equals(content.toString())) {
                // A programmatic text replacement invalidates the previous action stack.
                content = new Content(targetText);
                mActiveDocument.content = content;
            }
            // Reusing Content preserves the Sora UndoManager and cursor state for this tab.
            mEditor.setText(content, true, null);

            // Storage-backed tabs stay pending until File Manager verifies the source and
            // applies any recovery patch. Only then can the saved text fingerprint be checked.
            if (!mActiveDocument.restorePending
                    && mActiveDocument.undoHistory != null
                    && !mActiveDocument.undoHistoryRestoreAttempted) {
                mActiveDocument.undoHistoryRestoreAttempted = true;
                if (!SoraUndoHistoryCodec.restore(content, mActiveDocument.undoHistory)) {
                    android.util.Log.w("YummyEditor",
                        "Saved undo history did not match the restored document: "
                            + mActiveDocument.id);
                    mActiveDocument.undoHistory = null;
                }
            }
        } finally {
            mSuppressContentChanges = false;
        }
    }

    /** Hides all document-editing surfaces when there is no active tab. */
    private void updateEditorVisibility() {
        boolean hasActiveDocument = mActiveDocument != null;
        int visibility = hasActiveDocument ? View.VISIBLE : View.GONE;
        if (mEditorContainer != null) mEditorContainer.setVisibility(visibility);
        if (mEditorTopDivider != null) mEditorTopDivider.setVisibility(visibility);
        if (mEditorBottomDivider != null) mEditorBottomDivider.setVisibility(visibility);
        if (mStatusBarDivider != null) mStatusBarDivider.setVisibility(visibility);
        if (mEditorStatusBar != null) mEditorStatusBar.setVisibility(visibility);
        if (mKeysRow != null) mKeysRow.setVisibility(visibility);
        if (mEditor != null) {
            mEditor.setEnabled(hasActiveDocument && !mActiveDocument.restorePending);
            if (!hasActiveDocument) mEditor.clearFocus();
        }
        if (mSaveButton != null) {
            mSaveButton.setEnabled(hasActiveDocument);
            mSaveButton.setAlpha(hasActiveDocument ? 1f : 0.38f);
        }
        if (mStatusEncodingGroup != null) {
            mStatusEncodingGroup.setEnabled(hasActiveDocument);
        }
    }

    private void updateTitle() {
        TextView title = mActivity.findViewById(R.id.yummy_title);
        if (title != null) {
            title.setTypeface(nunitoTypeface(true));
            title.setText(mActiveDocument == null ? "Yummy" : mActiveDocument.name);
        }
    }

    private void notifyActiveDocumentChanged() {
        if (mDocumentActionListener != null) {
            mDocumentActionListener.onActiveDocumentChanged(getActiveDocumentId());
        }
    }

    private void closeDocumentNow(String documentId) {
        EditorDocument document = findDocument(documentId);
        if (document == null) return;
        int index = mDocuments.indexOf(document);
        boolean wasActive = document == mActiveDocument;
        mDocuments.remove(document);
        if (mDocumentActionListener != null) mDocumentActionListener.onDocumentClosed(documentId);
        if (wasActive) {
            EditorDocument next = mDocuments.isEmpty()
                ? null : mDocuments.get(Math.min(index, mDocuments.size() - 1));
            mActiveDocument = next;
            applyActiveDocumentText();
            applyLanguageForActiveDocument();
            updateTitle();
            updateStatus();
            updateEditorVisibility();
            notifyActiveDocumentChanged();
        }
        renderTabs();
        scheduleEditorSessionSave();
    }

    private void renderTabs() {
        if (mTabsContainer == null) return;
        mTabsContainer.removeAllViews();
        for (EditorDocument document : mDocuments) {
            final boolean active = document == mActiveDocument;
            LinearLayout tab = new LinearLayout(mActivity);
            tab.setOrientation(LinearLayout.HORIZONTAL);
            tab.setGravity(android.view.Gravity.CENTER_VERTICAL);
            tab.setPadding(dpInt(10), 0, dpInt(6), 0);
            tab.setBackgroundResource(active ? R.drawable.yummy_bg_tab_active : android.R.color.transparent);
            tab.setClickable(true);
            tab.setFocusable(true);

            ImageView icon = new ImageView(mActivity);
            // Gunakan Material Icon Theme yang sama persis dengan daftar File Manager.
            // Icon tidak diberi colorFilter agar warna asli tiap format tetap terlihat.
            icon.setImageDrawable(FileManagerIconResolver.file(mActivity, document.name));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setContentDescription("Icon " + document.name);
            tab.addView(icon, new LinearLayout.LayoutParams(dpInt(18), dpInt(18)));

            TextView name = new TextView(mActivity);
            name.setText(document.name);
            name.setTextColor(ContextCompat.getColor(mActivity,
                active ? R.color.yummy_text : R.color.yummy_text_dim));
            name.setTextSize(14f);
            name.setTypeface(nunitoTypeface(active));
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // Keep long filenames from making one tab dominate the horizontal tab strip.
            name.setMaxWidth(dpInt(140));
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nameParams.setMargins(dpInt(8), 0, dpInt(5), 0);
            tab.addView(name, nameParams);

            if (document.isDirty()) {
                TextView dirty = new TextView(mActivity);
                dirty.setText("•");
                dirty.setTextColor(ContextCompat.getColor(mActivity, R.color.yummy_text));
                dirty.setTextSize(16f);
                dirty.setTypeface(nunitoTypeface(true));
                tab.addView(dirty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }

            TextView close = new TextView(mActivity);
            close.setText("×");
            close.setTextColor(ContextCompat.getColor(mActivity, R.color.yummy_text_dim));
            close.setTextSize(20f);
            close.setTypeface(nunitoTypeface(false));
            close.setGravity(android.view.Gravity.CENTER);
            close.setContentDescription("Tutup " + document.name);
            LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dpInt(27), -1);
            closeParams.setMargins(dpInt(4), 0, 0, 0);
            tab.addView(close, closeParams);
            close.setOnClickListener(view -> closeDocument(document.id));
            tab.setOnClickListener(view -> activateDocument(document.id));

            LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
            mTabsContainer.addView(tab, tabParams);
        }
    }

    private int dpInt(float value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    private void configureEditor() {
        try {
            mEditor.setTypefaceText(Typeface.createFromAsset(
                mActivity.getAssets(), "fonts/JetBrainsMono-Regular.ttf"));
        } catch (RuntimeException e) {
            mEditor.setTypefaceText(Typeface.MONOSPACE);
        }
        mEditor.setTextSize(15f);
        mEditor.setTabWidth(4);
        mEditor.setLineNumberEnabled(true);
        // Gutter nomor baris tetap di tempat saat scroll horizontal.
        mEditor.setPinLineNumber(true);
        // Fitur indentasi dan pasangan simbol dikelola oleh Sora/TextMate.
        mEditor.getProps().autoIndent = false;
        mEditor.getProps().symbolPairAutoCompletion = true;
        mEditor.setWordwrap(false);
        // Backspace pada indentasi: hapus satu spasi per ketukan, bukan seluruh baris.
        mEditor.getProps().deleteEmptyLineFast = false;
        mEditor.getProps().deleteMultiSpaces = 1;

        // Gutter nomor baris yang lebih lega. Memakai refleksi supaya build tidak gagal kalau
        // nama method berbeda di versi pustaka; yang tidak ditemukan dicatat di logcat (YummyEditor).
        applyGutterSpacing();
        int bg = ContextCompat.getColor(mActivity, R.color.yummy_bg);
        int text = ContextCompat.getColor(mActivity, R.color.yummy_text);

        mEditor.setColorScheme(TextMateColorScheme.create(ThemeRegistry.getInstance()));
        EditorColorScheme scheme = mEditor.getColorScheme();

        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, bg);
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, text);

        scheme.setColor(
            EditorColorScheme.LINE_NUMBER_BACKGROUND,
            scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND)
        );
        scheme.setColor(EditorColorScheme.LINE_NUMBER, Color.TRANSPARENT);
        scheme.setColor(EditorColorScheme.LINE_DIVIDER, Color.TRANSPARENT);
        scheme.setColor(
                EditorColorScheme.CURRENT_LINE,
                scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND)
        );
        scheme.setColor(
                EditorColorScheme.LINE_NUMBER_CURRENT,
                scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND)
        );
        mEditor.setHighlightCurrentLine(true);
    }

    /** Ukuran gutter dalam dp; ubah angkanya kalau masih terasa sempit. */
    private static final float GUTTER_MARGIN_LEFT_DP = 6f;
    private static final float GUTTER_DIVIDER_LEFT_DP = 5f;
    private static final float GUTTER_DIVIDER_RIGHT_DP = 5f;

    private float dp(float value) {
        return android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            value,
            mActivity.getResources().getDisplayMetrics()
        );
    }

    private void applyGutterSpacing() {
        callIfPresent("setLineNumberMarginLeft", new Class<?>[]{float.class}, dp(GUTTER_MARGIN_LEFT_DP));
        callIfPresent("setDividerMargin", new Class<?>[]{float.class, float.class},
            dp(GUTTER_DIVIDER_LEFT_DP), dp(GUTTER_DIVIDER_RIGHT_DP));
    }

    private void callIfPresent(String name, Class<?>[] types, Object... args) {
        try {
            mEditor.getClass().getMethod(name, types).invoke(mEditor, args);
        } catch (Exception e) {
            android.util.Log.w("YummyEditor", "Method tidak tersedia di pustaka: " + name);
        }
    }

    /**
     * Inisialisasi registry grammar dan tema satu kali. Grammar setiap tab dipilih
     * secara terpisah melalui applyLanguageForActiveDocument().
     */
    private void initializeTextMateSupport() {
        try {
            synchronized (EditorController.class) {
                if (!sTextMateInitialized) {
                    FileProviderRegistry.getInstance().addFileProvider(
                        new AssetsFileResolver(
                            mActivity.getApplicationContext().getAssets()
                        )
                    );
                    String themePath = "textmate/yummy-dark.json";
                    java.io.InputStream themeStream =
                        FileProviderRegistry.getInstance().tryGetInputStream(themePath);

                    if (themeStream == null) {
                        throw new IllegalStateException(
                            "Theme TextMate tidak ditemukan: " + themePath
                        );
                    }
                    IThemeSource themeSource =
                        IThemeSource.fromInputStream(themeStream, themePath, null);

                    ThemeModel themeModel = new ThemeModel(themeSource, "yummy-dark");
                    themeModel.setDark(true);
                    ThemeRegistry.getInstance().loadTheme(themeModel);
                    GrammarRegistry.getInstance().loadGrammars("textmate/languages.json");
                    sTextMateInitialized = true;
                }
            }

            mTextMateReady = true;
            mEditor.setColorScheme(
                TextMateColorScheme.create(ThemeRegistry.getInstance())
            );
            EditorColorScheme scheme = mEditor.getColorScheme();
            scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND,
                scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
            scheme.setColor(EditorColorScheme.LINE_DIVIDER, Color.TRANSPARENT);
            mEditor.setHighlightCurrentLine(true);
            mEditor.getProps().autoIndent = true;
            mEditor.getProps().symbolPairAutoCompletion = true;
        } catch (Exception e) {
            mTextMateReady = false;
            android.util.Log.e(
                "YummyEditor",
                "Gagal menginisialisasi registry TextMate; editor tetap dapat digunakan.",
                e
            );
        }
    }

    /** Ganti grammar dan konfigurasi sesuai nama file pada tab aktif. */
    private void applyLanguageForActiveDocument() {
        if (mEditor == null) return;
        String documentName = mActiveDocument == null ? "" : mActiveDocument.name;
        EditorLanguageRegistry.Language info = EditorLanguageRegistry.detect(documentName);

        if (!mTextMateReady) return;
        try {
            TextMateLanguage language = TextMateLanguage.create(info.scopeName, true);
            language.setTabSize(4);
            language.useTab(false);
            mEditor.setEditorLanguage(language);
            mEditor.getProps().autoIndent = true;
            mEditor.getProps().symbolPairAutoCompletion = true;
        } catch (Exception e) {
            android.util.Log.e(
                "YummyEditor",
                "Gagal mengaktifkan grammar " + info.scopeName + " untuk " + documentName,
                e
            );
            // Plain Text mempunyai grammar internal sederhana yang ikut didaftarkan,
            // sehingga dokumen tak dikenal tidak mewarisi syntax dokumen sebelumnya.
            try {
                TextMateLanguage fallback = TextMateLanguage.create("text.plain", true);
                fallback.setTabSize(4);
                fallback.useTab(false);
                mEditor.setEditorLanguage(fallback);
            } catch (Exception fallbackError) {
                android.util.Log.e("YummyEditor", "Gagal mengaktifkan Plain Text", fallbackError);
            }
        }
    }

    private void updateStatus() {
        if (mEditor == null) return;
        int line = mEditor.getCursor().getLeftLine() + 1;
        int column = mEditor.getCursor().getLeftColumn() + 1;
        if (mStatusPos != null) mStatusPos.setText(line > 999 ? "999+" : String.valueOf(line));
        if (mStatusCol != null) mStatusCol.setText(String.valueOf(column));
        if (mStatusIndent != null) mStatusIndent.setText(String.valueOf(mEditor.getTabWidth()));
        if (mStatusLang != null) {
            String name = mActiveDocument == null ? "" : mActiveDocument.name;
            EditorLanguageRegistry.Language language = EditorLanguageRegistry.detect(name);
            mStatusLang.setText(language.displayName);
            if (mStatusLangIcon != null) {
                try {
                    if ("plaintext".equals(language.id)) {
                        mStatusLangIcon.setImageResource(R.drawable.ic_yummy_language_plain);
                    } else {
                        Drawable icon = FileManagerIconResolver.file(mActivity, name);
                        mStatusLangIcon.setImageDrawable(icon);
                    }
                    mStatusLangIcon.setColorFilter(null);
                } catch (RuntimeException ignored) {
                    mStatusLangIcon.setImageResource(R.drawable.ic_yummy_file);
                }
            }
        }
        if (mStatusEncoding != null) {
            mStatusEncoding.setText(mActiveDocument == null ? "UTF-8" : mActiveDocument.encoding);
        }
    }

    private static final String[] SUPPORTED_ENCODINGS = new String[] {
        "UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE", "US-ASCII",
        "ISO-8859-1", "ISO-8859-2", "Windows-1252", "Shift_JIS",
        "EUC-JP", "GBK", "GB18030", "Big5", "KOI8-R"
    };

    private String normalizeEncoding(String value) {
        try { return Charset.forName(value).name(); }
        catch (Exception ignored) { return "UTF-8"; }
    }

    @NonNull
    public String getDocumentEncoding(@NonNull String documentId) {
        EditorDocument document = findDocument(documentId);
        return document == null ? "UTF-8" : document.encoding;
    }

    private void showEncodingPopup() {
        if (mStatusEncodingGroup == null || mActiveDocument == null) return;
        if (mEncodingPopup != null && mEncodingPopup.isShowing()) {
            mEncodingPopup.dismiss();
            return;
        }
        LinearLayout list = new LinearLayout(mActivity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(0xFF202329);
        list.setPadding(0, dpInt(5), 0, dpInt(5));
        for (String encoding : SUPPORTED_ENCODINGS) {
            TextView item = new TextView(mActivity);
            item.setText(encoding);
            item.setTextSize(13f);
            item.setTypeface(nunitoTypeface(true));
            item.setTextColor(encoding.equalsIgnoreCase(mActiveDocument.encoding) ? 0xFF80B7FF : 0xFFE5E7EB);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dpInt(16), 0, dpInt(16), 0);
            item.setMinHeight(dpInt(42));
            item.setBackgroundResource(android.R.drawable.list_selector_background);
            item.setOnClickListener(v -> {
                setActiveDocumentEncoding(encoding);
                if (mEncodingPopup != null) mEncodingPopup.dismiss();
            });
            list.addView(item, new LinearLayout.LayoutParams(-1, dpInt(42)));
        }
        ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(false);
        scroll.setBackgroundColor(0xFF202329);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        int popupWidth = dpInt(178);
        int maxPopupHeight = dpInt(280);
        scroll.measure(View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxPopupHeight, View.MeasureSpec.AT_MOST));
        int popupHeight = Math.min(maxPopupHeight, scroll.getMeasuredHeight());
        mEncodingPopup = new PopupWindow(scroll, popupWidth, popupHeight, true);
        mEncodingPopup.setBackgroundDrawable(new ColorDrawable(0xFF202329));
        mEncodingPopup.setOutsideTouchable(true);
        mEncodingPopup.setElevation(dpInt(10));
        int[] location = new int[2];
        mStatusEncodingGroup.getLocationOnScreen(location);
        int x = Math.max(dpInt(6), location[0] + mStatusEncodingGroup.getWidth() - popupWidth);
        int y = Math.max(dpInt(8), location[1] - popupHeight - dpInt(5));
        mEncodingPopup.showAtLocation(mStatusEncodingGroup, Gravity.TOP | Gravity.LEFT, x, y);
        scroll.setAlpha(0f);
        scroll.setTranslationY(dpInt(14));
        scroll.animate().alpha(1f).translationY(0f).setDuration(180L).start();
    }

    private void setActiveDocumentEncoding(@NonNull String encoding) {
        if (mActiveDocument == null) return;
        String normalized = normalizeEncoding(encoding);
        if (mActiveDocument.encoding.equalsIgnoreCase(normalized)) return;
        mActiveDocument.encoding = normalized;
        mActiveDocument.revision++;
        if (mStatusEncoding != null) mStatusEncoding.setText(normalized);
        renderTabs();
        scheduleEditorSessionSave();
    }

    private void bindKeys() {
        bindKey(R.id.yummy_key_tab, KeyEvent.KEYCODE_TAB);
        bindKey(R.id.yummy_key_up, KeyEvent.KEYCODE_DPAD_UP);
        bindKey(R.id.yummy_key_down, KeyEvent.KEYCODE_DPAD_DOWN);
        bindKey(R.id.yummy_key_left, KeyEvent.KEYCODE_DPAD_LEFT);
        bindKey(R.id.yummy_key_right, KeyEvent.KEYCODE_DPAD_RIGHT);
    }

    /**
     * Tombol aksi editor pada baris tombol bawah.
     * Undo/redo menggunakan undo manager bawaan Sora.
     */
    private void bindEditorActionButtons() {
        View undo = mActivity.findViewById(R.id.yummy_key_undo);
        View redo = mActivity.findViewById(R.id.yummy_key_redo);
        View paste = mActivity.findViewById(R.id.yummy_key_paste);

        if (undo != null) {
            undo.setOnClickListener(v -> {
                if (mEditor == null) return;
                mEditor.requestFocus();
                mEditor.undo();
            });
        }
        if (redo != null) {
            redo.setOnClickListener(v -> {
                if (mEditor == null) return;
                mEditor.requestFocus();
                mEditor.redo();
            });
        }
        if (paste != null) {
            paste.setOnClickListener(v -> sendEditorShortcut(KeyEvent.KEYCODE_V));
        }
    }

    /** Ctrl+V memanggil mekanisme paste clipboard native milik Sora Editor. */
    private void sendEditorShortcut(int keyCode) {
        if (mEditor == null) return;
        mEditor.requestFocus();
        long now = SystemClock.uptimeMillis();
        int meta = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        mEditor.dispatchKeyEvent(new KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta
        ));
        mEditor.dispatchKeyEvent(new KeyEvent(
            now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, meta
        ));
    }

    private void bindKey(int viewId, final int keyCode) {
        View view = mActivity.findViewById(viewId);
        if (view == null) return;
        view.setOnClickListener(v -> sendKey(keyCode));
    }

    /** Kirim tombol ke editor seperti dari papan ketik fisik. */
    private void sendKey(int keyCode) {
        if (mEditor == null) return;
        mEditor.requestFocus();
        long now = SystemClock.uptimeMillis();
        mEditor.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        mEditor.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private final Application.ActivityLifecycleCallbacks mLifecycle =
        new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
                if (activity != mActivity) return;
                // Final fallback for destruction paths that did not complete onStop() or
                // onSaveInstanceState(). Never release the live Sora buffer before its
                // latest Undo/Redo stack has had one synchronous persistence attempt.
                if (!mFinalCheckpointCompleted) {
                    persistEditorSessionBlocking();
                }
                activity.getApplication().unregisterActivityLifecycleCallbacks(this);
                if (mEditor != null) {
                    mEditor.release();
                    mEditor = null;
                }
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                // TermuxActivity commits synchronously from its own onStop().
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
                // TermuxActivity commits synchronously from its own onSaveInstanceState().
            }
        };
}
