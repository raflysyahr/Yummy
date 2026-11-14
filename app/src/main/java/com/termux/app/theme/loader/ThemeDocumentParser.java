package com.termux.app.theme.loader;

import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.VSCodeColorTheme;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.regex.Pattern;

/**
 * Parses the portable VS Code Color Theme JSON structure used by Yummy.
 * This parser performs runtime structural checks for the Color Theme fields
 * consumed or retained by Yummy. It is not a general JSON Schema engine.
 */
public final class ThemeDocumentParser {
    private static final Pattern HEX_COLOR = Pattern.compile(
        "^#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$"
    );
    private static final Pattern SEMANTIC_SELECTOR = Pattern.compile(
        "^(?:\\w+[-_\\w+]*|\\*)(?:\\.\\w+[-_\\w+]*)*(?::\\w+[-_\\w+]*)?$"
    );

    public VSCodeColorTheme parse(String json) throws ThemeLoadException {
        return parse(json, null);
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
            if (!(raw instanceof String)) {
                throw new ThemeLoadException("Color value for '" + identifier + "' must be a string");
            }
            String color = ((String) raw).trim();
            if (!"default".equals(color) && !isHexColor(color)) {
                throw new ThemeLoadException("Invalid color value for '" + identifier + "': " + color);
            }
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
            java.util.Iterator<String> ruleKeys = rule.keys();
            while (ruleKeys.hasNext()) {
                String key = ruleKeys.next();
                if (!"name".equals(key) && !"scope".equals(key) && !"settings".equals(key)) {
                    throw new ThemeLoadException(
                        "tokenColors[" + i + "] contains unsupported property: " + key
                    );
                }
            }
            Object name = rule.opt("name");
            if (name != null && !(name instanceof String)) {
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
                    requireHexColor(value, path + ".foreground");
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
                default:
                    throw new ThemeLoadException(path + " contains unsupported property: " + key);
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
            if (!SEMANTIC_SELECTOR.matcher(selector).matches()) {
                throw new ThemeLoadException("Invalid semantic token selector: " + selector);
            }
            Object style = selectors.opt(selector);
            if (style instanceof String) {
                requireHexColor(style, "semanticTokenColors." + selector);
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
                    requireHexColor(value, path + ".foreground");
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

    private static void requireHexColor(Object value, String path) throws ThemeLoadException {
        if (!(value instanceof String) || !isHexColor((String) value)) {
            throw new ThemeLoadException("Expected a hexadecimal color at " + path);
        }
    }

    private static boolean isHexColor(String value) {
        return value != null && HEX_COLOR.matcher(value.trim()).matches();
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
