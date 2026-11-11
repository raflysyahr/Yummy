package com.termux.app.theme.model;

import java.util.Locale;

/**
 * Appearance declared by a VS Code Color Theme's {@code type} property.
 */
public enum ThemeAppearance {
    DARK("dark"),
    LIGHT("light"),
    HIGH_CONTRAST_DARK("hcDark"),
    HIGH_CONTRAST_LIGHT("hcLight"),
    UNKNOWN("unknown");

    private final String vscodeValue;

    ThemeAppearance(String vscodeValue) {
        this.vscodeValue = vscodeValue;
    }

    public String getVscodeValue() {
        return vscodeValue;
    }

    /**
     * Converts a VS Code theme type to a Yummy enum value.
     * Unknown or missing values are kept explicit instead of being guessed.
     */
    public static ThemeAppearance fromVscodeValue(String value) {
        if (value == null) return UNKNOWN;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "dark":
                return DARK;
            case "light":
                return LIGHT;
            case "hcdark":
                return HIGH_CONTRAST_DARK;
            case "hclight":
                return HIGH_CONTRAST_LIGHT;
            default:
                return UNKNOWN;
        }
    }
}
