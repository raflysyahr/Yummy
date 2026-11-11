package com.termux.app.theme.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Immutable editor-syntax representation of a VS Code Color Theme.
 *
 * <p>The inline {@code tokenColors} array is the part that can be passed to Sora's
 * TextMate theme loader. A referenced {@code .tmTheme} file is retained as a safe,
 * package-relative path and must be opened by the package resource layer before it
 * can be loaded by Sora.</p>
 *
 * <p>VS Code semantic token settings are retained separately. Sora's TextMate
 * renderer consumes TextMate scopes; retaining semantic selectors here does not
 * imply that semantic-token production or rendering is enabled.</p>
 */
public final class ResolvedEditorTheme {
    private static final String REGISTRY_PREFIX = "yummy-theme:";

    private final String themeId;
    private final String label;
    private final ThemeAppearance appearance;
    private final JSONArray tokenColors;
    private final String tokenColorsReference;
    private final Boolean semanticHighlighting;
    private final JSONObject semanticTokenColors;

    public ResolvedEditorTheme(
        String themeId,
        String label,
        ThemeAppearance appearance,
        JSONArray tokenColors,
        String tokenColorsReference,
        Boolean semanticHighlighting,
        JSONObject semanticTokenColors
    ) {
        if (themeId == null || themeId.trim().isEmpty()) {
            throw new IllegalArgumentException("Theme ID is required");
        }
        if (label == null || label.trim().isEmpty()) {
            throw new IllegalArgumentException("Theme label is required");
        }
        if (tokenColors != null && tokenColorsReference != null) {
            throw new IllegalArgumentException(
                "A resolved editor theme cannot contain inline token rules and a reference together");
        }
        if (tokenColors == null && (tokenColorsReference == null
            || tokenColorsReference.trim().isEmpty())) {
            throw new IllegalArgumentException("TextMate token rules or a theme reference are required");
        }

        this.themeId = themeId;
        this.label = label;
        this.appearance = appearance == null ? ThemeAppearance.UNKNOWN : appearance;
        this.tokenColors = copyArray(tokenColors);
        this.tokenColorsReference = tokenColorsReference;
        this.semanticHighlighting = semanticHighlighting;
        this.semanticTokenColors = copyObject(semanticTokenColors);
    }

    public String getThemeId() {
        return themeId;
    }

    public String getLabel() {
        return label;
    }

    public ThemeAppearance getAppearance() {
        return appearance;
    }

    /** Stable registry key, namespaced to avoid collisions with other TextMate themes. */
    public String getRegistryName() {
        return REGISTRY_PREFIX + themeId;
    }

    public boolean isDarkAppearance() {
        return appearance == ThemeAppearance.DARK
            || appearance == ThemeAppearance.HIGH_CONTRAST_DARK;
    }

    public boolean isLightAppearance() {
        return appearance == ThemeAppearance.LIGHT
            || appearance == ThemeAppearance.HIGH_CONTRAST_LIGHT;
    }

    public boolean hasInlineTokenColors() {
        return tokenColors != null;
    }

    /** Returns a defensive copy, or null when the package references an external .tmTheme file. */
    public JSONArray getTokenColors() {
        return copyArray(tokenColors);
    }

    /** Returns the package-relative token theme reference, or null for inline token rules. */
    public String getTokenColorsReference() {
        return tokenColorsReference;
    }

    /** Returns null when the source theme did not declare semanticHighlighting. */
    public Boolean getSemanticHighlighting() {
        return semanticHighlighting;
    }

    public boolean isSemanticHighlightingEnabled() {
        return Boolean.TRUE.equals(semanticHighlighting);
    }

    /** Returns a defensive copy of VS Code semantic selectors and styles. */
    public JSONObject getSemanticTokenColors() {
        return copyObject(semanticTokenColors);
    }

    public boolean hasSemanticTokenColors() {
        return semanticTokenColors.length() > 0;
    }

    /**
     * Builds the syntax-only JSON document consumed by Sora's TextMate ThemeRegistry.
     * Workbench {@code colors} and semantic token selectors are deliberately excluded.
     *
     * @throws IllegalStateException when tokenColors points to a package resource instead
     *                                of containing inline TextMate rules
     */
    public JSONObject toTextMateThemeDocument() {
        if (tokenColors == null) {
            throw new IllegalStateException(
                "Theme " + themeId + " references an external token theme: "
                    + tokenColorsReference);
        }
        JSONObject document = new JSONObject();
        try {
            document.put("name", getRegistryName());
            if (appearance != ThemeAppearance.UNKNOWN) {
                document.put("type", appearance.getVscodeValue());
            }
            document.put("tokenColors", copyArray(tokenColors));
        } catch (JSONException error) {
            // The values above are already validated JSON values; this is defensive only.
            throw new IllegalStateException("Unable to build TextMate theme document", error);
        }
        return document;
    }

    /** Returns the syntax-only TextMate document as strict JSON. */
    public String toTextMateThemeJson() {
        return toTextMateThemeDocument().toString();
    }

    private static JSONArray copyArray(JSONArray value) {
        if (value == null) return null;
        try {
            return new JSONArray(value.toString());
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid tokenColors array", error);
        }
    }

    private static JSONObject copyObject(JSONObject value) {
        if (value == null) return new JSONObject();
        try {
            return new JSONObject(value.toString());
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid semanticTokenColors object", error);
        }
    }
}
