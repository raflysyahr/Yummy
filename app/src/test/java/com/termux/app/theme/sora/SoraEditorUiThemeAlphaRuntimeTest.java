package com.termux.app.theme.sora;

import android.app.Activity;

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

import java.util.Collections;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Runtime regression tests for alpha-bearing VS Code workbench colors. */
@RunWith(RobolectricTestRunner.class)
public class SoraEditorUiThemeAlphaRuntimeTest {

    @Test
    public void alphaColorsSurviveApplyThemeSwitchAndRestore() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CodeEditor editor = new CodeEditor(activity);
        EditorColorScheme scheme = editor.getColorScheme();
        assertNotNull("Sora must initialize a color scheme", scheme);
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, 0x44556677);
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, 0x8899AABB);
        scheme.setColor(EditorColorScheme.KEYWORD, 0xFF102030);

        SoraEditorUiThemeController controller = new SoraEditorUiThemeController(editor);
        controller.apply(theme("alpha-editor-ui",
            "{\"name\":\"Alpha Editor UI\",\"type\":\"dark\",\"colors\":{"
                + "\"editor.background\":\"#12345678\","
                + "\"editor.foreground\":\"#A1B2C3D4\"}}"));

        assertEquals(0x78123456, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        assertEquals(0xD4A1B2C3, scheme.getColor(EditorColorScheme.TEXT_NORMAL));
        assertEquals(0xFF102030, scheme.getColor(EditorColorScheme.KEYWORD));

        controller.apply(theme("alpha-editor-ui-sparse",
            "{\"name\":\"Sparse\",\"type\":\"dark\","
                + "\"colors\":{\"editor.background\":\"#01020304\"}}"));
        assertEquals(0x04010203, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        assertEquals(0x8899AABB, scheme.getColor(EditorColorScheme.TEXT_NORMAL));
        assertEquals(0xFF102030, scheme.getColor(EditorColorScheme.KEYWORD));

        controller.clear();
        assertEquals(0x44556677, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        assertEquals(0x8899AABB, scheme.getColor(EditorColorScheme.TEXT_NORMAL));
        assertEquals(0xFF102030, scheme.getColor(EditorColorScheme.KEYWORD));
        activity.finish();
    }

    private static LoadedTheme theme(String id, String document) throws Exception {
        ThemeMetadata metadata = new ThemeMetadata(
            id, id, ThemeAppearance.DARK, ThemeSource.IMPORTED, "theme.json",
            null, null, null, null, EnumSet.of(ThemeTarget.EDITOR_UI),
            Collections.<ThemeTarget>emptySet());
        return new LoadedTheme(metadata,
            new com.termux.app.theme.model.VSCodeColorTheme(
                new JSONObject(document), id, ThemeAppearance.DARK));
    }
}
