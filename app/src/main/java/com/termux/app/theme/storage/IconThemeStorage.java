package com.termux.app.theme.storage;

import android.content.Context;

import com.termux.app.theme.loader.ThemeLoadException;
import com.termux.app.theme.loader.VSCodeIconThemeParser;
import com.termux.app.theme.model.VSCodeIconTheme;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Stores imported VS Code File Icon Themes independently from Color Theme packages.
 * Only the selected Icon Theme document and its referenced local image/font assets are copied.
 */
public final class IconThemeStorage {
    public static final String ROOT_DIRECTORY_NAME = "icon-themes";
    public static final String IMPORTED_DIRECTORY_NAME = "imported";
    public static final String STAGING_DIRECTORY_NAME = ".staging";
    private static final String METADATA_FILE = "icon-theme-metadata.json";
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final int MAX_ASSETS = 10000;
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;
    private static final long MAX_PACKAGE_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;

    private final File filesDirectory;
    private final VSCodeIconThemeParser parser = new VSCodeIconThemeParser();

    public IconThemeStorage(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        Context app = context.getApplicationContext();
        this.filesDirectory = (app == null ? context : app).getFilesDir();
        if (filesDirectory == null) throw new IllegalArgumentException("App files directory is unavailable");
    }

    public synchronized File getImportedIconThemesDirectory() throws IOException {
        Directories dirs = ensureDirectories();
        return dirs.imported;
    }

    /** Installs a VSIX Icon Theme and only its manifest-referenced assets. */
    public synchronized InstalledIconTheme installFromVsix(
        String id,
        String label,
        String publisher,
        String extensionName,
        String version,
        String license,
        String licenseFile,
        File extensionRoot,
        String themeDocumentPath,
        String normalizedJson,
        List<String> resourcePaths
    ) throws IOException, ThemeLoadException {
        String safeId = requireId(id);
        String safeLabel = requireText(label, "Icon Theme label", 120);
        if (extensionRoot == null || !extensionRoot.isDirectory()) {
            throw new IOException("VSIX extension resource directory is unavailable");
        }
        String safeThemePath = normalizeRelativePath(themeDocumentPath);
        if (normalizedJson == null || normalizedJson.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IOException("Icon Theme JSON is missing or exceeds the 4 MiB limit");
        }
        VSCodeIconTheme parsed = parser.parse(normalizedJson, safeThemePath);
        List<String> declaredResources = parsed.getReferencedResourcePaths(safeThemePath);
        if (resourcePaths != null && resourcePaths.size() > MAX_ASSETS) {
            throw new IOException("Icon Theme references too many assets");
        }
        // The parsed document is authoritative. Ignore any path not declared by the document.
        List<String> safeResources = new ArrayList<>();
        for (String declared : declaredResources) {
            String normalized = normalizeRelativePath(declared);
            if (!safeResources.contains(normalized)) safeResources.add(normalized);
        }
        if (safeResources.size() > MAX_ASSETS) throw new IOException("Icon Theme references too many assets");

        Directories dirs = ensureDirectories();
        File destination = new File(dirs.imported, safeId);
        if (destination.exists()) throw new IOException("An Icon Theme with this ID is already installed: " + safeId);
        File staged = new File(dirs.staging, safeId + ".install-" + System.nanoTime());
        if (!staged.mkdir()) throw new IOException("Could not create Icon Theme staging directory");
        long[] total = new long[] {0L};
        try {
            writeUtf8(new File(staged, safeThemePath), normalizedJson, total);
            File sourceRoot = extensionRoot.getCanonicalFile();
            for (String resource : safeResources) {
                File source = resolveInside(sourceRoot, resource);
                if (!source.isFile() || source.length() <= 0L
                    || source.length() > MAX_FILE_BYTES) {
                    // Individual artwork is optional. Missing, empty, or oversized assets use
                    // the Yummy fallback instead of aborting an otherwise valid theme package.
                    continue;
                }
                copyBounded(source, new File(staged, resource), total);
            }

            String safeLicenseFile = null;
            if (licenseFile != null && !licenseFile.trim().isEmpty()) {
                safeLicenseFile = normalizeRelativePath(licenseFile);
                File sourceLicense = resolveInside(sourceRoot, safeLicenseFile);
                if (sourceLicense.isFile() && sourceLicense.length() > 0L
                    && sourceLicense.length() <= MAX_FILE_BYTES) {
                    copyBounded(sourceLicense, new File(staged, safeLicenseFile), total);
                } else {
                    safeLicenseFile = null;
                }
            }

            JSONArray warnings = new JSONArray();
            for (String warning : parsed.getWarnings()) warnings.put(warning);
            for (String resource : safeResources) {
                if (!resolveInside(staged, resource).isFile()) {
                    warnings.put("Referenced asset is unavailable: " + resource);
                }
            }
            JSONObject metadata = new JSONObject();
            metadata.put("schemaVersion", 1);
            metadata.put("id", safeId);
            metadata.put("label", safeLabel);
            metadata.put("publisher", optionalText(publisher));
            metadata.put("extensionName", optionalText(extensionName));
            metadata.put("version", optionalText(version));
            metadata.put("license", optionalText(license));
            metadata.put("licenseFile", safeLicenseFile);
            metadata.put("themeDocumentPath", safeThemePath);
            metadata.put("assetCount", safeResources.size());
            metadata.put("warnings", warnings);
            writeUtf8(new File(staged, METADATA_FILE), metadata.toString(2), total);

            // Re-open the staged document and verify all paths before exposing the package.
            String stagedJson = readUtf8(new File(staged, safeThemePath), MAX_JSON_BYTES);
            parser.parse(stagedJson, safeThemePath);
            if (!staged.renameTo(destination)) throw new IOException("Could not install Icon Theme package");
            return load(safeId);
        } catch (JSONException error) {
            deleteTree(staged);
            deleteTree(destination);
            throw new IOException("Could not write Icon Theme metadata: " + safeMessage(error), error);
        } catch (IOException | ThemeLoadException | RuntimeException error) {
            deleteTree(staged);
            // load() runs after the staged directory is renamed. Remove the destination as well
            // if its final integrity check fails, so a broken package is never left installed.
            deleteTree(destination);
            if (error instanceof IOException) throw (IOException) error;
            if (error instanceof ThemeLoadException) throw (ThemeLoadException) error;
            throw new IOException("Could not install Icon Theme: " + safeMessage(error), error);
        }
    }

    public synchronized boolean hasImportedTheme(String id) throws IOException {
        String safeId = requireId(id);
        Directories dirs = ensureDirectories();
        File candidate = new File(dirs.imported, safeId);
        if (!candidate.exists()) return false;
        requireDirectChild(dirs.imported, candidate);
        return candidate.isDirectory();
    }

    public synchronized List<IconThemeInfo> listImportedThemes() throws IOException {
        Directories dirs = ensureDirectories();
        File[] children = dirs.imported.listFiles();
        if (children == null) throw new IOException("Could not list imported Icon Themes");
        List<IconThemeInfo> result = new ArrayList<>();
        for (File child : children) {
            if (!child.isDirectory()) continue;
            try {
                requireDirectChild(dirs.imported, child);
                JSONObject metadata = readMetadata(child);
                String id = requireId(metadata.optString("id", ""));
                if (!id.equals(child.getName())) continue;
                String path = normalizeRelativePath(metadata.optString("themeDocumentPath", ""));
                File document = resolveInside(child, path);
                if (!document.isFile()) continue;
                VSCodeIconTheme parsed = parser.parse(readUtf8(document, MAX_JSON_BYTES), path);
                result.add(new IconThemeInfo(id,
                    metadata.optString("label", id),
                    optionalText(metadata.optString("publisher", null)),
                    optionalText(metadata.optString("version", null)),
                    parsed.getIconDefinitions().size(),
                    stringList(metadata.optJSONArray("warnings")).size(),
                    child.lastModified()));
            } catch (Exception ignored) {
                // A damaged package is hidden from selection but left on disk for diagnosis/removal.
            }
        }
        Collections.sort(result, Comparator.comparing(IconThemeInfo::getLabel, String.CASE_INSENSITIVE_ORDER));
        return Collections.unmodifiableList(result);
    }

    public synchronized InstalledIconTheme load(String id) throws IOException, ThemeLoadException {
        String safeId = requireId(id);
        Directories dirs = ensureDirectories();
        File directory = new File(dirs.imported, safeId).getCanonicalFile();
        requireDirectChild(dirs.imported, directory);
        if (!directory.isDirectory()) throw new IOException("Imported Icon Theme does not exist: " + safeId);
        JSONObject metadata = readMetadata(directory);
        if (!safeId.equals(metadata.optString("id", ""))) {
            throw new IOException("Icon Theme metadata ID does not match its storage directory");
        }
        String documentPath = normalizeRelativePath(metadata.optString("themeDocumentPath", ""));
        File document = resolveInside(directory, documentPath);
        if (!document.isFile()) throw new IOException("Installed Icon Theme document is missing");
        VSCodeIconTheme theme = parser.parse(readUtf8(document, MAX_JSON_BYTES), documentPath);
        return new InstalledIconTheme(safeId,
            metadata.optString("label", safeId),
            optionalText(metadata.optString("publisher", null)),
            optionalText(metadata.optString("version", null)),
            optionalText(metadata.optString("license", null)),
            documentPath, directory, theme,
            stringList(metadata.optJSONArray("warnings")));
    }

    public synchronized void removeImportedTheme(String id) throws IOException {
        String safeId = requireId(id);
        Directories dirs = ensureDirectories();
        File target = new File(dirs.imported, safeId).getCanonicalFile();
        if (!target.exists()) return;
        requireDirectChild(dirs.imported, target);
        if (!target.isDirectory()) throw new IOException("Icon Theme storage entry is not a directory");
        File staged = new File(dirs.staging, safeId + ".remove-" + System.nanoTime());
        if (!target.renameTo(staged)) throw new IOException("Could not stage Icon Theme removal");
        deleteTree(staged);
    }

    private Directories ensureDirectories() throws IOException {
        File root = new File(filesDirectory, ROOT_DIRECTORY_NAME).getCanonicalFile();
        File imported = new File(root, IMPORTED_DIRECTORY_NAME);
        File staging = new File(root, STAGING_DIRECTORY_NAME);
        ensureDirectory(root);
        ensureDirectory(imported);
        ensureDirectory(staging);
        File canonicalImported = imported.getCanonicalFile();
        File canonicalStaging = staging.getCanonicalFile();
        requireDirectChild(root, canonicalImported);
        requireDirectChild(root, canonicalStaging);
        return new Directories(root, canonicalImported, canonicalStaging);
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create Icon Theme storage directory");
        }
    }

    private static JSONObject readMetadata(File directory) throws IOException {
        File metadataFile = new File(directory, METADATA_FILE);
        if (!metadataFile.isFile()) throw new IOException("Icon Theme metadata is missing");
        try {
            return new JSONObject(readUtf8(metadataFile, 128 * 1024));
        } catch (JSONException error) {
            throw new IOException("Icon Theme metadata is invalid", error);
        }
    }

    private static List<String> stringList(JSONArray values) {
        if (values == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(values.length(), 512); i++) {
            String value = values.optString(i, null);
            if (value != null) result.add(value);
        }
        return Collections.unmodifiableList(result);
    }

    private static void copyBounded(File source, File destination, long[] total) throws IOException {
        if (source.length() <= 0L || source.length() > MAX_FILE_BYTES) {
            throw new IOException("Icon Theme asset exceeds the 4 MiB per-file limit");
        }
        File parent = destination.getParentFile();
        ensureDirectory(parent);
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            copyStream(input, output, total);
        }
    }

    private static void writeUtf8(File destination, String value, long[] total) throws IOException {
        File parent = destination.getParentFile();
        ensureDirectory(parent);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JSON_BYTES) throw new IOException("Icon Theme document exceeds its size limit");
        if (total[0] + bytes.length > MAX_PACKAGE_BYTES) throw new IOException("Icon Theme package exceeds 32 MiB");
        try (FileOutputStream output = new FileOutputStream(destination)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        total[0] += bytes.length;
    }

    private static void copyStream(InputStream input, OutputStream output, long[] total) throws IOException {
        byte[] buffer = new byte[8192];
        long fileBytes = 0L;
        int count;
        while ((count = input.read(buffer)) != -1) {
            fileBytes += count;
            total[0] += count;
            if (fileBytes > MAX_FILE_BYTES) throw new IOException("Icon Theme asset exceeds the 4 MiB per-file limit");
            if (total[0] > MAX_PACKAGE_BYTES) throw new IOException("Icon Theme package exceeds 32 MiB");
            output.write(buffer, 0, count);
        }
        output.flush();
        if (output instanceof FileOutputStream) ((FileOutputStream) output).getFD().sync();
    }

    private static String readUtf8(File file, int maximumBytes) throws IOException {
        if (file.length() < 0L || file.length() > maximumBytes) throw new IOException("Icon Theme JSON exceeds its size limit");
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maximumBytes) throw new IOException("Icon Theme JSON exceeds its size limit");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static File resolveInside(File root, String relative) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File target = new File(canonicalRoot, normalizeRelativePath(relative)).getCanonicalFile();
        requireDirectOrDescendant(canonicalRoot, target);
        return target;
    }

    private static void requireDirectChild(File root, File candidate) throws IOException {
        if (!candidate.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) {
            throw new IOException("Icon Theme path is outside its storage directory");
        }
    }

    private static void requireDirectOrDescendant(File root, File candidate) throws IOException {
        String rootPath = root.getCanonicalPath();
        String childPath = candidate.getCanonicalPath();
        if (!childPath.startsWith(rootPath + File.separator)) {
            throw new IOException("Icon Theme resource escapes its package directory");
        }
    }

    private static String normalizeRelativePath(String raw) throws IOException {
        if (raw == null) throw new IOException("Icon Theme resource path is missing");
        String path = raw.trim();
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0
            || path.indexOf('\0') >= 0 || path.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            throw new IOException("Icon Theme resource path must be relative");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                || segment.indexOf(':') >= 0) {
                throw new IOException("Icon Theme resource path contains an unsafe segment");
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) throw new IOException("Icon Theme resource path is empty");
        return android.text.TextUtils.join("/", segments);
    }

    private static String requireId(String value) throws IOException {
        if (value == null || !VALID_ID.matcher(value.trim()).matches()) throw new IOException("Invalid Icon Theme ID");
        return value.trim();
    }

    private static String requireText(String value, String field, int maxLength) throws IOException {
        if (value == null || value.trim().isEmpty() || value.trim().length() > maxLength) {
            throw new IOException(field + " is missing or too long");
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.trim().isEmpty() || "null".equals(value)
            ? null : value.trim();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static final class Directories {
        final File root; final File imported; final File staging;
        Directories(File root, File imported, File staging) {
            this.root = root; this.imported = imported; this.staging = staging;
        }
    }

    public static final class IconThemeInfo {
        private final String id, label, publisher, version;
        private final int definitionCount, warningCount;
        private final long modifiedAt;
        IconThemeInfo(String id, String label, String publisher, String version,
                      int definitionCount, int warningCount, long modifiedAt) {
            this.id = id; this.label = label; this.publisher = publisher; this.version = version;
            this.definitionCount = definitionCount; this.warningCount = warningCount;
            this.modifiedAt = modifiedAt;
        }
        public String getId() { return id; }
        public String getLabel() { return label; }
        public String getPublisher() { return publisher; }
        public String getVersion() { return version; }
        public int getDefinitionCount() { return definitionCount; }
        public int getWarningCount() { return warningCount; }
        public long getModifiedAt() { return modifiedAt; }
    }

    public static final class InstalledIconTheme {
        private final String id, label, publisher, version, license, themeDocumentPath;
        private final File packageDirectory;
        private final VSCodeIconTheme theme;
        private final List<String> warnings;
        InstalledIconTheme(String id, String label, String publisher, String version,
                           String license, String themeDocumentPath, File packageDirectory,
                           VSCodeIconTheme theme, List<String> warnings) {
            this.id = id; this.label = label; this.publisher = publisher; this.version = version;
            this.license = license; this.themeDocumentPath = themeDocumentPath;
            this.packageDirectory = packageDirectory; this.theme = theme;
            this.warnings = warnings;
        }
        public String getId() { return id; }
        public String getLabel() { return label; }
        public String getPublisher() { return publisher; }
        public String getVersion() { return version; }
        public String getLicense() { return license; }
        public String getThemeDocumentPath() { return themeDocumentPath; }
        public File getPackageDirectory() { return packageDirectory; }
        public VSCodeIconTheme getTheme() { return theme; }
        public List<String> getWarnings() { return warnings; }
    }
}
