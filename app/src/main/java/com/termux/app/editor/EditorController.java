package com.termux.app.editor;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
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

    // State dokumen dipakai FileManagerController untuk deteksi perubahan dan penyimpanan.
    private String mDocumentName = "Main.py";
    private long mDocumentRevision;
    private long mSavedRevision;
    private boolean mSuppressContentChanges;

    public EditorController(@NonNull Activity activity) {
        mActivity = activity;
    }

    /** Panggil sekali setelah setContentView(R.layout.activity_termux). */
    public void setup() {
        mEditor = mActivity.findViewById(R.id.yummy_code_editor);
        if (mEditor == null) return;

        mStatusPos = mActivity.findViewById(R.id.yummy_status_pos);
        mStatusIndent = mActivity.findViewById(R.id.yummy_status_indent);
        configureEditor();
        configurePythonLanguage();
        bindKeys();

        mEditor.subscribeEvent(SelectionChangeEvent.class, (event, unsubscribe) -> updateStatus());
        mEditor.subscribeEvent(ContentChangeEvent.class, (event, unsubscribe) -> {
            if (!mSuppressContentChanges) {
                mDocumentRevision++;
            }
            updateStatus();
        });

        // Dokumen contoh awal dianggap bersih, sama seperti dokumen yang baru dimuat.
        setDocumentContent(SAMPLE, "Main.py");
        updateStatus();

        mActivity.getApplication().registerActivityLifecycleCallbacks(mLifecycle);
    }

    /** Mengganti isi dokumen, misalnya ketika File Manager membuka file. */
    public void setDocumentContent(String content, String name) {
        if (name != null && !name.trim().isEmpty()) {
            setDocumentName(name);
        }
        if (mEditor == null) return;

        mSuppressContentChanges = true;
        try {
            mEditor.setText(content == null ? "" : content);
        } finally {
            mSuppressContentChanges = false;
        }

        // File yang baru dimuat atau dibuat menjadi baseline bersih.
        mDocumentRevision++;
        mSavedRevision = mDocumentRevision;
        updateStatus();
    }

    /** Mengambil teks terkini dari Sora Editor. */
    @NonNull
    public String getDocumentText() {
        return mEditor == null ? "" : mEditor.getText().toString();
    }

    /** Nama dokumen aktif, digunakan sebagai nama default pada Save As. */
    @NonNull
    public String getDocumentName() {
        return mDocumentName;
    }

    /** Memperbarui nama dokumen dan judul editor. */
    public void setDocumentName(String name) {
        if (name == null || name.trim().isEmpty()) return;
        mDocumentName = name;
        TextView title = mActivity.findViewById(R.id.yummy_title);
        if (title != null) title.setText(name);
    }

    /** Nomor revisi konten saat ini. */
    public long getDocumentRevision() {
        return mDocumentRevision;
    }

    /** Menandai revisi yang benar-benar sudah selesai ditulis ke storage. */
    public void markDocumentSaved(long revision) {
        mSavedRevision = revision;
    }

    /** Bernilai true bila editor berubah setelah revisi terakhir yang disimpan. */
    public boolean isDocumentDirty() {
        return mDocumentRevision != mSavedRevision;
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
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            }
        };
}
