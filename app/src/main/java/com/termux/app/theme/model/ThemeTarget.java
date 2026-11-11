package com.termux.app.theme.model;

import java.util.Locale;

/** Independent Yummy surface that can consume a VS Code Color Theme. */
public enum ThemeTarget {
    APP_UI("app"),
    EDITOR_SYNTAX("editor");

    private final String metadataValue;

    ThemeTarget(String metadataValue) {
        this.metadataValue = metadataValue;
    }

    public String getMetadataValue() {
        return metadataValue;
    }

    public static ThemeTarget fromMetadataValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Theme target is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ThemeTarget target : values()) {
            if (target.metadataValue.equals(normalized)) return target;
        }
        throw new IllegalArgumentException("Unsupported theme target: " + value);
    }
}
