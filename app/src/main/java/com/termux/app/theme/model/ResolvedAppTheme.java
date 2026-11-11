package com.termux.app.theme.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Immutable app-UI palette produced by {@code AppThemeAdapter}. */
public final class ResolvedAppTheme {
    private final String themeId;
    private final String label;
    private final ThemeAppearance appearance;
    private final Map<AppThemeColorRole, Integer> colors;
    private final Map<AppThemeColorRole, String> sourceIdentifiers;

    public ResolvedAppTheme(
        String themeId,
        String label,
        ThemeAppearance appearance,
        Map<AppThemeColorRole, Integer> colors,
        Map<AppThemeColorRole, String> sourceIdentifiers
    ) {
        if (themeId == null || themeId.trim().isEmpty()) {
            throw new IllegalArgumentException("Theme ID is required");
        }
        if (label == null || label.trim().isEmpty()) {
            throw new IllegalArgumentException("Theme label is required");
        }
        if (colors == null || sourceIdentifiers == null) {
            throw new IllegalArgumentException("Resolved color maps are required");
        }
        this.themeId = themeId;
        this.label = label;
        this.appearance = appearance == null ? ThemeAppearance.UNKNOWN : appearance;

        EnumMap<AppThemeColorRole, Integer> colorCopy =
            new EnumMap<>(AppThemeColorRole.class);
        colorCopy.putAll(colors);
        EnumMap<AppThemeColorRole, String> sourceCopy =
            new EnumMap<>(AppThemeColorRole.class);
        sourceCopy.putAll(sourceIdentifiers);
        if (!colorCopy.keySet().containsAll(sourceCopy.keySet())) {
            throw new IllegalArgumentException("A source identifier must have a resolved color");
        }
        this.colors = Collections.unmodifiableMap(colorCopy);
        this.sourceIdentifiers = Collections.unmodifiableMap(sourceCopy);
    }

    public String getThemeId() { return themeId; }
    public String getLabel() { return label; }
    public ThemeAppearance getAppearance() { return appearance; }

    /** Returns null when neither the theme nor its fallback palette defines this role. */
    public Integer getColor(AppThemeColorRole role) {
        return role == null ? null : colors.get(role);
    }

    public int getColorOr(AppThemeColorRole role, int fallbackColor) {
        Integer value = getColor(role);
        return value == null ? fallbackColor : value;
    }

    public boolean hasColor(AppThemeColorRole role) {
        return role != null && colors.containsKey(role);
    }

    /** Returns the VS Code workbench identifier that supplied this color, or null for fallback. */
    public String getSourceColorIdentifier(AppThemeColorRole role) {
        return role == null ? null : sourceIdentifiers.get(role);
    }

    public boolean isFromTheme(AppThemeColorRole role) {
        return getSourceColorIdentifier(role) != null;
    }

    public Map<AppThemeColorRole, Integer> getColors() { return colors; }
}
