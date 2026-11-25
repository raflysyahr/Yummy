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

/** Verifies underline is applied to every scope in a TextMate selector array, without bleed. */
@RunWith(RobolectricTestRunner.class)
public class SoraTextMateUnderlineMultiScopeSelectorRuntimeTest {

    @Test
    public void underlineArraySelectorAppliesToEachListedScopeOnly() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FileProviderRegistry.getInstance().addFileProvider(
            new AssetsFileResolver(activity.getAssets()));

        String baseThemeJson = new AssetThemeResourceReader(activity.getAssets())
            .readUtf8("textmate/yummy-dark.json");
        String baseRegistryName = "yummy-step56-base";
        IThemeSource baseSource = IThemeSource.fromInputStream(
            new ByteArrayInputStream(baseThemeJson.getBytes(StandardCharsets.UTF_8)),
            "yummy-step56-base.json", null);
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
        editor.setText("if\n\"double\"\n'single'\n// comment");
        editor.setEditorLanguage(language);

        try {
            Span baseDoubleString = firstSpan(language, "\"double\"", "base double string");
            Span baseSingleString = firstSpan(language, "'single'", "base single string");
            assertNull("Base double-quoted string must begin without underline",
                baseDoubleString.getUnderlineColor());
            assertNull("Base single-quoted string must begin without underline",
                baseSingleString.getUnderlineColor());

            JSONArray stringScopes = new JSONArray()
                .put("string.quoted.double")
                .put("string.quoted.single");
            JSONArray rules = new JSONArray()
                .put(tokenRule("keyword.control", "#FF3366", "normal"))
                .put(tokenRule(stringScopes, "#33CC99", "underline"))
                .put(tokenRuleWithoutFontStyle("comment.line", "#6699FF"));
            ResolvedEditorTheme importedTheme = new ResolvedEditorTheme(
                "step56-underline-multi-scope-selector",
                "Step 56 Underline Multi-Scope Selector",
                ThemeAppearance.DARK,
                rules,
                null,
                null,
                null
            );
            new SoraEditorThemeApplier().apply(editor, importedTheme, null, baseThemeJson);

            Span keyword = firstSpan(language, "if", "keyword");
            assertNull("Keyword normal must remain without underline",
                keyword.getUnderlineColor());
            assertEquals("Keyword foreground must remain independently resolved",
                Color.parseColor("#FF3366"),
                colorScheme.getColor(keyword.getForegroundColorId()));

            Span doubleString = firstSpan(language, "\"double\"", "double-quoted string");
            assertNotNull("First scope in selector array must receive underline",
                doubleString.getUnderlineColor());
            assertFalse("Underline must not add bold to double-quoted string",
                TextStyle.isBold(doubleString.getStyle()));
            assertFalse("Underline must not add italic to double-quoted string",
                TextStyle.isItalics(doubleString.getStyle()));
            assertEquals("Double-quoted string foreground must be correct",
                Color.parseColor("#33CC99"),
                colorScheme.getColor(doubleString.getForegroundColorId()));

            Span singleString = firstSpan(language, "'single'", "single-quoted string");
            assertNotNull("Second scope in selector array must receive underline",
                singleString.getUnderlineColor());
            assertEquals("Single-quoted string foreground must be correct",
                Color.parseColor("#33CC99"),
                colorScheme.getColor(singleString.getForegroundColorId()));

            Span comment = firstSpan(language, "// comment", "line comment");
            assertNull("Unlisted comment scope must not inherit array-selector underline",
                comment.getUnderlineColor());
            assertEquals("Comment foreground must remain independently resolved",
                Color.parseColor("#6699FF"),
                colorScheme.getColor(comment.getForegroundColorId()));
            assertSame("Applying array-selector underline must preserve TextMateColorScheme identity",
                colorScheme, editor.getColorScheme());

            assertTrue("Returning to Yummy base theme must succeed",
                registry.setTheme(baseRegistryName));
            Span restoredDoubleString = firstSpan(language, "\"double\"", "restored double string");
            Span restoredSingleString = firstSpan(language, "'single'", "restored single string");
            assertNull("Base double-quoted string must not retain imported underline",
                restoredDoubleString.getUnderlineColor());
            assertNull("Base single-quoted string must not retain imported underline",
                restoredSingleString.getUnderlineColor());
            assertSame("Base restoration must preserve TextMateColorScheme identity",
                colorScheme, editor.getColorScheme());
        } finally {
            registry.setTheme(baseRegistryName);
            activity.finish();
        }
    }

    private static JSONObject tokenRule(Object scope, String foreground, String fontStyle)
        throws Exception {
        return new JSONObject()
            .put("scope", scope)
            .put("settings", new JSONObject()
                .put("foreground", foreground)
                .put("fontStyle", fontStyle));
    }

    private static JSONObject tokenRuleWithoutFontStyle(String scope, String foreground)
        throws Exception {
        return new JSONObject()
            .put("scope", scope)
            .put("settings", new JSONObject().put("foreground", foreground));
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
