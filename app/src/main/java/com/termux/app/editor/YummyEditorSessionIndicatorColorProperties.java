package com.termux.app.editor;

/**
 * Stable Color Scheme property IDs and built-in fallback colors for the editor session-save
 * indicator. These semantic state colors intentionally remain independent from terminal and
 * editor syntax colors.
 */
final class YummyEditorSessionIndicatorColorProperties {
    static final String SAVING_BACKGROUND = "editor.statusBar.session.saving.background";
    static final String SAVING_FOREGROUND = "editor.statusBar.session.saving.foreground";
    static final String FAILED_BACKGROUND = "editor.statusBar.session.failed.background";
    static final String FAILED_FOREGROUND = "editor.statusBar.session.failed.foreground";
    static final String SAVED_BACKGROUND = "editor.statusBar.session.saved.background";
    static final String SAVED_FOREGROUND = "editor.statusBar.session.saved.foreground";

    private static final String[] PROPERTY_IDS = {
        SAVING_BACKGROUND, SAVING_FOREGROUND,
        FAILED_BACKGROUND, FAILED_FOREGROUND,
        SAVED_BACKGROUND, SAVED_FOREGROUND
    };

    private YummyEditorSessionIndicatorColorProperties() { }

    static String backgroundProperty(boolean saving, boolean failed) {
        if (saving) return SAVING_BACKGROUND;
        if (failed) return FAILED_BACKGROUND;
        return SAVED_BACKGROUND;
    }

    static String foregroundProperty(boolean saving, boolean failed) {
        if (saving) return SAVING_FOREGROUND;
        if (failed) return FAILED_FOREGROUND;
        return SAVED_FOREGROUND;
    }

    static int fallbackColor(String propertyId) {
        if (SAVING_BACKGROUND.equals(propertyId)) return 0xFF16A34A;
        if (SAVING_FOREGROUND.equals(propertyId)) return 0xFFFFFFFF;
        if (FAILED_BACKGROUND.equals(propertyId)) return 0xFFDC2626;
        if (FAILED_FOREGROUND.equals(propertyId)) return 0xFFFFFFFF;
        if (SAVED_BACKGROUND.equals(propertyId)) return 0x00000000;
        if (SAVED_FOREGROUND.equals(propertyId)) return 0xFF89919B;
        throw new IllegalArgumentException("Unknown session indicator color property: " + propertyId);
    }

    static String[] propertyIds() {
        return PROPERTY_IDS.clone();
    }
}
