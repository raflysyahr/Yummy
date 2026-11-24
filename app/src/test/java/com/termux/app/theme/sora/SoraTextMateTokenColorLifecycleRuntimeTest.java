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

/** Verifies TextMate token colors across successive registry theme activations. */
@RunWith(RobolectricTestRunner.class)
public class SoraTextMateTokenColorLifecycleRuntimeTest {

    @Test
    public void existingTextMateSchemeResolvesLatestTokenPaletteAcrossThemeSwitches()
        throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FileProviderRegistry.getInstance().addFileProvider(
            new AssetsFileResolver(activity.getAssets()));

        String baseThemeJson = new AssetThemeResourceReader(activity.getAssets())
            .readUtf8("textmate/yummy-dark.json");
        String baseRegistryName = "yummy-step43-base";
        IThemeSource baseSource = IThemeSource.fromInputStream(
            new ByteArrayInputStream(baseThemeJson.getBytes(StandardCharsets.UTF_8)),
            "yummy-step43-base.json", null);
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
        String keywordLine = "if";
        editor.setText(keywordLine);
        editor.setEditorLanguage(language);

        try {
            assertEquals("Initial keyword token must use Yummy's base TextMate palette",
                0xFFC792EA, resolveKeywordTokenColor(language, colorScheme, keywordLine));
            assertSame("The editor must retain the original TextMateColorScheme instance",
                colorScheme, editor.getColorScheme());

            activateSyntaxTheme("step43-token-theme-a", "#FF3366", baseThemeJson, editor);
            assertEquals("First syntax activation must recolor the keyword token",
                0xFFFF3366, resolveKeywordTokenColor(language, colorScheme, keywordLine));
            assertSame("First activation must not replace the attached TextMateColorScheme",
                colorScheme, editor.getColorScheme());

            activateSyntaxTheme("step43-token-theme-b", "#33CC99", baseThemeJson, editor);
            assertEquals("Second syntax activation must replace the previous token palette",
                0xFF33CC99, resolveKeywordTokenColor(language, colorScheme, keywordLine));
            assertSame("Second activation must keep the same TextMateColorScheme instance",
                colorScheme, editor.getColorScheme());

            activateSyntaxTheme("step43-token-theme-c", "#6699FF", baseThemeJson, editor);
            assertEquals("Third syntax activation must not retain either earlier token color",
                0xFF6699FF, resolveKeywordTokenColor(language, colorScheme, keywordLine));

            assertTrue("Returning to the base theme must succeed",
                registry.setTheme(baseRegistryName));
            assertEquals("Returning to the base theme must restore its keyword token color",
                0xFFC792EA, resolveKeywordTokenColor(language, colorScheme, keywordLine));
            assertSame("Returning to the base theme must not replace the attached color scheme",
                colorScheme, editor.getColorScheme());
        } finally {
            // ThemeRegistry is process-wide; never leak this test's active palette.
            registry.setTheme(baseRegistryName);
            activity.finish();
        }
    }

    private static void activateSyntaxTheme(
        String themeId,
        String foreground,
        String baseThemeJson,
        CodeEditor editor
    ) throws Exception {
        ResolvedEditorTheme theme = new ResolvedEditorTheme(
            themeId,
            themeId,
            ThemeAppearance.DARK,
            new JSONArray().put(new JSONObject()
                .put("scope", "keyword")
                .put("settings", new JSONObject().put("foreground", foreground))),
            null,
            null,
            null
        );
        new SoraEditorThemeApplier().apply(editor, theme, null, baseThemeJson);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int resolveKeywordTokenColor(
        TextMateLanguage language,
        TextMateColorScheme colorScheme,
        String sourceLine
    ) {
        IncrementalAnalyzeManager analyzer =
            (IncrementalAnalyzeManager) language.getAnalyzeManager();
        IncrementalAnalyzeManager.LineTokenizeResult result = analyzer.tokenizeLine(
            new ContentLine(sourceLine), analyzer.getInitialState(), 0);
        List<Span> spans = result.spans;
        if (spans == null || spans.isEmpty()) {
            throw new AssertionError("TextMate did not produce spans for Java keyword 'if'");
        }
        for (Span span : spans) {
            if (span.getColumn() == 0) {
                int foregroundColorId = span.getForegroundColorId();
                assertTrue("TextMate keyword must use a dynamic palette color ID",
                    foregroundColorId >= 255);
                return colorScheme.getColor(foregroundColorId);
            }
            if (span.getColumn() > 0) break;
        }
        throw new AssertionError("TextMate did not produce a span at the keyword start");
    }
}
