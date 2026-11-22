package com.termux.app.theme.storage;

import android.content.Context;

import com.termux.app.theme.loader.ThemePackageLoader;
import com.termux.app.theme.model.LoadedTheme;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** End-to-end coverage for imported Color Scheme sidecars and package backup/restore. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ThemeColorSchemeEndToEndTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void importedThemeGetsDefaultColorSchemeAndKeepsUserEdits() throws Exception {
        ThemePackageStorage storage = newStorage();
        File source = createPackage("step12-import");
        LoadedTheme imported = storage.importPackage(source);

        assertEquals("step12-import", imported.getMetadata().getId());
        File colorSchemeFile = storage.ensureColorSchemeFile("step12-import");
        assertTrue(colorSchemeFile.isFile());

        JSONObject generated = new ThemeColorSchemeStorage().load(colorSchemeFile.getParentFile());
        assertTrue(generated.has("sidebar"));
        assertTrue(generated.getJSONObject("sidebar").has("background"));

        JSONObject edited = new JSONObject();
        edited.put("version", generated.getInt("version"));
        JSONObject sidebar = new JSONObject();
        sidebar.put("background", "#123456");
        edited.put("sidebar", sidebar);
        write(colorSchemeFile, edited.toString(2));

        File sameFile = storage.ensureColorSchemeFile("step12-import");
        JSONObject persisted = new ThemeColorSchemeStorage().load(sameFile.getParentFile());
        assertEquals("#123456", persisted.getJSONObject("sidebar").getString("background"));
    }

    @Test
    public void backupAndRestorePreserveColorSchemeSidecarContents() throws Exception {
        ThemePackageStorage storage = newStorage();
        File source = createPackage("step12-restore");
        storage.importPackage(source);

        File colorSchemeFile = storage.ensureColorSchemeFile("step12-restore");
        JSONObject original = new ThemeColorSchemeStorage().load(colorSchemeFile.getParentFile());
        original.getJSONObject("sidebar").put("background", "#112233");
        write(colorSchemeFile, original.toString(2));

        ThemePackageStorage.BackupInfo backup = storage.backupImportedTheme("step12-restore");
        assertNotNull(backup);

        original.getJSONObject("sidebar").put("background", "#AABBCC");
        write(colorSchemeFile, original.toString(2));

        LoadedTheme restored = storage.restoreBackup("step12-restore", backup.getBackupId());
        assertEquals("step12-restore", restored.getMetadata().getId());

        File restoredColorScheme = storage.ensureColorSchemeFile("step12-restore");
        JSONObject restoredDocument = new ThemeColorSchemeStorage()
            .load(restoredColorScheme.getParentFile());
        assertEquals("#112233",
            restoredDocument.getJSONObject("sidebar").getString("background"));
    }

    private ThemePackageStorage newStorage() {
        Context context = RuntimeEnvironment.getApplication();
        return new ThemePackageStorage(
            temporaryFolder.getRoot(),
            context.getAssets(),
            new ThemePackageLoader()
        );
    }

    private File createPackage(String id) throws Exception {
        File directory = temporaryFolder.newFolder(id + "-source");
        String metadata = "{\"schemaVersion\":1,\"version\":\"1.0.0\","
            + "\"id\":\"" + id + "\",\"label\":\"Step 12 E2E\","
            + "\"type\":\"dark\",\"source\":\"imported\","
            + "\"themeFile\":\"theme.json\","
            + "\"capabilities\":{\"appUi\":true,\"editorSyntax\":true},"
            + "\"defaultFor\":[]}";
        String theme = "{\"name\":\"Step 12 E2E\",\"type\":\"dark\","
            + "\"colors\":{\"editor.background\":\"#101010\"},"
            + "\"tokenColors\":[{\"scope\":\"comment\","
            + "\"settings\":{\"foreground\":\"#808080\"}}]}";
        write(new File(directory, "metadata.json"), metadata);
        write(new File(directory, "theme.json"), theme);
        return directory;
    }

    private static void write(File file, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }
}
