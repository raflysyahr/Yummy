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
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Validates the complete Editor UI overlay across TextMate and App UI palette changes. */
@RunWith(RobolectricTestRunner.class)
public class SoraEditorUiThemeLayeredLifecycleRuntimeTest {

    @Test
    public void everyMappedFieldRebasesAcrossSyntaxAndAppThemeChanges() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CodeEditor editor = new CodeEditor(activity);
        EditorColorScheme scheme = editor.getColorScheme();
        EditorUiThemeAdapter adapter = new EditorUiThemeAdapter();

        Map<Integer, Integer> originalBase = new LinkedHashMap<>();
        Map<Integer, Integer> syntaxBase = new LinkedHashMap<>();
        Map<Integer, Integer> appUiBase = new LinkedHashMap<>();
        JSONObject denseColors = new JSONObject();
        int identifierIndex = 0;
        int fieldIndex = 0;

        for (Map.Entry<String, List<String>> mapping
            : adapter.getIdentifierMappings().entrySet()) {
            int editorUiColor = color(0x102030, ++identifierIndex);
            denseColors.put(mapping.getKey(), hex(editorUiColor));
            for (String fieldName : mapping.getValue()) {
                Field field = EditorColorScheme.class.getField(fieldName);
                int colorId = field.getInt(null);
                if (!originalBase.containsKey(colorId)) {
                    int initial = color(0x203040, ++fieldIndex);
                    originalBase.put(colorId, initial);
                    scheme.setColor(colorId, initial);
                    syntaxBase.put(colorId, color(0x304050, fieldIndex));
                    appUiBase.put(colorId, color(0x405060, fieldIndex));
                }
            }
        }

        assertFalse("The test must cover mapped Sora color IDs", originalBase.isEmpty());
        int originalKeyword = 0xFF010203;
        int syntaxKeyword = 0xFF112233;
        int unmappedPanel = 0xFF223344;
        scheme.setColor(EditorColorScheme.KEYWORD, originalKeyword);
        scheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL, unmappedPanel);

        SoraEditorUiThemeController controller = new SoraEditorUiThemeController(editor);
        controller.apply(theme("layered-dense", denseColors));
        assertMappedColors(scheme, adapter, denseColors);
        assertEquals(originalKeyword, scheme.getColor(EditorColorScheme.KEYWORD));
        assertEquals(unmappedPanel, scheme.getColor(EditorColorScheme.LINE_NUMBER_PANEL));

        // Activate a different TextMate syntax theme after removing the Editor UI overlay.
        controller.prepareForSyntaxThemeChange();
        assertColors(scheme, originalBase);
        for (Map.Entry<Integer, Integer> entry : syntaxBase.entrySet()) {
            scheme.setColor(entry.getKey(), entry.getValue());
        }
        scheme.setColor(EditorColorScheme.KEYWORD, syntaxKeyword);
        controller.captureSyntaxBaseColors();
        controller.refreshBaseAndReapply();
        assertMappedColors(scheme, adapter, denseColors);
        assertEquals("The newly activated TextMate syntax color must survive", syntaxKeyword,
            scheme.getColor(EditorColorScheme.KEYWORD));

        // App UI changes must start from the new syntax base, not the old Editor UI overlay.
        controller.prepareForAppThemeChange();
        assertColors(scheme, syntaxBase);
        for (Map.Entry<Integer, Integer> entry : appUiBase.entrySet()) {
            scheme.setColor(entry.getKey(), entry.getValue());
        }
        controller.refreshBaseAndReapply();
        assertMappedColors(scheme, adapter, denseColors);
        assertEquals(syntaxKeyword, scheme.getColor(EditorColorScheme.KEYWORD));

        // A sparse Editor UI theme must expose the latest App UI values for omitted fields.
        controller.apply(theme("layered-sparse", new JSONObject()
            .put("editor.background", "#AABBCC")));
        assertEquals(0xFFAABBCC, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        for (Map.Entry<String, List<String>> mapping
            : adapter.getIdentifierMappings().entrySet()) {
            if ("editor.background".equals(mapping.getKey())) continue;
            for (String fieldName : mapping.getValue()) {
                int colorId = EditorColorScheme.class.getField(fieldName).getInt(null);
                assertEquals("Sparse switch must restore latest App UI base: " + fieldName,
                    appUiBase.get(colorId).intValue(), scheme.getColor(colorId));
            }
        }

        controller.clear();
        assertColors(scheme, appUiBase);
        assertEquals("TextMate syntax color must remain independent", syntaxKeyword,
            scheme.getColor(EditorColorScheme.KEYWORD));
        assertEquals("Unmapped Sora field must remain untouched", unmappedPanel,
            scheme.getColor(EditorColorScheme.LINE_NUMBER_PANEL));
        activity.finish();
    }

    private static void assertMappedColors(EditorColorScheme scheme,
                                          EditorUiThemeAdapter adapter,
                                          JSONObject sourceColors) throws Exception {
        for (Map.Entry<String, List<String>> mapping
            : adapter.getIdentifierMappings().entrySet()) {
            int expected = parseHex(sourceColors.getString(mapping.getKey()));
            for (String fieldName : mapping.getValue()) {
                int colorId = EditorColorScheme.class.getField(fieldName).getInt(null);
                assertEquals("Editor UI overlay mismatch: " + mapping.getKey()
                    + " -> " + fieldName, expected, scheme.getColor(colorId));
            }
        }
    }

    private static void assertColors(EditorColorScheme scheme, Map<Integer, Integer> expected) {
        for (Map.Entry<Integer, Integer> entry : expected.entrySet()) {
            assertEquals("Base color mismatch for Sora color ID " + entry.getKey(),
                entry.getValue().intValue(), scheme.getColor(entry.getKey()));
        }
    }

    private static int color(int rgb, int index) {
        return 0xFF000000 | ((rgb + index * 0x010307) & 0x00FFFFFF);
    }

    private static String hex(int color) {
        return String.format(java.util.Locale.US, "#%06X", color & 0x00FFFFFF);
    }

    private static int parseHex(String value) {
        return 0xFF000000 | Integer.parseInt(value.substring(1), 16);
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
