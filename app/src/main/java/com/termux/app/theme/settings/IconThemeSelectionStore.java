package com.termux.app.theme.settings;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.regex.Pattern;

/** Persists the File Icon Theme independently from App UI, Editor UI, and Editor Syntax selections. */
public final class IconThemeSelectionStore {
    public static final String PREFERENCES_NAME = "yummy_icon_theme_selection";
    public static final String KEY_ICON_THEME_ID = "icon_theme_id";
    public static final String DEFAULT_ICON_THEME_ID = "yummy-default";
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private final SharedPreferences preferences;

    public IconThemeSelectionStore(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        Context app = context.getApplicationContext();
        preferences = (app == null ? context : app).getSharedPreferences(
            PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public String read() {
        String id = preferences.getString(KEY_ICON_THEME_ID, DEFAULT_ICON_THEME_ID);
        return isValidId(id) ? id.trim() : DEFAULT_ICON_THEME_ID;
    }

    public void select(String id) {
        if (!isValidId(id)) throw new IllegalArgumentException("Invalid Icon Theme ID");
        preferences.edit().putString(KEY_ICON_THEME_ID, id.trim()).apply();
    }

    public void clear() {
        preferences.edit().putString(KEY_ICON_THEME_ID, DEFAULT_ICON_THEME_ID).apply();
    }

    public static boolean isValidId(String id) {
        return id != null && VALID_ID.matcher(id.trim()).matches();
    }
}
