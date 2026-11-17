package com.termux.app.theme.storage;

import com.termux.app.theme.loader.ThemeDocumentParser;
import com.termux.app.theme.loader.ThemeLoadException;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ThemeUiSchemaStorageTest {
    private final ThemeUiSchemaStorage storage = new ThemeUiSchemaStorage();
    private final ThemeDocumentParser parser = new ThemeDocumentParser();

    @Test
    public void createsSchemaWithCompleteUnknownVscodeThemeData() throws Exception {
        File packageDirectory = Files.createTempDirectory("yummy-ui-schema-create").toFile();
        LoadedTheme theme = loadedTheme("storage-test", "#101010", "#abcdef");
        try {
            storage.ensureForInstall(packageDirectory, theme, null, null);

            JSONObject stored = storage.load(packageDirectory, theme);
            JSONObject colors = stored.getJSONObject("vscode")
                .getJSONObject("colorTheme").getJSONObject("colors");
            assertEquals("#101010", colors.getString("editor.background"));
            assertEquals("#abcdef", colors.getString("future.unknownColor"));
            assertEquals("kept-for-a-future-adapter", stored.getJSONObject("vscode")
                .getJSONObject("colorTheme").getJSONObject("futureSection").getString("marker"));
            assertEquals("imported", stored.getJSONObject("metadata")
                .getJSONObject("source").getString("kind"));
            assertEquals(0, stored.getJSONObject("yummy").getJSONObject("components").length());
        } finally {
            deleteTree(packageDirectory);
        }
    }

    @Test
    public void replacementPreservesYummySettingsAndRefreshesVscodeSource() throws Exception {
        File previousPackage = Files.createTempDirectory("yummy-ui-schema-old").toFile();
        File stagedPackage = Files.createTempDirectory("yummy-ui-schema-new").toFile();
        LoadedTheme oldTheme = loadedTheme("same-theme", "#101010", "#aaaaaa");
        LoadedTheme newTheme = loadedTheme("same-theme", "#202020", "#bbbbbb");
        try {
            storage.ensureForInstall(previousPackage, oldTheme, null, null);
            File previousSchema = storage.getSchemaFile(previousPackage);
            JSONObject edited = storage.load(previousPackage, oldTheme);
            JSONObject components = edited.getJSONObject("yummy").getJSONObject("components");
            components.put("sidebar.fileTree.iconSize", new JSONObject()
                .put("type", "dimension")
                .put("value", new JSONObject()
                    .put("source", "literal")
                    .put("value", 16)
                    .put("unit", "dp")));
            edited.getJSONObject("vscode").getJSONObject("configuration")
                .put("editor.fontSize", 14);
            edited.getJSONObject("vscode").getJSONObject("extension")
                .put("publisher", "example-publisher");
            edited.put("futureWrapperSection", new JSONObject().put("keep", true));
            write(previousSchema, edited.toString());

            storage.ensureForInstall(stagedPackage, newTheme, previousPackage, oldTheme);

            JSONObject replaced = storage.load(stagedPackage, newTheme);
            assertEquals(16, replaced.getJSONObject("yummy").getJSONObject("components")
                .getJSONObject("sidebar.fileTree.iconSize").getJSONObject("value").getInt("value"));
            assertEquals("#202020", replaced.getJSONObject("vscode")
                .getJSONObject("colorTheme").getJSONObject("colors").getString("editor.background"));
            assertEquals("#bbbbbb", replaced.getJSONObject("vscode")
                .getJSONObject("colorTheme").getJSONObject("colors").getString("future.unknownColor"));
            assertEquals(14, replaced.getJSONObject("vscode").getJSONObject("configuration")
                .getInt("editor.fontSize"));
            assertEquals("example-publisher", replaced.getJSONObject("vscode")
                .getJSONObject("extension").getString("publisher"));
            assertTrue(replaced.getJSONObject("futureWrapperSection").getBoolean("keep"));
        } finally {
            deleteTree(previousPackage);
            deleteTree(stagedPackage);
        }
    }

    @Test
    public void acceptsEditableVscodeValuesThatDifferFromOriginalImport() throws Exception {
        File packageDirectory = Files.createTempDirectory("yummy-ui-schema-edited").toFile();
        LoadedTheme theme = loadedTheme("editable-test", "#101010", "#abcdef");
        try {
            JSONObject edited = storage.createDefault(theme);
            edited.getJSONObject("vscode").getJSONObject("colorTheme")
                .getJSONObject("colors").put("editor.background", "#ffffff");
            write(storage.getSchemaFile(packageDirectory), edited.toString());

            JSONObject loaded = storage.load(packageDirectory, theme);
            assertEquals("#ffffff", loaded.getJSONObject("vscode")
                .getJSONObject("colorTheme").getJSONObject("colors").getString("editor.background"));
        } finally {
            deleteTree(packageDirectory);
        }
    }

    @Test
    public void refusesToReplaceWhenPreviousSchemaIsInvalid() throws Exception {
        File previousPackage = Files.createTempDirectory("yummy-ui-schema-invalid-old").toFile();
        File stagedPackage = Files.createTempDirectory("yummy-ui-schema-invalid-new").toFile();
        LoadedTheme oldTheme = loadedTheme("invalid-old", "#101010", "#aaaaaa");
        LoadedTheme newTheme = loadedTheme("invalid-old", "#202020", "#bbbbbb");
        try {
            File oldSchema = storage.getSchemaFile(previousPackage);
            write(oldSchema, "{broken");
            try {
                storage.ensureForInstall(stagedPackage, newTheme, previousPackage, oldTheme);
                fail("Expected replacement to stop rather than discard invalid existing schema");
            } catch (ThemeStorageException expected) {
                assertNotNull(expected.getMessage());
            }
        } finally {
            deleteTree(previousPackage);
            deleteTree(stagedPackage);
        }
    }

    private LoadedTheme loadedTheme(String id, String editorBackground, String futureColor)
        throws ThemeLoadException {
        String json = "{\"name\":\"Storage Test\",\"type\":\"dark\","
            + "\"colors\":{\"editor.background\":\"" + editorBackground + "\","
            + "\"future.unknownColor\":\"" + futureColor + "\"},"
            + "\"futureSection\":{\"marker\":\"kept-for-a-future-adapter\"}}";
        ThemeMetadata metadata = new ThemeMetadata(
            id,
            "Storage Test",
            ThemeAppearance.DARK,
            ThemeSource.IMPORTED,
            "theme.json",
            null,
            null,
            null,
            null,
            EnumSet.of(ThemeTarget.APP_UI),
            EnumSet.noneOf(ThemeTarget.class)
        );
        return new LoadedTheme(metadata, parser.parse(json, metadata));
    }

    private static void write(File file, String value) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) assertTrue(parent.mkdirs());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
