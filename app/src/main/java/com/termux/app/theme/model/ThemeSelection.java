package com.termux.app.theme.model;

/**
 * The user's independently selected App UI and Editor Syntax themes.
 * Persistence is intentionally left to the settings/storage layer.
 */
public final class ThemeSelection {
    private static final java.util.regex.Pattern VALID_ID =
        java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public static final String DEFAULT_APP_THEME_ID = "yummy-default";
    public static final String DEFAULT_EDITOR_THEME_ID = "yummy-default";

    private final String appColorSchemeId;
    private final String editorSyntaxThemeId;

    public ThemeSelection(String appColorSchemeId, String editorSyntaxThemeId) {
        this.appColorSchemeId = requireId(appColorSchemeId, "App color scheme ID");
        this.editorSyntaxThemeId = requireId(editorSyntaxThemeId, "Editor syntax theme ID");
    }

    public static ThemeSelection defaults() {
        return new ThemeSelection(DEFAULT_APP_THEME_ID, DEFAULT_EDITOR_THEME_ID);
    }

    private static String requireId(String value, String fieldName) {
        if (value == null || !VALID_ID.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("Invalid " + fieldName + ": " + value);
        }
        return value.trim();
    }

    public String getAppColorSchemeId() {
        return appColorSchemeId;
    }

    public String getEditorSyntaxThemeId() {
        return editorSyntaxThemeId;
    }

    public ThemeSelection withAppColorScheme(String themeId) {
        return new ThemeSelection(themeId, editorSyntaxThemeId);
    }

    public ThemeSelection withEditorSyntaxTheme(String themeId) {
        return new ThemeSelection(appColorSchemeId, themeId);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ThemeSelection)) return false;
        ThemeSelection that = (ThemeSelection) other;
        return appColorSchemeId.equals(that.appColorSchemeId)
            && editorSyntaxThemeId.equals(that.editorSyntaxThemeId);
    }

    @Override
    public int hashCode() {
        int result = appColorSchemeId.hashCode();
        result = 31 * result + editorSyntaxThemeId.hashCode();
        return result;
    }
}
