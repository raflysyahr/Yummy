package com.termux.app.theme.storage;

import com.termux.app.theme.loader.ThemeDocumentParser;
import com.termux.app.theme.loader.ThemeLoadException;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.schema.YummyComponentSchema;

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

/**
 * Stores the editable Yummy UI Schema sidecar for an imported Color Theme package.
 *
 * <p>The existing theme JSON remains untouched and continues to be consumed by the current
 * theme loader. This sidecar starts with a complete copy of that parsed VS Code theme under
 * {@code vscode.colorTheme}, plus Yummy-owned component settings. The sidecar is intentionally
 * editable, so its VS Code data may later diverge from the original import document. Keeping it
 * in the package makes it travel with package backups, restores, replacements, and removals.</p>
 *
 * <p>Unknown fields in the VS Code theme and unknown Yummy component entries are retained.
 * The wrapper is validated here, and the embedded color-theme document is checked by the same
 * parser used during import. Detailed Yummy component-property validation belongs to the later
 * UI Schema validator stage.</p>
 */
public final class ThemeUiSchemaStorage {
    public static final String FILE_NAME = "ui-schema.json";
    public static final int SCHEMA_VERSION = 1;
    public static final long MAX_SCHEMA_BYTES = 4L * 1024L * 1024L;

    private final ThemeDocumentParser colorThemeParser;

    public ThemeUiSchemaStorage() {
        this(new ThemeDocumentParser());
    }

    public ThemeUiSchemaStorage(ThemeDocumentParser colorThemeParser) {
        if (colorThemeParser == null) {
            throw new IllegalArgumentException("ThemeDocumentParser is required");
        }
        this.colorThemeParser = colorThemeParser;
    }

    /** Returns the root sidecar path for a package directory. */
    public File getSchemaFile(File packageDirectory) throws ThemeStorageException {
        File root = requirePackageDirectory(packageDirectory);
        File target = new File(root, FILE_NAME);
        try {
            File canonicalTarget = target.getCanonicalFile();
            if (!root.equals(canonicalTarget.getParentFile())
                || !target.getAbsoluteFile().equals(canonicalTarget)) {
                throw new ThemeStorageException(
                    ThemeStorageException.Code.UNSAFE_PATH,
                    "UI Schema path is redirected or resolves outside its theme package"
                );
            }
            return target;
        } catch (IOException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.UNSAFE_PATH,
                "Could not resolve UI Schema path",
                error
            );
        }
    }

    /**
     * Ensures that a staged package contains a valid UI Schema file.
     *
     * <p>If the incoming package already contains a schema, it is validated and preserved.
     * When replacing an older package that has no incoming schema, the previous Yummy namespace
     * and other wrapper data are carried forward while the embedded VS Code theme is refreshed.
     * A first-time import receives an empty Yummy component map and the complete source theme.</p>
     */
    public void ensureForInstall(
        File stagedPackageDirectory,
        LoadedTheme stagedTheme,
        File previousPackageDirectory,
        LoadedTheme previousTheme
    ) throws ThemeStorageException {
        if (stagedTheme == null) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Loaded theme is required to create its UI Schema"
            );
        }

        File target = getSchemaFile(stagedPackageDirectory);
        if (target.exists()) {
            load(stagedPackageDirectory, stagedTheme);
            return;
        }

        JSONObject document;
        File previousSchema = null;
        if (previousPackageDirectory != null) {
            previousSchema = getSchemaFile(previousPackageDirectory);
        }

        if (previousSchema != null && previousSchema.exists() && !previousSchema.isFile()) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "The previous theme's " + FILE_NAME + " path is not a regular file; "
                    + "replacement was stopped to avoid losing custom UI settings"
            );
        }

        if (previousSchema != null && previousSchema.isFile()) {
            if (previousTheme == null) {
                throw new ThemeStorageException(
                    ThemeStorageException.Code.INVALID_PACKAGE,
                    "The previous theme schema exists but its source theme could not be loaded; "
                        + "replacement was stopped to avoid losing custom UI settings"
                );
            }
            JSONObject previousDocument = load(previousPackageDirectory, previousTheme);
            document = mergeWithUpdatedTheme(previousDocument, stagedTheme);
        } else {
            document = createDefault(stagedTheme);
        }
        writeNewFileAtomically(target, document);
    }

    /** Reads and validates a package schema against its installed theme source. */
    public JSONObject load(File packageDirectory, LoadedTheme expectedTheme)
        throws ThemeStorageException {
        if (expectedTheme == null) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Expected theme is required to validate UI Schema"
            );
        }

        File source = getSchemaFile(packageDirectory);
        if (!source.isFile()) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Theme package does not contain " + FILE_NAME
            );
        }
        if (source.length() <= 0L || source.length() > MAX_SCHEMA_BYTES) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.PACKAGE_LIMIT_EXCEEDED,
                "UI Schema must be between 1 byte and 4 MiB"
            );
        }

        try {
            JSONObject document = new JSONObject(readUtf8Limited(source, MAX_SCHEMA_BYTES));
            validate(document, expectedTheme);
            validateEmbeddedColorTheme(document);
            return copyObject(document);
        } catch (JSONException | IOException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Invalid " + FILE_NAME + ": " + safeMessage(error),
                error
            );
        } catch (ThemeLoadException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Invalid vscode.colorTheme in " + FILE_NAME + ": " + safeMessage(error),
                error
            );
        }
    }

    /** Creates a forward-compatible wrapper around the complete parsed VS Code theme object. */
    public JSONObject createDefault(LoadedTheme loadedTheme) throws ThemeStorageException {
        if (loadedTheme == null) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Loaded theme is required to create UI Schema"
            );
        }

        try {
            ThemeMetadata metadata = loadedTheme.getMetadata();
            JSONObject source = new JSONObject();
            source.put("kind", "imported");

            JSONObject schemaMetadata = new JSONObject();
            schemaMetadata.put("id", metadata.getId());
            schemaMetadata.put("name", metadata.getLabel());
            schemaMetadata.put("source", source);

            JSONObject vscode = new JSONObject();
            vscode.put("colorTheme", loadedTheme.getColorTheme().toJson());
            vscode.put("configuration", new JSONObject());
            vscode.put("extension", new JSONObject());

            JSONObject yummy = new JSONObject();
            yummy.put("components", new JSONObject());

            JSONObject document = new JSONObject();
            document.put("schemaVersion", SCHEMA_VERSION);
            document.put("metadata", schemaMetadata);
            document.put("vscode", vscode);
            document.put("yummy", yummy);
            return document;
        } catch (JSONException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Could not construct UI Schema: " + safeMessage(error),
                error
            );
        }
    }

    /** Returns all Yummy component-property definitions as a defensive JSON copy. */
    public synchronized JSONObject getComponentProperties(
        File packageDirectory,
        LoadedTheme expectedTheme
    ) throws ThemeStorageException {
        try {
            return YummyComponentSchema.fromDocument(load(packageDirectory, expectedTheme)).toJson();
        } catch (JSONException | IllegalArgumentException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Invalid yummy.components: " + safeMessage(error),
                error
            );
        }
    }

    /** Returns one Yummy component-property definition, or null when it is not configured. */
    public synchronized JSONObject getComponentProperty(
        File packageDirectory,
        LoadedTheme expectedTheme,
        String propertyId
    ) throws ThemeStorageException {
        try {
            return YummyComponentSchema.fromDocument(load(packageDirectory, expectedTheme))
                .getProperty(propertyId);
        } catch (JSONException | IllegalArgumentException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Invalid yummy.components: " + safeMessage(error),
                error
            );
        }
    }

    /** Adds or replaces one Yummy component-property definition without changing sibling entries. */
    public synchronized void putComponentProperty(
        File packageDirectory,
        LoadedTheme expectedTheme,
        String propertyId,
        JSONObject definition
    ) throws ThemeStorageException {
        updateComponentProperties(packageDirectory, expectedTheme, propertyId, definition);
    }

    /** Removes one Yummy component-property definition without changing sibling entries. */
    public synchronized boolean removeComponentProperty(
        File packageDirectory,
        LoadedTheme expectedTheme,
        String propertyId
    ) throws ThemeStorageException {
        JSONObject document = load(packageDirectory, expectedTheme);
        try {
            YummyComponentSchema components = YummyComponentSchema.fromDocument(document);
            if (!components.removeProperty(propertyId)) return false;
            saveComponentProperties(packageDirectory, expectedTheme, document, components);
            return true;
        } catch (JSONException | IllegalArgumentException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Could not update yummy.components: " + safeMessage(error),
                error
            );
        }
    }

    private void updateComponentProperties(
        File packageDirectory,
        LoadedTheme expectedTheme,
        String propertyId,
        JSONObject definition
    ) throws ThemeStorageException {
        JSONObject document = load(packageDirectory, expectedTheme);
        try {
            YummyComponentSchema components = YummyComponentSchema.fromDocument(document);
            components.putProperty(propertyId, definition);
            saveComponentProperties(packageDirectory, expectedTheme, document, components);
        } catch (JSONException | IllegalArgumentException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Could not update yummy.components: " + safeMessage(error),
                error
            );
        }
    }

    private void saveComponentProperties(
        File packageDirectory,
        LoadedTheme expectedTheme,
        JSONObject previousDocument,
        YummyComponentSchema components
    ) throws ThemeStorageException, JSONException {
        JSONObject updated = components.applyToDocument(previousDocument);
        validate(updated, expectedTheme);
        try {
            validateEmbeddedColorTheme(updated);
        } catch (ThemeLoadException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Could not preserve valid vscode.colorTheme while updating yummy.components: "
                    + safeMessage(error),
                error
            );
        }
        replaceFileAtomically(getSchemaFile(packageDirectory), updated);
    }

    private static JSONObject mergeWithUpdatedTheme(
        JSONObject previousDocument,
        LoadedTheme nextTheme
    ) throws ThemeStorageException {
        try {
            JSONObject merged = copyObject(previousDocument);
            JSONObject metadata = merged.optJSONObject("metadata");
            JSONObject vscode = merged.optJSONObject("vscode");
            if (metadata == null || vscode == null) {
                throw new JSONException("Previous UI Schema is missing required sections");
            }

            metadata.put("id", nextTheme.getMetadata().getId());
            metadata.put("name", nextTheme.getMetadata().getLabel());
            vscode.put("colorTheme", nextTheme.getColorTheme().toJson());
            return merged;
        } catch (JSONException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Could not preserve previous Yummy UI settings: " + safeMessage(error),
                error
            );
        }
    }

    private static void validate(JSONObject document, LoadedTheme expectedTheme)
        throws ThemeStorageException {
        Object version = document.opt("schemaVersion");
        if (!(version instanceof Number)
            || ((Number) version).doubleValue() != SCHEMA_VERSION) {
            throw invalid("Unsupported or invalid UI Schema schemaVersion");
        }

        JSONObject metadata = document.optJSONObject("metadata");
        if (metadata == null) throw invalid("UI Schema metadata must be an object");
        String id = requiredString(metadata, "id", "metadata.id");
        requiredString(metadata, "name", "metadata.name");
        JSONObject source = metadata.optJSONObject("source");
        if (source == null) throw invalid("UI Schema metadata.source must be an object");
        requiredString(source, "kind", "metadata.source.kind");
        if (!expectedTheme.getMetadata().getId().equals(id)) {
            throw invalid("UI Schema theme ID does not match its package metadata");
        }

        JSONObject vscode = document.optJSONObject("vscode");
        if (vscode == null) throw invalid("UI Schema vscode section must be an object");
        JSONObject sourceTheme = vscode.optJSONObject("colorTheme");
        if (sourceTheme == null) {
            throw invalid("UI Schema vscode.colorTheme must contain the complete VS Code theme object");
        }
        JSONObject yummy = document.optJSONObject("yummy");
        if (yummy == null || yummy.optJSONObject("components") == null) {
            throw invalid("UI Schema yummy.components must be an object");
        }
    }

    /**
     * Validates the editable embedded VS Code document with the same parser used by imports.
     * The parser retains the complete object; this check only rejects malformed standard
     * sections that the current VS Code theme loader cannot safely interpret.
     */
    private void validateEmbeddedColorTheme(JSONObject document) throws ThemeLoadException {
        JSONObject vscode = document.optJSONObject("vscode");
        JSONObject colorTheme = vscode == null ? null : vscode.optJSONObject("colorTheme");
        if (colorTheme == null) {
            throw new ThemeLoadException("vscode.colorTheme must be an object");
        }
        colorThemeParser.parse(colorTheme);
    }

    private static String requiredString(JSONObject object, String key, String path)
        throws ThemeStorageException {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw invalid(path + " must be a non-empty string");
        }
        return ((String) value).trim();
    }

    private static JSONObject copyObject(JSONObject value) throws ThemeStorageException {
        try {
            return new JSONObject(value.toString());
        } catch (JSONException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Could not copy UI Schema JSON: " + safeMessage(error),
                error
            );
        }
    }

    private static String readUtf8Limited(File source, long maximumBytes) throws IOException {
        try (InputStream input = new FileInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) throw new IOException("UI Schema exceeds the 4 MiB limit");
                output.write(buffer, 0, count);
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray())).toString();
            } catch (CharacterCodingException error) {
                throw new IOException("UI Schema is not valid UTF-8", error);
            }
        }
    }

    private static void writeNewFileAtomically(File target, JSONObject document)
        throws ThemeStorageException {
        File parent = target.getParentFile();
        File temporary = new File(parent, "." + FILE_NAME + ".tmp");
        try {
            if (target.exists()) {
                throw new IOException("Refusing to overwrite an existing UI Schema during install");
            }
            byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > MAX_SCHEMA_BYTES) {
                throw new IOException("Generated UI Schema exceeds the 4 MiB limit");
            }
            if (temporary.exists() && !temporary.delete()) {
                throw new IOException("Could not remove a previous temporary UI Schema file");
            }
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }
            if (!temporary.renameTo(target)) {
                throw new IOException("Could not atomically install " + FILE_NAME);
            }
        } catch (IOException error) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new ThemeStorageException(
                ThemeStorageException.Code.IO_FAILURE,
                "Could not write UI Schema: " + safeMessage(error),
                error
            );
        }
    }

    /** Replaces an existing sidecar through a same-directory temporary file and rollback backup. */
    private static void replaceFileAtomically(File target, JSONObject document)
        throws ThemeStorageException {
        File parent = target.getParentFile();
        File backup = new File(parent, "." + FILE_NAME + ".bak");
        File temporary = null;
        boolean movedOriginal = false;
        try {
            if (!target.isFile()) {
                throw new IOException("Existing UI Schema file is unavailable for update");
            }
            if (backup.exists()) {
                if (target.exists()) {
                    if (!backup.delete()) {
                        throw new IOException("Could not remove a stale UI Schema backup");
                    }
                } else if (!backup.renameTo(target)) {
                    throw new IOException("Could not recover the previous UI Schema backup");
                }
            }

            byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > MAX_SCHEMA_BYTES) {
                throw new IOException("Updated UI Schema exceeds the 4 MiB limit");
            }
            temporary = File.createTempFile(".ui-schema-", ".tmp", parent);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }

            if (!target.renameTo(backup)) {
                throw new IOException("Could not preserve the previous UI Schema before update");
            }
            movedOriginal = true;
            if (!temporary.renameTo(target)) {
                if (!backup.renameTo(target)) {
                    throw new IOException(
                        "Could not install updated UI Schema or restore its previous version"
                    );
                }
                movedOriginal = false;
                throw new IOException("Could not atomically install updated UI Schema");
            }
            movedOriginal = false;
            // A leftover backup is harmless after the new target has been installed. The next
            // write will remove it before starting another replacement.
            //noinspection ResultOfMethodCallIgnored
            backup.delete();
        } catch (IOException error) {
            if (movedOriginal && backup.exists() && !target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                backup.renameTo(target);
            }
            throw new ThemeStorageException(
                ThemeStorageException.Code.IO_FAILURE,
                "Could not update " + FILE_NAME + ": " + safeMessage(error),
                error
            );
        } finally {
            if (temporary != null && temporary.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
        }
    }

    private static File requirePackageDirectory(File directory) throws ThemeStorageException {
        if (directory == null || !directory.isDirectory()) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_SOURCE,
                "Theme package directory is unavailable"
            );
        }
        try {
            File canonical = directory.getCanonicalFile();
            if (!canonical.equals(directory.getAbsoluteFile())) {
                throw new ThemeStorageException(
                    ThemeStorageException.Code.UNSAFE_PATH,
                    "Theme package directory is not a canonical directory"
                );
            }
            return canonical;
        } catch (IOException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.UNSAFE_PATH,
                "Could not resolve theme package directory",
                error
            );
        }
    }

    private static ThemeStorageException invalid(String message) {
        return new ThemeStorageException(ThemeStorageException.Code.INVALID_PACKAGE, message);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error == null ? "Unknown error" : error.getClass().getSimpleName()
            : message;
    }
}
