package com.termux.app.theme.adapter;

import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ResolvedEditorUiTheme;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;
import com.termux.app.theme.model.VSCodeColorTheme;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EditorUiThemeAdapterTest {
    private final EditorUiThemeAdapter adapter = new EditorUiThemeAdapter();

    @Test
    public void resolvesEditorSurfaceColorsWithoutReadingSyntaxTokens() throws Exception {
        LoadedTheme theme = theme(
            "{\"name\":\"Editor UI\",\"type\":\"dark\","
                + "\"colors\":{\"editor.background\":\"#112233\","
                + "\"editor.foreground\":\"#AABBCC\","
                + "\"editorLineNumber.foreground\":\"#445566\","
                + "\"editorCursor.foreground\":\"#ABCDEF\"},"
                + "\"tokenColors\":[{\"scope\":\"keyword\","
                + "\"settings\":{\"foreground\":\"#FF0000\"}}]}"
        );

        ResolvedEditorUiTheme resolved = adapter.resolve(theme);

        assertEquals(0xFF112233,
            (int) resolved.getColorsBySoraField().get("WHOLE_BACKGROUND"));
        assertEquals(0xFFAABBCC,
            (int) resolved.getColorsBySoraField().get("TEXT_NORMAL"));
        assertEquals(0xFF445566,
            (int) resolved.getColorsBySoraField().get("LINE_NUMBER"));
        assertEquals(0xFFABCDEF,
            (int) resolved.getColorsBySoraField().get("SELECTION_INSERT"));
        assertEquals("editor.background",
            resolved.getSourceIdentifiers().get("WHOLE_BACKGROUND"));
        assertFalse(resolved.getColorsBySoraField().containsKey("KEYWORD"));
    }

    @Test
    public void resolvesCompletionHoverDiagnosticAndInlineEditorWidgetColors() throws Exception {
        LoadedTheme theme = theme(
            "{\"name\":\"Editor Widgets\",\"type\":\"dark\",\"colors\":{"
                + "\"editorSuggestWidget.background\":\"#101112\","
                + "\"editorSuggestWidget.foreground\":\"#202122\","
                + "\"editorSuggestWidget.highlightForeground\":\"#303132\","
                + "\"editorSuggestWidget.selectedBackground\":\"#404142\","
                + "\"editorHoverWidget.background\":\"#505152\","
                + "\"editorHoverWidget.foreground\":\"#606162\","
                + "\"editorHoverWidget.highlightForeground\":\"#707172\","
                + "\"editorHoverWidget.border\":\"#808182\","
                + "\"editorError.foreground\":\"#909192\","
                + "\"editorWarning.foreground\":\"#A0A1A2\","
                + "\"editorInlayHint.background\":\"#B0B1B2\","
                + "\"editorInlayHint.foreground\":\"#C0C1C2\","
                + "\"editorStickyScroll.border\":\"#D0D1D2\","
                + "\"editorWhitespace.foreground\":\"#D1D2D3\","
                + "\"editor.lineHighlightBorder\":\"#D2D3D4\","
                + "\"editor.selectionForeground\":\"#D3D4D5\","
                + "\"editor.wordHighlightBackground\":\"#D4D5D6\","
                + "\"editor.wordHighlightStrongBackground\":\"#D5D6D7\","
                + "\"editor.wordHighlightBorder\":\"#D6D7D8\","
                + "\"editor.wordHighlightStrongBorder\":\"#D7D8D9\","
                + "\"editor.snippetTabstopHighlightBackground\":\"#D8D9DA\","
                + "\"editor.snippetFinalTabstopHighlightBackground\":\"#D9DADB\","
                + "\"scrollbarSlider.background\":\"#E0E1E2\","
                + "\"scrollbarSlider.activeBackground\":\"#F0F1F2\"}}"
        );

        ResolvedEditorUiTheme resolved = adapter.resolve(theme);
        java.util.Map<String, Integer> colors = resolved.getColorsBySoraField();
        assertEquals(Integer.valueOf(0xFF101112), colors.get("COMPLETION_WND_BACKGROUND"));
        assertEquals(Integer.valueOf(0xFF202122), colors.get("COMPLETION_WND_TEXT_PRIMARY"));
        assertEquals(Integer.valueOf(0xFF303132), colors.get("COMPLETION_WND_TEXT_MATCHED"));
        assertEquals(Integer.valueOf(0xFF404142), colors.get("COMPLETION_WND_ITEM_CURRENT"));
        assertEquals(Integer.valueOf(0xFF505152), colors.get("HOVER_BACKGROUND"));
        assertEquals(Integer.valueOf(0xFF606162), colors.get("HOVER_TEXT_NORMAL"));
        assertEquals(Integer.valueOf(0xFF707172), colors.get("HOVER_TEXT_HIGHLIGHTED"));
        assertEquals(Integer.valueOf(0xFF808182), colors.get("HOVER_BORDER"));
        assertEquals(Integer.valueOf(0xFF909192), colors.get("PROBLEM_ERROR"));
        assertEquals(Integer.valueOf(0xFFA0A1A2), colors.get("PROBLEM_WARNING"));
        assertEquals(Integer.valueOf(0xFFB0B1B2), colors.get("TEXT_INLAY_HINT_BACKGROUND"));
        assertEquals(Integer.valueOf(0xFFC0C1C2), colors.get("TEXT_INLAY_HINT_FOREGROUND"));
        assertEquals(Integer.valueOf(0xFFD0D1D2), colors.get("STICKY_SCROLL_DIVIDER"));
        assertEquals(Integer.valueOf(0xFFD1D2D3), colors.get("NON_PRINTABLE_CHAR"));
        assertEquals(Integer.valueOf(0xFFD2D3D4), colors.get("CURRENT_ROW_BORDER"));
        assertEquals(Integer.valueOf(0xFFD3D4D5), colors.get("TEXT_SELECTED"));
        assertEquals(Integer.valueOf(0xFFD4D5D6), colors.get("TEXT_HIGHLIGHT_BACKGROUND"));
        assertEquals(Integer.valueOf(0xFFD5D6D7), colors.get("TEXT_HIGHLIGHT_STRONG_BACKGROUND"));
        assertEquals(Integer.valueOf(0xFFD6D7D8), colors.get("TEXT_HIGHLIGHT_BORDER"));
        assertEquals(Integer.valueOf(0xFFD7D8D9), colors.get("TEXT_HIGHLIGHT_STRONG_BORDER"));
        assertEquals(Integer.valueOf(0xFFD8D9DA), colors.get("SNIPPET_BACKGROUND_EDITING"));
        assertEquals(Integer.valueOf(0xFFD9DADB), colors.get("SNIPPET_BACKGROUND_RELATED"));
        assertEquals(Integer.valueOf(0xFFE0E1E2), colors.get("SCROLL_BAR_THUMB"));
        assertEquals(Integer.valueOf(0xFFF0F1F2), colors.get("SCROLL_BAR_THUMB_PRESSED"));
        assertEquals("editorSuggestWidget.background",
            resolved.getSourceIdentifiers().get("COMPLETION_WND_BACKGROUND"));
    }

    @Test
    public void resolvesSecondarySoraWidgetsFromWorkbenchColors() throws Exception {
        LoadedTheme theme = theme(
            "{\"name\":\"Secondary Widgets\",\"type\":\"dark\",\"colors\":{"
                + "\"editorSuggestWidget.descriptionForeground\":\"#101112\","
                + "\"editorHoverWidget.background\":\"#202122\","
                + "\"editorHoverWidget.foreground\":\"#303132\","
                + "\"editorHoverWidget.highlightForeground\":\"#404142\","
                + "\"editorHoverWidget.border\":\"#505152\","
                + "\"textLink.foreground\":\"#606162\","
                + "\"menu.background\":\"#707172\","
                + "\"menu.foreground\":\"#808182\"}}"
        );

        java.util.Map<String, Integer> colors = adapter.resolve(theme).getColorsBySoraField();
        assertEquals(Integer.valueOf(0xFF101112), colors.get("COMPLETION_WND_TEXT_SECONDARY"));
        assertEquals(Integer.valueOf(0xFF202122), colors.get("DIAGNOSTIC_TOOLTIP_BACKGROUND"));
        assertEquals(Integer.valueOf(0xFF303132), colors.get("DIAGNOSTIC_TOOLTIP_BRIEF_MSG"));
        assertEquals(Integer.valueOf(0xFF303132), colors.get("DIAGNOSTIC_TOOLTIP_DETAILED_MSG"));
        assertEquals(Integer.valueOf(0xFF606162), colors.get("DIAGNOSTIC_TOOLTIP_ACTION"));
        assertEquals(Integer.valueOf(0xFF202122), colors.get("SIGNATURE_BACKGROUND"));
        assertEquals(Integer.valueOf(0xFF303132), colors.get("SIGNATURE_TEXT_NORMAL"));
        assertEquals(Integer.valueOf(0xFF404142), colors.get("SIGNATURE_TEXT_HIGHLIGHTED_PARAMETER"));
        assertEquals(Integer.valueOf(0xFF505152), colors.get("SIGNATURE_BORDER"));
        assertEquals(Integer.valueOf(0xFF707172), colors.get("TEXT_ACTION_WINDOW_BACKGROUND"));
        assertEquals(Integer.valueOf(0xFF808182), colors.get("TEXT_ACTION_WINDOW_ICON_COLOR"));
    }

    @Test
    public void resolvesRemainingSoraSurfaceColorsAndSeparatesBracketFromFindHighlights()
        throws Exception {
        LoadedTheme theme = theme(
            "{\"name\":\"Remaining Sora Surfaces\",\"type\":\"dark\",\"colors\":{"
                + "\"editorBracketMatch.background\":\"#101112\","
                + "\"editorBracketMatch.border\":\"#202122\","
                + "\"editorBracketMatch.foreground\":\"#303132\","
                + "\"editor.findMatchBackground\":\"#404142\","
                + "\"editorCursor.foreground\":\"#505152\","
                + "\"editorRuler.foreground\":\"#606162\","
                + "\"scrollbar.background\":\"#707172\","
                + "\"editorSuggestWidget.border\":\"#808182\"}}"
        );

        ResolvedEditorUiTheme resolved = adapter.resolve(theme);
        java.util.Map<String, Integer> colors = resolved.getColorsBySoraField();
        assertEquals(Integer.valueOf(0xFF101112),
            colors.get("HIGHLIGHTED_DELIMITERS_BACKGROUND"));
        assertEquals(Integer.valueOf(0xFF202122),
            colors.get("HIGHLIGHTED_DELIMITERS_BORDER"));
        assertEquals(Integer.valueOf(0xFF303132),
            colors.get("HIGHLIGHTED_DELIMITERS_FOREGROUND"));
        assertEquals(Integer.valueOf(0xFF404142), colors.get("MATCHED_TEXT_BACKGROUND"));
        assertEquals(Integer.valueOf(0xFF505152), colors.get("SELECTION_INSERT"));
        assertEquals(Integer.valueOf(0xFF505152), colors.get("SELECTION_HANDLE"));
        assertEquals(Integer.valueOf(0xFF606162), colors.get("HARD_WRAP_MARKER"));
        assertEquals(Integer.valueOf(0xFF707172), colors.get("SCROLL_BAR_TRACK"));
        assertEquals(Integer.valueOf(0xFF808182), colors.get("COMPLETION_WND_CORNER"));

        assertEquals("editor.findMatchBackground",
            resolved.getSourceIdentifiers().get("MATCHED_TEXT_BACKGROUND"));
        assertFalse("Bracket colors must not target find-match fields",
            adapter.getIdentifierMappings().get("editorBracketMatch.background")
                .contains("MATCHED_TEXT_BACKGROUND"));
    }

    @Test
    public void resolvesFindAndSelectionHighlightBordersIndependently() throws Exception {
        LoadedTheme theme = theme(
            "{\"name\":\"Editor Borders\",\"type\":\"dark\",\"colors\":{"
                + "\"editor.findMatchBorder\":\"#AABBCC\","
                + "\"editor.selectionHighlightBorder\":\"#DDEEFF\"}}"
        );

        ResolvedEditorUiTheme resolved = adapter.resolve(theme);
        java.util.Map<String, Integer> colors = resolved.getColorsBySoraField();
        assertEquals(Integer.valueOf(0xFFAABBCC), colors.get("MATCHED_TEXT_BORDER"));
        assertEquals(Integer.valueOf(0xFFDDEEFF), colors.get("SELECTED_TEXT_BORDER"));
        assertEquals("editor.findMatchBorder",
            resolved.getSourceIdentifiers().get("MATCHED_TEXT_BORDER"));
        assertEquals("editor.selectionHighlightBorder",
            resolved.getSourceIdentifiers().get("SELECTED_TEXT_BORDER"));
        assertFalse(colors.containsKey("KEYWORD"));
    }

    @Test
    public void resolvesEditorGutterDividerFromAvailableGutterSurfaceColor()
        throws Exception {
        LoadedTheme theme = theme(
            "{\"name\":\"Gutter Divider\",\"type\":\"dark\",\"colors\":{"
                + "\"editorGutter.background\":\"#AABBCC\"}}"
        );

        ResolvedEditorUiTheme resolved = adapter.resolve(theme);
        java.util.Map<String, Integer> colors = resolved.getColorsBySoraField();
        assertEquals(Integer.valueOf(0xFFAABBCC), colors.get("LINE_NUMBER_BACKGROUND"));
        assertEquals(Integer.valueOf(0xFFAABBCC), colors.get("LINE_DIVIDER"));
        assertEquals("editorGutter.background",
            resolved.getSourceIdentifiers().get("LINE_DIVIDER"));
        assertFalse(colors.containsKey("KEYWORD"));
    }

    @Test
    public void resolvesHintDiagnosticColorToSoraTypoMarker() throws Exception {
        LoadedTheme theme = theme(
            "{\"name\":\"Hint Diagnostics\",\"type\":\"dark\",\"colors\":{"
                + "\"editorHint.foreground\":\"#A1B2C3\"}}"
        );

        ResolvedEditorUiTheme resolved = adapter.resolve(theme);

        assertEquals(Integer.valueOf(0xFFA1B2C3),
            resolved.getColorsBySoraField().get("PROBLEM_TYPO"));
        assertEquals("editorHint.foreground",
            resolved.getSourceIdentifiers().get("PROBLEM_TYPO"));
        assertFalse(resolved.getColorsBySoraField().containsKey("KEYWORD"));
    }

    @Test
    public void resolvesVsCodeAlphaHexColorsIntoAndroidArgbWithoutLosingAlpha()
        throws Exception {
        LoadedTheme theme = theme(
            "{\"name\":\"Alpha Colors\",\"type\":\"dark\",\"colors\":{"
                + "\"editor.background\":\"#12345678\","
                + "\"editor.foreground\":\"#A1B2C3D4\","
                + "\"editorLineNumber.foreground\":\"#1234\"}}"
        );
        java.util.Map<String, Integer> colors = adapter.resolve(theme).getColorsBySoraField();
        assertEquals(Integer.valueOf(0x78123456), colors.get("WHOLE_BACKGROUND"));
        assertEquals(Integer.valueOf(0xD4A1B2C3), colors.get("TEXT_NORMAL"));
        assertEquals(Integer.valueOf(0x44112233), colors.get("LINE_NUMBER"));
    }

    @Test
    public void missingAndDefaultColorsAreOmittedSoRuntimeCanRestoreBasePalette()
        throws Exception {
        LoadedTheme theme = theme(
            "{\"name\":\"Sparse\",\"type\":\"dark\","
                + "\"colors\":{\"editor.background\":\"default\","
                + "\"editor.foreground\":\"not-a-color\"}}"
        );

        ResolvedEditorUiTheme resolved = adapter.resolve(theme);

        assertTrue(resolved.getColorsBySoraField().isEmpty());
        assertTrue(resolved.getSourceIdentifiers().isEmpty());
    }

    @Test
    public void rejectsThemesWithoutEditorUiCapability() throws Exception {
        LoadedTheme theme = theme(
            "{\"name\":\"Syntax only\",\"type\":\"dark\","
                + "\"tokenColors\":[{\"scope\":\"keyword\","
                + "\"settings\":{\"foreground\":\"#FF0000\"}}]}",
            EnumSet.of(ThemeTarget.EDITOR_SYNTAX)
        );

        try {
            adapter.resolve(theme);
            org.junit.Assert.fail("Expected unsupported Editor UI target to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Editor UI"));
        }
    }

    private static LoadedTheme theme(String json) throws Exception {
        return theme(json, EnumSet.of(ThemeTarget.EDITOR_UI));
    }

    private static LoadedTheme theme(String json, java.util.Set<ThemeTarget> targets)
        throws Exception {
        JSONObject source = new JSONObject(json);
        ThemeMetadata metadata = new ThemeMetadata(
            "editor-ui-test", "Editor UI Test", ThemeAppearance.DARK,
            ThemeSource.IMPORTED, "theme.json", null, null, null, null,
            targets, Collections.<ThemeTarget>emptySet()
        );
        return new LoadedTheme(
            metadata, new VSCodeColorTheme(source, "Editor UI Test", ThemeAppearance.DARK));
    }
}
