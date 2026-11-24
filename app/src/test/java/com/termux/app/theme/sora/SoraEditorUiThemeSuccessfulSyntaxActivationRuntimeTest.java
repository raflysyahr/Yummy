package com.termux.app.theme.sora;

import android.app.Activity;

import com.termux.app.theme.io.AssetThemeResourceReader;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ResolvedEditorTheme;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;

import org.eclipse.tm4e.core.registry.IThemeSource;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
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
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

import static org.junit.Assert.assertEquals;

/** Verifies that successful TextMate activation rebases, rather than displaces, Editor UI. */
@RunWith(RobolectricTestRunner.class)
public class SoraEditorUiThemeSuccessfulSyntaxActivationRuntimeTest {

    @Test
    public void successfulSyntaxActivationKeepsEditorUiOverlayAndRebasesLatestAppUi() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FileProviderRegistry.getInstance().addFileProvider(
            new AssetsFileResolver(activity.getAssets()));

        AssetThemeResourceReader assetReader = new AssetThemeResourceReader(activity.getAssets());
        String baseThemeJson = assetReader.readUtf8("textmate/yummy-dark.json");
        String baseRegistryName = "yummy-step42-base";
        IThemeSource baseSource = IThemeSource.fromInputStream(
            new ByteArrayInputStream(baseThemeJson.getBytes(StandardCharsets.UTF_8)),
            "yummy-step42-base.json", null);
        ThemeModel baseModel = new ThemeModel(baseSource, baseRegistryName);
        baseModel.setDark(true);
        ThemeRegistry registry = ThemeRegistry.getInstance();
        registry.loadTheme(baseModel);
        registry.setTheme(baseRegistryName);
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json");

        CodeEditor editor = new CodeEditor(activity);
        editor.setColorScheme(TextMateColorScheme.create(registry));
        // Match EditorController: TextMate color changes are exercised with a real grammar.
        String keywordLine = "if";
        TextMateLanguage textMateLanguage = TextMateLanguage.create("source.java", true);
        editor.setText(keywordLine);
        editor.setEditorLanguage(textMateLanguage);
        EditorColorScheme scheme = editor.getColorScheme();
        TextMateColorScheme textMateScheme = (TextMateColorScheme) scheme;
        assertEquals("Base TextMate keyword token must resolve from the base theme",
            0xFFC792EA, resolveKeywordTokenColor(textMateLanguage, textMateScheme, keywordLine));
        SoraEditorUiThemeController controller = new SoraEditorUiThemeController(editor);
        controller.apply(editorUiTheme("editor-ui-active",
            "{\"editor.background\":\"#AABBCC\",\"editor.foreground\":\"#DDEEFF\"}"));
        assertEquals(0xFFAABBCC, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        assertEquals(0xFFDDEEFF, scheme.getColor(EditorColorScheme.TEXT_NORMAL));

        // Match TermuxActivity.applyEditorSyntaxTheme(): remove the overlay before activation.
        controller.prepareForSyntaxThemeChange();
        ResolvedEditorTheme syntaxTheme = new ResolvedEditorTheme(
            "step42-successful-syntax", "Step 42 Syntax", ThemeAppearance.DARK,
            new JSONArray().put(new JSONObject()
                .put("scope", "keyword")
                .put("settings", new JSONObject().put("foreground", "#FF3366"))),
            null, null, null);
        new SoraEditorThemeApplier().apply(editor, syntaxTheme, null, baseThemeJson);

        // TextMate stores token foregrounds in dynamic Span color IDs (palette ID + 255).
        // EditorColorScheme.KEYWORD is a separate built-in fallback and is not the token color.
        assertEquals("Successful TextMate activation must update the keyword token color",
            0xFFFF3366, resolveKeywordTokenColor(textMateLanguage, textMateScheme, keywordLine));
        controller.captureSyntaxBaseColors();

        // The App UI base can change independently while syntax activation is in progress.
        int latestAppBackground = 0xFF405060;
        int latestAppForeground = 0xFF506070;
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, latestAppBackground);
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, latestAppForeground);
        controller.refreshBaseAndReapply();

        assertEquals("Editor UI background must remain the top overlay",
            0xFFAABBCC, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        assertEquals("Editor UI foreground must remain the top overlay",
            0xFFDDEEFF, scheme.getColor(EditorColorScheme.TEXT_NORMAL));
        assertEquals("Successful syntax activation must retain its new TextMate token color",
            0xFFFF3366, resolveKeywordTokenColor(textMateLanguage, textMateScheme, keywordLine));

        controller.apply(editorUiTheme("editor-ui-sparse", "{\"editor.background\":\"#ABCDEF\"}"));
        assertEquals(0xFFABCDEF, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        assertEquals(latestAppForeground, scheme.getColor(EditorColorScheme.TEXT_NORMAL));
        controller.clear();
        assertEquals(latestAppBackground, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        assertEquals(latestAppForeground, scheme.getColor(EditorColorScheme.TEXT_NORMAL));
        assertEquals("Clearing Editor UI must not roll back the newly activated TextMate syntax",
            0xFFFF3366, resolveKeywordTokenColor(textMateLanguage, textMateScheme, keywordLine));

        // Do not leave the process-wide TextMate registry on the test-only theme.
        registry.setTheme(baseRegistryName);
        activity.finish();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int resolveKeywordTokenColor(
        TextMateLanguage language,
        TextMateColorScheme colorScheme,
        String sourceLine
    ) {
        IncrementalAnalyzeManager analyzer =
            (IncrementalAnalyzeManager) language.getAnalyzeManager();
        IncrementalAnalyzeManager.LineTokenizeResult result = analyzer.tokenizeLine(
            new ContentLine(sourceLine), analyzer.getInitialState(), 0);
        List<Span> spans = result.spans;
        if (spans == null || spans.isEmpty()) {
            throw new AssertionError("TextMate did not produce spans for Java keyword 'if'");
        }
        Span keywordSpan = null;
        for (Span span : spans) {
            if (span.getColumn() <= 0) {
                keywordSpan = span;
            } else {
                break;
            }
        }
        if (keywordSpan == null || keywordSpan.getColumn() != 0) {
            throw new AssertionError("TextMate did not produce a span at the keyword start");
        }
        int foregroundColorId = keywordSpan.getForegroundColorId();
        if (foregroundColorId < 255) {
            throw new AssertionError(
                "Expected a TextMate palette color ID, got " + foregroundColorId);
        }
        return colorScheme.getColor(foregroundColorId);
    }

    private static LoadedTheme editorUiTheme(String id, String colors) throws Exception {
        JSONObject document = new JSONObject().put("name", id).put("type", "dark")
            .put("colors", new JSONObject(colors));
        ThemeMetadata metadata = new ThemeMetadata(
            id, id, ThemeAppearance.DARK, ThemeSource.IMPORTED, "theme.json",
            null, null, null, null, EnumSet.of(ThemeTarget.EDITOR_UI),
            Collections.<ThemeTarget>emptySet());
        return new LoadedTheme(metadata,
            new com.termux.app.theme.model.VSCodeColorTheme(
                document, id, ThemeAppearance.DARK));
    }
}
