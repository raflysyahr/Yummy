package com.termux.app.theme.sora;

import android.app.Activity;

import com.termux.app.theme.io.AssetThemeResourceReader;
import com.termux.app.theme.model.ResolvedEditorTheme;
import com.termux.app.theme.model.ThemeAppearance;

import org.eclipse.tm4e.core.registry.IThemeSource;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that one VS Code scope-array rule composes all supported font decorations
 * consistently across Java keyword, string, and comment tokens.
 */
@RunWith(RobolectricTestRunner.class)
public class SoraTextMateFontStyleMultiScopeCompositionRuntimeTest {

    @Test
    public void combinedFontStyleAppliesToEveryScopeInArrayAndRestoresBaseStyles()
        throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FileProviderRegistry.getInstance().addFileProvider(
            new AssetsFileResolver(activity.getAssets()));

        String baseThemeJson = new AssetThemeResourceReader(activity.getAssets())
            .readUtf8("textmate/yummy-dark.json");
        String baseRegistryName = "yummy-step48-base";
        IThemeSource baseSource = IThemeSource.fromInputStream(
            new ByteArrayInputStream(baseThemeJson.getBytes(StandardCharsets.UTF_8)),
            "yummy-step48-base.json", null);
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

        try {
            Span baseKeyword = firstSpan(language, "if", "base keyword");
            Span baseString = firstSpan(language, "\"yummy\"", "base string");
            Span baseComment = firstSpan(language, "// comment", "base comment");

            assertFalse("Base keyword must not be bold",
                TextStyle.isBold(baseKeyword.getStyle()));
            assertFalse("Base keyword must not be italic",
                TextStyle.isItalics(baseKeyword.getStyle()));
            assertFalse("Base string must not be bold",
                TextStyle.isBold(baseString.getStyle()));
            assertFalse("Base string must not be italic",
                TextStyle.isItalics(baseString.getStyle()));
            assertTrue("Yummy base comment is expected to be italic",
                TextStyle.isItalics(baseComment.getStyle()));

            ResolvedEditorTheme importedTheme = new ResolvedEditorTheme(
                "step48-font-style-multi-scope",
                "Step 48 Font Style Multi-Scope Theme",
                ThemeAppearance.DARK,
                createMultiScopeFontStyleRules(),
                null,
                null,
                null
            );
            new SoraEditorThemeApplier().apply(editor, importedTheme, null, baseThemeJson);

            assertCombinedStyle(language, colorScheme, "if", "keyword");
            assertCombinedStyle(language, colorScheme, "\"yummy\"", "double-quoted string");
            assertCombinedStyle(language, colorScheme, "// comment", "line comment");
            assertSame("Applying token font styles must preserve the attached TextMateColorScheme",
                colorScheme, editor.getColorScheme());

            assertTrue("Returning to Yummy's base theme must succeed",
                registry.setTheme(baseRegistryName));

            Span restoredKeyword = firstSpan(language, "if", "restored keyword");
            Span restoredString = firstSpan(language, "\"yummy\"", "restored string");
            Span restoredComment = firstSpan(language, "// comment", "restored comment");

            assertFalse("Base keyword must be restored without bold",
                TextStyle.isBold(restoredKeyword.getStyle()));
            assertFalse("Base keyword must be restored without italic",
                TextStyle.isItalics(restoredKeyword.getStyle()));
            assertFalse("Base string must be restored without bold",
                TextStyle.isBold(restoredString.getStyle()));
            assertFalse("Base string must be restored without italic",
                TextStyle.isItalics(restoredString.getStyle()));
            assertTrue("Base comment italic style must be restored",
                TextStyle.isItalics(restoredComment.getStyle()));
            assertFalse("Base comment must not retain imported bold",
                TextStyle.isBold(restoredComment.getStyle()));
            assertSame("Restoring the base theme must preserve the attached TextMateColorScheme",
                colorScheme, editor.getColorScheme());
        } finally {
            // ThemeRegistry is process-wide; never leak this test's active palette.
            registry.setTheme(baseRegistryName);
            activity.finish();
        }
    }

    private static JSONArray createMultiScopeFontStyleRules() throws Exception {
        JSONArray scopes = new JSONArray()
            .put("keyword.control")
            .put("string.quoted.double")
            .put("comment.line");
        JSONObject settings = new JSONObject()
            .put("foreground", "#FF3366")
            .put("fontStyle", "bold italic underline");
        return new JSONArray().put(new JSONObject()
            .put("scope", scopes)
            .put("settings", settings));
    }

    private static void assertCombinedStyle(
        TextMateLanguage language,
        TextMateColorScheme colorScheme,
        String sourceLine,
        String tokenDescription
    ) {
        Span span = firstSpan(language, sourceLine, tokenDescription);
        assertTrue(tokenDescription + " must receive bold",
            TextStyle.isBold(span.getStyle()));
        assertTrue(tokenDescription + " must receive italic",
            TextStyle.isItalics(span.getStyle()));
        assertNotNull(tokenDescription + " must receive underline",
            span.getUnderlineColor());
        assertEquals(tokenDescription + " must use the imported foreground",
            0xFFFF3366, colorScheme.getColor(span.getForegroundColorId()));
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
