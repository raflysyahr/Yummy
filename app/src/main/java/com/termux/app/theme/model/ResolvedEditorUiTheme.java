package com.termux.app.theme.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable Sora editor-surface palette resolved from a VS Code Editor UI theme. */
public final class ResolvedEditorUiTheme {
    private final String themeId;
    private final String label;
    private final Map<String, Integer> colorsBySoraField;
    private final Map<String, String> sourceIdentifiers;

    public ResolvedEditorUiTheme(
        String themeId,
        String label,
        Map<String, Integer> colorsBySoraField,
        Map<String, String> sourceIdentifiers
    ) {
        if (themeId == null || themeId.trim().isEmpty()) {
            throw new IllegalArgumentException("Editor UI theme ID is required");
        }
        if (label == null || label.trim().isEmpty()) {
            throw new IllegalArgumentException("Editor UI theme label is required");
        }
        if (colorsBySoraField == null || sourceIdentifiers == null) {
            throw new IllegalArgumentException("Editor UI color maps are required");
        }
        LinkedHashMap<String, Integer> colors = new LinkedHashMap<>(colorsBySoraField);
        LinkedHashMap<String, String> sources = new LinkedHashMap<>(sourceIdentifiers);
        if (!colors.keySet().containsAll(sources.keySet())) {
            throw new IllegalArgumentException("A source identifier must have a resolved color");
        }
        this.themeId = themeId;
        this.label = label;
        this.colorsBySoraField = Collections.unmodifiableMap(colors);
        this.sourceIdentifiers = Collections.unmodifiableMap(sources);
    }

    public String getThemeId() { return themeId; }
    public String getLabel() { return label; }
    public Map<String, Integer> getColorsBySoraField() { return colorsBySoraField; }
    public Map<String, String> getSourceIdentifiers() { return sourceIdentifiers; }
}
