package com.termux.app.theme.sora;

import android.app.Activity;

import com.termux.app.theme.io.AssetThemeResourceReader;
import com.termux.app.theme.model.ResolvedEditorTheme;
import com.termux.app.theme.model.ThemeAppearance;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.tm4e.core.registry.IThemeSource;

import io.github.rosemoe.sora.lang.analysis.IncrementalAnalyzeManager;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel;
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver;
import io.github.rosemoe.sora.text.ContentLine;
import io.github.rosemoe.sora.widget.CodeEditor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Verifies TextMate font decorations are replaced and restored across syntax-theme switches. */
@RunWith(RobolectricTestRunner.class)
public class SoraTextMateFontStyleThemeSwitchRuntimeTest {

    @Test
    public void fontStylesAreReplacedAcrossThemeSwitchesAndBaseThemeRestoration()
        throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FileProviderRegistry.getInstance().addFileProvider(
            new AssetsFileResolver(activity.getAssets()));

        String baseThemeJson = new AssetThemeResourceReader(activity.getAssets())
            .readUtf8("textmate/yummy-dark.json");
        String baseRegistryName = "yummy-step50-base";
        IThemeSource baseSource = IThemeSource.fromInputStream(
            new ByteArrayInputStream(baseThemeJson.getBytes(StandardCharsets.UTF_8)),
            "yummy-step50-base.json", null);
        ThemeModel baseModel = new ThemeModel(baseSource, baseRegistryName);
        baseModel.setDark(true);

        ThemeRegistry registry = ThemeRegistry.getInstance();
        registry.loadTheme(baseModel, false);
        assertTrue("Base TextMate theme must activate", registry.setTheme(baseRegistryName));
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json");

        CodeEditor editor = new CodeEditor(activity);
        TextMateColorScheme colorScheme = TextMateColorScheme.create(registry);
        editor.setColorScheme(colorScheme);
        TextMateLanguage language = TextMateLanguage.create("source.java", true);
        editor.setText("if\n\"yummy\"\n// comment");
        editor.setEditorLanguage(language);

        SoraEditorThemeApplier applier = new SoraEditorThemeApplier();
        try {
            Span baseKeyword = firstSpan(language, "if", "base keyword");
            Span baseComment = firstSpan(language, "// comment", "base comment");
            assertFalse("Base keyword must begin without bold",
                TextStyle.isBold(baseKeyword.getStyle()));
            assertFalse("Base keyword must begin without italic",
                TextStyle.isItalics(baseKeyword.getStyle()));
            assertTrue("Yummy base comment must begin italic",
                TextStyle.isItalics(baseComment.getStyle()));

            ResolvedEditorTheme themeA = importedTheme(
                "step50-theme-a",
                "Step 50 Theme A",
                new JSONArray()
                    .put(tokenRule("keyword.control", "#FF3366", "bold italic underline"))
                    .put(tokenRule("string.quoted.double", "#33AA66", "italic"))
                    .put(tokenRule("comment.line", "#6699FF", "normal"))
            );
            applier.apply(editor, themeA, null, baseThemeJson);

            Span themeAKeyword = firstSpan(language, "if", "Theme A keyword");
            Span themeAString = firstSpan(language, "\"yummy\"", "Theme A string");
            Span themeAComment = firstSpan(language, "// comment", "Theme A comment");
            assertTrue("Theme A keyword must be bold", TextStyle.isBold(themeAKeyword.getStyle()));
            assertTrue("Theme A keyword must be italic", TextStyle.isItalics(themeAKeyword.getStyle()));
            assertTrue("Theme A keyword must be underlined",
                themeAKeyword.getUnderlineColor() != null);
            assertTrue("Theme A string must be italic", TextStyle.isItalics(themeAString.getStyle()));
            assertFalse("Theme A string must not be bold", TextStyle.isBold(themeAString.getStyle()));
            assertFalse("Theme A comment normal must clear base italic",
                TextStyle.isItalics(themeAComment.getStyle()));
            assertNull("Theme A comment normal must clear underline",
                themeAComment.getUnderlineColor());
            assertEquals("Theme A keyword foreground must be retained", 0xFFFF3366,
                colorScheme.getColor(themeAKeyword.getForegroundColorId()));

            ResolvedEditorTheme themeB = importedTheme(
                "step50-theme-b",
                "Step 50 Theme B",
                new JSONArray()
                    .put(tokenRule("keyword.control", "#FF8844", "normal"))
                    .put(tokenRule("string.quoted.double", "#55AA88", "normal"))
                    .put(tokenRule("comment.line", "#6699FF", "bold underline"))
            );
            applier.apply(editor, themeB, null, baseThemeJson);

            Span themeBKeyword = firstSpan(language, "if", "Theme B keyword");
            Span themeBString = firstSpan(language, "\"yummy\"", "Theme B string");
            Span themeBComment = firstSpan(language, "// comment", "Theme B comment");
            assertFalse("Theme B normal must remove Theme A keyword bold",
                TextStyle.isBold(themeBKeyword.getStyle()));
            assertFalse("Theme B normal must remove Theme A keyword italic",
                TextStyle.isItalics(themeBKeyword.getStyle()));
            assertNull("Theme B normal must remove Theme A keyword underline",
                themeBKeyword.getUnderlineColor());
            assertFalse("Theme B normal must remove Theme A string italic",
                TextStyle.isItalics(themeBString.getStyle()));
            assertTrue("Theme B comment must receive bold", TextStyle.isBold(themeBComment.getStyle()));
            assertFalse("Theme B comment must not retain base italic",
                TextStyle.isItalics(themeBComment.getStyle()));
            assertTrue("Theme B comment must receive underline",
                themeBComment.getUnderlineColor() != null);
            assertEquals("Theme B keyword foreground must be active", 0xFFFF8844,
                colorScheme.getColor(themeBKeyword.getForegroundColorId()));

            applier.apply(editor, themeA, null, baseThemeJson);
            Span themeAAgainKeyword = firstSpan(language, "if", "Theme A reactivated keyword");
            Span themeAAgainComment = firstSpan(language, "// comment", "Theme A reactivated comment");
            assertTrue("Reactivated Theme A must restore keyword bold",
                TextStyle.isBold(themeAAgainKeyword.getStyle()));
            assertTrue("Reactivated Theme A must restore keyword italic",
                TextStyle.isItalics(themeAAgainKeyword.getStyle()));
            assertTrue("Reactivated Theme A must restore keyword underline",
                themeAAgainKeyword.getUnderlineColor() != null);
            assertFalse("Reactivated Theme A normal comment must remain non-italic",
                TextStyle.isItalics(themeAAgainComment.getStyle()));

            assertTrue("Returning to Yummy base theme must succeed",
                registry.setTheme(baseRegistryName));
            Span restoredKeyword = firstSpan(language, "if", "restored base keyword");
            Span restoredString = firstSpan(language, "\"yummy\"", "restored base string");
            Span restoredComment = firstSpan(language, "// comment", "restored base comment");
            assertFalse("Base keyword must not retain imported bold",
                TextStyle.isBold(restoredKeyword.getStyle()));
            assertFalse("Base keyword must not retain imported italic",
                TextStyle.isItalics(restoredKeyword.getStyle()));
            assertFalse("Base string must not retain imported italic",
                TextStyle.isItalics(restoredString.getStyle()));
            assertTrue("Base comment italic must be restored",
                TextStyle.isItalics(restoredComment.getStyle()));
            assertSame("Theme switches must preserve TextMateColorScheme identity",
                colorScheme, editor.getColorScheme());
        } finally {
            registry.setTheme(baseRegistryName);
            activity.finish();
        }
    }

    private static ResolvedEditorTheme importedTheme(String id, String name, JSONArray rules) {
        return new ResolvedEditorTheme(
            id, name, ThemeAppearance.DARK, rules, null, null, null);
    }

    private static JSONObject tokenRule(String scope, String foreground, String fontStyle)
        throws Exception {
        return new JSONObject()
            .put("scope", scope)
            .put("settings", new JSONObject()
                .put("foreground", foreground)
                .put("fontStyle", fontStyle));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Span firstSpan(
        TextMateLanguage language,
        String sourceLine,
        String tokenDescription
    ) {
        IncrementalAnalyzeManager analyzer =
            (IncrementalAnalyzeManager) language.getAnalyzeManager();
        IncrementalAnalyzeManager.LineTokenizeResult result = analyzer.tokenizeLine(
            new ContentLine(sourceLine), analyzer.getInitialState(), 0);
        List<Span> spans = result.spans;
        if (spans == null || spans.isEmpty()) {
            throw new AssertionError("TextMate did not produce spans for " + tokenDescription);
        }
        for (Span span : spans) {
            if (span.getColumn() == 0) return span;
            if (span.getColumn() > 0) break;
        }
        throw new AssertionError("TextMate did not produce a span at column 0 for "
            + tokenDescription);
    }
}
