package com.termux.app.theme.sora;

import android.app.Activity;

import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ResolvedEditorTheme;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;

import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Verifies that a rejected syntax theme cannot displace the active Editor UI overlay. */
@RunWith(RobolectricTestRunner.class)
public class SoraEditorUiThemeFailedSyntaxActivationRuntimeTest {

    @Test
    public void failedSyntaxPreparationRestoresBaseAndKeepsEditorUiOverlay() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CodeEditor editor = new CodeEditor(activity);
        EditorColorScheme scheme = editor.getColorScheme();
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, 0xFF102030);
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, 0xFF203040);
        int originalKeyword = 0xFF304050;
        scheme.setColor(EditorColorScheme.KEYWORD, originalKeyword);

        SoraEditorUiThemeController controller = new SoraEditorUiThemeController(editor);
        controller.apply(editorUiTheme("editor-ui-active", "{\"editor.background\":\"#AABBCC\","
            + "\"editor.foreground\":\"#DDEEFF\"}"));
        assertEquals(0xFFAABBCC, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        assertEquals(0xFFDDEEFF, scheme.getColor(EditorColorScheme.TEXT_NORMAL));

        controller.prepareForSyntaxThemeChange();
        assertEquals(0xFF102030, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        assertEquals(0xFF203040, scheme.getColor(EditorColorScheme.TEXT_NORMAL));

        // The syntax applier rejects this document before ThemeRegistry activation because its
        // required base colors object is missing. This exercises the failed-activation path.
        try {
            new SoraEditorThemeApplier().apply(
                editor,
                new ResolvedEditorTheme("broken-syntax", "Broken Syntax",
                    ThemeAppearance.DARK, new JSONArray().put(new JSONObject()
                        .put("scope", "keyword")
                        .put("settings", new JSONObject().put("foreground", "#FF0000"))),
                    null, null, null),
                null,
                "{\"name\":\"invalid-base\"}"
            );
            fail("Invalid TextMate base document must be rejected");
        } catch (SoraEditorThemeException expected) {
            // Expected: no new syntax theme has been activated.
        } finally {
            // Mirrors TermuxActivity.applyEditorSyntaxTheme()'s failure branch.
            controller.restoreSyntaxBaseColors();
            assertEquals(0xFF102030, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
            assertEquals(0xFF203040, scheme.getColor(EditorColorScheme.TEXT_NORMAL));

            // AppThemeController reapplies the accepted App UI editor surface in the same finally.
            scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, 0xFF405060);
            scheme.setColor(EditorColorScheme.TEXT_NORMAL, 0xFF506070);
            controller.refreshBaseAndReapply();
        }

        assertEquals("Active Editor UI background must remain over the accepted App UI base",
            0xFFAABBCC, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        assertEquals("Active Editor UI foreground must remain over the accepted App UI base",
            0xFFDDEEFF, scheme.getColor(EditorColorScheme.TEXT_NORMAL));
        assertEquals("Failed syntax preparation must not alter TextMate keyword color",
            originalKeyword, scheme.getColor(EditorColorScheme.KEYWORD));

        // A sparse replacement and clear must reveal the newly accepted App UI base, not stale
        // values captured before the failed syntax activation.
        controller.apply(editorUiTheme("editor-ui-sparse", "{\"editor.background\":\"#ABCDEF\"}"));
        assertEquals(0xFFABCDEF, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        assertEquals(0xFF506070, scheme.getColor(EditorColorScheme.TEXT_NORMAL));
        controller.clear();
        assertEquals(0xFF405060, scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND));
        assertEquals(0xFF506070, scheme.getColor(EditorColorScheme.TEXT_NORMAL));
        assertEquals(originalKeyword, scheme.getColor(EditorColorScheme.KEYWORD));
        activity.finish();
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
