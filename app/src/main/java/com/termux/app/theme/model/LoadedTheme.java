package com.termux.app.theme.model;

/** A validated theme package containing Yummy metadata and its VS Code theme document. */
public final class LoadedTheme {
    private final ThemeMetadata metadata;
    private final VSCodeColorTheme colorTheme;

    public LoadedTheme(ThemeMetadata metadata, VSCodeColorTheme colorTheme) {
        if (metadata == null) throw new IllegalArgumentException("Theme metadata is required");
        if (colorTheme == null) throw new IllegalArgumentException("Color Theme document is required");
        this.metadata = metadata;
        this.colorTheme = colorTheme;
    }

    public ThemeMetadata getMetadata() {
        return metadata;
    }

    public VSCodeColorTheme getColorTheme() {
        return colorTheme;
    }
}
