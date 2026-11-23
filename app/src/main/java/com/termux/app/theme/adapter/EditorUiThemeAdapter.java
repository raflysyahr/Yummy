package com.termux.app.theme.adapter;

import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ResolvedEditorUiTheme;
import com.termux.app.theme.model.ThemeTarget;
import com.termux.app.theme.model.VSCodeColorTheme;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves the VS Code workbench colors used by Sora's editor surface.
 *
 * <p>This is deliberately separate from AppThemeAdapter and EditorThemeAdapter:
 * it consumes only the Editor UI capability and never reads TextMate token colors.</p>
 */
public final class EditorUiThemeAdapter {
    private static final Map<String, String> IDENTIFIER_TO_SORA_FIELD = createMappings();

    public ResolvedEditorUiTheme resolve(LoadedTheme theme) {
        if (theme == null) throw new IllegalArgumentException("Loaded theme is required");
        if (!theme.getMetadata().supports(ThemeTarget.EDITOR_UI)) {
            throw new IllegalArgumentException(
                "Theme does not declare Editor UI support: " + theme.getMetadata().getId());
        }

        VSCodeColorTheme document = theme.getColorTheme();
        LinkedHashMap<String, Integer> colors = new LinkedHashMap<>();
        LinkedHashMap<String, String> sources = new LinkedHashMap<>();
        for (Map.Entry<String, String> mapping : IDENTIFIER_TO_SORA_FIELD.entrySet()) {
            String value = document.getColor(mapping.getKey());
            if (value == null || value.trim().isEmpty()
                || "default".equalsIgnoreCase(value.trim())) {
                continue;
            }
            try {
                colors.put(mapping.getValue(), AppThemeColorValueParser.parseHexColor(value));
                sources.put(mapping.getValue(), mapping.getKey());
            } catch (IllegalArgumentException ignored) {
                // Invalid scalar values are skipped defensively; package validation reports them.
            }
        }
        return new ResolvedEditorUiTheme(
            theme.getMetadata().getId(), theme.getMetadata().getLabel(), colors, sources);
    }

    /** Returns an immutable mapping of VS Code identifiers to optional Sora color fields. */
    public Map<String, String> getIdentifierMappings() {
        return IDENTIFIER_TO_SORA_FIELD;
    }

    private static Map<String, String> createMappings() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put("editor.background", "WHOLE_BACKGROUND");
        result.put("editor.foreground", "TEXT_NORMAL");
        result.put("editorLineNumber.foreground", "LINE_NUMBER");
        result.put("editorLineNumber.activeForeground", "LINE_NUMBER_CURRENT");
        result.put("editorGutter.background", "LINE_NUMBER_BACKGROUND");
        result.put("editor.lineHighlightBackground", "CURRENT_LINE");
        result.put("editor.selectionBackground", "SELECTED_TEXT_BACKGROUND");
        result.put("editorCursor.foreground", "SELECTION_INSERT");
        result.put("editorIndentGuide.background", "BLOCK_LINE");
        result.put("editorIndentGuide.activeBackground", "BLOCK_LINE_CURRENT");
        result.put("editorBracketMatch.background", "MATCHED_TEXT_BACKGROUND");
        result.put("editorBracketMatch.border", "MATCHED_TEXT_BORDER");

        // Sora completion and hover widgets.
        result.put("editorSuggestWidget.background", "COMPLETION_WND_BACKGROUND");
        result.put("editorSuggestWidget.foreground", "COMPLETION_WND_TEXT_PRIMARY");
        result.put("editorSuggestWidget.highlightForeground", "COMPLETION_WND_TEXT_MATCHED");
        result.put("editorSuggestWidget.selectedBackground", "COMPLETION_WND_ITEM_CURRENT");
        result.put("editorHoverWidget.background", "HOVER_BACKGROUND");
        result.put("editorHoverWidget.foreground", "HOVER_TEXT_NORMAL");
        result.put("editorHoverWidget.highlightForeground", "HOVER_TEXT_HIGHLIGHTED");
        result.put("editorHoverWidget.border", "HOVER_BORDER");

        // Inline hints, snippets and editor decorations.
        result.put("editorInlayHint.background", "TEXT_INLAY_HINT_BACKGROUND");
        result.put("editorInlayHint.foreground", "TEXT_INLAY_HINT_FOREGROUND");
        result.put("editorWhitespace.foreground", "NON_PRINTABLE_CHAR");
        result.put("editor.lineHighlightBorder", "CURRENT_ROW_BORDER");
        result.put("editor.selectionForeground", "TEXT_SELECTED");
        result.put("editor.wordHighlightBackground", "TEXT_HIGHLIGHT_BACKGROUND");
        result.put("editor.wordHighlightStrongBackground", "TEXT_HIGHLIGHT_STRONG_BACKGROUND");
        result.put("editor.wordHighlightBorder", "TEXT_HIGHLIGHT_BORDER");
        result.put("editor.wordHighlightStrongBorder", "TEXT_HIGHLIGHT_STRONG_BORDER");
        result.put("editor.snippetTabstopHighlightBackground", "SNIPPET_BACKGROUND_EDITING");
        result.put("editor.snippetFinalTabstopHighlightBackground", "SNIPPET_BACKGROUND_RELATED");
        result.put("editorStickyScroll.border", "STICKY_SCROLL_DIVIDER");

        // Diagnostic markers and scrollbar states supported by Sora 0.24.4.
        result.put("editorError.foreground", "PROBLEM_ERROR");
        result.put("editorWarning.foreground", "PROBLEM_WARNING");
        result.put("scrollbarSlider.background", "SCROLL_BAR_THUMB");
        result.put("scrollbarSlider.activeBackground", "SCROLL_BAR_THUMB_PRESSED");
        return Collections.unmodifiableMap(result);
    }
}
