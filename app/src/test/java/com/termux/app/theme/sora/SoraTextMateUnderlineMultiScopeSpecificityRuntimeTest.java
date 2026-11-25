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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Verifies a specific selector overrides an underline supplied by a multi-scope rule. */
@RunWith(RobolectricTestRunner.class)
public class SoraTextMateUnderlineMultiScopeSpecificityRuntimeTest {

    @Test
    public void specificNormalOverridesArrayUnderlineInEitherDeclarationOrder() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FileProviderRegistry.getInstance().addFileProvider(
            new AssetsFileResolver(activity.getAssets()));

        String baseThemeJson = new AssetThemeResourceReader(activity.getAssets())
            .readUtf8("textmate/yummy-dark.json");
        String baseRegistryName = "yummy-step57-base";
        IThemeSource baseSource = IThemeSource.fromInputStream(
            new ByteArrayInputStream(baseThemeJson.getBytes(StandardCharsets.UTF_8)),
            "yummy-step57-base.json", null);
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
        editor.setText("\"double\"\n'single'");
        editor.setEditorLanguage(language);

        JSONArray stringScopes = new JSONArray().put("string");
        try {
            activateTheme(editor, baseThemeJson, "step57-specific-first",
                new JSONArray()
                    .put(tokenRule("string.quoted.double", "#33CC99", "normal"))
                    .put(tokenRule(stringScopes, "#33CC99", "underline")));
            assertSpecificOverride(language, colorScheme, "specific-first declaration order");

            activateTheme(editor, baseThemeJson, "step57-array-first",
                new JSONArray()
                    .put(tokenRule(stringScopes, "#33CC99", "underline"))
                    .put(tokenRule("string.quoted.double", "#33CC99", "normal")));
            assertSpecificOverride(language, colorScheme, "array-first declaration order");

            assertSame("Selector specificity must preserve TextMateColorScheme identity",
                colorScheme, editor.getColorScheme());
            assertTrue("Returning to Yummy base theme must succeed",
                registry.setTheme(baseRegistryName));
            assertNull("Base double-quoted string must not retain imported underline",
                firstSpan(language, "\"double\"", "restored double string").getUnderlineColor());
            assertNull("Base single-quoted string must not retain imported underline",
                firstSpan(language, "'single'", "restored single string").getUnderlineColor());
            assertSame("Base restoration must preserve TextMateColorScheme identity",
                colorScheme, editor.getColorScheme());
        } finally {
            registry.setTheme(baseRegistryName);
            activity.finish();
        }
    }

    private static void assertSpecificOverride(
        TextMateLanguage language,
        TextMateColorScheme colorScheme,
        String scenario
    ) {
        Span doubleString = firstSpan(language, "\"double\"", scenario + " double string");
        assertNull(scenario + ": specific normal must clear array-selector underline",
            doubleString.getUnderlineColor());
        assertFalse(scenario + ": normal must not introduce bold",
            TextStyle.isBold(doubleString.getStyle()));
        assertFalse(scenario + ": normal must not introduce italic",
            TextStyle.isItalics(doubleString.getStyle()));
        assertEquals(scenario + ": specific token foreground must remain correct",
            Color.parseColor("#33CC99"),
            colorScheme.getColor(doubleString.getForegroundColorId()));

        Span singleString = firstSpan(language, "'single'", scenario + " single string");
        assertNotNull(scenario + ": sibling scope must retain array-selector underline",
            singleString.getUnderlineColor());
        assertEquals(scenario + ": sibling token foreground must remain correct",
            Color.parseColor("#33CC99"),
            colorScheme.getColor(singleString.getForegroundColorId()));
    }

    private static void activateTheme(
        CodeEditor editor,
        String baseThemeJson,
        String themeId,
        JSONArray tokenColors
    ) throws Exception {
        ResolvedEditorTheme theme = new ResolvedEditorTheme(
            themeId, themeId, ThemeAppearance.DARK, tokenColors, null, null, null);
        new SoraEditorThemeApplier().apply(editor, theme, null, baseThemeJson);
    }

    private static JSONObject tokenRule(Object scope, String foreground, String fontStyle)
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
