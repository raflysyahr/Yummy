package com.termux.app.theme.schema;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Factory and structural helpers for one Yummy-specific UI component property.
 *
 * <p>This class describes data only. It does not resolve VS Code references, apply values to
 * Android Views, or decide fallback precedence; those responsibilities belong to the schema
 * validator/resolver and runtime stages.</p>
 */
public final class YummyComponentProperty {
    public static final String TYPE_COLOR = "color";
    public static final String TYPE_DIMENSION = "dimension";
    public static final String TYPE_SPACING = "spacing";
    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_BOOLEAN = "boolean";
    public static final String TYPE_STRING = "string";
    public static final String TYPE_TYPOGRAPHY = "typography";
    public static final String TYPE_SHAPE = "shape";
    public static final String TYPE_ICON = "icon";
    public static final String TYPE_ANIMATION = "animation";
    public static final String TYPE_ENUM = "enum";
    public static final String TYPE_OBJECT = "object";

    public static final String SOURCE_LITERAL = "literal";
    public static final String SOURCE_VSCODE = "vscode";
    public static final String SOURCE_INHERIT = "inherit";
    public static final String SOURCE_DEFAULT = "default";

    private static final Set<String> SUPPORTED_TYPES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            TYPE_COLOR,
            TYPE_DIMENSION,
            TYPE_SPACING,
            TYPE_NUMBER,
            TYPE_BOOLEAN,
            TYPE_STRING,
            TYPE_TYPOGRAPHY,
            TYPE_SHAPE,
            TYPE_ICON,
            TYPE_ANIMATION,
            TYPE_ENUM,
            TYPE_OBJECT
        ))
    );

    private YummyComponentProperty() {
    }

    public static boolean isSupportedType(String type) {
        return type != null && SUPPORTED_TYPES.contains(type);
    }

    /** Creates a literal value source. Dimension and spacing callers must supply an explicit unit. */
    public static JSONObject literalSource(Object value, String unit) throws JSONException {
        if (!isJsonValue(value)) {
            throw new IllegalArgumentException("Literal value must be a JSON value and cannot be null");
        }
        JSONObject source = new JSONObject();
        source.put("source", SOURCE_LITERAL);
        source.put("value", value);
        if (unit != null) source.put("unit", requireText(unit, "unit"));
        return source;
    }

    /** Creates a reference to an original VS Code theme section and key. */
    public static JSONObject vscodeSource(String section, String key) throws JSONException {
        JSONObject source = new JSONObject();
        source.put("source", SOURCE_VSCODE);
        source.put("section", requireText(section, "section"));
        source.put("key", requireText(key, "key"));
        return source;
    }

    /** Creates explicit inheritance from another Yummy component-property ID. */
    public static JSONObject inheritSource(String propertyId) throws JSONException {
        JSONObject source = new JSONObject();
        source.put("source", SOURCE_INHERIT);
        source.put("from", YummyComponentSchema.requirePropertyId(propertyId));
        return source;
    }

    /** Creates a reference to a stable Yummy built-in default. */
    public static JSONObject defaultSource(String defaultKey) throws JSONException {
        JSONObject source = new JSONObject();
        source.put("source", SOURCE_DEFAULT);
        source.put("key", requireText(defaultKey, "default key"));
        return source;
    }

    /**
     * Creates a typed component-property definition while preserving optional extension fields.
     * The supplied source objects are copied and structurally checked.
     */
    public static JSONObject definition(
        String type,
        JSONObject valueSource,
        JSONObject fallbackSource,
        String description
    ) throws JSONException {
        if (!isSupportedType(type)) {
            throw new IllegalArgumentException("Unsupported Yummy component property type: " + type);
        }
        JSONObject value = copyAndValidateSource(valueSource, false);
        JSONObject fallback = fallbackSource == null
            ? null
            : copyAndValidateSource(fallbackSource, true);

        if (TYPE_DIMENSION.equals(type) || TYPE_SPACING.equals(type)) {
            requireLiteralUnitWhenNeeded(value);
            if (fallback != null) requireLiteralUnitWhenNeeded(fallback);
        }

        JSONObject result = new JSONObject();
        result.put("type", type);
        result.put("value", value);
        if (fallback != null) result.put("fallback", fallback);
        if (description != null) result.put("description", requireText(description, "description"));
        return result;
    }

    public static JSONObject literalDefinition(
        String type,
        Object value,
        String unit,
        String description
    ) throws JSONException {
        return definition(type, literalSource(value, unit), null, description);
    }

    public static JSONObject vscodeDefinition(
        String type,
        String section,
        String key,
        JSONObject fallbackSource,
        String description
    ) throws JSONException {
        return definition(type, vscodeSource(section, key), fallbackSource, description);
    }

    public static JSONObject inheritedDefinition(
        String type,
        String propertyId,
        JSONObject fallbackSource,
        String description
    ) throws JSONException {
        return definition(type, inheritSource(propertyId), fallbackSource, description);
    }

    public static JSONObject defaultDefinition(
        String type,
        String defaultKey,
        String description
    ) throws JSONException {
        return definition(type, defaultSource(defaultKey), null, description);
    }

    /** Validates and returns a defensive copy without discarding unknown extension fields. */
    public static JSONObject copyAndValidateDefinition(JSONObject definition) throws JSONException {
        if (definition == null) {
            throw new IllegalArgumentException("Yummy component property definition is required");
        }
        Object rawType = definition.opt("type");
        if (!(rawType instanceof String)) {
            throw new IllegalArgumentException("Yummy component property type must be a string");
        }
        String type = (String) rawType;
        Object rawValue = definition.opt("value");
        if (!(rawValue instanceof JSONObject)) {
            throw new IllegalArgumentException("Yummy component property value must be a source object");
        }
        JSONObject value = (JSONObject) rawValue;
        Object rawFallback = definition.opt("fallback");
        if (definition.has("fallback") && !(rawFallback instanceof JSONObject)) {
            throw new IllegalArgumentException("Yummy component property fallback must be a source object");
        }
        JSONObject fallback = (JSONObject) rawFallback;
        Object rawDescription = definition.opt("description");
        if (rawDescription != null && !(rawDescription instanceof String)) {
            throw new IllegalArgumentException("Yummy component property description must be a string");
        }
        String description = (String) rawDescription;
        JSONObject checked = definition(
            type,
            value,
            fallback,
            description == null || description.trim().isEmpty() ? null : description
        );
        JSONObject preserved = copyObject(definition);
        preserved.put("type", checked.getString("type"));
        preserved.put("value", checked.getJSONObject("value"));
        if (checked.has("fallback")) preserved.put("fallback", checked.getJSONObject("fallback"));
        else preserved.remove("fallback");
        if (checked.has("description")) preserved.put("description", checked.getString("description"));
        return preserved;
    }

    private static JSONObject copyAndValidateSource(JSONObject source, boolean fallback)
        throws JSONException {
        if (source == null) throw new IllegalArgumentException("Property value source is required");
        JSONObject copy = copyObject(source);
        Object rawKind = copy.opt("source");
        if (!(rawKind instanceof String)) {
            throw new IllegalArgumentException("Property value source must declare a string source");
        }
        String kind = (String) rawKind;
        if (SOURCE_LITERAL.equals(kind)) {
            Object value = copy.opt("value");
            if (!isJsonValue(value)) {
                throw new IllegalArgumentException("Literal source must contain a JSON value");
            }
            if (copy.has("unit")) requireString(copy, "unit", "unit");
        } else if (SOURCE_VSCODE.equals(kind) && !fallback) {
            requireString(copy, "section", "VS Code section");
            requireString(copy, "key", "VS Code key");
        } else if (SOURCE_INHERIT.equals(kind) && !fallback) {
            YummyComponentSchema.requirePropertyId(requireString(copy, "from", "inherited property ID"));
        } else if (SOURCE_DEFAULT.equals(kind)) {
            requireString(copy, "key", "default key");
        } else {
            throw new IllegalArgumentException(
                fallback
                    ? "Fallback source must be literal or default"
                    : "Unknown or unsupported Yummy property value source: " + kind
            );
        }
        return copy;
    }

    private static boolean isJsonValue(Object value) {
        return value != null && value != JSONObject.NULL
            && (value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof JSONObject
                || value instanceof org.json.JSONArray);
    }

    private static void requireLiteralUnitWhenNeeded(JSONObject source) {
        if (SOURCE_LITERAL.equals(source.optString("source", null))) {
            requireString(source, "unit", "literal dimension/spacing unit");
        }
    }

    private static String requireString(JSONObject object, String key, String label) {
        Object value = object.opt(key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(label + " must be a non-empty string");
        }
        return requireText((String) value, label);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must be a non-empty string");
        }
        return value.trim();
    }

    private static JSONObject copyObject(JSONObject value) throws JSONException {
        return new JSONObject(value.toString());
    }
}
