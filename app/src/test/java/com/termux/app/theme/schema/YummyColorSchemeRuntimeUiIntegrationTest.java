package com.termux.app.theme.schema;

import android.content.Context;
import android.graphics.Color;
import android.view.View;

import com.termux.app.theme.loader.ThemePackageLoader;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.storage.ThemePackageStorage;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Validates imported Color Scheme activation through the runtime into real Android Views. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class YummyColorSchemeRuntimeUiIntegrationTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void activatingImportedThemeAppliesItsColorSchemeToBoundView() throws Exception {
        ThemePackageStorage storage = newStorage();
        LoadedTheme theme = storage.importPackage(createPackage("runtime-ui-one"));
        writeColorScheme(storage.ensureColorSchemeFile("runtime-ui-one"), "#123456");

        YummyUiSchemaRuntime runtime = newRuntime();
        YummyUiSchemaViewBinder binder = new YummyUiSchemaViewBinder(runtime);
        View view = new View(RuntimeEnvironment.getApplication());
        binder.bindBackgroundTint("sidebar.background", view);
        YummyUiSchemaThemeManager manager = new YummyUiSchemaThemeManager(runtime, storage);
        try {
            YummyUiSchemaThemeManager.ActivationStatus status = manager.activate(theme);
            assertEquals("runtime-ui-one", status.getThemeId());
            assertEquals(Color.rgb(18, 52, 86), view.getBackgroundTintList().getDefaultColor());
            assertNotNull(manager.getActiveThemeId());
        } finally {
            manager.close();
            binder.unregisterAll();
            runtime.dispose();
        }
    }

    @Test
    public void switchingImportedThemesReplacesPreviousColorSchemeOnBoundView() throws Exception {
        ThemePackageStorage storage = newStorage();
        LoadedTheme first = storage.importPackage(createPackage("runtime-ui-first"));
        LoadedTheme second = storage.importPackage(createPackage("runtime-ui-second"));
        writeColorScheme(storage.ensureColorSchemeFile("runtime-ui-first"), "#112233");
        writeColorScheme(storage.ensureColorSchemeFile("runtime-ui-second"), "#AABBCC");

        YummyUiSchemaRuntime runtime = newRuntime();
        YummyUiSchemaViewBinder binder = new YummyUiSchemaViewBinder(runtime);
        View view = new View(RuntimeEnvironment.getApplication());
        binder.bindBackgroundTint("sidebar.background", view);
        YummyUiSchemaThemeManager manager = new YummyUiSchemaThemeManager(runtime, storage);
        try {
            manager.activate(first);
            assertEquals(Color.rgb(17, 34, 51), view.getBackgroundTintList().getDefaultColor());

            manager.activate(second);
            assertEquals("runtime-ui-second", manager.getActiveThemeId());
            assertEquals(Color.rgb(170, 187, 204), view.getBackgroundTintList().getDefaultColor());
        } finally {
            manager.close();
            binder.unregisterAll();
            runtime.dispose();
        }
    }

    private ThemePackageStorage newStorage() {
        Context context = RuntimeEnvironment.getApplication();
        return new ThemePackageStorage(
            temporaryFolder.getRoot(), context.getAssets(), new ThemePackageLoader());
    }

    private File createPackage(String id) throws Exception {
        File directory = temporaryFolder.newFolder(id + "-source");
        String metadata = "{\"schemaVersion\":1,\"version\":\"1.0.0\","
            + "\"id\":\"" + id + "\",\"label\":\"Runtime UI Integration\","
            + "\"type\":\"dark\",\"source\":\"imported\","
            + "\"themeFile\":\"theme.json\","
            + "\"capabilities\":{\"appUi\":true,\"editorSyntax\":true},"
            + "\"defaultFor\":[]}";
        String theme = "{\"name\":\"Runtime UI Integration\",\"type\":\"dark\","
            + "\"colors\":{\"editor.background\":\"#101010\"},"
            + "\"tokenColors\":[{\"scope\":\"comment\","
            + "\"settings\":{\"foreground\":\"#808080\"}}]}";
        write(new File(directory, "metadata.json"), metadata);
        write(new File(directory, "theme.json"), theme);
        return directory;
    }

    private static void writeColorScheme(File file, String color) throws Exception {
        JSONObject sidebar = new JSONObject().put("background", color);
        JSONObject document = new JSONObject().put("version", YummyColorSchemeDefaults.VERSION)
            .put("sidebar", sidebar);
        write(file, document.toString(2));
    }

    private static void write(File file, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static YummyUiSchemaRuntime newRuntime() {
        return new YummyUiSchemaRuntime(
            new YummyUiSchemaResolver(), new YummyUiComponentRegistry(), task -> {
                task.run();
                return true;
            });
    }
}
