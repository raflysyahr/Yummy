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

/** Regression tests for layered Sora surface colors during repeated target changes. */
@RunWith(RobolectricTestRunner.class)
public class SoraEditorUiThemeLifecycleTest {

    @Test
    public void appThemeRefreshDoesNotBecomeTheNextEditorThemeBase() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CodeEditor editor = new CodeEditor(activity);
        EditorColorScheme scheme = editor.getColorScheme();
        assertNotNull("Sora must initialize a color scheme", scheme);
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, 0xFF010203);
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, 0xFF111213);

        SoraEditorUiThemeController controller = new SoraEditorUiThemeController(editor);
        controller.apply(theme("editor-a",
            "{\"name\":\"Editor A\",\"type\":\"dark\",\"colors\":{"
                + "\"editor.background\":\"#AABBCC\"}}"));
        assertEquals(0xFFAABBCC, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));

        controller.prepareForAppThemeChange();
        assertEquals(0xFF010203, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        // Simulate App UI applying a new editor surface before its post-change callback.
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, 0xFF223344);
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, 0xFF334455);
        controller.refreshBaseAndReapply();
        assertEquals(0xFFAABBCC, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));

        // The new Editor UI theme does not define editor.background. It must expose the latest
        // App UI value rather than keeping the previous Editor UI theme's #AABBCC.
        controller.apply(theme("editor-b",
            "{\"name\":\"Editor B\",\"type\":\"dark\",\"colors\":{"
                + "\"editor.foreground\":\"#DDEEFF\"}}"));
        assertEquals(0xFF223344, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        assertEquals(0xFFDDEEFF, scheme.getColor(EditorColorScheme.TEXT_NORMAL));

        controller.clear();
        assertEquals(0xFF223344, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        assertEquals(0xFF334455, scheme.getColor(EditorColorScheme.TEXT_NORMAL));
        activity.finish();
    }

    @Test
    public void syntaxBaseRemainsIndependentFromAppAndEditorUiOverlays() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CodeEditor editor = new CodeEditor(activity);
        EditorColorScheme scheme = editor.getColorScheme();
        assertNotNull("Sora must initialize a color scheme", scheme);
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, 0xFF010203);

        SoraEditorUiThemeController controller = new SoraEditorUiThemeController(editor);
        controller.apply(theme("editor-a",
            "{\"name\":\"Editor A\",\"type\":\"dark\",\"colors\":{"
                + "\"editor.background\":\"#AABBCC\"}}"));

        controller.prepareForSyntaxThemeChange();
        assertEquals(0xFF010203, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        // Simulate a newly activated TextMate theme, then capture it before App UI is layered.
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, 0xFF445566);
        controller.captureSyntaxBaseColors();
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, 0xFF667788);
        controller.refreshBaseAndReapply();
        assertEquals(0xFFAABBCC, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));

        // Switching App UI to a theme without editor.background must remove #667788 and
        // return to the newly captured TextMate base before Editor UI is reapplied.
        controller.prepareForAppThemeChange();
        assertEquals(0xFF445566, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        controller.refreshBaseAndReapply();
        controller.apply(theme("editor-b",
            "{\"name\":\"Editor B\",\"type\":\"dark\",\"colors\":{"
                + "\"editor.foreground\":\"#DDEEFF\"}}"));
        assertEquals(0xFF445566, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));

        controller.clear();
        assertEquals(0xFF445566, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
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
