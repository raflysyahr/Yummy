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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Verifies VS Code token fontStyle flags and explicit style reset through Sora TextMate spans. */
@RunWith(RobolectricTestRunner.class)
public class SoraTextMateFontStyleRuntimeTest {

    @Test
    public void importedFontStylesApplyAndNormalClearsInheritedStyle() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FileProviderRegistry.getInstance().addFileProvider(
            new AssetsFileResolver(activity.getAssets()));

        String baseThemeJson = new AssetThemeResourceReader(activity.getAssets())
            .readUtf8("textmate/yummy-dark.json");
        String baseRegistryName = "yummy-step46-base";
        IThemeSource baseSource = IThemeSource.fromInputStream(
            new ByteArrayInputStream(baseThemeJson.getBytes(StandardCharsets.UTF_8)),
            "yummy-step46-base.json", null);
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
        editor.setText("if\n// comment");
        editor.setEditorLanguage(language);

        try {
            Span baseKeyword = firstSpan(language, "if", "base keyword");
            Span baseComment = firstSpan(language, "// comment", "base comment");
            assertFalse("Base keyword must not unexpectedly be bold",
                TextStyle.isBold(baseKeyword.getStyle()));
            assertFalse("Base keyword must not unexpectedly be italic",
                TextStyle.isItalics(baseKeyword.getStyle()));
            assertTrue("Yummy base comment is expected to be italic",
                TextStyle.isItalics(baseComment.getStyle()));

            JSONArray rules = new JSONArray()
                .put(tokenRule("keyword.control", "#FF3366", "bold italic underline"))
                // Yummy's base comment rule is italic. Explicit normal must clear that style.
                .put(tokenRule("comment.line", "#6699FF", "normal"));
            ResolvedEditorTheme importedTheme = new ResolvedEditorTheme(
                "step46-font-style-theme",
                "Step 46 Font Style Theme",
                ThemeAppearance.DARK,
                rules,
                null,
                null,
                null
            );
            new SoraEditorThemeApplier().apply(editor, importedTheme, null, baseThemeJson);

            Span importedKeyword = firstSpan(language, "if", "imported keyword");
            assertTrue("bold must be retained from VS Code fontStyle",
                TextStyle.isBold(importedKeyword.getStyle()));
            assertTrue("italic must be retained from VS Code fontStyle",
                TextStyle.isItalics(importedKeyword.getStyle()));
            assertNotNull("underline must be represented on the TextMate span",
                importedKeyword.getUnderlineColor());
            assertEquals("Keyword foreground must remain independently theme-controlled",
                0xFFFF3366,
                colorScheme.getColor(importedKeyword.getForegroundColorId()));

            Span resetComment = firstSpan(language, "// comment", "reset comment");
            assertFalse("fontStyle normal must clear inherited italic style",
                TextStyle.isItalics(resetComment.getStyle()));
            assertFalse("fontStyle normal must not introduce bold style",
                TextStyle.isBold(resetComment.getStyle()));
            assertNull("fontStyle normal must clear inherited underline",
                resetComment.getUnderlineColor());
            assertEquals("Comment foreground must remain independently theme-controlled",
                0xFF6699FF,
                colorScheme.getColor(resetComment.getForegroundColorId()));

            assertTrue("Returning to Yummy's base theme must succeed",
                registry.setTheme(baseRegistryName));
            Span restoredKeyword = firstSpan(language, "if", "restored keyword");
            Span restoredComment = firstSpan(language, "// comment", "restored comment");
            assertFalse("Base keyword must be restored without bold",
                TextStyle.isBold(restoredKeyword.getStyle()));
            assertFalse("Base keyword must be restored without italic",
                TextStyle.isItalics(restoredKeyword.getStyle()));
            assertTrue("Base comment italic style must be restored",
                TextStyle.isItalics(restoredComment.getStyle()));
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
