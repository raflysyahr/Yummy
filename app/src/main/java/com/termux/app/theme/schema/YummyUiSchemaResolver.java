package com.termux.app.theme.schema;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves Yummy component values from literals, preserved VS Code data, explicit inheritance,
 * and code-owned defaults. A declared fallback is used only when the primary source is unavailable.
 */
public final class YummyUiSchemaResolver {
    private final YummyUiSchemaValidator validator;

    public YummyUiSchemaResolver() {
        this(new YummyUiSchemaValidator());
    }

    public YummyUiSchemaResolver(YummyUiSchemaValidator validator) {
        if (validator == null) throw new IllegalArgumentException("Schema validator is required");
        this.validator = validator;
    }

    public ResolutionResult resolve(
        JSONObject document, Map<String, DefaultValue> defaults
    ) {
        List<YummyUiSchemaDiagnostic> diagnostics = new ArrayList<>();
        Map<String, ResolvedValue> resolved = new LinkedHashMap<>();
        if (document == null) {
            diagnostics.add(diagnostic("document_required", null, "$",
                "UI Schema document is required", true));
            return new ResolutionResult(false, resolved, diagnostics);
        }

        YummyUiSchemaValidationResult validation = validator.validate(document);
        diagnostics.addAll(validation.getDiagnostics());
        if (!validation.isValid()) {
            return new ResolutionResult(false, resolved, diagnostics);
        }

        JSONObject yummy = document.optJSONObject("yummy");
        JSONObject components = yummy == null ? null : yummy.optJSONObject("components");
        JSONObject vscode = document.optJSONObject("vscode");
        Map<String, DefaultValue> safeDefaults = defaults == null
            ? Collections.emptyMap() : defaults;
        Set<String> resolving = new HashSet<>();
        java.util.Iterator<String> propertyIds = components.keys();
        while (propertyIds.hasNext()) {
            String propertyId = propertyIds.next();
            resolveProperty(propertyId, components, vscode, safeDefaults,
                resolved, resolving, diagnostics);
        }
        return new ResolutionResult(true, resolved, diagnostics);
    }

    private ResolvedValue resolveProperty(
        String propertyId,
        JSONObject components,
        JSONObject vscode,
        Map<String, DefaultValue> defaults,
        Map<String, ResolvedValue> resolved,
        Set<String> resolving,
        List<YummyUiSchemaDiagnostic> diagnostics
    ) {
        if (resolved.containsKey(propertyId)) return resolved.get(propertyId);
        if (!resolving.add(propertyId)) {
            diagnostics.add(diagnostic("inheritance_cycle", propertyId,
                "yummy.components." + propertyId, "Inheritance cycle encountered during resolution", true));
            return null;
        }

        JSONObject definition = components.optJSONObject(propertyId);
        if (definition == null) {
            resolving.remove(propertyId);
            return null;
        }
        String type = definition.optString("type", null);
        JSONObject primary = definition.optJSONObject("value");
        ResolvedValue value = resolveSource(primary, type, propertyId, components, vscode,
            defaults, resolved, resolving, diagnostics);

        if (value == null) {
            JSONObject fallback = definition.optJSONObject("fallback");
            if (fallback != null) {
                value = resolveSource(fallback, type, propertyId, components, vscode,
                    defaults, resolved, resolving, diagnostics);
            }
        }
        resolving.remove(propertyId);
        if (value != null) resolved.put(propertyId, value);
        else diagnostics.add(diagnostic("property_unresolved", propertyId,
            "yummy.components." + propertyId,
            "No value could be resolved from the primary source or fallback", false));
        return value;
    }

    private ResolvedValue resolveSource(
        JSONObject source,
        String type,
        String propertyId,
        JSONObject components,
        JSONObject vscode,
        Map<String, DefaultValue> defaults,
        Map<String, ResolvedValue> resolved,
        Set<String> resolving,
        List<YummyUiSchemaDiagnostic> diagnostics
    ) {
        if (source == null) return null;
        String kind = source.optString("source", "");
        if (YummyComponentProperty.SOURCE_LITERAL.equals(kind)) {
            Object literal = source.opt("value");
            if (!matchesType(type, literal)) {
                diagnostics.add(diagnostic("source_type_mismatch", propertyId,
                    "yummy.components." + propertyId + ".value",
                    "Literal source does not match declared type '" + type + "'", false));
                return null;
            }
            return new ResolvedValue(type, literal, source.optString("unit", null), kind);
        }
        if (YummyComponentProperty.SOURCE_DEFAULT.equals(kind)) {
            String key = source.optString("key", "");
            DefaultValue value = defaults.get(key);
            if (value == null) return null;
            Object raw = value.getValue();
            if (!matchesType(type, raw)) {
                diagnostics.add(diagnostic("default_type_mismatch", propertyId,
                    "yummy.components." + propertyId + ".value",
                    "Built-in default '" + key + "' does not match declared type '" + type + "'", false));
                return null;
            }
            return new ResolvedValue(type, raw, value.getUnit(), kind);
        }
        if (YummyComponentProperty.SOURCE_VSCODE.equals(kind)) {
            Object value = readVscodeValue(vscode, source.optString("section", ""),
                source.optString("key", ""));
            if (value == null || value == JSONObject.NULL) return null;
            if (!matchesType(type, value)) {
                diagnostics.add(diagnostic("vscode_type_mismatch", propertyId,
                    "yummy.components." + propertyId + ".value",
                    "VS Code source does not match declared type '" + type + "'", false));
                return null;
            }
            return new ResolvedValue(type, value, null, kind);
        }
        if (YummyComponentProperty.SOURCE_INHERIT.equals(kind)) {
            String parent = source.optString("from", "");
            ResolvedValue inherited = resolveProperty(parent, components, vscode, defaults,
                resolved, resolving, diagnostics);
            if (inherited == null || !type.equals(inherited.getType())) return null;
            return new ResolvedValue(type, inherited.getValue(), inherited.getUnit(),
                "inherit:" + parent);
        }
        return null;
    }

    private static Object readVscodeValue(JSONObject vscode, String section, String key) {
        if (vscode == null || section == null || section.trim().isEmpty()
            || key == null || key.trim().isEmpty()) return null;

        String rootPath;
        switch (section) {
            case "colors":
            case "tokenColors":
            case "semanticTokenColors":
                rootPath = "colorTheme." + section;
                break;
            case "configuration":
            case "extension":
                rootPath = section;
                break;
            default:
                rootPath = section;
                break;
        }
        Object root = readPath(vscode, rootPath);
        if (root instanceof JSONObject) {
            Object exactKey = ((JSONObject) root).opt(key);
            if (exactKey != null && exactKey != JSONObject.NULL) return exactKey;
        }
        if (!(root instanceof JSONObject) && !(root instanceof org.json.JSONArray)) return null;
        return readPath(root, key);
    }

    private static Object readPath(Object root, String path) {
        if (root == null || path == null || path.trim().isEmpty()) return null;
        Object current = root;
        String[] parts = path.split("\\.");
        for (String part : parts) {
            if (current instanceof JSONObject) {
                current = ((JSONObject) current).opt(part);
            } else if (current instanceof org.json.JSONArray) {
                try {
                    int index = Integer.parseInt(part);
                    org.json.JSONArray array = (org.json.JSONArray) current;
                    if (index < 0 || index >= array.length()) return null;
                    current = array.opt(index);
                } catch (NumberFormatException error) {
                    return null;
                }
            } else {
                return null;
            }
            if (current == null || current == JSONObject.NULL) return null;
        }
        return current;
    }

    private static boolean matchesType(String type, Object value) {
        if (value == null || value == JSONObject.NULL) return false;
        if (YummyComponentProperty.TYPE_COLOR.equals(type)
            || YummyComponentProperty.TYPE_STRING.equals(type)
            || YummyComponentProperty.TYPE_ENUM.equals(type)
            || YummyComponentProperty.TYPE_ICON.equals(type)) {
            return value instanceof String;
        }
        if (YummyComponentProperty.TYPE_BOOLEAN.equals(type)) return value instanceof Boolean;
        if (YummyComponentProperty.TYPE_NUMBER.equals(type)
            || YummyComponentProperty.TYPE_DIMENSION.equals(type)
            || YummyComponentProperty.TYPE_SPACING.equals(type)) return value instanceof Number;
        return value instanceof JSONObject || value instanceof org.json.JSONArray
            || value instanceof String;
    }

    private static YummyUiSchemaDiagnostic diagnostic(
        String code, String propertyId, String path, String message, boolean error
    ) {
        return new YummyUiSchemaDiagnostic(
            code,
            error ? YummyUiSchemaDiagnostic.SEVERITY_ERROR : YummyUiSchemaDiagnostic.SEVERITY_WARNING,
            propertyId, path, message);
    }

    /** Immutable code-owned fallback value. */
    public static final class DefaultValue {
        private final Object value;
        private final String unit;

        private DefaultValue(Object value, String unit) {
            if (value == null || value == JSONObject.NULL) {
                throw new IllegalArgumentException("Default value must not be null");
            }
            this.value = defensiveCopy(value);
            this.unit = unit;
        }

        public static DefaultValue of(Object value, String unit) {
            return new DefaultValue(value, unit);
        }

        public Object getValue() { return defensiveCopy(value); }
        public String getUnit() { return unit; }
    }

    /** Immutable typed value ready for a registered component adapter. */
    public static final class ResolvedValue {
        private final String type;
        private final Object value;
        private final String unit;
        private final String source;

        public ResolvedValue(String type, Object value, String unit, String source) {
            this.type = type;
            this.value = defensiveCopy(value);
            this.unit = unit;
            this.source = source;
        }

        public String getType() { return type; }
        public Object getValue() { return defensiveCopy(value); }
        public String getUnit() { return unit; }
        public String getSource() { return source; }
    }

    /** Immutable result; unresolved properties are warnings and do not invalidate other values. */
    public static final class ResolutionResult {
        private final boolean usable;
        private final Map<String, ResolvedValue> values;
        private final List<YummyUiSchemaDiagnostic> diagnostics;

        private ResolutionResult(
            boolean usable, Map<String, ResolvedValue> values,
            List<YummyUiSchemaDiagnostic> diagnostics
        ) {
            this.usable = usable;
            this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }

        public boolean isUsable() { return usable; }
        public ResolvedValue get(String propertyId) { return values.get(propertyId); }
        public Map<String, ResolvedValue> getValues() { return values; }
        public List<YummyUiSchemaDiagnostic> getDiagnostics() { return diagnostics; }
    }

    private static Object defensiveCopy(Object value) {
        if (value instanceof JSONObject) {
            try { return new JSONObject(value.toString()); }
            catch (JSONException error) { throw new IllegalArgumentException("Could not copy JSON object", error); }
        }
        if (value instanceof org.json.JSONArray) {
            try { return new org.json.JSONArray(value.toString()); }
            catch (JSONException error) { throw new IllegalArgumentException("Could not copy JSON array", error); }
        }
        return value;
    }
}
