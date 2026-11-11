package com.termux.app.theme.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Parsed VS Code Color Theme document.
 *
 * The source JSON is retained so that fields not yet interpreted by Yummy remain
 * available to future adapters. Accessors return defensive JSON copies.
 */
public final class VSCodeColorTheme {
    private final JSONObject source;
    private final String name;
    private final ThemeAppearance appearance;

    public VSCodeColorTheme(JSONObject source, String name, ThemeAppearance appearance) {
        this.source = copyObject(source);
        this.name = name;
        this.appearance = appearance == null ? ThemeAppearance.UNKNOWN : appearance;
    }

    public String getName() {
        return name;
    }

    public ThemeAppearance getAppearance() {
        return appearance;
    }

    public boolean hasColors() {
        JSONObject colors = source.optJSONObject("colors");
        return colors != null && colors.length() > 0;
    }

    public boolean hasTokenColors() {
        Object value = source.opt("tokenColors");
        if (value instanceof JSONArray) return ((JSONArray) value).length() > 0;
        return value instanceof String && !((String) value).trim().isEmpty();
    }

    public boolean hasSemanticTokenColors() {
        JSONObject colors = source.optJSONObject("semanticTokenColors");
        return colors != null && colors.length() > 0;
    }

    /** Returns a copy of the VS Code workbench color map. */
    public JSONObject getColors() {
        JSONObject colors = source.optJSONObject("colors");
        return colors == null ? new JSONObject() : copyObject(colors);
    }

    /** Returns a single VS Code workbench color, or null when it is absent. */
    public String getColor(String identifier) {
        if (identifier == null) return null;
        JSONObject colors = source.optJSONObject("colors");
        return colors == null ? null : colors.optString(identifier, null);
    }

    /**
     * Returns TextMate token rules when {@code tokenColors} is an array.
     * Returns null when the source uses a referenced .tmTheme file instead.
     */
    public JSONArray getTokenColors() {
        Object value = source.opt("tokenColors");
        return value instanceof JSONArray ? copyArray((JSONArray) value) : null;
    }

    /** Returns the referenced token theme path when {@code tokenColors} is a string. */
    public String getTokenColorsReference() {
        Object value = source.opt("tokenColors");
        return value instanceof String ? (String) value : null;
    }

    /** Returns null when semanticHighlighting was not declared. */
    public Boolean getSemanticHighlighting() {
        Object value = source.opt("semanticHighlighting");
        return value instanceof Boolean ? (Boolean) value : null;
    }

    /** Returns a copy of semantic token selectors and styles. */
    public JSONObject getSemanticTokenColors() {
        JSONObject colors = source.optJSONObject("semanticTokenColors");
        return colors == null ? new JSONObject() : copyObject(colors);
    }

    /** Returns a defensive copy of the complete parsed theme JSON. */
    public JSONObject toJson() {
        return copyObject(source);
    }

    private static JSONObject copyObject(JSONObject value) {
        try {
            return new JSONObject(value.toString());
        } catch (JSONException error) {
            // The source object was already parsed successfully; this is defensive fallback.
            return new JSONObject();
        }
    }

    private static JSONArray copyArray(JSONArray value) {
        try {
            return new JSONArray(value.toString());
        } catch (JSONException error) {
            return new JSONArray();
        }
    }
}
