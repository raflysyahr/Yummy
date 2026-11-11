package com.termux.app.theme.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeSelection;
import com.termux.app.theme.model.ThemeTarget;
import com.termux.app.theme.registry.ThemeRegistrySnapshot;

import java.util.List;

/**
 * Persists the two Yummy theme targets independently.
 *
 * <p>App UI and Editor Syntax use separate preference keys. Updating one target never writes
 * the other key, and the terminal theme is deliberately not represented by this store.</p>
 */
public final class ThemeSelectionStore {
    public static final String PREFERENCES_NAME = "yummy_theme_selection";
    public static final String KEY_APP_UI_THEME_ID = "app_ui_theme_id";
    public static final String KEY_EDITOR_SYNTAX_THEME_ID = "editor_syntax_theme_id";

    private final SharedPreferences preferences;

    public ThemeSelectionStore(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;
        preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** Reads the stored IDs without requiring the theme registry to have been loaded. */
    public ThemeSelection read() {
        String appId = preferences.getString(
            KEY_APP_UI_THEME_ID, ThemeSelection.DEFAULT_APP_THEME_ID);
        String editorId = preferences.getString(
            KEY_EDITOR_SYNTAX_THEME_ID, ThemeSelection.DEFAULT_EDITOR_THEME_ID);

        // Recover independently from malformed preference values without discarding the
        // other target's valid selection.
        try {
            new ThemeSelection(appId, ThemeSelection.DEFAULT_EDITOR_THEME_ID);
        } catch (IllegalArgumentException invalidAppId) {
            appId = ThemeSelection.DEFAULT_APP_THEME_ID;
        }
        try {
            new ThemeSelection(ThemeSelection.DEFAULT_APP_THEME_ID, editorId);
        } catch (IllegalArgumentException invalidEditorId) {
            editorId = ThemeSelection.DEFAULT_EDITOR_THEME_ID;
        }
        return new ThemeSelection(appId, editorId);
    }

    /**
     * Resolves stored IDs against a registry snapshot. An unavailable selection falls back to
     * the target's declared default, then the first available theme. If no theme exists for a
     * target, its stored ID is preserved so a later registry refresh can recover it.
     */
    public ThemeSelection read(ThemeRegistrySnapshot snapshot) {
        ThemeSelection stored = read();
        if (snapshot == null) return stored;

        String appId = resolveAvailableId(
            stored.getAppColorSchemeId(),
            snapshot.getAppUiThemes(),
            snapshot.getDefaultThemeFor(ThemeTarget.APP_UI)
        );
        String editorId = resolveAvailableId(
            stored.getEditorSyntaxThemeId(),
            snapshot.getEditorSyntaxThemes(),
            snapshot.getDefaultThemeFor(ThemeTarget.EDITOR_SYNTAX)
        );

        ThemeSelection resolved = new ThemeSelection(appId, editorId);
        if (!appId.equals(stored.getAppColorSchemeId())) {
            preferences.edit().putString(KEY_APP_UI_THEME_ID, appId).apply();
        }
        if (!editorId.equals(stored.getEditorSyntaxThemeId())) {
            preferences.edit().putString(KEY_EDITOR_SYNTAX_THEME_ID, editorId).apply();
        }
        return resolved;
    }

    /** Updates only the App UI preference key. */
    public void selectAppUiTheme(String themeId) {
        ThemeSelection current = read();
        ThemeSelection validated = current.withAppColorScheme(themeId);
        preferences.edit()
            .putString(KEY_APP_UI_THEME_ID, validated.getAppColorSchemeId())
            .apply();
    }

    /** Updates only the Editor Syntax preference key. */
    public void selectEditorSyntaxTheme(String themeId) {
        ThemeSelection current = read();
        ThemeSelection validated = current.withEditorSyntaxTheme(themeId);
        preferences.edit()
            .putString(KEY_EDITOR_SYNTAX_THEME_ID, validated.getEditorSyntaxThemeId())
            .apply();
    }

    /** Writes both fields when an explicit complete selection object is supplied. */
    public void write(ThemeSelection selection) {
        if (selection == null) throw new IllegalArgumentException("Theme selection is required");
        preferences.edit()
            .putString(KEY_APP_UI_THEME_ID, selection.getAppColorSchemeId())
            .putString(KEY_EDITOR_SYNTAX_THEME_ID, selection.getEditorSyntaxThemeId())
            .apply();
    }

    private static String resolveAvailableId(
        String requestedId,
        List<LoadedTheme> available,
        LoadedTheme declaredDefault
    ) {
        if (containsId(available, requestedId)) return requestedId;
        if (available == null || available.isEmpty()) return requestedId;
        if (declaredDefault != null
            && containsId(available, declaredDefault.getMetadata().getId())) {
            return declaredDefault.getMetadata().getId();
        }
        return available.get(0).getMetadata().getId();
    }

    private static boolean containsId(List<LoadedTheme> themes, String id) {
        if (themes == null || id == null) return false;
        for (LoadedTheme theme : themes) {
            if (theme != null && id.equals(theme.getMetadata().getId())) return true;
        }
        return false;
    }
}
