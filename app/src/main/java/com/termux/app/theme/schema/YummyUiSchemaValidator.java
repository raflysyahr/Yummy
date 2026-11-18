package com.termux.app.theme.schema;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the UI Schema wrapper and Yummy component definitions without modifying the source.
 *
 * <p>Unknown VS Code fields and extension fields are deliberately retained. Validation is strict
 * for the fields Yummy consumes, while references to future VS Code color identifiers remain
 * legal and are reported as unresolved only by the resolver.</p>
 */
public final class YummyUiSchemaValidator {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public YummyUiSchemaValidationResult validate(JSONObject document) {
        List<YummyUiSchemaDiagnostic> errors = new ArrayList<>();
        List<YummyUiSchemaDiagnostic> warnings = new ArrayList<>();
        if (document == null) {
            add(errors, "document_required", null, "$", "UI Schema document is required");
            return new YummyUiSchemaValidationResult(errors, warnings);
        }

        Object version = document.opt("schemaVersion");
        if (!(version instanceof Number)
            || ((Number) version).doubleValue() != SUPPORTED_SCHEMA_VERSION) {
            add(errors, "schema_version_invalid", null, "schemaVersion",
                "schemaVersion must be " + SUPPORTED_SCHEMA_VERSION);
        }

        JSONObject metadata = document.optJSONObject("metadata");
        if (metadata == null) {
            add(errors, "metadata_required", null, "metadata", "metadata must be an object");
        } else {
            requireString(metadata, "id", "metadata.id", errors);
            requireString(metadata, "name", "metadata.name", errors);
            JSONObject source = metadata.optJSONObject("source");
            if (source == null) {
                add(errors, "metadata_source_required", null, "metadata.source",
                    "metadata.source must be an object");
            } else {
                requireString(source, "kind", "metadata.source.kind", errors);
            }
        }

        JSONObject vscode = document.optJSONObject("vscode");
        if (vscode == null) {
            add(errors, "vscode_required", null, "vscode", "vscode must be an object");
        } else if (vscode.optJSONObject("colorTheme") == null) {
            add(errors, "vscode_color_theme_required", null, "vscode.colorTheme",
                "vscode.colorTheme must contain the complete VS Code color theme object");
        }

        JSONObject yummy = document.optJSONObject("yummy");
        JSONObject components = yummy == null ? null : yummy.optJSONObject("components");
        if (components == null) {
            add(errors, "yummy_components_required", null, "yummy.components",
                "yummy.components must be an object");
            return new YummyUiSchemaValidationResult(errors, warnings);
        }

        Map<String, String> inheritance = new HashMap<>();
        Map<String, String> propertyTypes = new HashMap<>();
        Iterator<String> ids = components.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            String path = "yummy.components." + id;
            try {
                YummyComponentSchema.requirePropertyId(id);
            } catch (IllegalArgumentException error) {
                add(errors, "property_id_invalid", id, path, safeMessage(error));
                continue;
            }

            JSONObject definition = components.optJSONObject(id);
            if (definition == null) {
                add(errors, "property_definition_invalid", id, path,
                    "Property definition must be an object");
                continue;
            }
            try {
                JSONObject checked = YummyComponentProperty.copyAndValidateDefinition(definition);
                String type = checked.getString("type");
                propertyTypes.put(id, type);
                validateLiteral(type, checked.optJSONObject("value"), id, path + ".value", errors);
                JSONObject fallback = checked.optJSONObject("fallback");
                if (fallback != null) {
                    validateLiteral(type, fallback, id, path + ".fallback", errors);
                }
                JSONObject value = checked.getJSONObject("value");
                if (YummyComponentProperty.SOURCE_INHERIT.equals(value.optString("source"))) {
                    inheritance.put(id, value.optString("from"));
                }
            } catch (JSONException | IllegalArgumentException error) {
                add(errors, "property_definition_invalid", id, path, safeMessage(error));
            }
        }

        for (Map.Entry<String, String> entry : inheritance.entrySet()) {
            String id = entry.getKey();
            String parent = entry.getValue();
            if (!propertyTypes.containsKey(parent)) {
                add(errors, "inherit_target_missing", id, "yummy.components." + id + ".value.from",
                    "Inherited property '" + parent + "' does not exist");
            } else if (!propertyTypes.get(id).equals(propertyTypes.get(parent))) {
                add(errors, "inherit_type_mismatch", id, "yummy.components." + id + ".value.from",
                    "Inherited property '" + parent + "' has a different type");
            }
        }
        detectInheritanceCycles(inheritance, errors);

        return new YummyUiSchemaValidationResult(errors, warnings);
    }

    private static void validateLiteral(
        String type, JSONObject source, String propertyId, String path,
        List<YummyUiSchemaDiagnostic> errors
    ) {
        if (source == null || !YummyComponentProperty.SOURCE_LITERAL.equals(source.optString("source"))) {
            return;
        }
        Object value = source.opt("value");
        boolean valid = true;
        if (YummyComponentProperty.TYPE_COLOR.equals(type)
            || YummyComponentProperty.TYPE_STRING.equals(type)
            || YummyComponentProperty.TYPE_ENUM.equals(type)
            || YummyComponentProperty.TYPE_ICON.equals(type)) {
            valid = value instanceof String;
        } else if (YummyComponentProperty.TYPE_BOOLEAN.equals(type)) {
            valid = value instanceof Boolean;
        } else if (YummyComponentProperty.TYPE_NUMBER.equals(type)
            || YummyComponentProperty.TYPE_DIMENSION.equals(type)
            || YummyComponentProperty.TYPE_SPACING.equals(type)) {
            valid = value instanceof Number;
        } else if (YummyComponentProperty.TYPE_OBJECT.equals(type)
            || YummyComponentProperty.TYPE_TYPOGRAPHY.equals(type)
            || YummyComponentProperty.TYPE_SHAPE.equals(type)
            || YummyComponentProperty.TYPE_ANIMATION.equals(type)) {
            valid = value instanceof JSONObject || value instanceof org.json.JSONArray
                || value instanceof String;
        }
        if (!valid) {
            add(errors, "literal_type_mismatch", propertyId, path,
                "Literal value does not match declared type '" + type + "'");
        }
    }

    private static void detectInheritanceCycles(
        Map<String, String> edges, List<YummyUiSchemaDiagnostic> errors
    ) {
        Set<String> completed = new HashSet<>();
        Set<String> reported = new HashSet<>();
        for (String start : edges.keySet()) {
            Set<String> path = new HashSet<>();
            String current = start;
            while (current != null && edges.containsKey(current) && !completed.contains(current)) {
                if (!path.add(current)) {
                    if (reported.add(current)) {
                        add(errors, "inheritance_cycle", current,
                            "yummy.components." + current + ".value.from",
                            "Yummy component-property inheritance contains a cycle");
                    }
                    break;
                }
                current = edges.get(current);
            }
            completed.addAll(path);
        }
    }

    private static void requireString(
        JSONObject object, String key, String path, List<YummyUiSchemaDiagnostic> errors
    ) {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            add(errors, "required_string_missing", null, path,
                path + " must be a non-empty string");
        }
    }

    private static void add(
        List<YummyUiSchemaDiagnostic> target, String code, String propertyId,
        String path, String message
    ) {
        target.add(new YummyUiSchemaDiagnostic(
            code, YummyUiSchemaDiagnostic.SEVERITY_ERROR, propertyId, path, message));
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName() : message;
    }
}
