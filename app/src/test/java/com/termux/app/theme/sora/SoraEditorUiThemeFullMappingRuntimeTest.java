package com.termux.app.theme.sora;

import android.app.Activity;

import com.termux.app.theme.adapter.EditorUiThemeAdapter;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises every mapped Sora field through the real controller apply/restore path. */
@RunWith(RobolectricTestRunner.class)
public class SoraEditorUiThemeFullMappingRuntimeTest {

    @Test
    public void everyMappedSoraFieldAppliesAndRestoresWithoutTouchingSyntaxColors()
        throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CodeEditor editor = new CodeEditor(activity);
        EditorColorScheme scheme = editor.getColorScheme();
        EditorUiThemeAdapter adapter = new EditorUiThemeAdapter();

        Map<String, Integer> originalByField = new LinkedHashMap<>();
        Map<String, Integer> expectedByField = new LinkedHashMap<>();
        JSONObject colors = new JSONObject();
        int sourceIndex = 0;
        for (Map.Entry<String, java.util.List<String>> entry
            : adapter.getIdentifierMappings().entrySet()) {
            int color = 0xFF000000 | ((0x123456 + (++sourceIndex * 0x010203)) & 0x00FFFFFF);
            colors.put(entry.getKey(), String.format(java.util.Locale.US, "#%06X", color & 0x00FFFFFF));
            for (String fieldName : entry.getValue()) {
                assertFalse("Sora field must have one authoritative source: " + fieldName,
                    expectedByField.containsKey(fieldName));
                Field field = EditorColorScheme.class.getField(fieldName);
                int colorId = field.getInt(null);
                originalByField.put(fieldName, scheme.getColor(colorId));
                expectedByField.put(fieldName, color);
            }
        }
        assertTrue("The test must cover the complete non-empty mapping", !expectedByField.isEmpty());

        int syntaxColor = 0xFF13579B;
        scheme.setColor(EditorColorScheme.KEYWORD, syntaxColor);
        LoadedTheme theme = theme("full-mapping-runtime", colors);

        SoraEditorUiThemeController controller = new SoraEditorUiThemeController(editor);
        controller.apply(theme);
        for (Map.Entry<String, Integer> entry : expectedByField.entrySet()) {
            int colorId = EditorColorScheme.class.getField(entry.getKey()).getInt(null);
            assertEquals("Applied color mismatch for " + entry.getKey(),
                (int) entry.getValue(), scheme.getColor(colorId));
        }
        assertEquals("TextMate syntax color must remain untouched", syntaxColor,
            scheme.getColor(EditorColorScheme.KEYWORD));

        controller.clear();
        for (Map.Entry<String, Integer> entry : originalByField.entrySet()) {
            int colorId = EditorColorScheme.class.getField(entry.getKey()).getInt(null);
            assertEquals("Restored color mismatch for " + entry.getKey(),
                (int) entry.getValue(), scheme.getColor(colorId));
        }
        assertEquals("TextMate syntax color must remain untouched after restore", syntaxColor,
            scheme.getColor(EditorColorScheme.KEYWORD));
        activity.finish();
    }

    @Test
    public void switchingToSparseThemeRestoresEveryPreviouslyOverriddenField() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CodeEditor editor = new CodeEditor(activity);
        EditorColorScheme scheme = editor.getColorScheme();
        EditorUiThemeAdapter adapter = new EditorUiThemeAdapter();
        Map<String, Integer> originalByField = new LinkedHashMap<>();
        for (java.util.List<String> fields : adapter.getIdentifierMappings().values()) {
            for (String fieldName : fields) {
                int colorId = EditorColorScheme.class.getField(fieldName).getInt(null);
                int baseColor = 0xFF000000 | ((fieldName.hashCode() * 31) & 0x00FFFFFF);
                scheme.setColor(colorId, baseColor);
                originalByField.put(fieldName, baseColor);
            }
        }

        JSONObject populatedColors = new JSONObject().put("editor.background", "#AABBCC")
            .put("editor.foreground", "#DDEEFF")
            .put("editorHoverWidget.background", "#112233");
        SoraEditorUiThemeController controller = new SoraEditorUiThemeController(editor);
        controller.apply(theme("populated-editor-ui", populatedColors));
        assertEquals(0xFFAABBCC, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));

        controller.apply(theme("sparse-editor-ui", new JSONObject()));
        for (Map.Entry<String, Integer> entry : originalByField.entrySet()) {
            int colorId = EditorColorScheme.class.getField(entry.getKey()).getInt(null);
            assertEquals("Sparse theme must restore base field " + entry.getKey(),
                (int) entry.getValue(), scheme.getColor(colorId));
        }

        controller.clear();
        for (Map.Entry<String, Integer> entry : originalByField.entrySet()) {
            int colorId = EditorColorScheme.class.getField(entry.getKey()).getInt(null);
            assertEquals("Clear must preserve base field " + entry.getKey(),
                (int) entry.getValue(), scheme.getColor(colorId));
        }
        activity.finish();
    }

    private static LoadedTheme theme(String id, JSONObject colors) throws Exception {
        JSONObject document = new JSONObject().put("name", id).put("type", "dark")
            .put("colors", colors);
        ThemeMetadata metadata = new ThemeMetadata(
            id, id, ThemeAppearance.DARK, ThemeSource.IMPORTED, "theme.json",
            null, null, null, null, EnumSet.of(ThemeTarget.EDITOR_UI),
            Collections.<ThemeTarget>emptySet());
        return new LoadedTheme(metadata,
            new com.termux.app.theme.model.VSCodeColorTheme(
                document, id, ThemeAppearance.DARK));
    }

}
