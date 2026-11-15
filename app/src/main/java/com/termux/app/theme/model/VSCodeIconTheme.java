package com.termux.app.theme.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parsed VS Code File Icon Theme document.
 *
 * <p>The complete source document is retained so fields Yummy does not interpret yet are not
 * discarded. Parsed definitions and associations are exposed as immutable Java collections.</p>
 */
public final class VSCodeIconTheme {
    private final JSONObject source;
    private final String name;
    private final Map<String, IconDefinition> iconDefinitions;
    private final Associations associations;
    private final Associations lightAssociations;
    private final Associations highContrastAssociations;
    private final List<FontDefinition> fonts;
    private final Boolean hidesExplorerArrows;
    private final Boolean showLanguageModeIcons;
    private final List<String> warnings;

    public VSCodeIconTheme(
        JSONObject source,
        String name,
        Map<String, IconDefinition> iconDefinitions,
        Associations associations,
        Associations lightAssociations,
        Associations highContrastAssociations,
        List<FontDefinition> fonts,
        Boolean hidesExplorerArrows,
        Boolean showLanguageModeIcons,
        List<String> warnings
    ) {
        this.source = copyObject(source);
        this.name = name;
        this.iconDefinitions = immutableMap(iconDefinitions);
        this.associations = associations == null ? new Associations() : associations;
        this.lightAssociations = lightAssociations == null
            ? new Associations() : lightAssociations;
        this.highContrastAssociations = highContrastAssociations == null
            ? new Associations() : highContrastAssociations;
        this.fonts = immutableList(fonts);
        this.hidesExplorerArrows = hidesExplorerArrows;
        this.showLanguageModeIcons = showLanguageModeIcons;
        this.warnings = immutableList(warnings);
    }

    public String getName() { return name; }

    public Map<String, IconDefinition> getIconDefinitions() { return iconDefinitions; }

    public IconDefinition getIconDefinition(String id) {
        return id == null ? null : iconDefinitions.get(id);
    }

    /** Base associations used when no appearance-specific override applies. */
    public Associations getAssociations() { return associations; }

    /** Partial overrides for a light Color Theme. */
    public Associations getLightAssociations() { return lightAssociations; }

    /** Partial overrides for a high-contrast Color Theme. */
    public Associations getHighContrastAssociations() { return highContrastAssociations; }

    public List<FontDefinition> getFonts() { return fonts; }

    /** Null means the source Icon Theme did not declare this setting. */
    public Boolean getHidesExplorerArrows() { return hidesExplorerArrows; }

    /** Null means the source Icon Theme did not declare this setting. */
    public Boolean getShowLanguageModeIcons() { return showLanguageModeIcons; }

    /** Non-fatal compatibility findings, such as associations pointing to missing definitions. */
    public List<String> getWarnings() { return warnings; }

    /** Returns a defensive copy of the complete source JSON, including unknown properties. */
    public JSONObject toJson() { return copyObject(source); }

    /**
     * Returns all local icon/font assets referenced by this theme document.
     *
     * @param themeDocumentPath path of the Icon Theme JSON relative to the VSIX extension root,
     *                          for example {@code build/themes/icons.json}
     * @throws IllegalArgumentException when a resource path is absolute, external, or escapes
     *                                  the extension root after relative resolution
     */
    public List<String> getReferencedResourcePaths(String themeDocumentPath) {
        String documentPath = normalizeExtensionRelativePath(themeDocumentPath);
        Set<String> paths = new LinkedHashSet<>();

        for (IconDefinition definition : iconDefinitions.values()) {
            if (definition.getIconPath() != null) {
                paths.add(resolveResourcePath(documentPath, definition.getIconPath()));
            }
        }
        for (FontDefinition font : fonts) {
            for (FontSource source : font.getSources()) {
                paths.add(resolveResourcePath(documentPath, source.getPath()));
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(paths));
    }

    private static String resolveResourcePath(String documentPath, String rawReference) {
        if (rawReference == null) throw new IllegalArgumentException("Icon resource path is empty");
        String reference = rawReference.trim();
        if (reference.isEmpty()) throw new IllegalArgumentException("Icon resource path is empty");
        if (reference.startsWith("//")
            || reference.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            throw new IllegalArgumentException("External icon resource references are not supported");
        }
        if (reference.startsWith("/") || reference.indexOf('\\') >= 0
            || reference.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Icon resource path must be relative");
        }

        int slash = documentPath.lastIndexOf('/');
        String parent = slash < 0 ? "" : documentPath.substring(0, slash);
        String combined = parent.isEmpty() ? reference : parent + "/" + reference;
        return normalizeSegments(combined);
    }

    private static String normalizeExtensionRelativePath(String rawPath) {
        if (rawPath == null) throw new IllegalArgumentException("Icon Theme document path is required");
        String path = rawPath.trim();
        if (path.startsWith("./")) path = path.substring(2);
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0
            || path.indexOf('\0') >= 0 || path.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            throw new IllegalArgumentException("Icon Theme document path must be extension-relative");
        }
        return normalizeSegments(path);
    }

    private static String normalizeSegments(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException("Icon resource path escapes the extension directory");
                }
                segments.remove(segments.size() - 1);
            } else {
                if (segment.indexOf(':') >= 0) {
                    throw new IllegalArgumentException("Icon resource path contains an invalid segment");
                }
                segments.add(segment);
            }
        }
        if (segments.isEmpty()) throw new IllegalArgumentException("Icon resource path is empty");
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            if (result.length() > 0) result.append('/');
            result.append(segment);
        }
        return result.toString();
    }

    private static JSONObject copyObject(JSONObject value) {
        if (value == null) return new JSONObject();
        try {
            return new JSONObject(value.toString());
        } catch (JSONException error) {
            return new JSONObject();
        }
    }

    private static <T> Map<String, T> immutableMap(Map<String, T> value) {
        Map<String, T> copy = value == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
        return Collections.unmodifiableMap(copy);
    }

    private static <T> List<T> immutableList(List<T> value) {
        List<T> copy = value == null ? new ArrayList<>() : new ArrayList<>(value);
        return Collections.unmodifiableList(copy);
    }

    /** One entry from the VS Code {@code iconDefinitions} object. */
    public static final class IconDefinition {
        private final String id;
        private final String iconPath;
        private final String fontCharacter;
        private final String fontColor;
        private final String fontSize;
        private final String fontId;

        public IconDefinition(String id, String iconPath, String fontCharacter,
            String fontColor, String fontSize, String fontId) {
            this.id = id;
            this.iconPath = iconPath;
            this.fontCharacter = fontCharacter;
            this.fontColor = fontColor;
            this.fontSize = fontSize;
            this.fontId = fontId;
        }

        public String getId() { return id; }
        public String getIconPath() { return iconPath; }
        public String getFontCharacter() { return fontCharacter; }
        public String getFontColor() { return fontColor; }
        public String getFontSize() { return fontSize; }
        public String getFontId() { return fontId; }
        public boolean usesImage() { return iconPath != null; }
        public boolean usesFontGlyph() { return fontCharacter != null; }
    }

    /** VS Code glyph font declaration. */
    public static final class FontDefinition {
        private final String id;
        private final List<FontSource> sources;
        private final String weight;
        private final String style;
        private final String size;

        public FontDefinition(String id, List<FontSource> sources,
            String weight, String style, String size) {
            this.id = id;
            this.sources = immutableList(sources);
            this.weight = weight;
            this.style = style;
            this.size = size;
        }

        public String getId() { return id; }
        public List<FontSource> getSources() { return sources; }
        public String getWeight() { return weight; }
        public String getStyle() { return style; }
        public String getSize() { return size; }
    }

    /** One local font file and its declared VS Code format. */
    public static final class FontSource {
        private final String path;
        private final String format;

        public FontSource(String path, String format) {
            this.path = path;
            this.format = format;
        }

        public String getPath() { return path; }
        public String getFormat() { return format; }
    }

    /** Base or appearance-specific file/folder association set. */
    public static final class Associations {
        private final String file;
        private final String folder;
        private final String folderExpanded;
        private final String rootFolder;
        private final String rootFolderExpanded;
        private final Map<String, String> fileExtensions;
        private final Map<String, String> fileNames;
        private final Map<String, String> folderNames;
        private final Map<String, String> folderNamesExpanded;
        private final Map<String, String> rootFolderNames;
        private final Map<String, String> rootFolderNamesExpanded;
        private final Map<String, String> languageIds;

        public Associations() {
            this(null, null, null, null, null, null, null, null, null, null, null, null);
        }

        public Associations(String file, String folder, String folderExpanded,
            String rootFolder, String rootFolderExpanded,
            Map<String, String> fileExtensions, Map<String, String> fileNames,
            Map<String, String> folderNames, Map<String, String> folderNamesExpanded,
            Map<String, String> rootFolderNames, Map<String, String> rootFolderNamesExpanded,
            Map<String, String> languageIds) {
            this.file = file;
            this.folder = folder;
            this.folderExpanded = folderExpanded;
            this.rootFolder = rootFolder;
            this.rootFolderExpanded = rootFolderExpanded;
            this.fileExtensions = immutableMap(fileExtensions);
            this.fileNames = immutableMap(fileNames);
            this.folderNames = immutableMap(folderNames);
            this.folderNamesExpanded = immutableMap(folderNamesExpanded);
            this.rootFolderNames = immutableMap(rootFolderNames);
            this.rootFolderNamesExpanded = immutableMap(rootFolderNamesExpanded);
            this.languageIds = immutableMap(languageIds);
        }

        public String getFile() { return file; }
        public String getFolder() { return folder; }
        public String getFolderExpanded() { return folderExpanded; }
        public String getRootFolder() { return rootFolder; }
        public String getRootFolderExpanded() { return rootFolderExpanded; }
        public Map<String, String> getFileExtensions() { return fileExtensions; }
        public Map<String, String> getFileNames() { return fileNames; }
        public Map<String, String> getFolderNames() { return folderNames; }
        public Map<String, String> getFolderNamesExpanded() { return folderNamesExpanded; }
        public Map<String, String> getRootFolderNames() { return rootFolderNames; }
        public Map<String, String> getRootFolderNamesExpanded() { return rootFolderNamesExpanded; }
        public Map<String, String> getLanguageIds() { return languageIds; }

        public List<String> getDefinitionReferences() {
            Set<String> references = new LinkedHashSet<>();
            addReference(references, file);
            addReference(references, folder);
            addReference(references, folderExpanded);
            addReference(references, rootFolder);
            addReference(references, rootFolderExpanded);
            addReferences(references, fileExtensions);
            addReferences(references, fileNames);
            addReferences(references, folderNames);
            addReferences(references, folderNamesExpanded);
            addReferences(references, rootFolderNames);
            addReferences(references, rootFolderNamesExpanded);
            addReferences(references, languageIds);
            return Collections.unmodifiableList(new ArrayList<>(references));
        }

        private static void addReferences(Set<String> target, Map<String, String> values) {
            if (values != null) for (String value : values.values()) addReference(target, value);
        }

        private static void addReference(Set<String> target, String value) {
            if (value != null && !value.trim().isEmpty()) target.add(value);
        }
    }
}
