package com.termux.app.theme.schema;

import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Verifies the runtime hand-off between UI Schema values and Color Scheme overrides. */
public class YummyColorSchemeRuntimeIntegrationTest {

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

    private JSONObject validUiSchema(String color) throws Exception {
        JSONObject components = new JSONObject().put(
            "sidebar.background",
            YummyComponentProperty.literalDefinition(
                YummyComponentProperty.TYPE_COLOR, color, null, null));
        return new JSONObject()
            .put("schemaVersion", 1)
            .put("metadata", new JSONObject()
                .put("id", "runtime-test")
                .put("name", "Runtime Test")
                .put("source", new JSONObject().put("kind", "imported")))
            .put("vscode", new JSONObject()
                .put("colorTheme", new JSONObject().put("colors", new JSONObject()))
                .put("configuration", new JSONObject())
                .put("extension", new JSONObject()))
            .put("yummy", new JSONObject().put("components", components));
    }

    @Test
    public void colorSchemeOverridesUiSchemaAndClearingRestoresUiSchemaValue() throws Exception {
        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            YummyUiSchemaRuntime.ActivationResult activation = runtime.activate(validUiSchema("#112233"));
            assertTrue(activation.getResolution().getDiagnostics().toString(), activation.isAccepted());
            assertEquals("#112233", runtime.getResolvedValue("sidebar.background").getValue());

            Map<String, String> colors = new LinkedHashMap<>();
            colors.put("sidebar.background", "#AABBCC");
            runtime.setColorSchemeOverrides(colors);

            YummyUiSchemaResolver.ResolvedValue override = runtime.getResolvedValue("sidebar.background");
            assertNotNull(override);
            assertEquals("#AABBCC", override.getValue());
            assertEquals("color-scheme", override.getSource());

            runtime.clearColorSchemeOverrides();
            YummyUiSchemaResolver.ResolvedValue restored = runtime.getResolvedValue("sidebar.background");
            assertNotNull(restored);
            assertEquals("#112233", restored.getValue());
            assertEquals("literal", restored.getSource());
        } finally {
            runtime.dispose();
        }
    }

    @Test
    public void invalidUiSchemaDoesNotReplaceLastAcceptedUiSchemaOrColorScheme() throws Exception {
        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            assertTrue(runtime.activate(validUiSchema("#112233")).isAccepted());
            Map<String, String> overrides = new LinkedHashMap<>();
            overrides.put("sidebar.background", "#AABBCC");
            runtime.setColorSchemeOverrides(overrides);
            long acceptedGeneration = runtime.getGeneration();

            YummyUiSchemaRuntime.ActivationResult rejected = runtime.activate(new JSONObject());

            assertFalse(rejected.isAccepted());
            assertEquals(acceptedGeneration, runtime.getGeneration());
            assertEquals("#AABBCC", runtime.getResolvedValue("sidebar.background").getValue());
            assertTrue(runtime.hasActiveSchema());
        } finally {
            runtime.dispose();
        }
    }

    @Test
    public void deactivationClearsUiSchemaAndColorSchemeSnapshots() throws Exception {
        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            assertTrue(runtime.activate(validUiSchema("#112233")).isAccepted());
            Map<String, String> overrides = new LinkedHashMap<>();
            overrides.put("sidebar.background", "#AABBCC");
            runtime.setColorSchemeOverrides(overrides);
            long beforeDeactivate = runtime.getGeneration();

            runtime.deactivate();

            assertTrue(runtime.getGeneration() > beforeDeactivate);
            assertFalse(runtime.hasActiveSchema());
            assertNull(runtime.getResolvedValue("sidebar.background"));
        } finally {
            runtime.dispose();
        }
    }

    @Test
    public void overrideSnapshotIsDefensiveAndIgnoresNullEntries() throws Exception {
        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            assertTrue(runtime.activate(validUiSchema("#112233")).isAccepted());
            Map<String, String> overrides = new LinkedHashMap<>();
            overrides.put("sidebar.background", "#AABBCC");
            overrides.put("sidebar.foreground", null);
            runtime.setColorSchemeOverrides(overrides);

            overrides.put("sidebar.background", "#FFFFFF");
            overrides.put("sidebar.foreground", "#000000");

            assertEquals("#AABBCC", runtime.getResolvedValue("sidebar.background").getValue());
            assertNull(runtime.getResolvedValue("sidebar.foreground"));
        } finally {
            runtime.dispose();
        }
    }

    @Test
    public void replacingAndClearingColorSchemeSnapshotsAdvancesGeneration() throws Exception {
        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            assertTrue(runtime.activate(validUiSchema("#112233")).isAccepted());
            long afterActivation = runtime.getGeneration();

            Map<String, String> first = new LinkedHashMap<>();
            first.put("sidebar.background", "#AABBCC");
            runtime.setColorSchemeOverrides(first);
            long afterFirstOverride = runtime.getGeneration();
            assertTrue(afterFirstOverride > afterActivation);

            Map<String, String> second = new LinkedHashMap<>();
            second.put("sidebar.background", "#DDEEFF");
            runtime.setColorSchemeOverrides(second);
            long afterSecondOverride = runtime.getGeneration();
            assertTrue(afterSecondOverride > afterFirstOverride);
            assertEquals("#DDEEFF", runtime.getResolvedValue("sidebar.background").getValue());

            runtime.clearColorSchemeOverrides();
            assertTrue(runtime.getGeneration() > afterSecondOverride);
            assertEquals("#112233", runtime.getResolvedValue("sidebar.background").getValue());
        } finally {
            runtime.dispose();
        }
    }
}
