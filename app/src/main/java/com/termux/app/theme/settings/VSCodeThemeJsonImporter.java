package com.termux.app.theme.settings;

import android.content.Context;

import com.termux.app.theme.loader.ThemeDocumentParser;
import com.termux.app.theme.loader.ThemeLoadException;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.VSCodeColorTheme;
import com.termux.app.theme.storage.ThemePackageStorage;
import com.termux.app.theme.storage.ThemeStorageException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Imports one standalone VS Code Color Theme JSON document into a Yummy theme package.
 *
 * <p>The importer accepts strict JSON and JSONC comments/trailing commas, validates the
 * normalized document with Yummy's existing ThemeDocumentParser, and creates Yummy's separate
 * metadata.json sidecar. It never edits the source file.</p>
 *
 * <p>Only inline TextMate tokenColors arrays can be imported as Editor Syntax in this stage.
 * A string-valued tokenColors reference needs sibling resource files, which are not included
 * when a single JSON document is selected. semanticTokenColors are retained in the JSON but
 * are not treated as Sora TextMate rules.</p>
 */
public final class VSCodeThemeJsonImporter {
    public static final int MAX_THEME_JSON_BYTES = 2 * 1024 * 1024;
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private final ThemeDocumentParser parser;

    public VSCodeThemeJsonImporter() {
        this(new ThemeDocumentParser());
    }

    public VSCodeThemeJsonImporter(ThemeDocumentParser parser) {
        if (parser == null) throw new IllegalArgumentException("ThemeDocumentParser is required");
        this.parser = parser;
    }

    /** Reads, normalizes, and validates a selected JSON document without installing it. */
    public Preview inspect(InputStream input, String fallbackName) throws ThemeImportException {
        if (input == null) throw new ThemeImportException("Selected theme file could not be opened");
        final String source;
        try {
            source = readUtf8Limited(input, MAX_THEME_JSON_BYTES);
        } catch (IOException error) {
            throw new ThemeImportException("Could not read theme JSON: " + safeMessage(error), error);
        }

        String normalized;
        try {
            normalized = normalizeJsonc(source);
        } catch (IllegalArgumentException error) {
            throw new ThemeImportException("Invalid JSONC syntax: " + safeMessage(error), error);
        }

        final JSONObject root;
        final VSCodeColorTheme parsed;
        try {
            root = new JSONObject(normalized);
            parsed = parser.parse(normalized);
        } catch (JSONException | ThemeLoadException error) {
            throw new ThemeImportException("Invalid VS Code Color Theme: " + safeMessage(error), error);
        }

        String name = optionalString(root, "name");
        if (name == null) name = stripJsonExtension(fallbackName);
        if (name == null || name.trim().isEmpty()) name = parsed.getName();
        name = limit(name.trim(), 120);

        boolean supportsAppUi = parsed.hasColors();
        JSONArray tokenRules = parsed.getTokenColors();
        boolean supportsEditorSyntax = tokenRules != null && tokenRules.length() > 0;
        String tokenReference = parsed.getTokenColorsReference();
        List<String> warnings = new ArrayList<>();
        if (tokenReference != null) {
            warnings.add("This theme references an external TextMate file ('" + tokenReference
                + "'). Select a complete theme package in a future package-import workflow to include it.");
        }
        if (parsed.hasSemanticTokenColors() && !supportsEditorSyntax) {
            warnings.add("semanticTokenColors are preserved, but this stage imports Sora TextMate rules only. "
                + "This theme will not be offered as an Editor Syntax Theme.");
        }
        warnings.add("The standalone JSON does not carry verified license information. Review the "
            + "theme's original license before sharing or redistributing it.");
        if (!supportsAppUi && !supportsEditorSyntax) {
            throw new ThemeImportException(
                "This JSON has no importable Yummy target. It needs a non-empty 'colors' object "
                    + "or an inline 'tokenColors' array."
            );
        }

        ThemeAppearance appearance = parsed.getAppearance();
        String id = createThemeId(name);
        return new Preview(
            normalized,
            name,
            id,
            appearance,
            supportsAppUi,
            supportsEditorSyntax,
            tokenReference,
            warnings
        );
    }

    /**
     * Installs the preview as an imported package. An explicit appearance is required when the
     * source JSON omitted its VS Code type. Replacement is only allowed when the caller has
     * already obtained user confirmation for an existing imported theme ID.
     */
    public String install(
        Context context,
        ThemePackageStorage storage,
        Preview preview,
        ThemeAppearance appearance,
        String themeId,
        boolean replaceExisting
    ) throws ThemeImportException {
        if (context == null) throw new ThemeImportException("Context is required");
        if (storage == null) throw new ThemeImportException("ThemePackageStorage is required");
        if (preview == null) throw new ThemeImportException("Import preview is required");

        ThemeAppearance selectedAppearance = appearance == null
            || appearance == ThemeAppearance.UNKNOWN ? preview.getAppearance() : appearance;
        if (selectedAppearance == null || selectedAppearance == ThemeAppearance.UNKNOWN) {
            throw new ThemeImportException("Choose a theme appearance before importing");
        }

        String safeId = sanitizeThemeId(themeId == null ? preview.getSuggestedId() : themeId);
        File stagingRoot = new File(context.getCacheDir(), "theme-import-staging");
        File packageDirectory = null;
        try {
            if (!stagingRoot.isDirectory() && !stagingRoot.mkdirs()) {
                throw new IOException("Could not create temporary import directory");
            }
            packageDirectory = new File(stagingRoot, "package-" + UUID.randomUUID());
            if (!packageDirectory.mkdir()) throw new IOException("Could not create temporary theme package");

            writeUtf8(new File(packageDirectory, "theme.json"), preview.getNormalizedJson());
            writeUtf8(new File(packageDirectory, "metadata.json"), createMetadataJson(
                preview, safeId, selectedAppearance).toString());

            if (replaceExisting) storage.replacePackage(packageDirectory);
            else storage.importPackage(packageDirectory);
            return safeId;
        } catch (IOException | JSONException error) {
            throw new ThemeImportException("Could not prepare imported theme package: "
                + safeMessage(error), error);
        } catch (ThemeStorageException error) {
            throw new ThemeImportException("Could not install imported theme: "
                + safeMessage(error), error);
        } finally {
            if (packageDirectory != null) deleteTree(packageDirectory);
        }
    }

    /**
     * Removes JSONC comments without touching quoted strings, then removes trailing commas.
     * The scanner deliberately rejects unterminated block comments and unterminated strings.
     */
    public static String normalizeJsonc(String source) {
        if (source == null) throw new IllegalArgumentException("JSON source is required");
        String value = source.startsWith("\uFEFF") ? source.substring(1) : source;
        StringBuilder withoutComments = new StringBuilder(value.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (inString) {
                withoutComments.append(current);
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') inString = false;
                continue;
            }
            if (current == '"') {
                inString = true;
                withoutComments.append(current);
                continue;
            }
            if (current == '/' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                if (next == '/') {
                    i += 2;
                    while (i < value.length() && value.charAt(i) != '\n' && value.charAt(i) != '\r') i++;
                    if (i < value.length()) withoutComments.append(value.charAt(i));
                    continue;
                }
                if (next == '*') {
                    i += 2;
                    boolean closed = false;
                    while (i < value.length()) {
                        char commentChar = value.charAt(i);
                        if (commentChar == '\n' || commentChar == '\r') withoutComments.append(commentChar);
                        if (commentChar == '*' && i + 1 < value.length() && value.charAt(i + 1) == '/') {
                            i++;
                            closed = true;
                            break;
                        }
                        i++;
                    }
                    if (!closed) throw new IllegalArgumentException("Unterminated block comment");
                    continue;
                }
            }
            withoutComments.append(current);
        }
        if (inString) throw new IllegalArgumentException("Unterminated JSON string");

        StringBuilder result = new StringBuilder(withoutComments.length());
        inString = false;
        escaped = false;
        for (int i = 0; i < withoutComments.length(); i++) {
            char current = withoutComments.charAt(i);
            if (inString) {
                result.append(current);
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') inString = false;
                continue;
            }
            if (current == '"') {
                inString = true;
                result.append(current);
            } else if (current == ',') {
                int next = i + 1;
                while (next < withoutComments.length()
                    && Character.isWhitespace(withoutComments.charAt(next))) next++;
                if (next >= withoutComments.length()
                    || (withoutComments.charAt(next) != '}' && withoutComments.charAt(next) != ']')) {
                    result.append(current);
                }
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static JSONObject createMetadataJson(
        Preview preview, String id, ThemeAppearance appearance
    ) throws JSONException {
        JSONObject capabilities = new JSONObject();
        capabilities.put("appUi", preview.supportsAppUi());
        capabilities.put("editorSyntax", preview.supportsEditorSyntax());

        JSONObject metadata = new JSONObject();
        metadata.put("schemaVersion", 1);
        metadata.put("version", "1.0.0");
        metadata.put("id", id);
        metadata.put("label", preview.getName());
        metadata.put("type", appearance.getVscodeValue());
        metadata.put("source", "imported");
        metadata.put("themeFile", "theme.json");
        metadata.put("capabilities", capabilities);
        metadata.put("defaultFor", new JSONArray());
        return metadata;
    }

    private static String readUtf8Limited(InputStream input, int maximumBytes) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = source.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) throw new IOException("Theme JSON exceeds the 2 MiB limit");
                output.write(buffer, 0, count);
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray())).toString();
            } catch (CharacterCodingException error) {
                throw new IOException("Theme JSON is not valid UTF-8", error);
            }
        }
    }

    private static void writeUtf8(File target, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
    }

    private static String createThemeId(String label) {
        String normalized = Normalizer.normalize(label, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        String id = normalized.replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (id.isEmpty()) id = "imported-theme";
        return limit(id, 64).replaceAll("-+$", "");
    }

    private static String sanitizeThemeId(String value) throws ThemeImportException {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!VALID_ID.matcher(id).matches()) {
            throw new ThemeImportException("Generated theme ID is invalid: " + id);
        }
        return id;
    }

    private static String optionalString(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        if (!(value instanceof String)) return null;
        String text = ((String) value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String stripJsonExtension(String name) {
        if (name == null) return null;
        String value = name.trim();
        if (value.toLowerCase(Locale.ROOT).endsWith(".json")) {
            value = value.substring(0, value.length() - 5);
        }
        return value.trim().isEmpty() ? null : value.trim();
    }

    private static String limit(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum).trim();
    }

    private static void deleteTree(File target) {
        if (target == null || !target.exists()) return;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        target.delete();
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error == null ? "Unknown error" : error.getClass().getSimpleName()
            : message;
    }

    /** Immutable summary and validated content prepared before the user confirms installation. */
    public static final class Preview {
        private final String normalizedJson;
        private final String name;
        private final String suggestedId;
        private final ThemeAppearance appearance;
        private final boolean supportsAppUi;
        private final boolean supportsEditorSyntax;
        private final String tokenColorsReference;
        private final List<String> warnings;

        private Preview(
            String normalizedJson,
            String name,
            String suggestedId,
            ThemeAppearance appearance,
            boolean supportsAppUi,
            boolean supportsEditorSyntax,
            String tokenColorsReference,
            List<String> warnings
        ) {
            this.normalizedJson = normalizedJson;
            this.name = name;
            this.suggestedId = suggestedId;
            this.appearance = appearance;
            this.supportsAppUi = supportsAppUi;
            this.supportsEditorSyntax = supportsEditorSyntax;
            this.tokenColorsReference = tokenColorsReference;
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        public String getNormalizedJson() { return normalizedJson; }
        public String getName() { return name; }
        public String getSuggestedId() { return suggestedId; }
        public ThemeAppearance getAppearance() { return appearance; }
        public boolean supportsAppUi() { return supportsAppUi; }
        public boolean supportsEditorSyntax() { return supportsEditorSyntax; }
        public String getTokenColorsReference() { return tokenColorsReference; }
        public List<String> getWarnings() { return warnings; }
    }
}
