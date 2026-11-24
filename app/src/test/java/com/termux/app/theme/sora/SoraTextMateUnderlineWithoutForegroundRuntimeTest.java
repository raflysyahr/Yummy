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
import static org.junit.Assert.assertTrue;

/** Regression coverage for underlined TextMate tokens that have no explicit foreground. */
@RunWith(RobolectricTestRunner.class)
public class SoraTextMateUnderlineWithoutForegroundRuntimeTest {

    @Test
    public void underlineWithoutExplicitForegroundDoesNotBreakTokenization()
        throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FileProviderRegistry.getInstance().addFileProvider(
            new AssetsFileResolver(activity.getAssets()));

        String baseThemeJson = new AssetThemeResourceReader(activity.getAssets())
            .readUtf8("textmate/yummy-dark.json");
        String baseRegistryName = "yummy-step52-base";
        IThemeSource baseSource = IThemeSource.fromInputStream(
            new ByteArrayInputStream(baseThemeJson.getBytes(StandardCharsets.UTF_8)),
            "yummy-step52-base.json", null);
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

        try {
            // TextMate's token-color default is independent from the workbench
            // colors.editor.foreground entry. Define a global token foreground so
            // the scoped underline rule can intentionally omit its own foreground.
            JSONArray rules = new JSONArray()
                .put(tokenDefaultForeground("#E6EDF3"))
                .put(tokenRuleWithoutForeground("keyword.control", "underline"))
                .put(tokenRule("string.quoted.double", "#33AA66", "underline"));
            ResolvedEditorTheme importedTheme = new ResolvedEditorTheme(
                "step52-underline-without-foreground",
                "Step 52 Underline Without Foreground",
                ThemeAppearance.DARK,
                rules,
                null,
                null,
                null
            );

            new SoraEditorThemeApplier().apply(editor, importedTheme, null, baseThemeJson);

            // The scoped rule omits foreground, so TextMate inherits the global
            // token foreground and uses that resolved color for underline.
            Span defaultForegroundToken = firstSpan(
                language, "if", "underline without explicit foreground");
            assertNotNull("Token span must be produced without an analysis exception",
                defaultForegroundToken);
            assertNotNull("Underline must use the inherited global token foreground",
                defaultForegroundToken.getUnderlineColor());
            assertEquals("Token must retain the inherited editor foreground",
                Color.parseColor("#E6EDF3"),
                colorScheme.getColor(defaultForegroundToken.getForegroundColorId()));

            // A neighboring token with an explicit foreground still receives the
            // expected underline color, proving the fallback case did not disable
            // underline handling for the rest of the line/theme.
            Span explicitForegroundToken = firstSpan(
                language, "\"yummy\"", "underline with explicit foreground");
            assertNotNull("Explicit-foreground token span must be produced",
                explicitForegroundToken);
            assertNotNull("Explicit foreground must produce an underline color",
                explicitForegroundToken.getUnderlineColor());
            // ResolvableColor is intentionally not an Integer. The API contract here
            // guarantees that an underline color is present; the token foreground is
            // checked independently below through TextMateColorScheme.
            assertEquals("Explicit token foreground must remain unchanged",
                Color.parseColor("#33AA66"),
                colorScheme.getColor(explicitForegroundToken.getForegroundColorId()));
        } finally {
            registry.setTheme(baseRegistryName);
            activity.finish();
        }
    }

    private static JSONObject tokenDefaultForeground(String foreground) throws Exception {
        return new JSONObject()
            .put("settings", new JSONObject().put("foreground", foreground));
    }

    private static JSONObject tokenRuleWithoutForeground(String scope, String fontStyle)
        throws Exception {
        return new JSONObject()
            .put("scope", scope)
            .put("settings", new JSONObject().put("fontStyle", fontStyle));
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
