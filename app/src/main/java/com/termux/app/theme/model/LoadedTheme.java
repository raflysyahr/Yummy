package com.termux.app.theme.model;

import com.termux.app.theme.io.ThemeResourceReader;

/** A validated theme package containing Yummy metadata and its VS Code theme document. */
public final class LoadedTheme {
    private final ThemeMetadata metadata;
    private final VSCodeColorTheme colorTheme;
    private final ThemeResourceReader resourceReader;

    public LoadedTheme(ThemeMetadata metadata, VSCodeColorTheme colorTheme) {
        this(metadata, colorTheme, null);
    }

    public LoadedTheme(
        ThemeMetadata metadata,
        VSCodeColorTheme colorTheme,
        ThemeResourceReader resourceReader
    ) {
        if (metadata == null) throw new IllegalArgumentException("Theme metadata is required");
        if (colorTheme == null) throw new IllegalArgumentException("Color Theme document is required");
        this.metadata = metadata;
        this.colorTheme = colorTheme;
        this.resourceReader = resourceReader;
    }

    public ThemeMetadata getMetadata() {
        return metadata;
    }

    public VSCodeColorTheme getColorTheme() {
        return colorTheme;
    }

    /** Package-relative resource access for adapters that consume referenced theme files. */
    public ThemeResourceReader getResourceReader() {
        return resourceReader;
    }
}
