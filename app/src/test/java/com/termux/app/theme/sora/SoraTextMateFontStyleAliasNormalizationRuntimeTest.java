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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Verifies VS Code fontStyle aliases are normalized before TextMate tokenization. */
@RunWith(RobolectricTestRunner.class)
public class SoraTextMateFontStyleAliasNormalizationRuntimeTest {

    @Test
    public void supportedAliasesNormalizeAndUnknownFlagsDoNotDiscardKnownStyles()
        throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FileProviderRegistry.getInstance().addFileProvider(
            new AssetsFileResolver(activity.getAssets()));

        String baseThemeJson = new AssetThemeResourceReader(activity.getAssets())
            .readUtf8("textmate/yummy-dark.json");
        String baseRegistryName = "yummy-step51-base";
        IThemeSource baseSource = IThemeSource.fromInputStream(
            new ByteArrayInputStream(baseThemeJson.getBytes(StandardCharsets.UTF_8)),
            "yummy-step51-base.json", null);
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
        editor.setText("if\n\"yummy\"\n// line comment\n/* block comment */\n42");
        editor.setEditorLanguage(language);

        SoraEditorThemeApplier applier = new SoraEditorThemeApplier();
        try {
            Span baseComment = firstSpan(language, "// line comment", "base line comment");
            Span baseBlockComment = firstSpan(language, "/* block comment */", "base block comment");
            assertTrue("Yummy base line comment is expected to be italic",
                TextStyle.isItalics(baseComment.getStyle()));
            assertTrue("Yummy base block comment is expected to be italic",
                TextStyle.isItalics(baseBlockComment.getStyle()));

            JSONArray rules = new JSONArray()
                .put(tokenRule("keyword.control", "#FF3366", "oblique"))
                .put(tokenRule("string.quoted.double", "#33AA66", "plain"))
                .put(tokenRule("comment.line", "#6699FF", "regular"))
                .put(tokenRule("comment.block", "#6699FF", "none"))
                .put(tokenRule("constant.numeric", "#9966CC", "bold unsupported-style"));
            ResolvedEditorTheme importedTheme = new ResolvedEditorTheme(
                "step51-font-style-alias-theme",
                "Step 51 Font Style Alias Theme",
                ThemeAppearance.DARK,
                rules,
                null,
                null,
                null
            );
            applier.apply(editor, importedTheme, null, baseThemeJson);

            Span keyword = firstSpan(language, "if", "oblique keyword");
            assertTrue("oblique must normalize to italic", TextStyle.isItalics(keyword.getStyle()));
            assertFalse("oblique must not introduce bold", TextStyle.isBold(keyword.getStyle()));

            Span string = firstSpan(language, "\"yummy\"", "plain string");
            assertFalse("plain must normalize to no italic decoration",
                TextStyle.isItalics(string.getStyle()));
            assertFalse("plain must normalize to no bold decoration",
                TextStyle.isBold(string.getStyle()));

            Span lineComment = firstSpan(language, "// line comment", "regular line comment");
            assertFalse("regular must clear inherited line-comment italic",
                TextStyle.isItalics(lineComment.getStyle()));
            assertFalse("regular must not introduce bold", TextStyle.isBold(lineComment.getStyle()));

            Span blockComment = firstSpan(language, "/* block comment */", "none block comment");
            assertFalse("none must clear inherited block-comment italic",
                TextStyle.isItalics(blockComment.getStyle()));
            assertFalse("none must not introduce bold", TextStyle.isBold(blockComment.getStyle()));

            Span number = firstSpan(language, "42", "known flag with unknown alias");
            assertTrue("A recognized bold flag must survive an unknown adjacent flag",
                TextStyle.isBold(number.getStyle()));
            assertFalse("Unknown style words must not be interpreted as italic",
                TextStyle.isItalics(number.getStyle()));

            assertTrue("Returning to Yummy base theme must succeed",
                registry.setTheme(baseRegistryName));
            Span restoredLineComment = firstSpan(language, "// line comment", "restored line comment");
            Span restoredBlockComment = firstSpan(language, "/* block comment */", "restored block comment");
            assertTrue("Base line-comment italic must be restored",
                TextStyle.isItalics(restoredLineComment.getStyle()));
            assertTrue("Base block-comment italic must be restored",
                TextStyle.isItalics(restoredBlockComment.getStyle()));
            assertSame("Alias normalization must preserve TextMateColorScheme identity",
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
