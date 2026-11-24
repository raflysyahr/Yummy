package com.termux.app.theme.sora;

import android.app.Activity;
import android.graphics.Color;

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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Verifies that a specific TextMate scope can clear underline from a broad selector. */
@RunWith(RobolectricTestRunner.class)
public class SoraTextMateUnderlineScopeSpecificityRuntimeTest {

    @Test
    public void specificNormalClearsBroadUnderlineInEitherDeclarationOrder() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FileProviderRegistry.getInstance().addFileProvider(
            new AssetsFileResolver(activity.getAssets()));

        String baseThemeJson = new AssetThemeResourceReader(activity.getAssets())
            .readUtf8("textmate/yummy-dark.json");
        String baseRegistryName = "yummy-step53-base";
        IThemeSource baseSource = IThemeSource.fromInputStream(
            new ByteArrayInputStream(baseThemeJson.getBytes(StandardCharsets.UTF_8)),
            "yummy-step53-base.json", null);
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
            // The broad selector supplies underline; the more-specific selector must
            // explicitly clear it even when the broad rule appears later in the JSON.
            activateTheme(editor, baseThemeJson, "step53-specific-first",
                new JSONArray()
                    .put(tokenRule("keyword.control", "normal"))
                    .put(tokenRule("keyword", "underline")));
            assertSpecificNormal(language, colorScheme, "specific-first declaration order");

            // Reverse declaration order to ensure the result comes from scope
            // specificity rather than whichever matching rule happens to appear last.
            activateTheme(editor, baseThemeJson, "step53-broad-first",
                new JSONArray()
                    .put(tokenRule("keyword", "underline"))
                    .put(tokenRule("keyword.control", "normal")));
            assertSpecificNormal(language, colorScheme, "broad-first declaration order");
        } finally {
            // ThemeRegistry is process-wide; never leak this test's active palette.
            registry.setTheme(baseRegistryName);
            activity.finish();
        }
    }

    private static void assertSpecificNormal(
        TextMateLanguage language,
        TextMateColorScheme colorScheme,
        String scenario
    ) {
        Span span = firstSpan(language, "if", scenario);
        assertNull(scenario + ": specific normal must clear broad underline",
            span.getUnderlineColor());
        assertFalse(scenario + ": specific normal must not introduce bold",
            TextStyle.isBold(span.getStyle()));
        assertFalse(scenario + ": specific normal must not introduce italic",
            TextStyle.isItalics(span.getStyle()));
        assertEquals(scenario + ": token foreground must remain independently resolved",
            Color.parseColor("#FF3366"),
            colorScheme.getColor(span.getForegroundColorId()));
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
