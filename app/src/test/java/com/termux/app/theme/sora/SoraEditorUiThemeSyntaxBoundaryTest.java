package com.termux.app.theme.sora;

import android.app.Activity;

import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;
import com.termux.app.theme.model.VSCodeColorTheme;

import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Runtime guards for the boundary between Editor UI workbench colors and TextMate colors. */
@RunWith(RobolectricTestRunner.class)
public class SoraEditorUiThemeSyntaxBoundaryTest {

    private static final String[] TEXTMATE_FIELDS = {
        "ATTRIBUTE_VALUE",
        "ATTRIBUTE_NAME",
        "HTML_TAG",
        "ANNOTATION",
        "FUNCTION_NAME",
        "IDENTIFIER_NAME",
        "IDENTIFIER_VAR",
        "LITERAL",
        "OPERATOR",
        "COMMENT",
        "KEYWORD",
        "STATIC_SPAN_BACKGROUND",
        "STATIC_SPAN_FOREGROUND"
    };

    @Test
    public void editorUiApplyAndClearNeverChangeAnyTextMateOwnedField() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CodeEditor editor = new CodeEditor(activity);
        EditorColorScheme scheme = editor.getColorScheme();
        assertNotNull("Sora must initialize a color scheme", scheme);
        Map<String, Integer> syntaxBase = seedSyntaxFields(scheme, 0xFF102000);

        SoraEditorUiThemeController controller = new SoraEditorUiThemeController(editor);
        controller.apply(theme("dense-editor-ui",
            "{\"name\":\"Dense Editor UI\",\"type\":\"dark\",\"colors\":{"
                + "\"editor.background\":\"#AABBCC\","
                + "\"editor.foreground\":\"#DDEEFF\","
                + "\"editorSuggestWidget.background\":\"#112233\","
                + "\"editorHoverWidget.foreground\":\"#223344\","
                + "\"editorError.foreground\":\"#334455\","
                + "\"editorHint.foreground\":\"#445566\"}}"));

        assertSyntaxFields(scheme, syntaxBase);
        controller.clear();
        assertSyntaxFields(scheme, syntaxBase);
        activity.finish();
    }

    @Test
    public void syntaxRebaseAndSparseEditorUiSwitchPreserveAllCurrentTextMateFields()
        throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CodeEditor editor = new CodeEditor(activity);
        EditorColorScheme scheme = editor.getColorScheme();
        assertNotNull("Sora must initialize a color scheme", scheme);
        seedSyntaxFields(scheme, 0xFF201000);

        SoraEditorUiThemeController controller = new SoraEditorUiThemeController(editor);
        controller.apply(theme("editor-ui-a",
            "{\"name\":\"Editor UI A\",\"type\":\"dark\",\"colors\":{"
                + "\"editor.background\":\"#AABBCC\","
                + "\"editorSuggestWidget.background\":\"#112233\","
                + "\"editorHoverWidget.foreground\":\"#223344\"}}"));

        // Simulate a TextMate syntax-theme switch using the lifecycle hooks used by TermuxActivity.
        controller.prepareForSyntaxThemeChange();
        Map<String, Integer> updatedSyntaxBase = seedSyntaxFields(scheme, 0xFF302000);
        controller.captureSyntaxBaseColors();
        controller.refreshBaseAndReapply();
        assertSyntaxFields(scheme, updatedSyntaxBase);

        controller.apply(theme("editor-ui-sparse",
            "{\"name\":\"Sparse Editor UI\",\"type\":\"dark\","
                + "\"colors\":{\"editor.background\":\"#C0D0E0\"}}"));
        assertSyntaxFields(scheme, updatedSyntaxBase);

        controller.clear();
        assertSyntaxFields(scheme, updatedSyntaxBase);
        activity.finish();
    }

    private static Map<String, Integer> seedSyntaxFields(
        EditorColorScheme scheme, int firstColor) throws Exception {
        Map<String, Integer> values = new LinkedHashMap<>();
        int index = 0;
        for (String fieldName : TEXTMATE_FIELDS) {
            int color = firstColor + index++;
            scheme.setColor(colorId(fieldName), color);
            values.put(fieldName, color);
        }
        return values;
    }

    private static void assertSyntaxFields(
        EditorColorScheme scheme, Map<String, Integer> expected) throws Exception {
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            assertEquals("TextMate-owned Sora field must remain unchanged: " + entry.getKey(),
                entry.getValue().intValue(), scheme.getColor(colorId(entry.getKey())));
        }
    }

    private static int colorId(String fieldName) throws Exception {
        Field field = EditorColorScheme.class.getField(fieldName);
        return field.getInt(null);
    }

    private static LoadedTheme theme(String id, String json) throws Exception {
        ThemeMetadata metadata = new ThemeMetadata(
            id, id, ThemeAppearance.DARK, ThemeSource.IMPORTED, "theme.json",
            null, null, null, null, EnumSet.of(ThemeTarget.EDITOR_UI),
            Collections.<ThemeTarget>emptySet());
        return new LoadedTheme(metadata,
            new VSCodeColorTheme(new JSONObject(json), id, ThemeAppearance.DARK));
    }
}
