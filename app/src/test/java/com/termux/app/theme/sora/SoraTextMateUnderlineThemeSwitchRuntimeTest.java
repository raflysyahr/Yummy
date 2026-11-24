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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Verifies underline decorations are replaced and restored across syntax-theme switches. */
@RunWith(RobolectricTestRunner.class)
public class SoraTextMateUnderlineThemeSwitchRuntimeTest {

    @Test
    public void underlineDecorationsAreReplacedAcrossThemeSwitchesAndBaseRestoration()
        throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FileProviderRegistry.getInstance().addFileProvider(
            new AssetsFileResolver(activity.getAssets()));

        String baseThemeJson = new AssetThemeResourceReader(activity.getAssets())
            .readUtf8("textmate/yummy-dark.json");
        String baseRegistryName = "yummy-step54-base";
        IThemeSource baseSource = IThemeSource.fromInputStream(
            new ByteArrayInputStream(baseThemeJson.getBytes(StandardCharsets.UTF_8)),
            "yummy-step54-base.json", null);
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
        editor.setText("if\n\"yummy\"");
        editor.setEditorLanguage(language);

        SoraEditorThemeApplier applier = new SoraEditorThemeApplier();
        try {
            Span baseKeyword = firstSpan(language, "if", "base keyword");
            Span baseString = firstSpan(language, "\"yummy\"", "base string");
            assertNull("Yummy base keyword must begin without underline",
                baseKeyword.getUnderlineColor());
            assertNull("Yummy base string must begin without underline",
                baseString.getUnderlineColor());
            assertEquals("Base keyword foreground must be intact", 0xFFC792EA,
                colorScheme.getColor(baseKeyword.getForegroundColorId()));
            assertEquals("Base string foreground must be intact", 0xFFA5D6A7,
                colorScheme.getColor(baseString.getForegroundColorId()));

            ResolvedEditorTheme themeA = importedTheme(
                "step54-theme-a",
                "Step 54 Theme A",
                new JSONArray()
                    .put(tokenRule("keyword.control", "#FF3366", "underline"))
                    .put(tokenRule("string.quoted.double", "#33AA66", "normal"))
            );
            applier.apply(editor, themeA, null, baseThemeJson);

            Span themeAKeyword = firstSpan(language, "if", "Theme A keyword");
            Span themeAString = firstSpan(language, "\"yummy\"", "Theme A string");
            assertNotNull("Theme A keyword must receive underline",
                themeAKeyword.getUnderlineColor());
            assertNull("Theme A string normal must not receive underline",
                themeAString.getUnderlineColor());
            assertEquals("Theme A keyword foreground must be active", 0xFFFF3366,
                colorScheme.getColor(themeAKeyword.getForegroundColorId()));
            assertEquals("Theme A string foreground must be active", 0xFF33AA66,
                colorScheme.getColor(themeAString.getForegroundColorId()));

            ResolvedEditorTheme themeB = importedTheme(
                "step54-theme-b",
                "Step 54 Theme B",
                new JSONArray()
                    .put(tokenRule("keyword.control", "#FF8844", "normal"))
                    .put(tokenRule("string.quoted.double", "#55AA88", "underline"))
            );
            applier.apply(editor, themeB, null, baseThemeJson);

            Span themeBKeyword = firstSpan(language, "if", "Theme B keyword");
            Span themeBString = firstSpan(language, "\"yummy\"", "Theme B string");
            assertNull("Theme B normal must remove Theme A keyword underline",
                themeBKeyword.getUnderlineColor());
            assertNotNull("Theme B string must receive underline",
                themeBString.getUnderlineColor());
            assertEquals("Theme B keyword foreground must be active", 0xFFFF8844,
                colorScheme.getColor(themeBKeyword.getForegroundColorId()));
            assertEquals("Theme B string foreground must be active", 0xFF55AA88,
                colorScheme.getColor(themeBString.getForegroundColorId()));

            applier.apply(editor, themeA, null, baseThemeJson);
            Span themeAAgainKeyword = firstSpan(language, "if", "Theme A reactivated keyword");
            Span themeAAgainString = firstSpan(language, "\"yummy\"", "Theme A reactivated string");
            assertNotNull("Reactivated Theme A must restore keyword underline",
                themeAAgainKeyword.getUnderlineColor());
            assertNull("Reactivated Theme A must keep string without underline",
                themeAAgainString.getUnderlineColor());
            assertEquals("Reactivated Theme A keyword foreground must be restored", 0xFFFF3366,
                colorScheme.getColor(themeAAgainKeyword.getForegroundColorId()));

            assertTrue("Returning to Yummy base theme must succeed",
                registry.setTheme(baseRegistryName));
            Span restoredKeyword = firstSpan(language, "if", "restored base keyword");
            Span restoredString = firstSpan(language, "\"yummy\"", "restored base string");
            assertNull("Base keyword must not retain imported underline",
                restoredKeyword.getUnderlineColor());
            assertNull("Base string must not retain imported underline",
                restoredString.getUnderlineColor());
            assertEquals("Base keyword foreground must be restored", 0xFFC792EA,
                colorScheme.getColor(restoredKeyword.getForegroundColorId()));
            assertEquals("Base string foreground must be restored", 0xFFA5D6A7,
                colorScheme.getColor(restoredString.getForegroundColorId()));
            assertSame("Theme switches must preserve TextMateColorScheme identity",
                colorScheme, editor.getColorScheme());
        } finally {
            registry.setTheme(baseRegistryName);
            activity.finish();
        }
    }

    private static ResolvedEditorTheme importedTheme(String id, String name, JSONArray rules) {
        return new ResolvedEditorTheme(
            id, name, ThemeAppearance.DARK, rules, null, null, null);
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
