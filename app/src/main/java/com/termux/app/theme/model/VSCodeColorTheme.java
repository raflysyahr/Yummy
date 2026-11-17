package com.termux.app.theme.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Parsed VS Code Color Theme document.
 *
 * The source JSON is retained so that fields not yet interpreted by Yummy remain
 * available to future adapters. Accessors return defensive JSON copies.
 */
public final class VSCodeColorTheme {
    private static final Set<String> STANDARD_FIELDS = new HashSet<>(Arrays.asList(
        "name", "type", "colors", "tokenColors", "semanticHighlighting", "semanticTokenColors"
    ));
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
        Object value = getColorValue(identifier);
        return value instanceof String ? (String) value : null;
    }

    /**
     * Returns the original JSON value for a workbench color identifier.
     *
     * <p>Most workbench colors are strings. Some VS Code color identifiers can use arrays,
     * such as symbol icon color palettes. Callers that only render scalar colors should use
     * {@link #getColor(String)}; future adapters can inspect the full value through this method.</p>
     */
    public Object getColorValue(String identifier) {
        if (identifier == null) return null;
        JSONObject colors = source.optJSONObject("colors");
        return colors == null ? null : copyValue(colors.opt(identifier));
    }

    /**
     * Returns a defensive copy of any top-level field from the original VS Code theme.
     * Unknown and future VS Code fields are intentionally available through this method.
     */
    public Object getRawSection(String sectionName) {
        if (sectionName == null) return null;
        return copyValue(source.opt(sectionName));
    }

    /**
     * Returns all top-level fields not currently part of the core VS Code Color Theme format.
     * Nothing is removed from {@link #toJson()}; this is a convenience view for future adapters.
     */
    public JSONObject getAdditionalProperties() {
        JSONObject additional = copyObject(source);
        Iterator<String> keys = additional.keys();
        java.util.List<String> remove = new java.util.ArrayList<>();
        while (keys.hasNext()) {
            String key = keys.next();
            if (STANDARD_FIELDS.contains(key)) remove.add(key);
        }
        for (String key : remove) additional.remove(key);
        return additional;
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

    private static Object copyValue(Object value) {
        if (value instanceof JSONObject) return copyObject((JSONObject) value);
        if (value instanceof JSONArray) return copyArray((JSONArray) value);
        return value;
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
