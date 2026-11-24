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

/** Verifies TextMate scope specificity and ordering for overlapping token color rules. */
@RunWith(RobolectricTestRunner.class)
public class SoraTextMateScopeSpecificityRuntimeTest {

    @Test
    public void moreSpecificScopeWinsOverLaterBroadSelector() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FileProviderRegistry.getInstance().addFileProvider(
            new AssetsFileResolver(activity.getAssets()));

        String baseThemeJson = new AssetThemeResourceReader(activity.getAssets())
            .readUtf8("textmate/yummy-dark.json");
        String baseRegistryName = "yummy-step45-base";
        IThemeSource baseSource = IThemeSource.fromInputStream(
            new ByteArrayInputStream(baseThemeJson.getBytes(StandardCharsets.UTF_8)),
            "yummy-step45-base.json", null);
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
            assertEquals("Initial keyword must use Yummy's base palette",
                0xFFC792EA, resolveKeywordColor(language, colorScheme));

            // A later, broad selector must not override an earlier, more specific selector.
            JSONArray specificityRules = new JSONArray()
                .put(tokenRule("keyword.control", "#FF3366"))
                .put(tokenRule("keyword", "#33CC99"));
            activateTheme(editor, baseThemeJson, "step45-specificity", specificityRules);
            assertEquals("More-specific keyword.control must outrank later broad keyword",
                0xFFFF3366, resolveKeywordColor(language, colorScheme));
            assertSame("Specificity resolution must retain the attached TextMateColorScheme",
                colorScheme, editor.getColorScheme());

            assertTrue("Returning to Yummy's base theme must succeed",
                registry.setTheme(baseRegistryName));
            assertEquals("Base keyword color must be restored after specificity check",
                0xFFC792EA, resolveKeywordColor(language, colorScheme));
        } finally {
            // ThemeRegistry is process-wide; never leak this test's active palette.
            registry.setTheme(baseRegistryName);
            activity.finish();
        }
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

    private static JSONObject tokenRule(String scope, String foreground) throws Exception {
        return new JSONObject()
            .put("scope", scope)
            .put("settings", new JSONObject().put("foreground", foreground));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int resolveKeywordColor(
        TextMateLanguage language,
        TextMateColorScheme colorScheme
    ) {
        IncrementalAnalyzeManager analyzer =
            (IncrementalAnalyzeManager) language.getAnalyzeManager();
        IncrementalAnalyzeManager.LineTokenizeResult result = analyzer.tokenizeLine(
            new ContentLine("if"), analyzer.getInitialState(), 0);
        List<Span> spans = result.spans;
        if (spans == null || spans.isEmpty()) {
            throw new AssertionError("TextMate did not produce spans for Java keyword 'if'");
        }
        for (Span span : spans) {
            if (span.getColumn() == 0) {
                int foregroundColorId = span.getForegroundColorId();
                assertTrue("Keyword must use a dynamic TextMate foreground color ID",
                    foregroundColorId >= 255);
                return colorScheme.getColor(foregroundColorId);
            }
            if (span.getColumn() > 0) break;
        }
        throw new AssertionError("TextMate did not produce a span at the keyword start");
    }
}
