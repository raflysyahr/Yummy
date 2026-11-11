package com.termux.app.theme.loader;

import android.content.res.AssetManager;

import com.termux.app.theme.io.AssetThemeResourceReader;
import com.termux.app.theme.io.DirectoryThemeResourceReader;
import com.termux.app.theme.io.ThemeResourceReader;
import com.termux.app.theme.io.ThemeResourcePaths;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;
import com.termux.app.theme.model.VSCodeColorTheme;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Loads a Yummy theme package: sidecar metadata plus a VS Code Color Theme JSON file.
 * This class deliberately does not register or apply a theme; that belongs to later stages.
 */
public final class ThemePackageLoader implements ThemePackageSourceLoader {
    private static final int SUPPORTED_METADATA_SCHEMA_VERSION = 1;
    private final ThemeDocumentParser documentParser;

    public ThemePackageLoader() {
        this(new ThemeDocumentParser());
    }

    public ThemePackageLoader(ThemeDocumentParser documentParser) {
        if (documentParser == null) {
            throw new IllegalArgumentException("ThemeDocumentParser is required");
        }
        this.documentParser = documentParser;
    }

    /** Loads a built-in package. themeFile paths are relative to the APK assets root. */
    public LoadedTheme loadBuiltin(AssetManager assets, String metadataAssetPath)
        throws ThemeLoadException {
        if (assets == null) throw new ThemeLoadException("AssetManager is required");
        try {
            ThemeResourceReader reader = new AssetThemeResourceReader(assets);
            String metadataJson = reader.readUtf8(metadataAssetPath);
            ThemeMetadata metadata = parseMetadata(metadataJson);
            if (metadata.getSource() != ThemeSource.BUILT_IN) {
                throw new ThemeLoadException("Expected a built-in theme package: " + metadata.getId());
            }
            return load(metadata, reader);
        } catch (IOException | IllegalArgumentException error) {
            throw new ThemeLoadException("Could not read built-in theme metadata: " + error.getMessage(), error);
        }
    }

    /** Loads an imported package whose metadata file is named metadata.json at its root. */
    public LoadedTheme loadImported(File packageDirectory) throws ThemeLoadException {
        try {
            ThemeResourceReader reader = new DirectoryThemeResourceReader(packageDirectory);
            ThemeMetadata metadata = parseMetadata(reader.readUtf8("metadata.json"));
            if (metadata.getSource() != ThemeSource.IMPORTED) {
                throw new ThemeLoadException("Expected an imported theme package: " + metadata.getId());
            }
            return load(metadata, reader);
        } catch (IOException | IllegalArgumentException error) {
            throw new ThemeLoadException("Could not read imported theme package: " + error.getMessage(), error);
        }
    }

    /** Loads a parsed metadata object from any package resource reader. */
    public LoadedTheme load(ThemeMetadata metadata, ThemeResourceReader resources)
        throws ThemeLoadException {
        if (metadata == null) throw new ThemeLoadException("Theme metadata is required");
        if (resources == null) throw new ThemeLoadException("Theme resource reader is required");
        try {
            String themeJson = resources.readUtf8(metadata.getThemeFile());
            VSCodeColorTheme colorTheme = documentParser.parse(themeJson, metadata);
            validateCapabilities(metadata, colorTheme);
            return new LoadedTheme(metadata, colorTheme, resources);
        } catch (IOException | IllegalArgumentException error) {
            throw new ThemeLoadException(
                "Could not read Color Theme for '" + metadata.getId() + "': " + error.getMessage(),
                error
            );
        }
    }

    /** Parses and validates the Yummy-owned metadata sidecar. */
    public ThemeMetadata parseMetadata(String json) throws ThemeLoadException {
        if (json == null || json.trim().isEmpty()) {
            throw new ThemeLoadException("Theme metadata JSON is empty");
        }
        if (json.length() > 128 * 1024) {
            throw new ThemeLoadException("Theme metadata exceeds the 128 KiB limit");
        }

        try {
            String normalized = json.charAt(0) == '\uFEFF' ? json.substring(1) : json;
            JSONObject root = new JSONObject(normalized);
            rejectUnknownMetadataFields(root);

            optionalString(root, "$schema");
            Object schemaVersionValue = root.opt("schemaVersion");
            if (!(schemaVersionValue instanceof Number)
                || ((Number) schemaVersionValue).doubleValue() != SUPPORTED_METADATA_SCHEMA_VERSION) {
                throw new ThemeLoadException("Unsupported or invalid theme metadata schemaVersion");
            }

            String id = requireString(root, "id");
            String label = requireString(root, "label");
            if (label.length() > 120) throw new ThemeLoadException("Theme label exceeds 120 characters");
            String type = requireString(root, "type");
            String source = requireString(root, "source");
            String themeFile = requireString(root, "themeFile");
            ThemeAppearance appearance = ThemeAppearance.fromVscodeValue(type);
            if (appearance == ThemeAppearance.UNKNOWN) {
                throw new ThemeLoadException("Unsupported theme appearance: " + type);
            }
            ThemeSource themeSource = ThemeSource.fromMetadataValue(source);

            JSONObject capabilities = root.optJSONObject("capabilities");
            if (capabilities == null) {
                throw new ThemeLoadException("Theme metadata 'capabilities' must be an object");
            }
            rejectUnknownCapabilityFields(capabilities);
            boolean appUi = requireBoolean(capabilities, "appUi");
            boolean editorSyntax = requireBoolean(capabilities, "editorSyntax");
            EnumSet<ThemeTarget> supportedTargets = EnumSet.noneOf(ThemeTarget.class);
            if (appUi) supportedTargets.add(ThemeTarget.APP_UI);
            if (editorSyntax) supportedTargets.add(ThemeTarget.EDITOR_SYNTAX);
            if (supportedTargets.isEmpty()) {
                throw new ThemeLoadException("Theme must support at least one target");
            }

            JSONArray defaultFor = root.optJSONArray("defaultFor");
            if (defaultFor == null) {
                throw new ThemeLoadException("Theme metadata 'defaultFor' must be an array");
            }
            if (defaultFor.length() > 2) {
                throw new ThemeLoadException("Theme metadata 'defaultFor' may contain at most two targets");
            }
            EnumSet<ThemeTarget> defaultTargets = EnumSet.noneOf(ThemeTarget.class);
            for (int i = 0; i < defaultFor.length(); i++) {
                Object value = defaultFor.opt(i);
                if (!(value instanceof String)) {
                    throw new ThemeLoadException("defaultFor[" + i + "] must be a string");
                }
                ThemeTarget target = ThemeTarget.fromMetadataValue((String) value);
                if (!defaultTargets.add(target)) {
                    throw new ThemeLoadException("Duplicate defaultFor target: " + value);
                }
            }
            if (!supportedTargets.containsAll(defaultTargets)) {
                throw new ThemeLoadException("defaultFor contains a target the theme does not support");
            }

            String author = optionalString(root, "author");
            String license = optionalString(root, "license");
            String licenseFile = optionalString(root, "licenseFile");
            String sourceUrl = optionalString(root, "sourceUrl");
            if (author != null && author.length() > 160) {
                throw new ThemeLoadException("Theme author exceeds 160 characters");
            }
            if (license != null && license.length() > 120) {
                throw new ThemeLoadException("Theme license exceeds 120 characters");
            }
            if (licenseFile != null) {
                ThemeResourcePaths.requireSafeRelativePath(licenseFile);
            }
            validateSourceUrl(sourceUrl);

            return new ThemeMetadata(
                id,
                label,
                appearance,
                themeSource,
                ThemeResourcePaths.requireSafeRelativePath(themeFile),
                author,
                license,
                licenseFile,
                sourceUrl,
                supportedTargets,
                defaultTargets
            );
        } catch (JSONException | IllegalArgumentException error) {
            throw new ThemeLoadException("Invalid theme metadata: " + error.getMessage(), error);
        }
    }

    private static void validateCapabilities(ThemeMetadata metadata, VSCodeColorTheme theme)
        throws ThemeLoadException {
        if (metadata.supports(ThemeTarget.APP_UI) && !theme.hasColors()) {
            throw new ThemeLoadException(
                "Theme '" + metadata.getId() + "' declares appUi support but has no colors object"
            );
        }
        if (metadata.supports(ThemeTarget.EDITOR_SYNTAX)
            && !theme.hasTokenColors() && !theme.hasSemanticTokenColors()) {
            throw new ThemeLoadException(
                "Theme '" + metadata.getId() + "' declares editorSyntax support but has no token rules"
            );
        }
    }

    private static void rejectUnknownMetadataFields(JSONObject root) throws ThemeLoadException {
        Set<String> allowed = new java.util.HashSet<>(java.util.Arrays.asList(
            "$schema", "schemaVersion", "id", "label", "type", "source", "themeFile",
            "author", "license", "licenseFile", "sourceUrl", "capabilities", "defaultFor"
        ));
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) {
                throw new ThemeLoadException("Unknown theme metadata property: " + key);
            }
        }
    }

    private static void rejectUnknownCapabilityFields(JSONObject capabilities)
        throws ThemeLoadException {
        Iterator<String> keys = capabilities.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!"appUi".equals(key) && !"editorSyntax".equals(key)) {
                throw new ThemeLoadException("Unknown theme capability: " + key);
            }
        }
    }

    private static String requireString(JSONObject object, String key) throws ThemeLoadException {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new ThemeLoadException("Theme metadata '" + key + "' is required and must be a string");
        }
        return ((String) value).trim();
    }

    private static String optionalString(JSONObject object, String key) throws ThemeLoadException {
        Object value = object.opt(key);
        if (value == null) return null;
        if (!(value instanceof String)) {
            throw new ThemeLoadException("Theme metadata '" + key + "' must be a string");
        }
        String result = ((String) value).trim();
        return result.isEmpty() ? null : result;
    }

    private static boolean requireBoolean(JSONObject object, String key) throws ThemeLoadException {
        Object value = object.opt(key);
        if (!(value instanceof Boolean)) {
            throw new ThemeLoadException("Theme capability '" + key + "' must be a boolean");
        }
        return (Boolean) value;
    }

    private static void validateSourceUrl(String value) throws ThemeLoadException {
        if (value == null) return;
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute()) throw new URISyntaxException(value, "URI must be absolute");
        } catch (URISyntaxException error) {
            throw new ThemeLoadException("Invalid theme sourceUrl: " + value, error);
        }
    }
}
