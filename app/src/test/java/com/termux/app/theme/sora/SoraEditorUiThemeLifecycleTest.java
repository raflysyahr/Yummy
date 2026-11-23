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
import java.lang.reflect.Modifier;
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
    public void everyEditorUiMappingTargetsAFieldInPinnedSoraVersion() throws Exception {
        for (java.util.List<String> fieldNames
            : new EditorUiThemeAdapter().getIdentifierMappings().values()) {
            for (String fieldName : fieldNames) {
                Field field = EditorColorScheme.class.getField(fieldName);
                assertEquals("Sora color field must be an int: " + fieldName,
                    int.class, field.getType());
                assertEquals("Sora color field must be static: " + fieldName,
                    true, Modifier.isStatic(field.getModifiers()));
            }
        }
    }

    @Test
    public void editorWidgetColorsApplyAndRestoreWithoutChangingSyntaxColors() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CodeEditor editor = new CodeEditor(activity);
        EditorColorScheme scheme = editor.getColorScheme();
        assertNotNull("Sora must initialize a color scheme", scheme);
        scheme.setColor(EditorColorScheme.KEYWORD, 0xFF010203);
        scheme.setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, 0xFF111213);
        scheme.setColor(EditorColorScheme.HOVER_BACKGROUND, 0xFF212223);
        scheme.setColor(EditorColorScheme.PROBLEM_ERROR, 0xFF313233);
        scheme.setColor(EditorColorScheme.TEXT_INLAY_HINT_FOREGROUND, 0xFF414243);

        SoraEditorUiThemeController controller = new SoraEditorUiThemeController(editor);
        controller.apply(theme("editor-widgets",
            "{\"name\":\"Editor Widgets\",\"type\":\"dark\",\"colors\":{"
                + "\"editorSuggestWidget.background\":\"#AABBCC\","
                + "\"editorHoverWidget.background\":\"#DDEEFF\","
                + "\"editorError.foreground\":\"#112233\","
                + "\"editorInlayHint.foreground\":\"#445566\"}}"));

        assertEquals(0xFFAABBCC, scheme.getColor(EditorColorScheme.COMPLETION_WND_BACKGROUND));
        assertEquals(0xFFDDEEFF, scheme.getColor(EditorColorScheme.HOVER_BACKGROUND));
        assertEquals(0xFF112233, scheme.getColor(EditorColorScheme.PROBLEM_ERROR));
        assertEquals(0xFF445566, scheme.getColor(EditorColorScheme.TEXT_INLAY_HINT_FOREGROUND));
        assertEquals(0xFF010203, scheme.getColor(EditorColorScheme.KEYWORD));

        controller.clear();
        assertEquals(0xFF111213, scheme.getColor(EditorColorScheme.COMPLETION_WND_BACKGROUND));
        assertEquals(0xFF212223, scheme.getColor(EditorColorScheme.HOVER_BACKGROUND));
        assertEquals(0xFF313233, scheme.getColor(EditorColorScheme.PROBLEM_ERROR));
        assertEquals(0xFF414243, scheme.getColor(EditorColorScheme.TEXT_INLAY_HINT_FOREGROUND));
        assertEquals(0xFF010203, scheme.getColor(EditorColorScheme.KEYWORD));
        activity.finish();
    }

    @Test
    public void secondaryWidgetColorsApplyAndRestoreAsIndependentSoraFields() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CodeEditor editor = new CodeEditor(activity);
        EditorColorScheme scheme = editor.getColorScheme();
        assertNotNull("Sora must initialize a color scheme", scheme);
        scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, 0xFF010203);
        scheme.setColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_BACKGROUND, 0xFF111213);
        scheme.setColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_BRIEF_MSG, 0xFF212223);
        scheme.setColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_DETAILED_MSG, 0xFF313233);
        scheme.setColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_ACTION, 0xFF414243);
        scheme.setColor(EditorColorScheme.SIGNATURE_BACKGROUND, 0xFF515253);
        scheme.setColor(EditorColorScheme.SIGNATURE_TEXT_NORMAL, 0xFF616263);
        scheme.setColor(EditorColorScheme.SIGNATURE_TEXT_HIGHLIGHTED_PARAMETER, 0xFF717273);
        scheme.setColor(EditorColorScheme.SIGNATURE_BORDER, 0xFF818283);
        scheme.setColor(EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND, 0xFF919293);
        scheme.setColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR, 0xFFA1A2A3);

        SoraEditorUiThemeController controller = new SoraEditorUiThemeController(editor);
        controller.apply(theme("secondary-widgets",
            "{\"name\":\"Secondary Widgets\",\"type\":\"dark\",\"colors\":{"
                + "\"editorSuggestWidget.descriptionForeground\":\"#AABBCC\","
                + "\"editorHoverWidget.background\":\"#DDEEFF\","
                + "\"editorHoverWidget.foreground\":\"#112233\","
                + "\"editorHoverWidget.highlightForeground\":\"#223344\","
                + "\"editorHoverWidget.border\":\"#334455\","
                + "\"textLink.foreground\":\"#445566\","
                + "\"menu.background\":\"#556677\","
                + "\"menu.foreground\":\"#667788\"}}"));

        assertEquals(0xFFAABBCC, scheme.getColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY));
        assertEquals(0xFFDDEEFF, scheme.getColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_BACKGROUND));
        assertEquals(0xFF112233, scheme.getColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_BRIEF_MSG));
        assertEquals(0xFF112233, scheme.getColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_DETAILED_MSG));
        assertEquals(0xFF445566, scheme.getColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_ACTION));
        assertEquals(0xFFDDEEFF, scheme.getColor(EditorColorScheme.SIGNATURE_BACKGROUND));
        assertEquals(0xFF112233, scheme.getColor(EditorColorScheme.SIGNATURE_TEXT_NORMAL));
        assertEquals(0xFF223344, scheme.getColor(EditorColorScheme.SIGNATURE_TEXT_HIGHLIGHTED_PARAMETER));
        assertEquals(0xFF334455, scheme.getColor(EditorColorScheme.SIGNATURE_BORDER));
        assertEquals(0xFF556677, scheme.getColor(EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND));
        assertEquals(0xFF667788, scheme.getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR));

        controller.clear();
        assertEquals(0xFF010203, scheme.getColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY));
        assertEquals(0xFF111213, scheme.getColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_BACKGROUND));
        assertEquals(0xFF212223, scheme.getColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_BRIEF_MSG));
        assertEquals(0xFF313233, scheme.getColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_DETAILED_MSG));
        assertEquals(0xFF414243, scheme.getColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_ACTION));
        assertEquals(0xFF515253, scheme.getColor(EditorColorScheme.SIGNATURE_BACKGROUND));
        assertEquals(0xFF616263, scheme.getColor(EditorColorScheme.SIGNATURE_TEXT_NORMAL));
        assertEquals(0xFF717273, scheme.getColor(EditorColorScheme.SIGNATURE_TEXT_HIGHLIGHTED_PARAMETER));
        assertEquals(0xFF818283, scheme.getColor(EditorColorScheme.SIGNATURE_BORDER));
        assertEquals(0xFF919293, scheme.getColor(EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND));
        assertEquals(0xFFA1A2A3, scheme.getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR));
        activity.finish();
    }

    @Test
    public void remainingSoraSurfaceColorsApplyAndRestoreWithoutChangingSyntaxColors()
        throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CodeEditor editor = new CodeEditor(activity);
        EditorColorScheme scheme = editor.getColorScheme();
        assertNotNull("Sora must initialize a color scheme", scheme);
        scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND, 0xFF010203);
        scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BORDER, 0xFF111213);
        scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, 0xFF212223);
        scheme.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, 0xFF313233);
        scheme.setColor(EditorColorScheme.SELECTION_INSERT, 0xFF414243);
        scheme.setColor(EditorColorScheme.SELECTION_HANDLE, 0xFF515253);
        scheme.setColor(EditorColorScheme.HARD_WRAP_MARKER, 0xFF616263);
        scheme.setColor(EditorColorScheme.SCROLL_BAR_TRACK, 0xFF717273);
        scheme.setColor(EditorColorScheme.COMPLETION_WND_CORNER, 0xFF818283);
        scheme.setColor(EditorColorScheme.KEYWORD, 0xFF919293);

        SoraEditorUiThemeController controller = new SoraEditorUiThemeController(editor);
        controller.apply(theme("remaining-sora-surfaces",
            "{\"name\":\"Remaining Sora Surfaces\",\"type\":\"dark\",\"colors\":{"
                + "\"editorBracketMatch.background\":\"#AABBCC\","
                + "\"editorBracketMatch.border\":\"#BBCCDD\","
                + "\"editorBracketMatch.foreground\":\"#CCDDEE\","
                + "\"editor.findMatchBackground\":\"#DDEEFF\","
                + "\"editorCursor.foreground\":\"#112233\","
                + "\"editorRuler.foreground\":\"#223344\","
                + "\"scrollbar.background\":\"#334455\","
                + "\"editorSuggestWidget.border\":\"#445566\"}}"));

        assertEquals(0xFFAABBCC,
            scheme.getColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND));
        assertEquals(0xFFBBCCDD,
            scheme.getColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BORDER));
        assertEquals(0xFFCCDDEE,
            scheme.getColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND));
        assertEquals(0xFFDDEEFF, scheme.getColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND));
        assertEquals(0xFF112233, scheme.getColor(EditorColorScheme.SELECTION_INSERT));
        assertEquals(0xFF112233, scheme.getColor(EditorColorScheme.SELECTION_HANDLE));
        assertEquals(0xFF223344, scheme.getColor(EditorColorScheme.HARD_WRAP_MARKER));
        assertEquals(0xFF334455, scheme.getColor(EditorColorScheme.SCROLL_BAR_TRACK));
        assertEquals(0xFF445566, scheme.getColor(EditorColorScheme.COMPLETION_WND_CORNER));
        assertEquals("TextMate syntax colors must remain untouched", 0xFF919293,
            scheme.getColor(EditorColorScheme.KEYWORD));

        controller.clear();
        assertEquals(0xFF010203,
            scheme.getColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND));
        assertEquals(0xFF111213,
            scheme.getColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BORDER));
        assertEquals(0xFF212223,
            scheme.getColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND));
        assertEquals(0xFF313233, scheme.getColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND));
        assertEquals(0xFF414243, scheme.getColor(EditorColorScheme.SELECTION_INSERT));
        assertEquals(0xFF515253, scheme.getColor(EditorColorScheme.SELECTION_HANDLE));
        assertEquals(0xFF616263, scheme.getColor(EditorColorScheme.HARD_WRAP_MARKER));
        assertEquals(0xFF717273, scheme.getColor(EditorColorScheme.SCROLL_BAR_TRACK));
        assertEquals(0xFF818283, scheme.getColor(EditorColorScheme.COMPLETION_WND_CORNER));
        assertEquals(0xFF919293, scheme.getColor(EditorColorScheme.KEYWORD));
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

    @Test
    public void selectionAndFindBordersApplyAndRestoreWithoutChangingSyntaxColors()
        throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CodeEditor editor = new CodeEditor(activity);
        EditorColorScheme scheme = editor.getColorScheme();
        assertNotNull("Sora must initialize a color scheme", scheme);
        scheme.setColor(EditorColorScheme.MATCHED_TEXT_BORDER, 0xFF010203);
        scheme.setColor(EditorColorScheme.SELECTED_TEXT_BORDER, 0xFF111213);
        scheme.setColor(EditorColorScheme.KEYWORD, 0xFF212223);

        SoraEditorUiThemeController controller = new SoraEditorUiThemeController(editor);
        controller.apply(theme("selection-find-borders",
            "{\"name\":\"Selection and Find Borders\",\"type\":\"dark\",\"colors\":{"
                + "\"editor.findMatchBorder\":\"#AABBCC\","
                + "\"editor.selectionHighlightBorder\":\"#DDEEFF\"}}"));

        assertEquals(0xFFAABBCC, scheme.getColor(EditorColorScheme.MATCHED_TEXT_BORDER));
        assertEquals(0xFFDDEEFF, scheme.getColor(EditorColorScheme.SELECTED_TEXT_BORDER));
        assertEquals("TextMate syntax colors must remain untouched", 0xFF212223,
            scheme.getColor(EditorColorScheme.KEYWORD));

        controller.clear();
        assertEquals(0xFF010203, scheme.getColor(EditorColorScheme.MATCHED_TEXT_BORDER));
        assertEquals(0xFF111213, scheme.getColor(EditorColorScheme.SELECTED_TEXT_BORDER));
        assertEquals(0xFF212223, scheme.getColor(EditorColorScheme.KEYWORD));
        activity.finish();
    }

    @Test
    public void editorGutterSurfaceFallbackAppliesToDividerAndRestoresWithoutChangingSyntaxColors()
        throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CodeEditor editor = new CodeEditor(activity);
        EditorColorScheme scheme = editor.getColorScheme();
        assertNotNull("Sora must initialize a color scheme", scheme);
        scheme.setColor(EditorColorScheme.LINE_DIVIDER, 0xFF010203);
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, 0xFF111213);
        scheme.setColor(EditorColorScheme.KEYWORD, 0xFF212223);

        SoraEditorUiThemeController controller = new SoraEditorUiThemeController(editor);
        controller.apply(theme("gutter-divider",
            "{\"name\":\"Gutter Divider\",\"type\":\"dark\","
                + "\"colors\":{\"editorGutter.background\":\"#AABBCC\"}}"));

        assertEquals(0xFFAABBCC, scheme.getColor(EditorColorScheme.LINE_DIVIDER));
        assertEquals(0xFFAABBCC, scheme.getColor(EditorColorScheme.LINE_NUMBER_BACKGROUND));
        assertEquals("TextMate syntax colors must remain untouched", 0xFF212223,
            scheme.getColor(EditorColorScheme.KEYWORD));

        controller.clear();
        assertEquals(0xFF010203, scheme.getColor(EditorColorScheme.LINE_DIVIDER));
        assertEquals(0xFF111213, scheme.getColor(EditorColorScheme.LINE_NUMBER_BACKGROUND));
        assertEquals(0xFF212223, scheme.getColor(EditorColorScheme.KEYWORD));
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
