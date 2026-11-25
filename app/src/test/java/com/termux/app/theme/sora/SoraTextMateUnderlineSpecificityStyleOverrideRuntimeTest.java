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

/** Verifies a specific non-underline fontStyle replaces a broader underline rule. */
@RunWith(RobolectricTestRunner.class)
public class SoraTextMateUnderlineSpecificityStyleOverrideRuntimeTest {

    @Test
    public void specificBoldItalicStyleReplacesBroadUnderlineWithoutAffectingSiblingKeyword()
        throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FileProviderRegistry.getInstance().addFileProvider(
            new AssetsFileResolver(activity.getAssets()));

        String baseThemeJson = new AssetThemeResourceReader(activity.getAssets())
            .readUtf8("textmate/yummy-dark.json");
        String baseRegistryName = "yummy-step58-base";
        IThemeSource baseSource = IThemeSource.fromInputStream(
            new ByteArrayInputStream(baseThemeJson.getBytes(StandardCharsets.UTF_8)),
            "yummy-step58-base.json", null);
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
        editor.setText("if\ninstanceof");
        editor.setEditorLanguage(language);

        try {
            JSONArray rules = new JSONArray()
                .put(tokenRule("keyword", "#33AA66", "underline"))
                .put(tokenRule("keyword.control", "#FF3366", "bold italic"));
            ResolvedEditorTheme importedTheme = new ResolvedEditorTheme(
                "step58-specific-style-replaces-underline",
                "Step 58 Specific Style Replaces Underline",
                ThemeAppearance.DARK,
                rules,
                null,
                null,
                null
            );
            new SoraEditorThemeApplier().apply(editor, importedTheme, null, baseThemeJson);

            Span specificControl = firstSpan(language, "if", "specific keyword.control");
            assertNull("Specific bold italic rule must replace broad underline",
                specificControl.getUnderlineColor());
            assertTrue("Specific rule must retain requested bold style",
                TextStyle.isBold(specificControl.getStyle()));
            assertTrue("Specific rule must retain requested italic style",
                TextStyle.isItalics(specificControl.getStyle()));
            assertEquals("Specific rule foreground must remain correct", Color.parseColor("#FF3366"),
                colorScheme.getColor(specificControl.getForegroundColorId()));

            Span broadKeyword = firstSpan(language, "instanceof", "broad keyword operator");
            assertNotNull("Sibling keyword scope must retain broad underline",
                broadKeyword.getUnderlineColor());
            assertFalse("Broad underline must not add bold", TextStyle.isBold(broadKeyword.getStyle()));
            assertFalse("Broad underline must not add italic", TextStyle.isItalics(broadKeyword.getStyle()));
            assertEquals("Broad rule foreground must remain correct", Color.parseColor("#33AA66"),
                colorScheme.getColor(broadKeyword.getForegroundColorId()));
            assertSame("Applying the specific style override must preserve TextMateColorScheme identity",
                colorScheme, editor.getColorScheme());

            assertTrue("Returning to Yummy base theme must succeed",
                registry.setTheme(baseRegistryName));
            Span restoredControl = firstSpan(language, "if", "restored keyword.control");
            assertNull("Base keyword must not retain imported underline",
                restoredControl.getUnderlineColor());
            assertFalse("Base keyword must not retain imported bold",
                TextStyle.isBold(restoredControl.getStyle()));
            assertFalse("Base keyword must not retain imported italic",
                TextStyle.isItalics(restoredControl.getStyle()));
            assertEquals("Base keyword foreground must be restored", 0xFFC792EA,
                colorScheme.getColor(restoredControl.getForegroundColorId()));
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
