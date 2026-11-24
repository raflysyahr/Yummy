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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that TextMate fontStyle matching follows scope specificity rather than allowing
 * a broad selector to override a more-specific selector merely because it appears later.
 */
@RunWith(RobolectricTestRunner.class)
public class SoraTextMateFontStyleScopeSpecificityRuntimeTest {

    @Test
    public void specificFontStyleWinsOverBroadRuleInEitherDeclarationOrder() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FileProviderRegistry.getInstance().addFileProvider(
            new AssetsFileResolver(activity.getAssets()));

        String baseThemeJson = new AssetThemeResourceReader(activity.getAssets())
            .readUtf8("textmate/yummy-dark.json");
        String baseRegistryName = "yummy-step47-base";
        IThemeSource baseSource = IThemeSource.fromInputStream(
            new ByteArrayInputStream(baseThemeJson.getBytes(StandardCharsets.UTF_8)),
            "yummy-step47-base.json", null);
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
        editor.setText("if");
        editor.setEditorLanguage(language);

        try {
            // The broad selector is deliberately later. Its normal style must not erase
            // the more-specific keyword.control style.
            activateTheme(editor, baseThemeJson, "step47-specific-first",
                new JSONArray()
                    .put(tokenRule("keyword.control", "bold italic underline"))
                    .put(tokenRule("keyword", "normal")));
            assertSpecificStyle(language, "specific-first declaration order");

            // Reverse declaration order: the same specific selector must still win.
            activateTheme(editor, baseThemeJson, "step47-broad-first",
                new JSONArray()
                    .put(tokenRule("keyword", "normal"))
                    .put(tokenRule("keyword.control", "bold italic underline")));
            assertSpecificStyle(language, "broad-first declaration order");
        } finally {
            // ThemeRegistry is process-wide; never leak this test's active palette.
            registry.setTheme(baseRegistryName);
            activity.finish();
        }
    }

    private static void assertSpecificStyle(TextMateLanguage language, String scenario) {
        Span span = firstSpan(language, "if", scenario);
        assertTrue(scenario + ": specific bold flag must be retained",
            TextStyle.isBold(span.getStyle()));
        assertTrue(scenario + ": specific italic flag must be retained",
            TextStyle.isItalics(span.getStyle()));
        assertNotNull(scenario + ": specific underline must be retained",
            span.getUnderlineColor());
    }

    private static void activateTheme(
        CodeEditor editor,
        String baseThemeJson,
        String themeId,
        JSONArray tokenColors
    ) throws Exception {
        ResolvedEditorTheme theme = new ResolvedEditorTheme(
            themeId,
            themeId,
            ThemeAppearance.DARK,
            tokenColors,
            null,
            null,
            null
        );
        new SoraEditorThemeApplier().apply(editor, theme, null, baseThemeJson);
    }

    private static JSONObject tokenRule(String scope, String fontStyle) throws Exception {
        // Keep a concrete foreground color on every rule. Sora derives the TextMate
        // underline color from the token foreground; without it, this test can exercise
        // an unrelated undefined-underline-color path instead of scope specificity.
        return new JSONObject()
            .put("scope", scope)
            .put("settings", new JSONObject()
                .put("foreground", "#FF3366")
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
