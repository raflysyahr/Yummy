package com.termux.app.theme.loader;

import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.VSCodeColorTheme;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Parses the portable VS Code Color Theme JSON structure used by Yummy.
 * This parser performs runtime structural checks for the Color Theme fields
 * consumed or retained by Yummy. It is not a general JSON Schema engine.
 */
public final class ThemeDocumentParser {
    public static String normalizeJsonc(String source) {
        if (source == null) throw new IllegalArgumentException("JSON source is required");
        String value = source.startsWith("\uFEFF") ? source.substring(1) : source;
        StringBuilder withoutComments = new StringBuilder(value.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (inString) {
                withoutComments.append(current);
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') inString = false;
                continue;
            }
            if (current == '"') {
                inString = true;
                withoutComments.append(current);
                continue;
            }
            if (current == '/' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                if (next == '/') {
                    i += 2;
                    while (i < value.length() && value.charAt(i) != '\n' && value.charAt(i) != '\r') i++;
                    if (i < value.length()) withoutComments.append(value.charAt(i));
                    continue;
                }
                if (next == '*') {
                    i += 2;
                    boolean closed = false;
                    while (i < value.length()) {
                        char commentChar = value.charAt(i);
                        if (commentChar == '\n' || commentChar == '\r') withoutComments.append(commentChar);
                        if (commentChar == '*' && i + 1 < value.length() && value.charAt(i + 1) == '/') {
                            i++;
                            closed = true;
                            break;
                        }
                        i++;
                    }
                    if (!closed) throw new IllegalArgumentException("Unterminated block comment");
                    continue;
                }
            }
            withoutComments.append(current);
        }
        if (inString) throw new IllegalArgumentException("Unterminated JSON string");

        StringBuilder result = new StringBuilder(withoutComments.length());
        inString = false;
        escaped = false;
        for (int i = 0; i < withoutComments.length(); i++) {
            char current = withoutComments.charAt(i);
            if (inString) {
                result.append(current);
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') inString = false;
                continue;
            }
            if (current == '"') {
                inString = true;
                result.append(current);
            } else if (current == ',') {
                int next = i + 1;
                while (next < withoutComments.length()
                    && Character.isWhitespace(withoutComments.charAt(next))) next++;
                if (next >= withoutComments.length()
                    || (withoutComments.charAt(next) != '}' && withoutComments.charAt(next) != ']')) {
                    result.append(current);
                }
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }


    public VSCodeColorTheme parse(String json) throws ThemeLoadException {
        return parse(json, null);
    }

    /** Parses an already-decoded theme object without narrowing its preserved VS Code fields. */
    public VSCodeColorTheme parse(JSONObject source) throws ThemeLoadException {
        return parse(source, null);
    }

    /** Parses an already-decoded theme object against package metadata. */
    public VSCodeColorTheme parse(JSONObject source, ThemeMetadata metadata)
        throws ThemeLoadException {
        if (source == null) throw new ThemeLoadException("Color Theme JSON object is required");
        return parse(source.toString(), metadata);
    }

    public VSCodeColorTheme parse(String json, ThemeMetadata metadata)
        throws ThemeLoadException {
        if (json == null || json.trim().isEmpty()) {
            throw new ThemeLoadException("Color Theme JSON is empty");
        }
        if (json.length() > 2 * 1024 * 1024) {
            throw new ThemeLoadException("Color Theme JSON exceeds the 2 MiB limit");
        }

        try {
            String normalized = stripBom(json);
            JSONObject root = new JSONObject(normalized);
            String embeddedName = optionalString(root, "name");
            String embeddedType = optionalString(root, "type");
            ThemeAppearance embeddedAppearance = embeddedType == null
                ? ThemeAppearance.UNKNOWN
                : ThemeAppearance.fromVscodeValue(embeddedType);

            if (embeddedType != null && embeddedAppearance == ThemeAppearance.UNKNOWN) {
                throw new ThemeLoadException("Unsupported Color Theme type: " + embeddedType);
            }
            if (metadata != null && embeddedAppearance != ThemeAppearance.UNKNOWN
                && embeddedAppearance != metadata.getAppearance()) {
                throw new ThemeLoadException(
                    "Color Theme type does not match metadata type for theme " + metadata.getId()
                );
            }

            validateColors(root.opt("colors"));
            validateTokenColors(root.opt("tokenColors"));
            validateSemanticHighlighting(root.opt("semanticHighlighting"));
            validateSemanticTokenColors(root.opt("semanticTokenColors"));

            String name = notBlank(embeddedName)
                ? embeddedName.trim()
                : (metadata == null ? "Untitled Theme" : metadata.getLabel());
            ThemeAppearance appearance = metadata != null
                ? metadata.getAppearance() : embeddedAppearance;

            return new VSCodeColorTheme(root, name, appearance);
        } catch (JSONException error) {
            throw new ThemeLoadException("Invalid Color Theme JSON: " + error.getMessage(), error);
        }
    }

    private static void validateColors(Object value) throws ThemeLoadException {
        if (value == null) return;
        if (!(value instanceof JSONObject)) {
            throw new ThemeLoadException("'colors' must be a JSON object");
        }
        JSONObject colors = (JSONObject) value;
        Iterator<String> keys = colors.keys();
        while (keys.hasNext()) {
            String identifier = keys.next();
            Object raw = colors.opt(identifier);
            if (raw instanceof String) {
                validateWorkbenchColor((String) raw, "colors." + identifier);
            } else if (raw instanceof JSONArray) {
                // VS Code permits color arrays for certain workbench color identifiers,
                // including symbolIcon.* palettes. Yummy currently consumes scalar colors
                // for its App UI roles, but the complete source document is retained so
                // these arrays remain available for forward-compatible adapters.
                JSONArray palette = (JSONArray) raw;
                if (palette.length() == 0) {
                    throw new ThemeLoadException("Color palette for '" + identifier + "' cannot be empty");
                }
                for (int i = 0; i < palette.length(); i++) {
                    Object item = palette.opt(i);
                    if (!(item instanceof String)) {
                        throw new ThemeLoadException(
                            "Color value for '" + identifier + "[" + i + "]' must be a string");
                    }
                    validateWorkbenchColor((String) item, "colors." + identifier + "[" + i + "]");
                }
            } else {
                throw new ThemeLoadException(
                    "Color value for '" + identifier + "' must be a string or color array");
            }
        }
    }

    private static void validateWorkbenchColor(String raw, String path)
        throws ThemeLoadException {
        String color = raw == null ? "" : raw.trim();
        // Keep VS Code's CSS color strings intact. The Yummy adapter parses the formats it
        // currently renders and uses its configured fallback for values it cannot render.
        if (color.isEmpty()) {
            throw new ThemeLoadException("Color value at " + path + " cannot be empty");
        }
    }

    private static void validateTokenColors(Object value) throws ThemeLoadException {
        if (value == null) return;
        if (value instanceof String) {
            validateRelativeReference((String) value, "tokenColors");
            return;
        }
        if (!(value instanceof JSONArray)) {
            throw new ThemeLoadException("'tokenColors' must be an array or a relative .tmTheme path");
        }

        JSONArray rules = (JSONArray) value;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) {
                throw new ThemeLoadException("tokenColors[" + i + "] must be an object");
            }
            // Preserve additional VS Code/TextMate rule metadata. Yummy only interprets
            // name, scope, and settings; rejecting an unfamiliar property would make an
            // otherwise valid upstream theme impossible to import.
            Object ruleName = rule.opt("name");
            if (ruleName != null && !(ruleName instanceof String)) {
                throw new ThemeLoadException("tokenColors[" + i + "].name must be a string");
            }
            Object scope = rule.opt("scope");
            if (scope != null && !(scope instanceof String) && !(scope instanceof JSONArray)) {
                throw new ThemeLoadException("tokenColors[" + i + "].scope must be a string or array");
            }
            if (scope instanceof JSONArray) {
                JSONArray scopes = (JSONArray) scope;
                for (int j = 0; j < scopes.length(); j++) {
                    if (!(scopes.opt(j) instanceof String)) {
                        throw new ThemeLoadException(
                            "tokenColors[" + i + "].scope[" + j + "] must be a string"
                        );
                    }
                }
            }
            JSONObject settings = rule.optJSONObject("settings");
            if (settings == null) {
                throw new ThemeLoadException("tokenColors[" + i + "].settings must be an object");
            }
            validateTokenSettings(settings, "tokenColors[" + i + "].settings");
        }
    }

    private static void validateTokenSettings(JSONObject settings, String path)
        throws ThemeLoadException {
        Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = settings.opt(key);
            switch (key) {
                case "foreground":
                    requireColorString(value, path + ".foreground");
                    break;
                case "background":
                case "fontFamily":
                    if (!(value instanceof String)) {
                        throw new ThemeLoadException(path + "." + key + " must be a string");
                    }
                    break;
                case "fontStyle":
                    if (!(value instanceof String)) {
                        throw new ThemeLoadException(path + ".fontStyle must be a string");
                    }
                    // Preserve VS Code/TextMate fontStyle values. The downstream Sora TextMate
                    // theme engine interprets supported style flags; aliases such as "normal"
                    // and "regular" must not make an otherwise valid theme fail to import.
                    break;
                case "fontSize":
                case "lineHeight":
                    if (!(value instanceof Number)) {
                        throw new ThemeLoadException(path + "." + key + " must be a number");
                    }
                    break;
                case "content":
                    if (!(value instanceof String)) {
                        throw new ThemeLoadException(path + ".content must be a string");
                    }
                    break;
                default:
                    // VS Code's TextMate theme schema can evolve independently of Sora.
                    // Keep unknown settings in VSCodeColorTheme's retained source JSON and
                    // ignore them in the downstream Sora adapter instead of rejecting import.
                    break;
            }
        }
    }

    private static void validateSemanticHighlighting(Object value) throws ThemeLoadException {
        if (value != null && !(value instanceof Boolean)) {
            throw new ThemeLoadException("'semanticHighlighting' must be a boolean");
        }
    }

    private static void validateSemanticTokenColors(Object value) throws ThemeLoadException {
        if (value == null) return;
        if (!(value instanceof JSONObject)) {
            throw new ThemeLoadException("'semanticTokenColors' must be a JSON object");
        }
        JSONObject selectors = (JSONObject) value;
        Iterator<String> keys = selectors.keys();
        while (keys.hasNext()) {
            String selector = keys.next();
            if (selector == null || selector.trim().isEmpty()) {
                throw new ThemeLoadException("Semantic token selector cannot be empty");
            }
            // Keep future or extension-specific selectors intact. Yummy currently retains
            // semanticTokenColors but does not translate them into Sora TextMate rules.
            Object style = selectors.opt(selector);
            if (style instanceof String) {
                requireColorString(style, "semanticTokenColors." + selector);
            } else if (style instanceof JSONObject) {
                validateSemanticStyle((JSONObject) style, "semanticTokenColors." + selector);
            } else {
                throw new ThemeLoadException(
                    "Semantic token style for '" + selector + "' must be a color or object"
                );
            }
        }
    }

    private static void validateSemanticStyle(JSONObject style, String path)
        throws ThemeLoadException {
        Iterator<String> keys = style.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = style.opt(key);
            switch (key) {
                case "foreground":
                    requireColorString(value, path + ".foreground");
                    break;
                case "background":
                    if (!(value instanceof String)) {
                        throw new ThemeLoadException(path + ".background must be a string");
                    }
                    break;
                case "fontStyle":
                    if (!(value instanceof String)) {
                        throw new ThemeLoadException(path + ".fontStyle must be a string");
                    }
                    // Semantic styles are retained for forward compatibility; keep their
                    // fontStyle strings instead of applying a narrower Yummy-only whitelist.
                    break;
                case "bold":
                case "italic":
                case "underline":
                case "strikethrough":
                    if (!(value instanceof Boolean)) {
                        throw new ThemeLoadException(path + "." + key + " must be a boolean");
                    }
                    break;
                default:
                    // Kept extensible to avoid rejecting future VS Code semantic style fields.
                    break;
            }
        }
    }

    private static void requireColorString(Object value, String path) throws ThemeLoadException {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new ThemeLoadException("Expected a non-empty VS Code color string at " + path);
        }
    }

    private static String optionalString(JSONObject object, String key) throws ThemeLoadException {
        Object value = object.opt(key);
        if (value == null) return null;
        if (!(value instanceof String)) {
            throw new ThemeLoadException("'" + key + "' must be a string");
        }
        return (String) value;
    }

    private static void validateRelativeReference(String value, String field)
        throws ThemeLoadException {
        String path = value == null ? "" : value.trim().replace('\\', '/');
        if (path.isEmpty() || path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new ThemeLoadException("'" + field + "' must be a non-empty relative path");
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            // VS Code theme packages commonly prefix local references with "./".
            if (segment.isEmpty() || "..".equals(segment)) {
                throw new ThemeLoadException("'" + field + "' contains an unsafe path segment");
            }
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String stripBom(String value) {
        return value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }
}
