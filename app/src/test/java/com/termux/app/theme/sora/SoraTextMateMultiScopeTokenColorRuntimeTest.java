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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Verifies VS Code/TextMate tokenColors selectors against several Java token scopes. */
@RunWith(RobolectricTestRunner.class)
public class SoraTextMateMultiScopeTokenColorRuntimeTest {

    @Test
    public void importedThemeColorsKeywordStringCommentAndNumberScopes() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FileProviderRegistry.getInstance().addFileProvider(
            new AssetsFileResolver(activity.getAssets()));

        String baseThemeJson = new AssetThemeResourceReader(activity.getAssets())
            .readUtf8("textmate/yummy-dark.json");
        String baseRegistryName = "yummy-step44-base";
        IThemeSource baseSource = IThemeSource.fromInputStream(
            new ByteArrayInputStream(baseThemeJson.getBytes(StandardCharsets.UTF_8)),
            "yummy-step44-base.json", null);
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
        editor.setText("if\n\"yummy\"\n'x'\n// comment\n42");
        editor.setEditorLanguage(language);

        try {
            assertBasePalette(language, colorScheme);
            assertSame("The editor must retain its original TextMateColorScheme",
                colorScheme, editor.getColorScheme());

            ResolvedEditorTheme importedTheme = new ResolvedEditorTheme(
                "step44-multi-scope-theme",
                "Step 44 Multi-Scope Syntax Theme",
                ThemeAppearance.DARK,
                createMultiScopeTokenColors(),
                null,
                null,
                null
            );
            new SoraEditorThemeApplier().apply(editor, importedTheme, null, baseThemeJson);

            assertEquals("keyword.control scope must use the imported keyword color",
                0xFFFF3366, resolveTokenColor(language, colorScheme, "if", "keyword"));
            assertEquals("string.quoted.double scope must use the array selector color",
                0xFF33CC99, resolveTokenColor(language, colorScheme, "\"yummy\"", "double-quoted string"));
            assertEquals("string.quoted.single scope must share the array selector color",
                0xFF33CC99, resolveTokenColor(language, colorScheme, "'x'", "single-quoted string"));
            assertEquals("comment.line scope must use the imported comment color",
                0xFF6699FF, resolveTokenColor(language, colorScheme, "// comment", "line comment"));
            assertEquals("constant.numeric scope must use the imported numeric color",
                0xFFFFCC33, resolveTokenColor(language, colorScheme, "42", "numeric literal"));
            assertSame("Applying tokenColors must not replace the attached TextMateColorScheme",
                colorScheme, editor.getColorScheme());

            assertTrue("Returning to Yummy's base theme must succeed",
                registry.setTheme(baseRegistryName));
            assertBasePalette(language, colorScheme);
            assertSame("Restoring the base palette must preserve the attached color scheme",
                colorScheme, editor.getColorScheme());
        } finally {
            // ThemeRegistry is process-wide; never leak this test's active palette.
            registry.setTheme(baseRegistryName);
            activity.finish();
        }
    }

    private static JSONArray createMultiScopeTokenColors() throws Exception {
        JSONArray rules = new JSONArray();
        rules.put(tokenRule("keyword.control", "#FF3366"));
        rules.put(tokenRule(new JSONArray()
            .put("string.quoted.double")
            .put("string.quoted.single"), "#33CC99"));
        rules.put(tokenRule("comment.line", "#6699FF"));
        rules.put(tokenRule("constant.numeric", "#FFCC33"));
        return rules;
    }

    private static JSONObject tokenRule(Object scope, String foreground) throws Exception {
        return new JSONObject()
            .put("scope", scope)
            .put("settings", new JSONObject().put("foreground", foreground));
    }

    private static void assertBasePalette(
        TextMateLanguage language,
        TextMateColorScheme colorScheme
    ) {
        assertEquals("Yummy base keyword color must be restored",
            0xFFC792EA, resolveTokenColor(language, colorScheme, "if", "base keyword"));
        assertEquals("Yummy base string color must be restored",
            0xFFA5D6A7, resolveTokenColor(language, colorScheme, "\"yummy\"", "base string"));
        assertEquals("Yummy base single-quoted string color must be restored",
            0xFFA5D6A7, resolveTokenColor(language, colorScheme, "'x'", "base character"));
        assertEquals("Yummy base comment color must be restored",
            0xFF7D8590, resolveTokenColor(language, colorScheme, "// comment", "base comment"));
        assertEquals("Yummy base numeric color must be restored",
            0xFFF2A272, resolveTokenColor(language, colorScheme, "42", "base numeric"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int resolveTokenColor(
        TextMateLanguage language,
        TextMateColorScheme colorScheme,
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
            if (span.getColumn() == 0) {
                int foregroundColorId = span.getForegroundColorId();
                assertTrue("TextMate must resolve a dynamic foreground color for "
                    + tokenDescription, foregroundColorId >= 255);
                return colorScheme.getColor(foregroundColorId);
            }
            if (span.getColumn() > 0) break;
        }
        throw new AssertionError("TextMate did not produce a span at column 0 for "
            + tokenDescription);
    }
}
