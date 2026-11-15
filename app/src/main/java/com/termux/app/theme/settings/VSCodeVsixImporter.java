package com.termux.app.theme.settings;

import android.content.Context;

import com.termux.app.theme.io.ThemeResourcePaths;
import com.termux.app.theme.loader.ThemeCompatibilityValidator;
import com.termux.app.theme.loader.ThemeDocumentParser;
import com.termux.app.theme.loader.VSCodeIconThemeParser;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.VSCodeIconTheme;
import com.termux.app.theme.storage.ThemePackageStorage;
import com.termux.app.theme.storage.ThemeStorageException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports VS Code extensions directly from their .vsix archive.
 *
 * <p>The importer reads extension/package.json, discovers theme contributions, validates each
 * declared Color Theme document, and installs each selected contribution as an independent
 * Yummy package. Archive entries are indexed first; only declared theme documents, referenced
 * TextMate resources, icon-theme manifests/assets, and the license are extracted. JSONC syntax
 * is normalized for documents consumed by Yummy.</p>
 */
public final class VSCodeVsixImporter {
    private static final long MAX_ARCHIVE_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_EXPANDED_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;
    // VSIX extensions may contain thousands of SVG icons. Count every central-directory
    // entry for safety, but extract only manifest-declared theme documents and their assets.
    private static final int MAX_ARCHIVE_ENTRIES = 20000;
    private static final int MAX_SELECTED_ENTRIES = 10000;
    private static final int MAX_ICON_THEME_DOCUMENTS = 128;
    private static final int MAX_THEMES = 64;
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;

    private final ThemeDocumentParser documentParser = new ThemeDocumentParser();
    private final VSCodeIconThemeParser iconThemeParser = new VSCodeIconThemeParser();

    /** Extracts and inspects a VSIX without installing or modifying the selected file. */
    public Preview inspect(InputStream source, File cacheDirectory) throws ThemeImportException {
        if (source == null) throw new ThemeImportException("Selected VSIX file could not be opened");
        if (cacheDirectory == null) throw new ThemeImportException("Theme cache directory is unavailable");

        File stagingRoot = new File(cacheDirectory, "vsix-inspect-" + UUID.randomUUID());
        if (!stagingRoot.mkdirs()) {
            throw new ThemeImportException("Could not create temporary VSIX inspection directory");
        }

        boolean success = false;
        try {
            extractExtension(source, stagingRoot);
            File extensionRoot = new File(stagingRoot, "extension");
            File packageJson = new File(extensionRoot, "package.json");
            if (!packageJson.isFile()) {
                throw new IOException("VSIX does not contain extension/package.json");
            }

            JSONObject manifest = new JSONObject(ThemeDocumentParser.normalizeJsonc(
                readUtf8Limited(packageJson, MAX_JSON_BYTES)));
            String extensionName = optionalString(manifest, "name");
            String publisher = optionalString(manifest, "publisher");
            String displayName = optionalString(manifest, "displayName");
            String version = optionalString(manifest, "version");
            String license = optionalString(manifest, "license");
            String sourceUrl = repositoryUrl(manifest.opt("repository"));
            if (extensionName == null || publisher == null || version == null) {
                throw new IOException("extension/package.json must declare publisher, name, and version");
            }
            if (displayName == null) displayName = publisher + "." + extensionName;

            JSONObject contributes = manifest.optJSONObject("contributes");
            JSONArray themes = contributes == null ? null : contributes.optJSONArray("themes");
            if (themes == null || themes.length() == 0) {
                throw new IOException("VSIX does not declare contributes.themes");
            }
            if (themes.length() > MAX_THEMES) {
                throw new IOException("VSIX declares more than " + MAX_THEMES + " color themes");
            }

            String licenseFile = findLicenseFile(extensionRoot);
            List<ThemeEntry> entries = new ArrayList<>();
            for (int i = 0; i < themes.length(); i++) {
                JSONObject contribution = themes.optJSONObject(i);
                if (contribution == null) {
                    throw new IOException("contributes.themes[" + i + "] must be an object");
                }
                String label = optionalString(contribution, "label");
                String rawPath = optionalString(contribution, "path");
                String uiTheme = optionalString(contribution, "uiTheme");
                if (label == null || rawPath == null || uiTheme == null) {
                    throw new IOException("contributes.themes[" + i
                        + "] must declare label, uiTheme, and path");
                }

                ThemeAppearance appearance = ThemeAppearance.fromVscodeUiTheme(uiTheme);
                if (appearance == ThemeAppearance.UNKNOWN) {
                    throw new IOException("Unsupported VS Code uiTheme '" + uiTheme
                        + "' for contribution '" + label + "'");
                }

                String themePath = normalizeExtensionPath(rawPath);
                File themeFile = resolveInside(extensionRoot, themePath);
                if (!themeFile.isFile()) {
                    throw new IOException("Theme file declared by '" + label
                        + "' does not exist: " + themePath);
                }
                String normalizedJson = VSCodeThemeJsonImporter.normalizeJsonc(
                    readUtf8Limited(themeFile, MAX_JSON_BYTES));

                final com.termux.app.theme.model.VSCodeColorTheme parsed;
                try {
                    parsed = documentParser.parse(normalizedJson);
                } catch (Exception error) {
                    throw new IOException("Invalid Color Theme '" + label + "': "
                        + safeMessage(error), error);
                }
                if (parsed.getAppearance() != ThemeAppearance.UNKNOWN
                    && parsed.getAppearance() != appearance) {
                    throw new IOException("Color Theme type conflicts with uiTheme for '" + label + "'");
                }

                String tokenReference = parsed.getTokenColorsReference();
                if (tokenReference != null) {
                    String safeReference = normalizeResourceReference(tokenReference);
                    File referenced = resolveInside(extensionRoot, safeReference);
                    if (!referenced.isFile()) {
                        throw new IOException("Theme '" + label
                            + "' references a missing TextMate resource: " + safeReference);
                    }
                }

                boolean appUi = parsed.hasColors();
                boolean editorSyntax = parsed.hasTokenColors();
                if (!appUi && !editorSyntax) {
                    throw new IOException("Theme '" + label
                        + "' has no importable colors or TextMate token rules");
                }

                String effectiveLabel = label.length() > 120 ? label.substring(0, 120) : label;
                String suggestedId = createThemeId(publisher + "-" + extensionName + "-" + label);
                entries.add(new ThemeEntry(
                    effectiveLabel, suggestedId, appearance, uiTheme, themePath,
                    normalizedJson, tokenReference, appUi, editorSyntax
                ));
            }

            if (entries.isEmpty()) throw new IOException("VSIX contains no importable color themes");

            // Icon Themes are optional for the Color Theme import flow. Parse each declared
            // contribution independently so an unsupported/malformed icon manifest does not
            // prevent the Color Themes in the same VSIX from being previewed or installed.
            List<IconThemeEntry> iconEntries = new ArrayList<>();
            List<String> iconThemeWarnings = new ArrayList<>();
            inspectIconThemes(extensionRoot, contributes == null ? null
                : contributes.optJSONArray("iconThemes"), publisher, extensionName,
                iconEntries, iconThemeWarnings);

            success = true;
            return new Preview(stagingRoot, displayName, publisher, extensionName, version,
                license, licenseFile, sourceUrl, entries, iconEntries, iconThemeWarnings);
        } catch (IOException | JSONException | IllegalArgumentException error) {
            throw new ThemeImportException("Could not inspect VSIX: " + safeMessage(error), error);
        } finally {
            if (!success) deleteTree(stagingRoot);
        }
    }

    private void inspectIconThemes(
        File extensionRoot,
        JSONArray contributions,
        String publisher,
        String extensionName,
        List<IconThemeEntry> entries,
        List<String> warnings
    ) {
        if (contributions == null) return;
        int limit = Math.min(contributions.length(), MAX_ICON_THEME_DOCUMENTS);
        if (contributions.length() > limit) {
            warnings.add("Only the first " + MAX_ICON_THEME_DOCUMENTS
                + " Icon Theme contributions were inspected");
        }

        for (int i = 0; i < limit; i++) {
            JSONObject contribution = contributions.optJSONObject(i);
            String label = optionalString(contribution, "label");
            String contributionId = optionalString(contribution, "id");
            String rawPath = optionalString(contribution, "path");
            if (label == null || contributionId == null || rawPath == null) {
                warnings.add("contributes.iconThemes[" + i
                    + "] must declare id, label, and path; contribution was skipped");
                continue;
            }

            try {
                String themePath = normalizeExtensionPath(rawPath);
                File themeFile = resolveInside(extensionRoot, themePath);
                if (!themeFile.isFile()) {
                    warnings.add("Icon Theme '" + label + "' manifest was not extracted: "
                        + themePath);
                    continue;
                }

                String normalizedJson = ThemeDocumentParser.normalizeJsonc(
                    readUtf8Limited(themeFile, MAX_JSON_BYTES));
                VSCodeIconTheme parsed = iconThemeParser.parse(normalizedJson, themePath);
                List<String> resourcePaths = parsed.getReferencedResourcePaths(themePath);
                List<String> entryWarnings = new ArrayList<>(parsed.getWarnings());
                for (String resourcePath : resourcePaths) {
                    File resource = resolveInside(extensionRoot, resourcePath);
                    if (!resource.isFile()) {
                        entryWarnings.add("Referenced icon asset is unavailable: " + resourcePath);
                    }
                }

                String effectiveLabel = label.length() > 120 ? label.substring(0, 120) : label;
                String suggestedId = createThemeId(
                    publisher + "-" + extensionName + "-" + contributionId);
                entries.add(new IconThemeEntry(effectiveLabel, contributionId, suggestedId,
                    themePath, normalizedJson, parsed, resourcePaths, entryWarnings));
            } catch (Exception error) {
                warnings.add("Icon Theme '" + label + "' was skipped: " + safeMessage(error));
            }
        }
    }

    /**
     * Installs one contribution as a standalone Yummy theme package. Callers may invoke this
     * for every entry in a VSIX preview to import all variants in one operation.
     */
    public String installTheme(
        Context context,
        ThemePackageStorage storage,
        Preview preview,
        ThemeEntry entry,
        String themeId
    ) throws ThemeImportException {
        if (context == null) throw new ThemeImportException("Context is required");
        if (storage == null) throw new ThemeImportException("ThemePackageStorage is required");
        if (preview == null || entry == null) throw new ThemeImportException("VSIX theme selection is required");

        String safeId = sanitizeThemeId(themeId == null ? entry.getSuggestedId() : themeId);
        File packageDirectory = new File(context.getCacheDir(), "vsix-theme-" + UUID.randomUUID());
        try {
            if (!packageDirectory.mkdir()) {
                throw new IOException("Could not create temporary Yummy theme package");
            }
            File extensionRoot = new File(preview.getStagingRoot(), "extension");
            // Store the normalized JSONC document so the package loader can parse it as strict
            // JSON later. The selected source VSIX remains untouched.
            writeUtf8(new File(packageDirectory, entry.getThemePath()), entry.getNormalizedJson());
            if (entry.getTokenColorsReference() != null) {
                String reference = normalizeResourceReference(entry.getTokenColorsReference());
                if (!reference.equals(entry.getThemePath())) {
                    if (reference.toLowerCase(Locale.ROOT).endsWith(".json")) {
                        File sourceReference = resolveInside(extensionRoot, reference);
                        String normalizedReference = VSCodeThemeJsonImporter.normalizeJsonc(
                            readUtf8Limited(sourceReference, MAX_JSON_BYTES));
                        writeUtf8(new File(packageDirectory, reference), normalizedReference);
                    } else {
                        copyResource(extensionRoot, reference, packageDirectory);
                    }
                }
            }

            String licenseFile = preview.getLicenseFile();
            if (licenseFile != null) copyResource(extensionRoot, licenseFile, packageDirectory);

            writeUtf8(new File(packageDirectory, "metadata.json"),
                createMetadata(preview, entry, safeId).toString());
            storage.importPackage(packageDirectory);
            return safeId;
        } catch (IOException | JSONException error) {
            throw new ThemeImportException("Could not prepare VSIX theme package: "
                + safeMessage(error), error);
        } catch (ThemeStorageException error) {
            throw new ThemeImportException("Could not install VSIX theme: "
                + safeMessage(error), error);
        } finally {
            deleteTree(packageDirectory);
        }
    }

    /** Removes the temporary extracted VSIX after import or cancellation. */
    public void cleanup(Preview preview) {
        if (preview != null) deleteTree(preview.getStagingRoot());
    }

    private void extractExtension(InputStream source, File stagingRoot)
        throws IOException, JSONException, ThemeImportException {
        File archiveFile = new File(stagingRoot, ".source.vsix");
        try {
            copyBoundedArchive(source, archiveFile);
            try (ZipFile archive = new ZipFile(archiveFile)) {
                Map<String, ZipEntry> entries = indexArchiveEntries(archive);
                ZipEntry packageEntry = entries.get("extension/package.json");
                if (packageEntry == null || packageEntry.isDirectory()) {
                    throw new IOException("VSIX does not contain extension/package.json");
                }

                String manifestText = readArchiveEntryUtf8(archive, packageEntry, MAX_JSON_BYTES);
                JSONObject manifest = new JSONObject(
                    ThemeDocumentParser.normalizeJsonc(manifestText));
                Set<String> requiredPaths = new LinkedHashSet<>();
                Set<String> optionalPaths = new LinkedHashSet<>();
                collectRequiredArchiveEntries(
                    archive, entries, manifest, requiredPaths, optionalPaths);

                if (requiredPaths.size() > MAX_SELECTED_ENTRIES) {
                    throw new IOException("VSIX declares too many required theme resources");
                }

                long expandedBytes = 0L;
                int extractedEntries = 0;
                for (String path : requiredPaths) {
                    ZipEntry entry = entries.get(path);
                    if (entry == null || entry.isDirectory()) {
                        throw new IOException("VSIX theme resource is missing: " + path);
                    }
                    expandedBytes = extractSelectedEntry(
                        archive, entry, stagingRoot, expandedBytes);
                    extractedEntries++;
                }

                // Icon assets are optional during Color Theme import. Extract them only when
                // they fit the same per-file, total-size, and selected-entry budgets.
                for (String path : optionalPaths) {
                    if (requiredPaths.contains(path)) continue;
                    if (extractedEntries >= MAX_SELECTED_ENTRIES) break;
                    ZipEntry entry = entries.get(path);
                    if (entry == null || entry.isDirectory()) continue;
                    long declaredSize = entry.getSize();
                    if (declaredSize < 0L || declaredSize > MAX_FILE_BYTES
                        || expandedBytes + declaredSize > MAX_EXPANDED_BYTES) {
                        continue;
                    }
                    try {
                        expandedBytes = extractSelectedEntry(
                            archive, entry, stagingRoot, expandedBytes);
                        extractedEntries++;
                    } catch (IOException optionalAssetError) {
                        File incomplete = new File(stagingRoot, path);
                        //noinspection ResultOfMethodCallIgnored
                        incomplete.delete();
                        // A broken/oversized optional icon must not block Color Theme import.
                    }
                }
            }
        } finally {
            // The compressed source archive is only a temporary random-access index.
            // The staging directory retains only selected theme documents and assets.
            //noinspection ResultOfMethodCallIgnored
            archiveFile.delete();
        }
    }

    private static Map<String, ZipEntry> indexArchiveEntries(ZipFile archive)
        throws IOException {
        Map<String, ZipEntry> entries = new LinkedHashMap<>();
        Enumeration<? extends ZipEntry> enumeration = archive.entries();
        int count = 0;
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            if (++count > MAX_ARCHIVE_ENTRIES) {
                throw new IOException("VSIX contains too many archive entries (limit "
                    + MAX_ARCHIVE_ENTRIES + ")");
            }

            String path = validateArchivePath(entry.getName(), entry.isDirectory());
            if (entries.put(path, entry) != null) {
                throw new IOException("VSIX contains a duplicate archive path: " + path);
            }
        }
        return entries;
    }

    /**
     * Builds a whitelist from package.json and the documents referenced by its contributions.
     * Color theme JSON and TextMate references are required. Icon and product-icon theme
     * documents/assets are collected opportunistically so later import stages can use them,
     * without making a broken optional icon contribution block Color Theme import.
     */
    private void collectRequiredArchiveEntries(
        ZipFile archive,
        Map<String, ZipEntry> entries,
        JSONObject manifest,
        Set<String> required,
        Set<String> optional
    ) throws IOException, JSONException, ThemeImportException {
        addRequiredArchiveEntry(entries, required, "extension/package.json");

        String[] licenseCandidates = {
            "extension/LICENSE", "extension/LICENSE.txt",
            "extension/LICENSE.md", "extension/LICENSE-MIT"
        };
        for (String candidate : licenseCandidates) {
            ZipEntry license = entries.get(candidate);
            if (license != null && !license.isDirectory()) {
                required.add(candidate);
                break;
            }
        }

        JSONObject contributes = manifest.optJSONObject("contributes");
        if (contributes == null) return;

        JSONArray themes = contributes.optJSONArray("themes");
        if (themes != null) {
            if (themes.length() > MAX_THEMES) {
                throw new IOException("VSIX declares more than " + MAX_THEMES + " color themes");
            }
            for (int i = 0; i < themes.length(); i++) {
                JSONObject contribution = themes.optJSONObject(i);
                if (contribution == null) {
                    throw new IOException("contributes.themes[" + i + "] must be an object");
                }
                String rawPath = optionalString(contribution, "path");
                if (rawPath == null) {
                    throw new IOException("contributes.themes[" + i + "] must declare path");
                }

                String themePath = normalizeExtensionPath(rawPath);
                String archivePath = "extension/" + themePath;
                ZipEntry themeEntry = entries.get(archivePath);
                if (themeEntry == null || themeEntry.isDirectory()) {
                    throw new IOException("Theme file declared by contributes.themes[" + i
                        + "] does not exist: " + themePath);
                }
                required.add(archivePath);

                String themeJson = VSCodeThemeJsonImporter.normalizeJsonc(
                    readArchiveEntryUtf8(archive, themeEntry, MAX_JSON_BYTES));
                com.termux.app.theme.model.VSCodeColorTheme parsed;
                try {
                    parsed = documentParser.parse(themeJson);
                } catch (Exception error) {
                    throw new IOException("Invalid Color Theme at " + themePath + ": "
                        + safeMessage(error), error);
                }

                String tokenReference = parsed.getTokenColorsReference();
                if (tokenReference != null) {
                    String safeReference = normalizeResourceReference(tokenReference);
                    addRequiredArchiveEntry(
                        entries, required, "extension/" + safeReference);
                }
            }
        }

        collectIconThemeResources(
            archive, entries, contributes.optJSONArray("iconThemes"), optional);
        collectProductIconThemeResources(
            archive, entries, contributes.optJSONArray("productIconThemes"), optional);
    }

    private static void collectIconThemeResources(
        ZipFile archive,
        Map<String, ZipEntry> entries,
        JSONArray contributions,
        Set<String> optional
    ) {
        if (contributions == null) return;
        int limit = Math.min(contributions.length(), MAX_ICON_THEME_DOCUMENTS);
        for (int i = 0; i < limit; i++) {
            JSONObject contribution = contributions.optJSONObject(i);
            String rawPath = optionalString(contribution, "path");
            if (rawPath == null) continue;

            final String themePath;
            try {
                themePath = normalizeExtensionPath(rawPath);
            } catch (IOException ignored) {
                continue;
            }

            String archivePath = "extension/" + themePath;
            ZipEntry themeEntry = entries.get(archivePath);
            if (themeEntry == null || themeEntry.isDirectory()) continue;
            optional.add(archivePath);

            final JSONObject iconTheme;
            try {
                iconTheme = new JSONObject(ThemeDocumentParser.normalizeJsonc(
                    readArchiveEntryUtf8(archive, themeEntry, MAX_JSON_BYTES)));
            } catch (Exception ignored) {
                // A malformed optional icon theme must not prevent Color Theme import.
                continue;
            }

            JSONObject definitions = iconTheme.optJSONObject("iconDefinitions");
            if (definitions != null) {
                java.util.Iterator<String> keys = definitions.keys();
                while (keys.hasNext()) {
                    JSONObject definition = definitions.optJSONObject(keys.next());
                    String iconPath = optionalString(definition, "iconPath");
                    if (iconPath == null) continue;

                    try {
                        String relativePath = resolveContributionResourcePath(themePath, iconPath);
                        if (relativePath == null) continue;
                        addOptionalArchiveEntry(entries, optional, "extension/" + relativePath);
                    } catch (IOException ignored) {
                        // Unsafe or external icon references are not extracted.
                    }
                }
            }

            // Glyph-based Icon Themes can reference WOFF/TTF/OTF assets instead of SVG/PNG.
            // Keep their font files alongside image assets for the Icon Theme parser/runtime.
            JSONArray fonts = iconTheme.optJSONArray("fonts");
            if (fonts != null) {
                for (int fontIndex = 0; fontIndex < fonts.length(); fontIndex++) {
                    JSONObject font = fonts.optJSONObject(fontIndex);
                    JSONArray sources = font == null ? null : font.optJSONArray("src");
                    if (sources == null) continue;
                    for (int sourceIndex = 0; sourceIndex < sources.length(); sourceIndex++) {
                        JSONObject fontSource = sources.optJSONObject(sourceIndex);
                        String fontPath = optionalString(fontSource, "path");
                        if (fontPath == null) continue;
                        try {
                            String relativePath = resolveContributionResourcePath(themePath, fontPath);
                            if (relativePath != null) {
                                addOptionalArchiveEntry(entries, optional, "extension/" + relativePath);
                            }
                        } catch (IOException ignored) {
                            // Unsafe or external font references are not extracted.
                        }
                    }
                }
            }
        }
    }

    private static void collectProductIconThemeResources(
        ZipFile archive,
        Map<String, ZipEntry> entries,
        JSONArray contributions,
        Set<String> optional
    ) {
        if (contributions == null) return;
        int limit = Math.min(contributions.length(), MAX_ICON_THEME_DOCUMENTS);
        for (int i = 0; i < limit; i++) {
            JSONObject contribution = contributions.optJSONObject(i);
            String rawPath = optionalString(contribution, "path");
            if (rawPath == null) continue;

            final String themePath;
            try {
                themePath = normalizeExtensionPath(rawPath);
            } catch (IOException ignored) {
                continue;
            }

            String archivePath = "extension/" + themePath;
            ZipEntry themeEntry = entries.get(archivePath);
            if (themeEntry == null || themeEntry.isDirectory()) continue;
            optional.add(archivePath);

            final JSONObject productTheme;
            try {
                productTheme = new JSONObject(ThemeDocumentParser.normalizeJsonc(
                    readArchiveEntryUtf8(archive, themeEntry, MAX_JSON_BYTES)));
            } catch (Exception ignored) {
                continue;
            }

            JSONArray fonts = productTheme.optJSONArray("fonts");
            if (fonts == null) continue;
            for (int fontIndex = 0; fontIndex < fonts.length(); fontIndex++) {
                JSONObject font = fonts.optJSONObject(fontIndex);
                JSONArray sources = font == null ? null : font.optJSONArray("src");
                if (sources == null) continue;
                for (int sourceIndex = 0; sourceIndex < sources.length(); sourceIndex++) {
                    JSONObject fontSource = sources.optJSONObject(sourceIndex);
                    String fontPath = optionalString(fontSource, "path");
                    if (fontPath == null) continue;
                    try {
                        String relativePath = resolveContributionResourcePath(themePath, fontPath);
                        if (relativePath != null) {
                            addOptionalArchiveEntry(
                                entries, optional, "extension/" + relativePath);
                        }
                    } catch (IOException ignored) {
                        // Unsafe or external font references are not extracted.
                    }
                }
            }
        }
    }

    private static void addRequiredArchiveEntry(
        Map<String, ZipEntry> entries, Set<String> selected, String path
    ) throws IOException {
        ZipEntry entry = entries.get(path);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("VSIX theme resource is missing: " + path);
        }
        selected.add(path);
    }

    private static void addOptionalArchiveEntry(
        Map<String, ZipEntry> entries, Set<String> selected, String path
    ) {
        ZipEntry entry = entries.get(path);
        if (entry != null && !entry.isDirectory()) selected.add(path);
    }

    /**
     * Resolve a resource path relative to a contribution JSON document. Parent segments are
     * allowed only while they remain inside the extension root (for example ../icons/a.svg).
     * External URI references are returned as null and are never downloaded.
     */
    private static String resolveContributionResourcePath(
        String contributionPath, String rawReference
    ) throws IOException {
        if (rawReference == null) return null;
        String reference = rawReference.trim();
        if (reference.isEmpty()) return null;
        if (reference.startsWith("//")
            || reference.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            return null;
        }
        if (reference.startsWith("/") || reference.indexOf('\\') >= 0
            || reference.indexOf('\0') >= 0) {
            throw new IOException("Icon resource path must be relative");
        }

        int slash = contributionPath.lastIndexOf('/');
        String base = slash < 0 ? "" : contributionPath.substring(0, slash);
        String combined = base.isEmpty() ? reference : base + "/" + reference;
        List<String> segments = new ArrayList<>();
        for (String segment : combined.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    throw new IOException("Icon resource path escapes extension directory");
                }
                segments.remove(segments.size() - 1);
            } else {
                if (segment.indexOf(':') >= 0) {
                    throw new IOException("Icon resource path contains an invalid segment");
                }
                segments.add(segment);
            }
        }
        if (segments.isEmpty()) throw new IOException("Icon resource path is empty");
        StringBuilder normalized = new StringBuilder();
        for (String segment : segments) {
            if (normalized.length() > 0) normalized.append('/');
            normalized.append(segment);
        }
        return normalized.toString();
    }

    private static void copyBoundedArchive(InputStream source, File archiveFile)
        throws IOException {
        try (InputStream input = source;
             FileOutputStream output = new FileOutputStream(archiveFile)) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_ARCHIVE_BYTES) {
                    throw new IOException("VSIX archive exceeds the 16 MiB compressed size limit");
                }
                output.write(buffer, 0, count);
            }
            output.flush();
            output.getFD().sync();
        }
    }

    private static String readArchiveEntryUtf8(
        ZipFile archive, ZipEntry entry, int maximumBytes
    ) throws IOException {
        if (entry.getSize() > maximumBytes) {
            throw new IOException("VSIX JSON exceeds the 2 MiB limit");
        }
        try (InputStream input = archive.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) {
                    throw new IOException("VSIX JSON exceeds the 2 MiB limit");
                }
                output.write(buffer, 0, count);
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray())).toString();
            } catch (CharacterCodingException error) {
                throw new IOException("VSIX JSON is not valid UTF-8", error);
            }
        }
    }

    private static long extractSelectedEntry(
        ZipFile archive, ZipEntry entry, File stagingRoot, long expandedBytes
    ) throws IOException {
        if (entry.getSize() > MAX_FILE_BYTES) {
            throw new IOException("A selected VSIX resource exceeds the 4 MiB extraction limit");
        }
        if (entry.getSize() > 0L && expandedBytes + entry.getSize() > MAX_EXPANDED_BYTES) {
            throw new IOException("Selected VSIX resources exceed the 32 MiB extraction limit");
        }

        String path = validateArchivePath(entry.getName(), false);
        if (!path.startsWith("extension/")) {
            throw new IOException("Refusing to extract VSIX content outside extension/");
        }
        File output = new File(stagingRoot, path);
        ensureInside(stagingRoot, output);
        File parent = output.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Could not create VSIX resource directory");
        }
        if (!output.createNewFile()) {
            throw new IOException("VSIX contains a duplicate selected resource: " + path);
        }

        long fileBytes = 0L;
        try (InputStream input = archive.getInputStream(entry);
             FileOutputStream file = new FileOutputStream(output)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                fileBytes += count;
                if (fileBytes > MAX_FILE_BYTES) {
                    throw new IOException("A VSIX file exceeds the 4 MiB extraction limit");
                }
                if (expandedBytes + fileBytes > MAX_EXPANDED_BYTES) {
                    throw new IOException("Selected VSIX resources exceed the 32 MiB extraction limit");
                }
                file.write(buffer, 0, count);
            }
            file.flush();
            file.getFD().sync();
        }
        return expandedBytes + fileBytes;
    }

    private static String validateArchivePath(String raw, boolean directory) throws IOException {
        if (raw == null || raw.isEmpty() || raw.length() > 512
            || raw.startsWith("/") || raw.indexOf('\\') >= 0 || raw.indexOf('\0') >= 0
            || raw.matches("^[A-Za-z]:.*")) {
            throw new IOException("VSIX contains an unsafe archive path");
        }
        String path = directory && raw.endsWith("/")
            ? raw.substring(0, raw.length() - 1) : raw;
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("VSIX contains a path traversal entry");
            }
        }
        return path;
    }

    private static String normalizeExtensionPath(String raw) throws IOException {
        String path = raw.trim().replace('\\', '/');
        while (path.startsWith("./")) path = path.substring(2);
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new IOException("Theme contribution path must be relative");
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("Theme contribution path contains an unsafe segment");
            }
        }
        try {
            return ThemeResourcePaths.requireSafeRelativePath(path);
        } catch (IllegalArgumentException error) {
            throw new IOException("Unsupported theme contribution path: " + raw, error);
        }
    }

    private static String normalizeResourceReference(String raw) throws ThemeImportException {
        try {
            return ThemeCompatibilityValidator.normalizeTokenColorsReference(raw);
        } catch (Exception error) {
            throw new ThemeImportException("Unsafe or unsupported TextMate resource reference: "
                + raw, error);
        }
    }

    private static File resolveInside(File root, String relativePath) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File target = new File(canonicalRoot, relativePath).getCanonicalFile();
        if (!target.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
            throw new IOException("VSIX resource escapes extension directory: " + relativePath);
        }
        return target;
    }

    private static void copyResource(File sourceRoot, String relativePath, File destinationRoot)
        throws IOException {
        File source = resolveInside(sourceRoot, relativePath);
        if (!source.isFile()) throw new IOException("Missing VSIX resource: " + relativePath);
        File destination = new File(destinationRoot, relativePath);
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Could not create imported theme resource directory");
        }
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            output.getFD().sync();
        }
    }

    private static JSONObject createMetadata(Preview preview, ThemeEntry entry, String id)
        throws JSONException {
        JSONObject capabilities = new JSONObject();
        capabilities.put("appUi", entry.supportsAppUi());
        capabilities.put("editorSyntax", entry.supportsEditorSyntax());

        JSONObject metadata = new JSONObject();
        metadata.put("schemaVersion", 1);
        metadata.put("version", preview.getVersion());
        metadata.put("id", id);
        metadata.put("label", entry.getLabel());
        metadata.put("type", entry.getAppearance().getVscodeValue());
        metadata.put("source", "imported");
        metadata.put("themeFile", entry.getThemePath());
        metadata.put("capabilities", capabilities);
        metadata.put("defaultFor", new JSONArray());
        metadata.put("author", preview.getPublisher());
        if (preview.getLicense() != null) metadata.put("license", preview.getLicense());
        if (preview.getLicenseFile() != null) metadata.put("licenseFile", preview.getLicenseFile());
        if (preview.getSourceUrl() != null) metadata.put("sourceUrl", preview.getSourceUrl());
        return metadata;
    }

    private static String findLicenseFile(File extensionRoot) {
        String[] candidates = {"LICENSE", "LICENSE.txt", "LICENSE.md", "LICENSE-MIT"};
        for (String candidate : candidates) {
            File file = new File(extensionRoot, candidate);
            if (file.isFile()) return candidate;
        }
        return null;
    }

    private static String repositoryUrl(Object repository) {
        if (repository instanceof String) return nonBlank((String) repository);
        if (repository instanceof JSONObject) {
            return nonBlank(((JSONObject) repository).optString("url", null));
        }
        return null;
    }

    private static String optionalString(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        return value instanceof String ? nonBlank((String) value) : null;
    }

    private static String nonBlank(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private static String createThemeId(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        String id = normalized.replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^[^a-z0-9]+|[-._]+$", "");
        if (id.isEmpty()) id = "imported-vsix-theme";
        return id.length() <= 64 ? id : id.substring(0, 64).replaceAll("[-._]+$", "");
    }

    private static String sanitizeThemeId(String value) throws ThemeImportException {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new ThemeImportException("Generated VSIX theme ID is invalid: " + id);
        }
        return id;
    }

    private static String readUtf8Limited(File file, int maximumBytes) throws IOException {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) throw new IOException("VSIX JSON exceeds the 2 MiB limit");
                output.write(buffer, 0, count);
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray())).toString();
            } catch (CharacterCodingException error) {
                throw new IOException("VSIX JSON is not valid UTF-8", error);
            }
        }
    }

    private static void writeUtf8(File file, String value) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create metadata directory");
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
    }

    private static void ensureInside(File root, File candidate) throws IOException {
        String rootPath = root.getCanonicalPath();
        String candidatePath = candidate.getCanonicalPath();
        if (!candidatePath.startsWith(rootPath + File.separator)) {
            throw new IOException("VSIX archive path escapes temporary extraction directory");
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error == null ? "Unknown error" : error.getClass().getSimpleName()
            : message;
    }

    public static final class Preview {
        private final File stagingRoot;
        private final String displayName;
        private final String publisher;
        private final String extensionName;
        private final String version;
        private final String license;
        private final String licenseFile;
        private final String sourceUrl;
        private final List<ThemeEntry> themes;
        private final List<IconThemeEntry> iconThemes;
        private final List<String> iconThemeWarnings;

        private Preview(File stagingRoot, String displayName, String publisher,
            String extensionName, String version, String license, String licenseFile,
            String sourceUrl, List<ThemeEntry> themes, List<IconThemeEntry> iconThemes,
            List<String> iconThemeWarnings) {
            this.stagingRoot = stagingRoot;
            this.displayName = displayName;
            this.publisher = publisher;
            this.extensionName = extensionName;
            this.version = version;
            this.license = license;
            this.licenseFile = licenseFile;
            this.sourceUrl = sourceUrl;
            this.themes = Collections.unmodifiableList(new ArrayList<>(themes));
            this.iconThemes = Collections.unmodifiableList(new ArrayList<>(iconThemes));
            this.iconThemeWarnings = Collections.unmodifiableList(new ArrayList<>(iconThemeWarnings));
        }

        public File getStagingRoot() { return stagingRoot; }
        public String getDisplayName() { return displayName; }
        public String getPublisher() { return publisher; }
        public String getExtensionName() { return extensionName; }
        public String getVersion() { return version; }
        public String getLicense() { return license; }
        public String getLicenseFile() { return licenseFile; }
        public String getSourceUrl() { return sourceUrl; }
        public List<ThemeEntry> getThemes() { return themes; }
        public List<IconThemeEntry> getIconThemes() { return iconThemes; }
        public List<String> getIconThemeWarnings() { return iconThemeWarnings; }
    }

    /** Parsed VS Code File Icon Theme contribution exposed to later import/preview stages. */
    public static final class IconThemeEntry {
        private final String label;
        private final String contributionId;
        private final String suggestedId;
        private final String themePath;
        private final String normalizedJson;
        private final VSCodeIconTheme parsedTheme;
        private final List<String> resourcePaths;
        private final List<String> warnings;

        private IconThemeEntry(String label, String contributionId, String suggestedId,
            String themePath, String normalizedJson, VSCodeIconTheme parsedTheme,
            List<String> resourcePaths, List<String> warnings) {
            this.label = label;
            this.contributionId = contributionId;
            this.suggestedId = suggestedId;
            this.themePath = themePath;
            this.normalizedJson = normalizedJson;
            this.parsedTheme = parsedTheme;
            this.resourcePaths = Collections.unmodifiableList(new ArrayList<>(resourcePaths));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        public String getLabel() { return label; }
        public String getContributionId() { return contributionId; }
        public String getSuggestedId() { return suggestedId; }
        public String getThemePath() { return themePath; }
        public String getNormalizedJson() { return normalizedJson; }
        public VSCodeIconTheme getParsedTheme() { return parsedTheme; }
        public List<String> getResourcePaths() { return resourcePaths; }
        public List<String> getWarnings() { return warnings; }
    }

    public static final class ThemeEntry {
        private final String label;
        private final String suggestedId;
        private final ThemeAppearance appearance;
        private final String uiTheme;
        private final String themePath;
        private final String normalizedJson;
        private final String tokenColorsReference;
        private final boolean supportsAppUi;
        private final boolean supportsEditorSyntax;

        private ThemeEntry(String label, String suggestedId, ThemeAppearance appearance,
            String uiTheme, String themePath, String normalizedJson, String tokenColorsReference,
            boolean supportsAppUi, boolean supportsEditorSyntax) {
            this.label = label;
            this.suggestedId = suggestedId;
            this.appearance = appearance;
            this.uiTheme = uiTheme;
            this.themePath = themePath;
            this.normalizedJson = normalizedJson;
            this.tokenColorsReference = tokenColorsReference;
            this.supportsAppUi = supportsAppUi;
            this.supportsEditorSyntax = supportsEditorSyntax;
        }

        public String getLabel() { return label; }
        public String getSuggestedId() { return suggestedId; }
        public ThemeAppearance getAppearance() { return appearance; }
        public String getUiTheme() { return uiTheme; }
        public String getThemePath() { return themePath; }
        public String getNormalizedJson() { return normalizedJson; }
        public String getTokenColorsReference() { return tokenColorsReference; }
        public boolean supportsAppUi() { return supportsAppUi; }
        public boolean supportsEditorSyntax() { return supportsEditorSyntax; }
    }
}
