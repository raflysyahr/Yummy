package com.termux.app.theme.schema;

import com.termux.app.theme.adapter.AppThemeColorValueParser;
import com.termux.app.theme.model.LoadedTheme;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves the editable color-scheme.json tree into the stable dotted property IDs consumed by
 * YummyUiSchemaRuntime. Leaf values support exactly three sources: theme:<workbench-key>, a
 * manual VS Code HEX color, or this.<local-property-path>.
 */
public final class YummyColorSchemeResolver {
    public static final int SUPPORTED_VERSION = 1;
    private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]*$");

    public ResolutionResult resolve(JSONObject document, LoadedTheme theme) {
        List<YummyUiSchemaDiagnostic> diagnostics = new ArrayList<>();
        Map<String, String> definitions = new LinkedHashMap<>();
        Map<String, String> resolved = new LinkedHashMap<>();
        if (document == null) {
            add(diagnostics, "document_required", "$", "Color Scheme document is required");
            return new ResolutionResult(false, resolved, diagnostics);
        }
        Object version = document.opt("version");
        if (!(version instanceof Number)
            || ((Number) version).doubleValue() != SUPPORTED_VERSION) {
            add(diagnostics, "version_invalid", "version",
                "version must be " + SUPPORTED_VERSION);
        }

        java.util.Iterator<String> roots = document.keys();
        while (roots.hasNext()) {
            String root = roots.next();
            if ("version".equals(root)) continue;
            Object value = document.opt(root);
            if (!(value instanceof JSONObject)) {
                add(diagnostics, "component_group_invalid", root,
                    "Each top-level component group must be an object");
                continue;
            }
            flatten((JSONObject) value, root, definitions, diagnostics);
        }
        if (hasErrors(diagnostics)) return new ResolutionResult(false, resolved, diagnostics);

        Set<String> resolving = new LinkedHashSet<>();
        for (String key : definitions.keySet()) {
            resolveKey(key, definitions, theme, resolved, resolving, diagnostics);
        }
        Map<String, String> runtimeColors = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            String runtimeId = toRuntimePropertyId(entry.getKey());
            if (runtimeColors.containsKey(runtimeId)) {
                add(diagnostics, "runtime_property_collision", entry.getKey(),
                    "More than one Color Scheme path maps to runtime property '" + runtimeId + "'");
            } else {
                runtimeColors.put(runtimeId, entry.getValue());
            }
        }
        return new ResolutionResult(!hasErrors(diagnostics), runtimeColors, diagnostics);
    }

    private static void flatten(JSONObject object, String path, Map<String, String> output,
                                 List<YummyUiSchemaDiagnostic> diagnostics) {
        if (!isValidSegments(path)) {
            add(diagnostics, "property_path_invalid", path,
                "Color Scheme paths must use valid dot-separated identifiers");
            return;
        }
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String childPath = path + "." + key;
            Object value = object.opt(key);
            if (value instanceof JSONObject) {
                flatten((JSONObject) value, childPath, output, diagnostics);
            } else if (value instanceof String) {
                if (!isValidPath(childPath)) {
                    add(diagnostics, "property_path_invalid", childPath,
                        "Color Scheme paths must use valid dot-separated identifiers");
                } else {
                    output.put(childPath, ((String) value).trim());
                    if (((String) value).trim().isEmpty()) {
                        add(diagnostics, "value_empty", childPath, "Color value cannot be empty");
                    }
                }
            } else {
                add(diagnostics, "value_type_invalid", childPath,
                    "Color Scheme leaf values must be strings");
            }
        }
    }

    private static String resolveKey(String key, Map<String, String> definitions, LoadedTheme theme,
                                     Map<String, String> resolved, Set<String> resolving,
                                     List<YummyUiSchemaDiagnostic> diagnostics) {
        if (resolved.containsKey(key)) return resolved.get(key);
        String expression = definitions.get(key);
        if (expression == null) return null;
        if (!resolving.add(key)) {
            add(diagnostics, "reference_cycle", key,
                "Local Color Scheme references contain a cycle");
            return null;
        }

        String result = null;
        if (expression.startsWith("#")) {
            try {
                AppThemeColorValueParser.parseHexColor(expression);
                result = expression;
            } catch (IllegalArgumentException error) {
                add(diagnostics, "manual_color_invalid", key, error.getMessage());
            }
        } else if (expression.startsWith("theme:")) {
            String themeKey = expression.substring("theme:".length()).trim();
            if (themeKey.isEmpty()) {
                add(diagnostics, "theme_key_empty", key, "Theme color key cannot be empty");
            } else if (theme == null) {
                add(diagnostics, "theme_unavailable", key,
                    "An active imported VS Code Color Theme is required");
            } else {
                String color = theme.getColorTheme().getColor(themeKey);
                if (color == null || color.trim().isEmpty()) {
                    addWarning(diagnostics, "theme_key_missing", key,
                        "VS Code theme does not define color key '" + themeKey
                            + "'; the component will use its existing UI Schema/default value");
                } else if ("default".equalsIgnoreCase(color.trim())) {
                    addWarning(diagnostics, "theme_key_default", key,
                        "VS Code theme key '" + themeKey
                            + "' requests the host default; the component will use its existing UI Schema/default value");
                } else {
                    try {
                        AppThemeColorValueParser.parseHexColor(color);
                        result = color;
                    } catch (IllegalArgumentException error) {
                        add(diagnostics, "theme_color_invalid", key,
                            "Theme key '" + themeKey + "' is not a supported HEX color");
                    }
                }
            }
        } else if (expression.startsWith("this.")) {
            String target = expression.substring("this.".length()).trim();
            if (target.isEmpty() || !definitions.containsKey(target)) {
                add(diagnostics, "local_reference_missing", key,
                    "Local Color Scheme key '" + target + "' does not exist");
            } else {
                result = resolveKey(target, definitions, theme, resolved, resolving, diagnostics);
                if (result == null) {
                    addWarning(diagnostics, "local_reference_fallback", key,
                        "Local reference '" + target
                            + "' did not resolve; the component will use its existing UI Schema/default value");
                }
            }
        } else {
            add(diagnostics, "source_unknown", key,
                "Value must be theme:<key>, a HEX color, or this.<component>.<property>");
        }

        resolving.remove(key);
        if (result != null) resolved.put(key, result);
        return result;
    }

    /** Maps the public storage_bar component name to the existing sidebar.storage bindings. */
    private static String toRuntimePropertyId(String path) {
        if (path.startsWith("storage_bar.")) {
            return "sidebar.storage." + path.substring("storage_bar.".length());
        }
        return path;
    }

    private static boolean isValidPath(String path) {
        if (path == null || path.isEmpty()) return false;
        String[] segments = path.split("\\.");
        return segments.length >= 2 && isValidSegments(path);
    }

    private static boolean isValidSegments(String path) {
        if (path == null || path.isEmpty()) return false;
        String[] segments = path.split("\\.");
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) return false;
        }
        return true;
    }

    private static boolean hasErrors(List<YummyUiSchemaDiagnostic> diagnostics) {
        for (YummyUiSchemaDiagnostic diagnostic : diagnostics) {
            if (diagnostic.isError()) return true;
        }
        return false;
    }

    private static void add(List<YummyUiSchemaDiagnostic> diagnostics, String code,
                            String path, String message) {
        diagnostics.add(new YummyUiSchemaDiagnostic(code,
            YummyUiSchemaDiagnostic.SEVERITY_ERROR, path, path, message));
    }

    private static void addWarning(List<YummyUiSchemaDiagnostic> diagnostics, String code,
                                   String path, String message) {
        diagnostics.add(new YummyUiSchemaDiagnostic(code,
            YummyUiSchemaDiagnostic.SEVERITY_WARNING, path, path, message));
    }

    private static void addWarning(List<YummyUiSchemaDiagnostic> diagnostics, String code,
                                   String path, String message) {
        diagnostics.add(new YummyUiSchemaDiagnostic(code,
            YummyUiSchemaDiagnostic.SEVERITY_WARNING, path, path, message));
    }

    public static final class ResolutionResult {
        private final boolean usable;
        private final Map<String, String> colors;
        private final List<YummyUiSchemaDiagnostic> diagnostics;

        private ResolutionResult(boolean usable, Map<String, String> colors,
                                 List<YummyUiSchemaDiagnostic> diagnostics) {
            this.usable = usable;
            this.colors = Collections.unmodifiableMap(new LinkedHashMap<>(colors));
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }

        public boolean isUsable() { return usable; }
        public Map<String, String> getColors() { return colors; }
        public List<YummyUiSchemaDiagnostic> getDiagnostics() { return diagnostics; }
    }
}
