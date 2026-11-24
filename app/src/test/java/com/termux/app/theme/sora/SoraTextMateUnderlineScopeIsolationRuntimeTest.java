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

/** Ensures underline decorations stay isolated to the TextMate scopes that declare them. */
@RunWith(RobolectricTestRunner.class)
public class SoraTextMateUnderlineScopeIsolationRuntimeTest {

    @Test
    public void underlineRemainsIsolatedAcrossKeywordStringAndCommentScopes() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FileProviderRegistry.getInstance().addFileProvider(
            new AssetsFileResolver(activity.getAssets()));

        String baseThemeJson = new AssetThemeResourceReader(activity.getAssets())
            .readUtf8("textmate/yummy-dark.json");
        String baseRegistryName = "yummy-step55-base";
        IThemeSource baseSource = IThemeSource.fromInputStream(
            new ByteArrayInputStream(baseThemeJson.getBytes(StandardCharsets.UTF_8)),
            "yummy-step55-base.json", null);
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
            Span baseComment = firstSpan(language, "// comment", "base comment");
            assertTrue("Yummy base comment must begin italic",
                TextStyle.isItalics(baseComment.getStyle()));
            assertNull("Yummy base comment must begin without underline",
                baseComment.getUnderlineColor());

            // Only keyword.control declares underline. The string explicitly clears
            // decorations with normal, while the comment rule deliberately omits
            // fontStyle altogether. Neither sibling scope may inherit keyword underline.
            JSONArray rules = new JSONArray()
                .put(tokenRule("keyword.control", "#FF3366", "underline"))
                .put(tokenRule("string.quoted.double", "#33AA66", "normal"))
                .put(tokenRuleWithoutFontStyle("comment.line", "#6699FF"));
            ResolvedEditorTheme importedTheme = new ResolvedEditorTheme(
                "step55-underline-scope-isolation",
                "Step 55 Underline Scope Isolation",
                ThemeAppearance.DARK,
                rules,
                null,
                null,
                null
            );
            new SoraEditorThemeApplier().apply(editor, importedTheme, null, baseThemeJson);

            Span keyword = firstSpan(language, "if", "keyword");
            assertNotNull("Keyword scope must receive its declared underline",
                keyword.getUnderlineColor());
            assertFalse("Keyword underline must not add bold", TextStyle.isBold(keyword.getStyle()));
            assertFalse("Keyword underline must not add italic",
                TextStyle.isItalics(keyword.getStyle()));
            assertEquals("Keyword foreground must remain independently resolved",
                Color.parseColor("#FF3366"),
                colorScheme.getColor(keyword.getForegroundColorId()));

            Span string = firstSpan(language, "\"yummy\"", "string");
            assertNull("String normal must not inherit keyword underline",
                string.getUnderlineColor());
            assertFalse("String must not inherit keyword bold", TextStyle.isBold(string.getStyle()));
            assertFalse("String normal must clear italic decoration",
                TextStyle.isItalics(string.getStyle()));
            assertEquals("String foreground must remain independently resolved",
                Color.parseColor("#33AA66"),
                colorScheme.getColor(string.getForegroundColorId()));

            Span comment = firstSpan(language, "// comment", "comment");
            assertNull("Comment without fontStyle must not inherit keyword underline",
                comment.getUnderlineColor());
            assertFalse("Comment must not inherit keyword bold", TextStyle.isBold(comment.getStyle()));
            assertEquals("Comment foreground must remain independently resolved",
                Color.parseColor("#6699FF"),
                colorScheme.getColor(comment.getForegroundColorId()));
            assertSame("Scope isolation must preserve TextMateColorScheme identity",
                colorScheme, editor.getColorScheme());

            assertTrue("Returning to Yummy base theme must succeed",
                registry.setTheme(baseRegistryName));
            Span restoredComment = firstSpan(language, "// comment", "restored base comment");
            assertTrue("Base comment italic must be restored",
                TextStyle.isItalics(restoredComment.getStyle()));
            assertNull("Base comment must remain without imported underline",
                restoredComment.getUnderlineColor());
            assertSame("Base restoration must preserve TextMateColorScheme identity",
                colorScheme, editor.getColorScheme());
        } finally {
            registry.setTheme(baseRegistryName);
            activity.finish();
        }
    }

    private static JSONObject tokenRule(String scope, String foreground, String fontStyle)
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
