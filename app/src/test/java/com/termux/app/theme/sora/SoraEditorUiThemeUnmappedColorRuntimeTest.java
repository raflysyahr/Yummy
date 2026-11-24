package com.termux.app.theme.sora;

import android.app.Activity;

import com.termux.app.theme.adapter.EditorUiThemeAdapter;
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
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Runtime guard ensuring the Editor UI controller leaves unmapped Sora fields untouched. */
@RunWith(RobolectricTestRunner.class)
public class SoraEditorUiThemeUnmappedColorRuntimeTest {

    @Test
    public void unmappedSoraFieldsRemainUnchangedAcrossDenseSparseSwitchAndClear()
        throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CodeEditor editor = new CodeEditor(activity);
        EditorColorScheme scheme = editor.getColorScheme();
        EditorUiThemeAdapter adapter = new EditorUiThemeAdapter();
        Set<String> mappedFields = mappedFields(adapter.getIdentifierMappings());
        Map<Integer, Integer> expectedUnmappedColors = new HashMap<>();
        Set<String> unmappedFieldNames = new HashSet<>();

        int seedIndex = 0;
        for (Field field : EditorColorScheme.class.getFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != int.class
                || !Modifier.isPublic(modifiers)
                || !Modifier.isStatic(modifiers)
                || !Modifier.isFinal(modifiers)
                || mappedFields.contains(field.getName())) {
                continue;
            }

            unmappedFieldNames.add(field.getName());
            int colorId = field.getInt(null);
            if (expectedUnmappedColors.containsKey(colorId)) {
                continue;
            }
            int color = 0xFF000000 | ((0x214365 + (++seedIndex * 0x010307)) & 0x00FFFFFF);
            scheme.setColor(colorId, color);
            expectedUnmappedColors.put(colorId, color);
        }

        assertFalse("The test must cover unmapped Sora color IDs",
            expectedUnmappedColors.isEmpty());
        assertTrue("The test must include TextMate-owned fields",
            unmappedFieldNames.contains("KEYWORD"));
        assertTrue("The test must include Sora fields without a workbench mapping",
            unmappedFieldNames.contains("LINE_NUMBER_PANEL"));

        SoraEditorUiThemeController controller = new SoraEditorUiThemeController(editor);
        controller.apply(theme("dense-unmapped-preservation", denseColors(adapter)));
        assertUnmappedColors(scheme, expectedUnmappedColors);

        controller.apply(theme("sparse-unmapped-preservation", new JSONObject()
            .put("editor.background", "#AABBCC")));
        assertUnmappedColors(scheme, expectedUnmappedColors);

        controller.clear();
        assertUnmappedColors(scheme, expectedUnmappedColors);
        activity.finish();
    }

    private static void assertUnmappedColors(
        EditorColorScheme scheme, Map<Integer, Integer> expected) {
        for (Map.Entry<Integer, Integer> entry : expected.entrySet()) {
            assertEquals("Unmapped Sora color ID must remain unchanged: " + entry.getKey(),
                entry.getValue().intValue(), scheme.getColor(entry.getKey()));
        }
    }

    private static Set<String> mappedFields(Map<String, List<String>> mappings) {
        Set<String> result = new HashSet<>();
        for (List<String> fields : mappings.values()) result.addAll(fields);
        return result;
    }

    private static JSONObject denseColors(EditorUiThemeAdapter adapter) throws Exception {
        JSONObject colors = new JSONObject();
        int index = 0;
        for (String identifier : adapter.getIdentifierMappings().keySet()) {
            int color = 0xFF000000 | ((0x654321 + (++index * 0x010203)) & 0x00FFFFFF);
            colors.put(identifier, String.format(java.util.Locale.US, "#%06X",
                color & 0x00FFFFFF));
        }
        return colors;
    }

    private static LoadedTheme theme(String id, JSONObject colors) throws Exception {
        JSONObject document = new JSONObject().put("name", id).put("type", "dark")
            .put("colors", colors);
        ThemeMetadata metadata = new ThemeMetadata(
            id, id, ThemeAppearance.DARK, ThemeSource.IMPORTED, "theme.json",
            null, null, null, null, EnumSet.of(ThemeTarget.EDITOR_UI),
            Collections.<ThemeTarget>emptySet());
        return new LoadedTheme(metadata,
            new VSCodeColorTheme(document, id, ThemeAppearance.DARK));
    }
}
