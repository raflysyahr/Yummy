package com.termux.app.theme.adapter;

import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ResolvedEditorUiTheme;
import com.termux.app.theme.model.ThemeTarget;
import com.termux.app.theme.model.VSCodeColorTheme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the VS Code workbench colors used by Sora's editor surface.
 *
 * <p>This is deliberately separate from AppThemeAdapter and EditorThemeAdapter:
 * it consumes only the Editor UI capability and never reads TextMate token colors.</p>
 */
public final class EditorUiThemeAdapter {
    private static final Map<String, List<String>> IDENTIFIER_TO_SORA_FIELDS = createMappings();

    public ResolvedEditorUiTheme resolve(LoadedTheme theme) {
        if (theme == null) throw new IllegalArgumentException("Loaded theme is required");
        if (!theme.getMetadata().supports(ThemeTarget.EDITOR_UI)) {
            throw new IllegalArgumentException(
                "Theme does not declare Editor UI support: " + theme.getMetadata().getId());
        }

        VSCodeColorTheme document = theme.getColorTheme();
        LinkedHashMap<String, Integer> colors = new LinkedHashMap<>();
        LinkedHashMap<String, String> sources = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> mapping : IDENTIFIER_TO_SORA_FIELDS.entrySet()) {
            String value = document.getColor(mapping.getKey());
            if (value == null || value.trim().isEmpty()
                || "default".equalsIgnoreCase(value.trim())) {
                continue;
            }
            try {
                int color = AppThemeColorValueParser.parseHexColor(value);
                for (String fieldName : mapping.getValue()) {
                    colors.put(fieldName, color);
                    sources.put(fieldName, mapping.getKey());
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid scalar values are skipped defensively; package validation reports them.
            }
        }
        return new ResolvedEditorUiTheme(
            theme.getMetadata().getId(), theme.getMetadata().getLabel(), colors, sources);
    }

    /** Returns immutable VS Code identifier mappings; one source color may feed several Sora fields. */
    public Map<String, List<String>> getIdentifierMappings() {
        return IDENTIFIER_TO_SORA_FIELDS;
    }

    private static Map<String, List<String>> createMappings() {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        addMapping(result, "editor.background", "WHOLE_BACKGROUND");
        addMapping(result, "editor.foreground", "TEXT_NORMAL");
        addMapping(result, "editorLineNumber.foreground", "LINE_NUMBER");
        addMapping(result, "editorLineNumber.activeForeground", "LINE_NUMBER_CURRENT");
        addMapping(result, "editorGutter.background", "LINE_NUMBER_BACKGROUND");
        addMapping(result, "editor.lineHighlightBackground", "CURRENT_LINE");
        addMapping(result, "editor.selectionBackground", "SELECTED_TEXT_BACKGROUND");
        addMapping(result, "editorCursor.foreground", "SELECTION_INSERT");
        addMapping(result, "editorCursor.foreground", "SELECTION_HANDLE");
        addMapping(result, "editorIndentGuide.background", "BLOCK_LINE");
        addMapping(result, "editorIndentGuide.activeBackground", "BLOCK_LINE_CURRENT");
        addMapping(result, "editorRuler.foreground", "HARD_WRAP_MARKER");
        // Bracket-match colors drive Sora's active matching-delimiter decoration.
        addMapping(result, "editorBracketMatch.background", "HIGHLIGHTED_DELIMITERS_BACKGROUND");
        addMapping(result, "editorBracketMatch.border", "HIGHLIGHTED_DELIMITERS_BORDER");
        addMapping(result, "editorBracketMatch.foreground", "HIGHLIGHTED_DELIMITERS_FOREGROUND");
        // Sora's MATCHED_TEXT_* fields are used for find/matched text occurrences, not brackets.
        addMapping(result, "editor.findMatchBackground", "MATCHED_TEXT_BACKGROUND");

        // Sora completion and hover widgets.
        addMapping(result, "editorSuggestWidget.background", "COMPLETION_WND_BACKGROUND");
        addMapping(result, "editorSuggestWidget.foreground", "COMPLETION_WND_TEXT_PRIMARY");
        addMapping(result, "editorSuggestWidget.highlightForeground", "COMPLETION_WND_TEXT_MATCHED");
        addMapping(result, "editorSuggestWidget.selectedBackground", "COMPLETION_WND_ITEM_CURRENT");
        addMapping(result, "editorSuggestWidget.border", "COMPLETION_WND_CORNER");
        addMapping(result, "editorSuggestWidget.descriptionForeground", "COMPLETION_WND_TEXT_SECONDARY");
        addMapping(result, "editorHoverWidget.background", "HOVER_BACKGROUND");
        addMapping(result, "editorHoverWidget.background", "DIAGNOSTIC_TOOLTIP_BACKGROUND");
        addMapping(result, "editorHoverWidget.background", "SIGNATURE_BACKGROUND");
        addMapping(result, "editorHoverWidget.foreground", "HOVER_TEXT_NORMAL");
        addMapping(result, "editorHoverWidget.foreground", "DIAGNOSTIC_TOOLTIP_BRIEF_MSG");
        addMapping(result, "editorHoverWidget.foreground", "DIAGNOSTIC_TOOLTIP_DETAILED_MSG");
        addMapping(result, "editorHoverWidget.foreground", "SIGNATURE_TEXT_NORMAL");
        addMapping(result, "editorHoverWidget.highlightForeground", "HOVER_TEXT_HIGHLIGHTED");
        addMapping(result, "editorHoverWidget.highlightForeground", "SIGNATURE_TEXT_HIGHLIGHTED_PARAMETER");
        addMapping(result, "editorHoverWidget.border", "HOVER_BORDER");
        addMapping(result, "editorHoverWidget.border", "SIGNATURE_BORDER");
        addMapping(result, "textLink.foreground", "DIAGNOSTIC_TOOLTIP_ACTION");
        addMapping(result, "menu.background", "TEXT_ACTION_WINDOW_BACKGROUND");
        addMapping(result, "menu.foreground", "TEXT_ACTION_WINDOW_ICON_COLOR");

        // Inline hints, snippets and editor decorations.
        addMapping(result, "editorInlayHint.background", "TEXT_INLAY_HINT_BACKGROUND");
        addMapping(result, "editorInlayHint.foreground", "TEXT_INLAY_HINT_FOREGROUND");
        addMapping(result, "editorWhitespace.foreground", "NON_PRINTABLE_CHAR");
        addMapping(result, "editor.lineHighlightBorder", "CURRENT_ROW_BORDER");
        addMapping(result, "editor.selectionForeground", "TEXT_SELECTED");
        addMapping(result, "editor.wordHighlightBackground", "TEXT_HIGHLIGHT_BACKGROUND");
        addMapping(result, "editor.wordHighlightStrongBackground", "TEXT_HIGHLIGHT_STRONG_BACKGROUND");
        addMapping(result, "editor.wordHighlightBorder", "TEXT_HIGHLIGHT_BORDER");
        addMapping(result, "editor.wordHighlightStrongBorder", "TEXT_HIGHLIGHT_STRONG_BORDER");
        addMapping(result, "editor.snippetTabstopHighlightBackground", "SNIPPET_BACKGROUND_EDITING");
        addMapping(result, "editor.snippetFinalTabstopHighlightBackground", "SNIPPET_BACKGROUND_RELATED");
        addMapping(result, "editorStickyScroll.border", "STICKY_SCROLL_DIVIDER");

        // Diagnostic markers and scrollbar states supported by Sora 0.24.4.
        addMapping(result, "editorError.foreground", "PROBLEM_ERROR");
        addMapping(result, "editorWarning.foreground", "PROBLEM_WARNING");
        addMapping(result, "scrollbar.background", "SCROLL_BAR_TRACK");
        addMapping(result, "scrollbarSlider.background", "SCROLL_BAR_THUMB");
        addMapping(result, "scrollbarSlider.activeBackground", "SCROLL_BAR_THUMB_PRESSED");
        LinkedHashMap<String, List<String>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : result.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableList(
                new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(immutable);
    }

    private static void addMapping(Map<String, List<String>> mappings,
                                   String identifier, String soraField) {
        List<String> fields = mappings.get(identifier);
        if (fields == null) {
            fields = new ArrayList<>();
            mappings.put(identifier, fields);
        }
        if (!fields.contains(soraField)) fields.add(soraField);
    }
}
