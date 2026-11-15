package com.termux.app.theme.loader;

import com.termux.app.theme.model.VSCodeIconTheme;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses VS Code File Icon Theme JSON/JSONC documents without discarding unknown fields. */
public final class VSCodeIconThemeParser {
    private static final int MAX_DOCUMENT_CHARS = 4 * 1024 * 1024;
    private static final int MAX_ICON_DEFINITIONS = 12000;
    private static final int MAX_ASSOCIATIONS_PER_MAP = 50000;
    private static final int MAX_FONTS = 128;
    private static final int MAX_FONT_SOURCES = 32;
    private static final int MAX_WARNINGS = 512;

    /** Parses an Icon Theme document. Resource paths are retained but not resolved. */
    public VSCodeIconTheme parse(String json) throws ThemeLoadException {
        if (json == null || json.trim().isEmpty()) {
            throw new ThemeLoadException("Icon Theme JSON is empty");
        }
        if (json.length() > MAX_DOCUMENT_CHARS) {
            throw new ThemeLoadException("Icon Theme JSON exceeds the 4 MiB limit");
        }

        try {
            JSONObject root = new JSONObject(ThemeDocumentParser.normalizeJsonc(json));
            return parseRoot(root);
        } catch (JSONException error) {
            throw new ThemeLoadException("Invalid Icon Theme JSON: " + safeMessage(error), error);
        } catch (IllegalArgumentException error) {
            throw new ThemeLoadException("Invalid Icon Theme JSON: " + safeMessage(error), error);
        }
    }

    /**
     * Parses an Icon Theme and validates that all local image/font references stay inside the
     * extension directory after resolving them relative to the declared theme document path.
     */
    public VSCodeIconTheme parse(String json, String themeDocumentPath)
        throws ThemeLoadException {
        VSCodeIconTheme theme = parse(json);
        try {
            theme.getReferencedResourcePaths(themeDocumentPath);
            return theme;
        } catch (IllegalArgumentException error) {
            throw new ThemeLoadException("Unsafe Icon Theme resource reference: "
                + safeMessage(error), error);
        }
    }

    private VSCodeIconTheme parseRoot(JSONObject root) throws ThemeLoadException {
        Object definitionsValue = root.opt("iconDefinitions");
        if (!(definitionsValue instanceof JSONObject)) {
            throw new ThemeLoadException("'iconDefinitions' must be an object");
        }

        JSONObject rawDefinitions = (JSONObject) definitionsValue;
        if (rawDefinitions.length() == 0) {
            throw new ThemeLoadException("'iconDefinitions' cannot be empty");
        }
        if (rawDefinitions.length() > MAX_ICON_DEFINITIONS) {
            throw new ThemeLoadException("Icon Theme contains too many icon definitions");
        }

        List<String> warnings = new ArrayList<>();
        Map<String, VSCodeIconTheme.IconDefinition> definitions =
            parseIconDefinitions(rawDefinitions, warnings);
        VSCodeIconTheme.Associations base = parseAssociations(root, "Icon Theme");
        VSCodeIconTheme.Associations light = parseOptionalAssociations(root, "light");
        VSCodeIconTheme.Associations highContrast = parseOptionalAssociations(root, "highContrast");
        List<VSCodeIconTheme.FontDefinition> fonts = parseFonts(root.opt("fonts"), warnings);

        Boolean hidesExplorerArrows = optionalBoolean(root, "hidesExplorerArrows");
        Boolean showLanguageModeIcons = optionalBoolean(root, "showLanguageModeIcons");

        Set<String> fontIds = new LinkedHashSet<>();
        for (VSCodeIconTheme.FontDefinition font : fonts) fontIds.add(font.getId());
        validateDefinitionReferences(base, definitions, warnings, "base");
        validateDefinitionReferences(light, definitions, warnings, "light");
        validateDefinitionReferences(highContrast, definitions, warnings, "highContrast");
        for (VSCodeIconTheme.IconDefinition definition : definitions.values()) {
            if (definition.getFontId() != null && !fontIds.contains(definition.getFontId())) {
                addWarning(warnings, "Icon definition '" + definition.getId()
                    + "' references missing font '" + definition.getFontId() + "'");
            }
        }

        String name = optionalString(root, "name");
        return new VSCodeIconTheme(root, name, definitions, base, light, highContrast,
            fonts, hidesExplorerArrows, showLanguageModeIcons, warnings);
    }

    private static Map<String, VSCodeIconTheme.IconDefinition> parseIconDefinitions(
        JSONObject rawDefinitions, List<String> warnings
    ) throws ThemeLoadException {
        Map<String, VSCodeIconTheme.IconDefinition> result = new LinkedHashMap<>();
        java.util.Iterator<String> keys = rawDefinitions.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            Object rawValue = rawDefinitions.opt(id);
            if (id == null || id.trim().isEmpty() || !(rawValue instanceof JSONObject)) {
                throw new ThemeLoadException("iconDefinitions entries must have a non-empty ID and object value");
            }
            JSONObject definition = (JSONObject) rawValue;
            String iconPath = optionalDefinitionString(definition, "iconPath", id);
            String fontCharacter = optionalDefinitionString(definition, "fontCharacter", id);
            String fontColor = optionalDefinitionString(definition, "fontColor", id);
            String fontSize = optionalDefinitionString(definition, "fontSize", id);
            String fontId = optionalDefinitionString(definition, "fontId", id);

            if (iconPath == null && fontCharacter == null) {
                addWarning(warnings, "Icon definition '" + id
                    + "' has neither iconPath nor fontCharacter and will use fallback");
            }
            if (iconPath != null && fontCharacter != null) {
                addWarning(warnings, "Icon definition '" + id
                    + "' declares both iconPath and fontCharacter; image path takes precedence");
            }
            result.put(id, new VSCodeIconTheme.IconDefinition(
                id, iconPath, fontCharacter, fontColor, fontSize, fontId));
        }
        return result;
    }

    private static VSCodeIconTheme.Associations parseOptionalAssociations(
        JSONObject root, String key
    ) throws ThemeLoadException {
        Object value = root.opt(key);
        if (value == null || value == JSONObject.NULL) return new VSCodeIconTheme.Associations();
        if (!(value instanceof JSONObject)) {
            throw new ThemeLoadException("'" + key + "' must be an object when present");
        }
        return parseAssociations((JSONObject) value, "'" + key + "'");
    }

    private static VSCodeIconTheme.Associations parseAssociations(
        JSONObject source, String path
    ) throws ThemeLoadException {
        String file = optionalAssociationString(source, "file", path);
        String folder = optionalAssociationString(source, "folder", path);
        String folderExpanded = optionalAssociationString(source, "folderExpanded", path);
        String rootFolder = optionalAssociationString(source, "rootFolder", path);
        String rootFolderExpanded = optionalAssociationString(source, "rootFolderExpanded", path);

        Map<String, String> fileExtensions = readStringMap(source, "fileExtensions", path);
        Map<String, String> fileNames = readStringMap(source, "fileNames", path);
        Map<String, String> folderNames = readStringMap(source, "folderNames", path);
        Map<String, String> folderNamesExpanded = readStringMap(source, "folderNamesExpanded", path);
        Map<String, String> rootFolderNames = readStringMap(source, "rootFolderNames", path);
        Map<String, String> rootFolderNamesExpanded = readStringMap(source, "rootFolderNamesExpanded", path);
        Map<String, String> languageIds = readStringMap(source, "languageIds", path);

        return new VSCodeIconTheme.Associations(
            file, folder, folderExpanded, rootFolder, rootFolderExpanded,
            fileExtensions, fileNames, folderNames, folderNamesExpanded,
            rootFolderNames, rootFolderNamesExpanded, languageIds);
    }

    private static Map<String, String> readStringMap(
        JSONObject source, String key, String path
    ) throws ThemeLoadException {
        Object value = source.opt(key);
        if (value == null || value == JSONObject.NULL) return new LinkedHashMap<>();
        if (!(value instanceof JSONObject)) {
            throw new ThemeLoadException(path + "." + key + " must be an object");
        }

        JSONObject object = (JSONObject) value;
        if (object.length() > MAX_ASSOCIATIONS_PER_MAP) {
            throw new ThemeLoadException(path + "." + key + " contains too many associations");
        }
        Map<String, String> result = new LinkedHashMap<>();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String association = keys.next();
            Object rawDefinitionId = object.opt(association);
            if (association == null || association.trim().isEmpty()
                || !(rawDefinitionId instanceof String)
                || ((String) rawDefinitionId).trim().isEmpty()) {
                throw new ThemeLoadException(path + "." + key
                    + " must map non-empty association keys to icon definition IDs");
            }
            result.put(association, ((String) rawDefinitionId).trim());
        }
        return result;
    }

    private static List<VSCodeIconTheme.FontDefinition> parseFonts(
        Object value, List<String> warnings
    ) throws ThemeLoadException {
        List<VSCodeIconTheme.FontDefinition> result = new ArrayList<>();
        if (value == null || value == JSONObject.NULL) return result;
        if (!(value instanceof JSONArray)) {
            throw new ThemeLoadException("'fonts' must be an array when present");
        }

        JSONArray fonts = (JSONArray) value;
        if (fonts.length() > MAX_FONTS) {
            throw new ThemeLoadException("Icon Theme contains too many font definitions");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < fonts.length(); i++) {
            JSONObject font = fonts.optJSONObject(i);
            if (font == null) {
                addWarning(warnings, "fonts[" + i + "] is not an object and was ignored");
                continue;
            }
            String id = optionalString(font, "id");
            if (id == null) {
                addWarning(warnings, "fonts[" + i + "] has no valid id and was ignored");
                continue;
            }
            if (!ids.add(id)) {
                addWarning(warnings, "Duplicate font id '" + id + "' was ignored");
                continue;
            }

            List<VSCodeIconTheme.FontSource> sources = new ArrayList<>();
            Object rawSources = font.opt("src");
            if (rawSources instanceof JSONArray) {
                JSONArray sourceArray = (JSONArray) rawSources;
                int limit = Math.min(sourceArray.length(), MAX_FONT_SOURCES);
                for (int sourceIndex = 0; sourceIndex < limit; sourceIndex++) {
                    JSONObject fontSource = sourceArray.optJSONObject(sourceIndex);
                    String sourcePath = optionalString(fontSource, "path");
                    if (sourcePath == null) {
                        addWarning(warnings, "fonts[" + i + "].src[" + sourceIndex
                            + "] has no valid path and was ignored");
                        continue;
                    }
                    sources.add(new VSCodeIconTheme.FontSource(
                        sourcePath, optionalString(fontSource, "format")));
                }
                if (sourceArray.length() > MAX_FONT_SOURCES) {
                    addWarning(warnings, "fonts[" + i + "] has extra font sources beyond the parser limit");
                }
            } else {
                addWarning(warnings, "fonts[" + i + "] has no src array");
            }

            if (sources.isEmpty()) {
                addWarning(warnings, "Font '" + id + "' has no usable source and cannot be rendered");
            }
            result.add(new VSCodeIconTheme.FontDefinition(
                id, sources, optionalString(font, "weight"),
                optionalString(font, "style"), optionalString(font, "size")));
        }
        return result;
    }

    private static void validateDefinitionReferences(
        VSCodeIconTheme.Associations associations,
        Map<String, VSCodeIconTheme.IconDefinition> definitions,
        List<String> warnings,
        String section
    ) {
        for (String reference : associations.getDefinitionReferences()) {
            if (!definitions.containsKey(reference)) {
                addWarning(warnings, section + " association references missing icon definition '"
                    + reference + "'; Yummy should use its fallback icon");
            }
        }
    }

    private static String optionalDefinitionString(JSONObject source, String key, String id)
        throws ThemeLoadException {
        Object value = source.opt(key);
        if (value == null || value == JSONObject.NULL) return null;
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new ThemeLoadException("iconDefinitions['" + id + "']." + key
                + " must be a non-empty string");
        }
        return ((String) value).trim();
    }

    private static String optionalAssociationString(JSONObject source, String key, String path)
        throws ThemeLoadException {
        Object value = source.opt(key);
        if (value == null || value == JSONObject.NULL) return null;
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new ThemeLoadException(path + "." + key + " must be a non-empty string");
        }
        return ((String) value).trim();
    }

    private static Boolean optionalBoolean(JSONObject source, String key)
        throws ThemeLoadException {
        Object value = source.opt(key);
        if (value == null || value == JSONObject.NULL) return null;
        if (!(value instanceof Boolean)) {
            throw new ThemeLoadException("'" + key + "' must be a boolean");
        }
        return (Boolean) value;
    }

    private static String optionalString(JSONObject source, String key) {
        Object value = source == null ? null : source.opt(key);
        if (!(value instanceof String)) return null;
        String text = ((String) value).trim();
        return text.isEmpty() ? null : text;
    }

    private static void addWarning(List<String> warnings, String warning) {
        if (warnings.size() >= MAX_WARNINGS || warning == null || warnings.contains(warning)) return;
        warnings.add(warning);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error == null ? "Unknown error" : error.getClass().getSimpleName()
            : message;
    }
}
