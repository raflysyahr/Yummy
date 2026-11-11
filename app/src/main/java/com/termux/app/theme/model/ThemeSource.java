package com.termux.app.theme.model;

import java.util.Locale;

/** Origin of a theme package. */
public enum ThemeSource {
    BUILT_IN("builtin"),
    IMPORTED("imported");

    private final String metadataValue;

    ThemeSource(String metadataValue) {
        this.metadataValue = metadataValue;
    }

    public String getMetadataValue() {
        return metadataValue;
    }

    public static ThemeSource fromMetadataValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Theme source is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ThemeSource source : values()) {
            if (source.metadataValue.equals(normalized)) return source;
        }
        throw new IllegalArgumentException("Unsupported theme source: " + value);
    }
}
