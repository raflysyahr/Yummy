package com.termux.app.theme.resolver;

import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.VSCodeIconTheme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves VS Code File Icon Theme associations without depending on Android UI classes.
 *
 * <p>Create one resolver for the active icon theme and appearance, then reuse it for file and
 * folder lookups. A {@code null} result means that the theme has no usable matching definition;
 * callers should then use Yummy's built-in fallback icon.</p>
 *
 * <p>Resolution follows VS Code's association precedence: parent-qualified file name, file name,
 * parent-qualified extension, extension, language ID, then the default file icon. Folder lookup
 * checks expanded/name-specific associations before generic folder definitions.</p>
 */
public final class VSCodeIconThemeResolver {
    private final VSCodeIconTheme theme;
    private final List<AssociationIndex> layers;

    public VSCodeIconThemeResolver(VSCodeIconTheme theme, ThemeAppearance appearance) {
        if (theme == null) throw new IllegalArgumentException("Icon Theme is required");
        this.theme = theme;
        this.layers = buildLayers(theme, appearance);
    }

    /**
     * Resolves a file path. Only the file name and its direct parent folder are used for
     * parent-qualified associations, matching VS Code's single-parent-segment syntax.
     */
    public Resolution resolveFile(String filePath, String languageId) {
        if (filePath == null || filePath.trim().isEmpty()) return resolveDefaultFile();
        String normalized = filePath.replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        String parent = null;
        if (slash > 0) {
            int parentStart = normalized.lastIndexOf('/', slash - 1);
            parent = normalized.substring(parentStart + 1, slash);
        }
        return resolveFile(fileName, parent, languageId);
    }

    /** Resolves a file using a display name and optional direct parent folder name. */
    public Resolution resolveFile(String fileName, String parentFolderName, String languageId) {
        String name = normalizeName(fileName);
        if (name.isEmpty()) return resolveDefaultFile();
        String parent = normalizeName(parentFolderName);

        // Exact file names are the strongest match. Parent-qualified form wins over global form.
        if (!parent.isEmpty()) {
            Resolution match = resolveMap(
                index -> index.fileNames, parent + "/" + name,
                MatchType.FILE_NAME_PARENT);
            if (match != null) return match;
        }
        Resolution named = resolveMap(index -> index.fileNames, name, MatchType.FILE_NAME);
        if (named != null) return named;

        List<String> extensions = extensionSuffixes(name);
        // A direct-parent extension mapping is stronger than an unqualified extension mapping.
        if (!parent.isEmpty()) {
            for (String extension : extensions) {
                Resolution match = resolveMap(index -> index.fileExtensions,
                    parent + "/" + extension, MatchType.FILE_EXTENSION_PARENT);
                if (match != null) return match;
            }
        }
        for (String extension : extensions) {
            Resolution match = resolveMap(index -> index.fileExtensions,
                extension, MatchType.FILE_EXTENSION);
            if (match != null) return match;
        }

        String language = normalizeName(languageId);
        if (!language.isEmpty()) {
            Resolution languageMatch = resolveMap(index -> index.languageIds,
                language, MatchType.LANGUAGE_ID);
            if (languageMatch != null) return languageMatch;
        }

        return resolveDefaultFile();
    }

    /** Resolves a folder by path. The {@code root} flag identifies a workspace/storage root. */
    public Resolution resolveFolder(String folderPath, boolean expanded, boolean root) {
        if (folderPath == null || folderPath.trim().isEmpty()) return resolveGenericFolder(expanded, root);
        String normalized = folderPath.replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int slash = normalized.lastIndexOf('/');
        String folderName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        String parent = null;
        if (slash > 0) {
            int parentStart = normalized.lastIndexOf('/', slash - 1);
            parent = normalized.substring(parentStart + 1, slash);
        }
        return resolveFolder(folderName, parent, expanded, root);
    }

    /** Resolves a folder using its name and optional direct parent folder name. */
    public Resolution resolveFolder(
        String folderName, String parentFolderName, boolean expanded, boolean root
    ) {
        String name = normalizeName(folderName);
        String parent = normalizeName(parentFolderName);
        if (root) {
            if (expanded) {
                Resolution expandedNamed = resolveMap(index -> index.rootFolderNamesExpanded,
                    name, MatchType.ROOT_FOLDER_NAME_EXPANDED);
                if (expandedNamed != null) return expandedNamed;
            }
            Resolution named = resolveMap(index -> index.rootFolderNames,
                name, MatchType.ROOT_FOLDER_NAME);
            if (named != null) return named;
            return resolveGenericFolder(expanded, true);
        }

        if (expanded) {
            if (!parent.isEmpty()) {
                Resolution expandedParent = resolveMap(index -> index.folderNamesExpanded,
                    parent + "/" + name, MatchType.FOLDER_NAME_EXPANDED_PARENT);
                if (expandedParent != null) return expandedParent;
            }
            Resolution expandedNamed = resolveMap(index -> index.folderNamesExpanded,
                name, MatchType.FOLDER_NAME_EXPANDED);
            if (expandedNamed != null) return expandedNamed;
        }

        if (!parent.isEmpty()) {
            Resolution namedParent = resolveMap(index -> index.folderNames,
                parent + "/" + name, MatchType.FOLDER_NAME_PARENT);
            if (namedParent != null) return namedParent;
        }
        Resolution named = resolveMap(index -> index.folderNames,
            name, MatchType.FOLDER_NAME);
        if (named != null) return named;

        return resolveGenericFolder(expanded, false);
    }

    /** Returns the parsed theme used by this resolver. */
    public VSCodeIconTheme getTheme() {
        return theme;
    }

    private Resolution resolveDefaultFile() {
        return resolveString(index -> index.file, MatchType.DEFAULT_FILE, "file");
    }

    private Resolution resolveGenericFolder(boolean expanded, boolean root) {
        if (expanded) {
            if (root) {
                Resolution rootExpanded = resolveString(index -> index.rootFolderExpanded,
                    MatchType.ROOT_FOLDER_EXPANDED, "rootFolderExpanded");
                if (rootExpanded != null) return rootExpanded;
                Resolution rootCollapsed = resolveString(index -> index.rootFolder,
                    MatchType.ROOT_FOLDER, "rootFolder");
                if (rootCollapsed != null) return rootCollapsed;
            } else {
                Resolution folderExpanded = resolveString(index -> index.folderExpanded,
                    MatchType.FOLDER_EXPANDED, "folderExpanded");
                if (folderExpanded != null) return folderExpanded;
            }
        } else if (root) {
            Resolution rootCollapsed = resolveString(index -> index.rootFolder,
                MatchType.ROOT_FOLDER, "rootFolder");
            if (rootCollapsed != null) return rootCollapsed;
        }

        return resolveString(index -> index.folder, MatchType.FOLDER, "folder");
    }

    private Resolution resolveMap(
        MapSelector selector, String key, MatchType matchType
    ) {
        if (key == null || key.isEmpty()) return null;
        String normalizedKey = normalizeName(key);
        for (int i = layers.size() - 1; i >= 0; i--) {
            AssociationIndex layer = layers.get(i);
            String definitionId = selector.get(layer).get(normalizedKey);
            Resolution result = usableResolution(definitionId, matchType, key);
            if (result != null) return result;
        }
        return null;
    }

    private Resolution resolveString(
        StringSelector selector, MatchType matchType, String associationKey
    ) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            String definitionId = selector.get(layers.get(i));
            Resolution result = usableResolution(definitionId, matchType, associationKey);
            if (result != null) return result;
        }
        return null;
    }

    private Resolution usableResolution(
        String definitionId, MatchType matchType, String associationKey
    ) {
        if (definitionId == null || definitionId.trim().isEmpty()) return null;
        VSCodeIconTheme.IconDefinition definition = theme.getIconDefinition(definitionId);
        if (!isUsable(definition)) return null;

        String fontId = definition.getFontId();
        String fontColor = definition.getFontColor();
        String fontSize = definition.getFontSize();
        if (definition.usesFontGlyph()) {
            VSCodeIconTheme.IconDefinition defaultFile = getDefaultFileDefinition();
            if (defaultFile != null) {
                if (isEmpty(fontId)) fontId = defaultFile.getFontId();
                if (isEmpty(fontColor)) fontColor = defaultFile.getFontColor();
                if (isEmpty(fontSize)) fontSize = defaultFile.getFontSize();
            }
            if (isEmpty(fontId)) {
                VSCodeIconTheme.FontDefinition defaultFont = firstUsableFont();
                if (defaultFont != null) fontId = defaultFont.getId();
            }
        }
        VSCodeIconTheme.FontDefinition fontDefinition = findFont(fontId);
        return new Resolution(definitionId, definition, matchType, associationKey,
            fontId, fontColor, fontSize, fontDefinition);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isUsable(VSCodeIconTheme.IconDefinition definition) {
        if (definition == null) return false;
        if (definition.usesImage()) return true;
        if (!definition.usesFontGlyph()) return false;
        String fontId = definition.getFontId();
        if (fontId == null || fontId.trim().isEmpty()) {
            // VS Code uses the first usable declared font when an icon definition omits fontId.
            return firstUsableFont() != null;
        }
        return findFont(fontId) != null;
    }

    private VSCodeIconTheme.IconDefinition getDefaultFileDefinition() {
        for (int i = layers.size() - 1; i >= 0; i--) {
            String definitionId = layers.get(i).file;
            VSCodeIconTheme.IconDefinition definition = theme.getIconDefinition(definitionId);
            if (isUsable(definition)) return definition;
        }
        return null;
    }

    private VSCodeIconTheme.FontDefinition findFont(String fontId) {
        if (fontId == null) return null;
        for (VSCodeIconTheme.FontDefinition font : theme.getFonts()) {
            if (fontId.equals(font.getId()) && !font.getSources().isEmpty()) return font;
        }
        return null;
    }

    private VSCodeIconTheme.FontDefinition firstUsableFont() {
        for (VSCodeIconTheme.FontDefinition font : theme.getFonts()) {
            if (!font.getSources().isEmpty()) return font;
        }
        return null;
    }

    private static List<AssociationIndex> buildLayers(
        VSCodeIconTheme theme, ThemeAppearance appearance
    ) {
        List<AssociationIndex> result = new ArrayList<>();
        result.add(new AssociationIndex(theme.getAssociations()));
        ThemeAppearance selected = appearance == null ? ThemeAppearance.UNKNOWN : appearance;
        if (selected == ThemeAppearance.LIGHT || selected == ThemeAppearance.HIGH_CONTRAST_LIGHT) {
            result.add(new AssociationIndex(theme.getLightAssociations()));
        }
        if (selected == ThemeAppearance.HIGH_CONTRAST_DARK
            || selected == ThemeAppearance.HIGH_CONTRAST_LIGHT) {
            result.add(new AssociationIndex(theme.getHighContrastAssociations()));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> extensionSuffixes(String fileName) {
        List<String> result = new ArrayList<>();
        int dot = fileName.indexOf('.');
        while (dot >= 0 && dot < fileName.length() - 1) {
            String suffix = fileName.substring(dot + 1);
            if (!suffix.isEmpty()) result.add(suffix);
            dot = fileName.indexOf('.', dot + 1);
        }
        // Suffixes are generated from left to right, so compound extensions come first.
        return result;
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private interface MapSelector {
        Map<String, String> get(AssociationIndex index);
    }

    private interface StringSelector {
        String get(AssociationIndex index);
    }

    private static final class AssociationIndex {
        final String file;
        final String folder;
        final String folderExpanded;
        final String rootFolder;
        final String rootFolderExpanded;
        final Map<String, String> fileExtensions;
        final Map<String, String> fileNames;
        final Map<String, String> folderNames;
        final Map<String, String> folderNamesExpanded;
        final Map<String, String> rootFolderNames;
        final Map<String, String> rootFolderNamesExpanded;
        final Map<String, String> languageIds;

        AssociationIndex(VSCodeIconTheme.Associations source) {
            file = source.getFile();
            folder = source.getFolder();
            folderExpanded = source.getFolderExpanded();
            rootFolder = source.getRootFolder();
            rootFolderExpanded = source.getRootFolderExpanded();
            fileExtensions = index(source.getFileExtensions());
            fileNames = index(source.getFileNames());
            folderNames = index(source.getFolderNames());
            folderNamesExpanded = index(source.getFolderNamesExpanded());
            rootFolderNames = index(source.getRootFolderNames());
            rootFolderNamesExpanded = index(source.getRootFolderNamesExpanded());
            languageIds = index(source.getLanguageIds());
        }

        private static Map<String, String> index(Map<String, String> source) {
            Map<String, String> result = new LinkedHashMap<>();
            if (source != null) {
                for (Map.Entry<String, String> entry : source.entrySet()) {
                    String key = normalizeName(entry.getKey());
                    if (!key.isEmpty() && entry.getValue() != null) {
                        // First declaration wins when keys differ only by case.
                        if (!result.containsKey(key)) result.put(key, entry.getValue());
                    }
                }
            }
            return Collections.unmodifiableMap(result);
        }
    }

    /** The association type that won resolution. */
    public enum MatchType {
        FILE_NAME_PARENT,
        FILE_NAME,
        FILE_EXTENSION_PARENT,
        FILE_EXTENSION,
        LANGUAGE_ID,
        DEFAULT_FILE,
        FOLDER_NAME_EXPANDED_PARENT,
        FOLDER_NAME_EXPANDED,
        FOLDER_NAME_PARENT,
        FOLDER_NAME,
        ROOT_FOLDER_NAME_EXPANDED,
        ROOT_FOLDER_NAME,
        FOLDER_EXPANDED,
        FOLDER,
        ROOT_FOLDER_EXPANDED,
        ROOT_FOLDER
    }

    /** Immutable result. Null from a resolver method indicates that Yummy should use fallback. */
    public static final class Resolution {
        private final String definitionId;
        private final VSCodeIconTheme.IconDefinition definition;
        private final MatchType matchType;
        private final String associationKey;
        private final String effectiveFontId;
        private final String effectiveFontColor;
        private final String effectiveFontSize;
        private final VSCodeIconTheme.FontDefinition fontDefinition;

        private Resolution(String definitionId, VSCodeIconTheme.IconDefinition definition,
            MatchType matchType, String associationKey, String effectiveFontId,
            String effectiveFontColor, String effectiveFontSize,
            VSCodeIconTheme.FontDefinition fontDefinition) {
            this.definitionId = definitionId;
            this.definition = definition;
            this.matchType = matchType;
            this.associationKey = associationKey;
            this.effectiveFontId = effectiveFontId;
            this.effectiveFontColor = effectiveFontColor;
            this.effectiveFontSize = effectiveFontSize;
            this.fontDefinition = fontDefinition;
        }

        public String getDefinitionId() { return definitionId; }
        public VSCodeIconTheme.IconDefinition getDefinition() { return definition; }
        public MatchType getMatchType() { return matchType; }
        public String getAssociationKey() { return associationKey; }
        public String getIconPath() { return definition.getIconPath(); }
        public String getFontCharacter() { return definition.getFontCharacter(); }
        /** Returns the effective font ID after VS Code default-file inheritance is applied. */
        public String getFontId() { return effectiveFontId; }
        /** Returns the effective font color after default-file inheritance is applied. */
        public String getFontColor() { return effectiveFontColor; }
        /** Returns the effective font size after default-file inheritance is applied. */
        public String getFontSize() { return effectiveFontSize; }
        public VSCodeIconTheme.FontDefinition getFontDefinition() { return fontDefinition; }
    }
}
