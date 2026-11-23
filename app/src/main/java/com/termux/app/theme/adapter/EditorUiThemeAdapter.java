package com.termux.app.theme.adapter;

import com.termux.app.theme.model.LoadedTheme;
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
        return Collections.unmodifiableMap(result);
    }
}
