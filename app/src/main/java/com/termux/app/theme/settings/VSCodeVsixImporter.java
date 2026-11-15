package com.termux.app.theme.settings;

import android.content.Context;

import com.termux.app.theme.io.ThemeResourcePaths;
import com.termux.app.theme.loader.ThemeCompatibilityValidator;
import com.termux.app.theme.loader.ThemeDocumentParser;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.storage.ThemePackageStorage;
import com.termux.app.theme.storage.ThemeStorageException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports VS Code extensions directly from their .vsix archive.
 *
 * <p>The importer reads extension/package.json, discovers contributes.themes, validates each
 * declared Color Theme document, and installs each selected contribution as an independent
 * Yummy package. The theme document and referenced TextMate resource are retained (JSONC syntax is normalized).</p>
 */
public final class VSCodeVsixImporter {
    private static final long MAX_ARCHIVE_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_EXPANDED_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_THEMES = 64;
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;

    private final ThemeDocumentParser documentParser = new ThemeDocumentParser();

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
            success = true;
            return new Preview(stagingRoot, displayName, publisher, extensionName, version,
                license, licenseFile, sourceUrl, entries);
        } catch (IOException | JSONException | IllegalArgumentException error) {
            throw new ThemeImportException("Could not inspect VSIX: " + safeMessage(error), error);
        } finally {
            if (!success) deleteTree(stagingRoot);
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

    private static void extractExtension(InputStream source, File stagingRoot) throws IOException {
        int entries = 0;
        long expandedBytes = 0L;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(
            new BufferedInputStream(new BoundedInputStream(source, MAX_ARCHIVE_BYTES)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("VSIX contains too many archive entries");
                String path = validateArchivePath(entry.getName(), entry.isDirectory());
                if (entry.isDirectory()) {
                    if (path.startsWith("extension/") || "extension".equals(path)) {
                        File directory = new File(stagingRoot, path);
                        ensureInside(stagingRoot, directory);
                        if (!directory.isDirectory() && !directory.mkdirs()) {
                            throw new IOException("Could not create VSIX extension directory");
                        }
                    }
                    if (zip.read() != -1) {
                        throw new IOException("VSIX directory entry contains unexpected data");
                    }
                    zip.closeEntry();
                    continue;
                }

                // VSIX metadata outside extension/ is not needed to load Color Themes.
                if (!path.startsWith("extension/")) {
                    long ignoredFileBytes = 0L;
                    int ignoredCount;
                    while ((ignoredCount = zip.read(buffer)) != -1) {
                        ignoredFileBytes += ignoredCount;
                        expandedBytes += ignoredCount;
                        if (ignoredFileBytes > MAX_FILE_BYTES) {
                            throw new IOException("A VSIX archive file exceeds the 4 MiB extraction limit");
                        }
                        if (expandedBytes > MAX_EXPANDED_BYTES) {
                            throw new IOException("Expanded VSIX exceeds the 32 MiB extraction limit");
                        }
                    }
                    zip.closeEntry();
                    continue;
                }

                File output = new File(stagingRoot, path);
                ensureInside(stagingRoot, output);
                File parent = output.getParentFile();
                if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                    throw new IOException("Could not create VSIX resource directory");
                }
                if (!output.createNewFile()) throw new IOException("VSIX contains a duplicate file path");
                long fileBytes = 0L;
                try (FileOutputStream file = new FileOutputStream(output)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        fileBytes += count;
                        expandedBytes += count;
                        if (fileBytes > MAX_FILE_BYTES) {
                            throw new IOException("A VSIX file exceeds the 4 MiB extraction limit");
                        }
                        if (expandedBytes > MAX_EXPANDED_BYTES) {
                            throw new IOException("Expanded VSIX exceeds the 32 MiB extraction limit");
                        }
                        file.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
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

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maximum;
        private long consumed;

        BoundedInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) account(1);
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) account(count);
            return count;
        }

        private void account(int count) throws IOException {
            consumed += count;
            if (consumed > maximum) throw new IOException("VSIX archive exceeds the 16 MiB limit");
        }
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

        private Preview(File stagingRoot, String displayName, String publisher,
            String extensionName, String version, String license, String licenseFile,
            String sourceUrl, List<ThemeEntry> themes) {
            this.stagingRoot = stagingRoot;
            this.displayName = displayName;
            this.publisher = publisher;
            this.extensionName = extensionName;
            this.version = version;
            this.license = license;
            this.licenseFile = licenseFile;
            this.sourceUrl = sourceUrl;
            this.themes = Collections.unmodifiableList(new ArrayList<>(themes));
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
