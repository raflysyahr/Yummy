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
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.SelectionChangeEvent;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bagian editor kode pada layar Yummy. Sengaja terpisah dari panel terminal
 * (EditorLayoutController): kelas ini tidak menyentuh terminal sama sekali.
 */
public class EditorController {
    private static boolean sTextMateInitialized;

    private static final String SAMPLE =
        "import urwid\n\nclass Application:\n    def __init__(self):\n"
            + "        self.loop = MainLoop()\n\n    def run(self):\n"
            + "        self.loop.run()\n\n\nif __name__ == \"__main__\":\n"
            + "    app = Application()\n    app.run()\n";

    private final Activity mActivity;
    private CodeEditor mEditor;
    private TextView mStatusPos;
    private TextView mStatusIndent;
    private LinearLayout mTabsContainer;
    private HorizontalScrollView mTabsScroll;
    private View mSessionSavingPill;
    private ImageView mSessionSavingIcon;
    private Animation mSessionSavingAnimation;

    private final Handler mSessionHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPendingSessionSave = this::persistEditorSessionNow;
    private WorkspaceSessionManager mWorkspaceSessionManager;

    private final List<EditorDocument> mDocuments = new ArrayList<>();
    private EditorDocument mActiveDocument;
    private boolean mSuppressContentChanges;
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
        String text;
        long revision;
        long savedRevision;
        boolean closePending;

        EditorDocument(String id, String name, String text) {
            this.id = id;
            this.name = name;
            this.text = text == null ? "" : text;
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
        mStatusIndent = mActivity.findViewById(R.id.yummy_status_indent);
        mTabsContainer = mActivity.findViewById(R.id.yummy_tabs_container);
        mTabsScroll = mActivity.findViewById(R.id.yummy_tabs_scroll);
        mSessionSavingPill = mActivity.findViewById(R.id.yummy_session_save_pill);
        mSessionSavingIcon = mActivity.findViewById(R.id.yummy_session_save_icon);

        mWorkspaceSessionManager = new WorkspaceSessionManager(
            mActivity.getApplicationContext(),
            (saving, failed) -> updateSessionSavingIndicator(saving, failed)
        );
        updateSessionSavingIndicator(false, false);

        configureEditor();
        configurePythonLanguage();
        bindKeys();

        mEditor.subscribeEvent(SelectionChangeEvent.class, (event, unsubscribe) -> updateStatus());
        mEditor.subscribeEvent(ContentChangeEvent.class, (event, unsubscribe) -> {
            if (!mSuppressContentChanges && mActiveDocument != null) {
                mActiveDocument.revision++;
                mActiveDocument.text = mEditor.getText().toString();
                renderTabs();
                scheduleEditorSessionSave();
            }
            updateStatus();
        });

        boolean restored = restoreEditorSession();
        if (!restored) {
            // Dokumen awal hanya dibuat pada instalasi/sesi pertama.
            EditorDocument initial = new EditorDocument(
                "yummy:initial:" + UUID.randomUUID(), "Main.py", SAMPLE
            );
            initial.revision = 1;
            initial.savedRevision = 1;
            mDocuments.add(initial);
            mActiveDocument = initial;
            activateDocumentInternal(initial, false);
        } else if (mActiveDocument != null) {
            activateDocumentInternal(mActiveDocument, false);
        } else {
            mEditor.setText("");
            updateTitle();
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
                EditorDocument document = new EditorDocument(
                    id, item.optString("name", "Untitled"), item.optString("text", "")
                );
                document.revision = item.optLong("revision", 0L);
                document.savedRevision = item.optLong("savedRevision", document.revision);
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
        mSessionHandler.removeCallbacks(mPendingSessionSave);
        mSessionHandler.postDelayed(mPendingSessionSave, 350L);
    }

    /** Capture and enqueue the complete in-memory editor state immediately. */
    public void persistEditorSessionNow() {
        mSessionHandler.removeCallbacks(mPendingSessionSave);
        if (mWorkspaceSessionManager == null) return;
        if (mEditor != null && mActiveDocument != null) {
            mActiveDocument.text = mEditor.getText().toString();
        }
        JSONObject session = new JSONObject();
        JSONArray documents = new JSONArray();
        try {
            session.put("version", 1);
            session.put("activeDocumentId", mActiveDocument == null ? JSONObject.NULL : mActiveDocument.id);
            for (EditorDocument document : mDocuments) {
                JSONObject item = new JSONObject();
                item.put("id", document.id);
                item.put("name", document.name);
                item.put("text", document.text == null ? "" : document.text);
                item.put("revision", document.revision);
                item.put("savedRevision", document.savedRevision);
                documents.put(item);
            }
            session.put("documents", documents);
        } catch (JSONException error) {
            android.util.Log.e("YummyEditor", "Could not create editor session snapshot", error);
            return;
        }
        mWorkspaceSessionManager.saveEditorSession(session);
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

    /** Membuka dokumen sebagai tab baru; file yang sudah terbuka cukup diaktifkan kembali. */
    public void openDocument(@NonNull String documentId, String content, @NonNull String name) {
        EditorDocument document = findDocument(documentId);
        if (document != null) {
            activateDocumentInternal(document, true);
            return;
        }
        document = new EditorDocument(documentId, name, content);
        document.revision = 1;
        document.savedRevision = 1;
        mDocuments.add(document);
        activateDocumentInternal(document, true);
        scheduleEditorSessionSave();
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

    @Nullable
    public String getActiveDocumentId() {
        return mActiveDocument == null ? null : mActiveDocument.id;
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
            mActiveDocument.text = content == null ? "" : content;
            mActiveDocument.revision++;
            mActiveDocument.savedRevision = mActiveDocument.revision;
            applyActiveDocumentText();
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
        if (document == mActiveDocument) updateTitle();
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
        new AlertDialog.Builder(mActivity)
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
            .show();
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
        if (mActiveDocument != null && mEditor != null) {
            mActiveDocument.text = mEditor.getText().toString();
        }
        mActiveDocument = document;
        applyActiveDocumentText();
        updateTitle();
        updateStatus();
        renderTabs();
        if (notify) notifyActiveDocumentChanged();
    }

    private void applyActiveDocumentText() {
        if (mEditor == null) return;
        mSuppressContentChanges = true;
        try {
            mEditor.setText(mActiveDocument == null ? "" : mActiveDocument.text);
        } finally {
            mSuppressContentChanges = false;
        }
    }

    private void updateTitle() {
        TextView title = mActivity.findViewById(R.id.yummy_title);
        if (title != null) title.setText(mActiveDocument == null ? "Yummy" : mActiveDocument.name);
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
            updateTitle();
            updateStatus();
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
            icon.setImageResource(R.drawable.ic_yummy_file);
            icon.setColorFilter(ContextCompat.getColor(mActivity,
                active ? R.color.yummy_accent_green : R.color.yummy_text_dim));
            tab.addView(icon, new LinearLayout.LayoutParams(dpInt(17), dpInt(17)));

            TextView name = new TextView(mActivity);
            name.setText(document.name);
            name.setTextColor(ContextCompat.getColor(mActivity,
                active ? R.color.yummy_text : R.color.yummy_text_dim));
            name.setTextSize(14f);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nameParams.setMargins(dpInt(8), 0, dpInt(5), 0);
            tab.addView(name, nameParams);

            if (document.isDirty()) {
                TextView dirty = new TextView(mActivity);
                dirty.setText("•");
                dirty.setTextColor(ContextCompat.getColor(mActivity, R.color.yummy_text));
                dirty.setTextSize(16f);
                tab.addView(dirty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }

            TextView close = new TextView(mActivity);
            close.setText("×");
            close.setTextColor(ContextCompat.getColor(mActivity, R.color.yummy_text_dim));
            close.setTextSize(20f);
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
     * Memuat TextMate Python, tema Yummy, auto-indent dan auto-closing pairs.
     * Registry TextMate bersifat global, sehingga grammar/theme cukup dimuat sekali.
     */
    private void configurePythonLanguage() {
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
                    // ThemeRegistry.getInstance().setTheme("");
                    GrammarRegistry.getInstance().loadGrammars(
                        "textmate/languages.json"
                    );

                    sTextMateInitialized = true;
                }
            }

            // Pasang theme TextMate terlebih dahulu agar hasil token syntax memiliki warna.
            mEditor.setColorScheme(
                TextMateColorScheme.create(ThemeRegistry.getInstance())
            );
            // Gutter mengikuti background editor dari theme aktif; garis pembatas dihilangkan.
            EditorColorScheme scheme = mEditor.getColorScheme();
            scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND,
                scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND)
            );
            scheme.setColor(EditorColorScheme.LINE_DIVIDER, Color.TRANSPARENT);

            mEditor.setHighlightCurrentLine(true);
            TextMateLanguage language =
                TextMateLanguage.create("source.python", true);
            language.setTabSize(4);
            language.useTab(false);

            mEditor.setEditorLanguage(language);
            mEditor.getProps().autoIndent = true;
            mEditor.getProps().symbolPairAutoCompletion = true;
        } catch (Exception e) {
            android.util.Log.e(
                "YummyEditor",
                "Gagal mengaktifkan TextMate Python; editor tetap dapat digunakan.",
                e
            );
        }
    }

    private void updateStatus() {
        if (mEditor == null) return;
        int line = mEditor.getCursor().getLeftLine() + 1;
        int column = mEditor.getCursor().getLeftColumn() + 1;
        if (mStatusPos != null) mStatusPos.setText("Ln " + line + ", Col " + column);
        if (mStatusIndent != null) mStatusIndent.setText("Spaces: " + mEditor.getTabWidth());
    }

    private void bindKeys() {
        bindKey(R.id.yummy_key_tab, KeyEvent.KEYCODE_TAB);
        bindKey(R.id.yummy_key_up, KeyEvent.KEYCODE_DPAD_UP);
        bindKey(R.id.yummy_key_down, KeyEvent.KEYCODE_DPAD_DOWN);
        bindKey(R.id.yummy_key_left, KeyEvent.KEYCODE_DPAD_LEFT);
        bindKey(R.id.yummy_key_right, KeyEvent.KEYCODE_DPAD_RIGHT);
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
                persistEditorSessionNow();
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
                if (activity == mActivity) persistEditorSessionNow();
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
                if (activity == mActivity) persistEditorSessionNow();
            }
        };
}
