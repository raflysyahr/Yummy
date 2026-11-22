package com.termux.app.theme.schema;

import android.graphics.Color;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.view.View;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Exercises the real Android View adapters used by Color Scheme component integrations. */
@RunWith(RobolectricTestRunner.class)
public class YummyUiSchemaViewBinderTest {

    private YummyUiSchemaRuntime newRuntime() {
        return new YummyUiSchemaRuntime(
            new YummyUiSchemaResolver(),
            new YummyUiComponentRegistry(),
            task -> {
                task.run();
                return true;
            }
        );
    }

    private JSONObject schema(String... propertyColorPairs) throws Exception {
        JSONObject components = new JSONObject();
        for (int i = 0; i < propertyColorPairs.length; i += 2) {
            components.put(propertyColorPairs[i],
                YummyComponentProperty.literalDefinition(
                    YummyComponentProperty.TYPE_COLOR, propertyColorPairs[i + 1], null, null));
        }
        return new JSONObject()
            .put("schemaVersion", 1)
            .put("metadata", new JSONObject()
                .put("id", "view-binder-test")
                .put("name", "View Binder Test")
                .put("source", new JSONObject().put("kind", "imported")))
            .put("vscode", new JSONObject()
                .put("colorTheme", new JSONObject().put("colors", new JSONObject()))
                .put("configuration", new JSONObject())
                .put("extension", new JSONObject()))
            .put("yummy", new JSONObject().put("components", components));
    }

    @Test
    public void backgroundTintTracksColorSchemeAndRestoresUiSchemaValue() throws Exception {
        JSONObject defaults = new JSONObject(YummyColorSchemeDefaults.toJson());
        assertEquals("theme:input.background",
            defaults.getJSONObject("sidebar").getJSONObject("dialog")
                .getJSONObject("input").getString("background"));

        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            View view = new View(org.robolectric.RuntimeEnvironment.getApplication());
            YummyUiSchemaViewBinder binder = new YummyUiSchemaViewBinder(runtime);
            binder.bindBackgroundTint("sidebar.dialog.input.background", view);

            YummyUiSchemaRuntime.ActivationResult result =
                runtime.activate(schema("sidebar.dialog.input.background", "#112233"));
            assertTrue(result.getResolution().getDiagnostics().toString(), result.isAccepted());
            assertEquals(Color.rgb(17, 34, 51), view.getBackgroundTintList().getDefaultColor());

            Map<String, String> override = new LinkedHashMap<>();
            override.put("sidebar.dialog.input.background", "#AABBCC");
            runtime.setColorSchemeOverrides(override);
            assertEquals(Color.rgb(170, 187, 204), view.getBackgroundTintList().getDefaultColor());

            runtime.clearColorSchemeOverrides();
            assertEquals(Color.rgb(17, 34, 51), view.getBackgroundTintList().getDefaultColor());
            binder.unregisterAll();
            runtime.deactivate();
        } finally {
            runtime.dispose();
        }
    }

    @Test
    public void textAndHintAdaptersApplyIndependentPropertiesToSameInput() throws Exception {
        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            EditText input = new EditText(org.robolectric.RuntimeEnvironment.getApplication());
            YummyUiSchemaViewBinder binder = new YummyUiSchemaViewBinder(runtime);
            binder.bindTextColor("sidebar.dialog.input.foreground", input);
            binder.bindHintTextColor("sidebar.dialog.input.hint", input);

            YummyUiSchemaRuntime.ActivationResult result = runtime.activate(schema(
                "sidebar.dialog.input.foreground", "#223344",
                "sidebar.dialog.input.hint", "#556677"));
            assertTrue(result.getResolution().getDiagnostics().toString(), result.isAccepted());
            assertEquals(Color.rgb(34, 51, 68), input.getTextColors().getDefaultColor());
            assertEquals(Color.rgb(85, 102, 119), input.getHintTextColors().getDefaultColor());

            binder.unregisterTree(input);
            assertEquals(0, runtime.getBindingCount());
        } finally {
            runtime.dispose();
        }
    }

    @Test
    public void progressAndSwitchAdaptersApplyTheirDedicatedTintLists() throws Exception {
        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            ProgressBar progress = new ProgressBar(org.robolectric.RuntimeEnvironment.getApplication());
            Switch toggle = new Switch(org.robolectric.RuntimeEnvironment.getApplication());
            YummyUiSchemaViewBinder binder = new YummyUiSchemaViewBinder(runtime);
            binder.bindProgressTint("sidebar.progress.foreground", progress);
            binder.bindSwitchThumbTint("settings.editor.control.accent", toggle);
            binder.bindSwitchTrackTint("settings.editor.control.track", toggle);

            YummyUiSchemaRuntime.ActivationResult result = runtime.activate(schema(
                "sidebar.progress.foreground", "#123456",
                "settings.editor.control.accent", "#234567",
                "settings.editor.control.track", "#345678"));
            assertTrue(result.getResolution().getDiagnostics().toString(), result.isAccepted());
            assertNotNull(progress.getProgressTintList());
            assertEquals(Color.rgb(18, 52, 86), progress.getProgressTintList().getDefaultColor());
            assertEquals(Color.rgb(35, 69, 103), toggle.getThumbTintList().getDefaultColor());
            assertEquals(Color.rgb(52, 86, 120), toggle.getTrackTintList().getDefaultColor());
            binder.unregisterAll();
        } finally {
            runtime.dispose();
        }
    }
}
