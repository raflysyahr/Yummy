#!/usr/bin/env python3
"""
Patch tahap 2b untuk Yummy: fitur dasar code editor berbasis Sora Editor 0.24.4.

Perubahan:
  1. Menambahkan dependency language-textmate:0.24.4.
  2. Menambahkan grammar TextMate Python + language configuration + tema gelap Yummy
     ke app/src/main/assets/textmate/.
  3. Mengaktifkan syntax highlighting Python melalui TextMate.
  4. Mengaktifkan auto indentation dan auto-closing pairs.
  5. Memberi warna background gutter nomor baris dan divider yang berbeda.
  6. Mengaktifkan pinLineNumber agar gutter tidak ikut scroll horizontal.

Catatan:
  - Tahap ini mengaktifkan grammar Python karena editor saat ini memakai main.py.
  - Grammar Python diunduh dari repository resmi Microsoft VS Code.
  - Tidak mengubah layout, pengelolaan tab, file manager, atau terminal.
  - Script melakukan preflight sebelum menulis file dan aman dijalankan ulang.
  - Jalankan dari root repository: python patch_yummy_editor_features.py

Rollback:
  git diff
  git restore app/build.gradle app/src/main/java/com/termux/app/editor/EditorController.java
  # Hapus hanya folder assets yang dibuat patch ini jika belum berisi file lain:
  rm -rf app/src/main/assets/textmate
"""

from pathlib import Path
import json
import sys
import urllib.request

ROOT = Path.cwd()

GRADLE = ROOT / "app/build.gradle"
CONTROLLER = ROOT / "app/src/main/java/com/termux/app/editor/EditorController.java"
ASSETS = ROOT / "app/src/main/assets/textmate"

GRAMMAR_REL = Path("python/syntaxes/python.tmLanguage.json")
CONFIG_REL = Path("python/language-configuration.json")
LANGUAGES_REL = Path("languages.json")
THEME_REL = Path("yummy-dark.json")

GRAMMAR_URL = (
    "https://raw.githubusercontent.com/microsoft/vscode/main/"
    "extensions/python/syntaxes/MagicPython.tmLanguage.json"
)

DEPENDENCY = 'implementation "io.github.rosemoe:language-textmate:0.24.4"'

LANGUAGE_CONFIG = {
    "comments": {
        "lineComment": "#",
        "blockComment": ['"""', '"""']
    },
    "brackets": [["{", "}"], ["[", "]"], ["(", ")"]],
    "autoClosingPairs": [
        {"open": "{", "close": "}"},
        {"open": "[", "close": "]"},
        {"open": "(", "close": ")"},
        {"open": '"', "close": '"', "notIn": ["string", "comment"]},
        {"open": "'", "close": "'", "notIn": ["string", "comment"]},
        {"open": "`", "close": "`", "notIn": ["string", "comment"]}
    ],
    "surroundingPairs": [
        ["{", "}"], ["[", "]"], ["(", ")"],
        ['"', '"'], ["'", "'"], ["`", "`"]
    ],
    "indentationRules": {
        "increaseIndentPattern": (
            r"^\s*(?:(?:async\s+)?(?:def|class)|if|elif|else|for|while|"
            r"try|except|finally|with|match|case)\b.*:\s*(?:#.*)?$"
        ),
        "decreaseIndentPattern": r"^\s*(?:elif|else|except|finally|case)\b"
    },
    "onEnterRules": [
        {
            "beforeText": (
                r"^\s*(?:(?:async\s+)?def|class|if|elif|else|for|while|"
                r"try|except|finally|with|match|case)\b.*:\s*(?:#.*)?$"
            ),
            "action": {"indent": "indent"}
        }
    ]
}

LANGUAGES = {
    "languages": [
        {
            "grammar": "textmate/python/syntaxes/python.tmLanguage.json",
            "name": "python",
            "scopeName": "source.python",
            "languageConfiguration": "textmate/python/language-configuration.json"
        }
    ]
}

YUMMY_THEME = {
    "name": "Yummy Dark",
    "type": "dark",
    "colors": {
        "editor.background": "#0B0D10",
        "editor.foreground": "#E6EDF3",
        "editorCursor.foreground": "#22C55E",
        "editor.selectionBackground": "#264F78",
        "editor.lineHighlightBackground": "#151A21",
        "editorLineNumber.foreground": "#71767B",
        "editorLineNumber.activeForeground": "#E6EDF3",
        "editorSuggestWidget.background": "#161B22",
        "editorSuggestWidget.foreground": "#E6EDF3",
        "editorSuggestWidget.selectedBackground": "#263342"
    },
    "tokenColors": [
        {
            "scope": ["comment", "punctuation.definition.comment"],
            "settings": {"foreground": "#7D8590", "fontStyle": "italic"}
        },
        {
            "scope": ["keyword", "keyword.control", "storage.type"],
            "settings": {"foreground": "#C792EA"}
        },
        {
            "scope": ["string", "string.quoted", "constant.character"],
            "settings": {"foreground": "#A5D6A7"}
        },
        {
            "scope": ["constant.numeric", "constant.language"],
            "settings": {"foreground": "#F2A272"}
        },
        {
            "scope": ["entity.name.function", "support.function"],
            "settings": {"foreground": "#82AAFF"}
        },
        {
            "scope": ["entity.name.class", "support.type"],
            "settings": {"foreground": "#FFCB6B"}
        },
        {
            "scope": ["variable.parameter"],
            "settings": {"foreground": "#F78C9C"}
        },
        {
            "scope": ["constant.language.python"],
            "settings": {"foreground": "#FF9E64"}
        },
        {
            "scope": ["keyword.operator"],
            "settings": {"foreground": "#89DDFF"}
        }
    ]
}


def fail(message):
    print("GAGAL:", message, file=sys.stderr)
    raise SystemExit(1)


def read_text(path):
    try:
        return path.read_text(encoding="utf-8")
    except Exception as exc:
        fail(f"Tidak bisa membaca {path.relative_to(ROOT)}: {exc}")


def download_python_grammar():
    target = ASSETS / GRAMMAR_REL
    if target.exists():
        try:
            parsed = json.loads(target.read_text(encoding="utf-8"))
            if parsed.get("scopeName") == "source.python":
                print("  - Grammar Python sudah ada, tidak diunduh ulang.")
                return target.read_bytes()
        except Exception:
            print("  - Grammar lama tidak valid; akan diunduh ulang.")

    request = urllib.request.Request(
        GRAMMAR_URL,
        headers={"User-Agent": "Yummy-Editor-Patcher/1.0"}
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            data = response.read()
        parsed = json.loads(data.decode("utf-8"))
        if parsed.get("scopeName") != "source.python":
            fail("Grammar yang diunduh bukan grammar source.python.")
        print(f"  - Grammar Python berhasil diunduh ({len(data)} bytes).")
        return data
    except Exception as exc:
        fail(
            "Gagal mengunduh grammar Python. Periksa koneksi internet "
            f"atau unduh manual dari {GRAMMAR_URL}. Detail: {exc}"
        )


def json_bytes(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def prepare_asset(path, expected, label):
    if path.exists():
        try:
            current = json.loads(path.read_text(encoding="utf-8"))
            wanted = json.loads(expected.decode("utf-8"))
            if current == wanted:
                print(f"  - {label} sudah sesuai.")
                return None
        except Exception:
            pass
        fail(
            f"{path.relative_to(ROOT)} sudah ada tetapi isinya berbeda/tidak valid. "
            "Script berhenti agar file milikmu tidak tertimpa."
        )
    return expected


def main():
    if not (ROOT / "app/src/main").is_dir():
        fail("Jalankan script dari root repository Yummy.")
    if not GRADLE.is_file():
        fail("app/build.gradle tidak ditemukan.")
    if not CONTROLLER.is_file():
        fail("EditorController.java tidak ditemukan.")

    gradle_text = read_text(GRADLE)
    controller_text = read_text(CONTROLLER)

    # ---------- Preflight Gradle ----------
    gradle_new = gradle_text
    if DEPENDENCY not in gradle_new:
        editor_dep = 'implementation "io.github.rosemoe:editor:0.24.4"'
        if gradle_new.count(editor_dep) != 1:
            fail(
                "Baris dependency io.github.rosemoe:editor:0.24.4 "
                "tidak ditemukan tepat satu kali."
            )
        gradle_new = gradle_new.replace(
            editor_dep, editor_dep + "\n        " + DEPENDENCY, 1
        )

    # ---------- Preflight EditorController ----------
    imports_to_add = [
        "import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme;",
        "import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;",
        "import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry;",
        "import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry;",
        "import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;",
        "import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel;",
        "import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver;",
        "import org.eclipse.tm4e.core.registry.IThemeSource;"
    ]

    missing_imports = [line for line in imports_to_add if line not in controller_text]
    if missing_imports:
        anchor = "import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;"
        if controller_text.count(anchor) != 1:
            fail("Anchor import EditorColorScheme tidak ditemukan tepat satu kali.")
        controller_text = controller_text.replace(
            anchor, anchor + "\n" + "\n".join(missing_imports), 1
        )

    if "private static boolean sTextMateInitialized" not in controller_text:
        anchor = "public class EditorController {"
        if controller_text.count(anchor) != 1:
            fail("Deklarasi class EditorController tidak ditemukan tepat satu kali.")
        controller_text = controller_text.replace(
            anchor,
            anchor + "\n    private static boolean sTextMateInitialized;\n",
            1
        )

    if "configurePythonLanguage();" not in controller_text:
        anchor = "        configureEditor();"
        if controller_text.count(anchor) != 1:
            fail("Pemanggilan configureEditor() tidak ditemukan tepat satu kali.")
        controller_text = controller_text.replace(
            anchor, anchor + "\n        configurePythonLanguage();", 1
        )

    # Pin gutter, auto-indent dan pair completion.
    if "mEditor.setPinLineNumber(true);" not in controller_text:
        anchor = "        mEditor.setLineNumberEnabled(true);"
        if controller_text.count(anchor) != 1:
            fail("Baris setLineNumberEnabled(true) tidak ditemukan tepat satu kali.")
        controller_text = controller_text.replace(
            anchor,
            anchor
            + "\n        // Gutter nomor baris tetap di tempat saat scroll horizontal."
            + "\n        mEditor.setPinLineNumber(true);"
            + "\n        // Fitur indentasi dan pasangan simbol dikelola oleh Sora/TextMate."
            + "\n        mEditor.getProps().autoIndent = true;"
            + "\n        mEditor.getProps().symbolPairAutoCompletion = true;",
            1
        )

    # Warnai gutter berbeda dari area kode.
    old_gutter = "        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, bg);"
    new_gutter = (
        "        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, Color.rgb(22, 26, 32));"
    )
    if old_gutter in controller_text:
        controller_text = controller_text.replace(old_gutter, new_gutter, 1)
    elif new_gutter not in controller_text:
        fail("Baris warna LINE_NUMBER_BACKGROUND tidak ditemukan.")

    old_divider = "        scheme.setColor(EditorColorScheme.LINE_DIVIDER, Color.TRANSPARENT);"
    new_divider = (
        "        scheme.setColor(EditorColorScheme.LINE_DIVIDER, Color.rgb(48, 54, 64));"
    )
    if old_divider in controller_text:
        controller_text = controller_text.replace(old_divider, new_divider, 1)
    elif new_divider not in controller_text:
        fail("Baris warna LINE_DIVIDER tidak ditemukan.")

    # Tambahkan method TextMate satu kali.
    if "private void configurePythonLanguage()" not in controller_text:
        anchor = "    private void updateStatus() {"
        if controller_text.count(anchor) != 1:
            fail("Anchor updateStatus() tidak ditemukan tepat satu kali.")

        method = r"""
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

            // Pertahankan gutter yang kontras setelah TextMate memasang color scheme.
            EditorColorScheme scheme = mEditor.getColorScheme();
            scheme.setColor(
                EditorColorScheme.LINE_NUMBER_BACKGROUND,
                Color.rgb(22, 26, 32)
            );
            scheme.setColor(
                EditorColorScheme.LINE_DIVIDER,
                Color.rgb(48, 54, 64)
            );

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

"""
        controller_text = controller_text.replace(anchor, method + anchor, 1)

    # ---------- Assets: unduh/siapkan seluruhnya sebelum menulis apa pun ----------
    grammar_data = download_python_grammar()

    config_data = json_bytes(LANGUAGE_CONFIG)
    languages_data = json_bytes(LANGUAGES)
    theme_data = json_bytes(YUMMY_THEME)

    asset_writes = {}
    grammar_path = ASSETS / GRAMMAR_REL
    if not grammar_path.exists():
        asset_writes[grammar_path] = grammar_data
    else:
        # File grammar valid yang sudah ada dipertahankan.
        json.loads(grammar_path.read_text(encoding="utf-8"))

    for relative, data, label in [
        (CONFIG_REL, config_data, "Konfigurasi bahasa Python"),
        (LANGUAGES_REL, languages_data, "Registry bahasa TextMate"),
        (THEME_REL, theme_data, "Tema gelap Yummy")
    ]:
        target = ASSETS / relative
        prepared = prepare_asset(target, data, label)
        if prepared is not None:
            asset_writes[target] = prepared

    # ---------- Tulis setelah semua pemeriksaan sukses ----------
    if gradle_new != gradle_text:
        GRADLE.write_text(gradle_new, encoding="utf-8")

    if controller_text != read_text(CONTROLLER):
        CONTROLLER.write_text(controller_text, encoding="utf-8")

    for path, data in asset_writes.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    print("\nPATCH BERHASIL.")
    print("File yang diperbarui:")
    if gradle_new != gradle_text:
        print("  - app/build.gradle")
    if controller_text != read_text(CONTROLLER):
        print("  - app/src/main/java/com/termux/app/editor/EditorController.java")
    for path in asset_writes:
        print("  - " + str(path.relative_to(ROOT)))

    print("\nFitur yang diaktifkan:")
    print("  - Syntax highlighting Python (TextMate)")
    print("  - Auto indentation Python (4 spasi)")
    print("  - Auto-closing: (), [], {}, tanda kutip, dan backtick")
    print("  - Background gutter dan divider nomor baris")
    print("  - Nomor baris pinned saat horizontal scrolling")
    print("\nSelanjutnya jalankan: ./gradlew :app:assembleDebug")


if __name__ == "__main__":
    main()

