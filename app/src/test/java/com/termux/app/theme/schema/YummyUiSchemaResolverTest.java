package com.termux.app.theme.schema;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class YummyUiSchemaResolverTest {
    private JSONObject document(JSONObject components) throws Exception {
        return new JSONObject()
            .put("schemaVersion", 1)
            .put("metadata", new JSONObject()
                .put("id", "test-theme")
                .put("name", "Test Theme")
                .put("source", new JSONObject().put("kind", "imported")))
            .put("vscode", new JSONObject()
                .put("colorTheme", new JSONObject()
                    .put("colors", new JSONObject()
                        .put("sideBar.background", "#112233")))
                .put("configuration", new JSONObject())
                .put("extension", new JSONObject()))
            .put("yummy", new JSONObject().put("components", components));
    }

    @Test
    public void validatesAndResolvesLiteralVscodeInheritanceAndDefaults() throws Exception {
        JSONObject components = new JSONObject()
            .put("sidebar.background", YummyComponentProperty.vscodeDefinition(
                YummyComponentProperty.TYPE_COLOR, "colors", "sideBar.background", null, null))
            .put("sidebar.foreground", YummyComponentProperty.inheritedDefinition(
                YummyComponentProperty.TYPE_COLOR, "sidebar.background", null, null))
            .put("sidebar.padding", YummyComponentProperty.defaultDefinition(
                YummyComponentProperty.TYPE_DIMENSION, "sidebar.padding", null))
            .put("sidebar.border", YummyComponentProperty.literalDefinition(
                YummyComponentProperty.TYPE_COLOR, "#000000", null, null));

        Map<String, YummyUiSchemaResolver.DefaultValue> defaults = new HashMap<>();
        defaults.put("sidebar.padding", YummyUiSchemaResolver.DefaultValue.of(12, "dp"));

        JSONObject candidate = document(components);
        YummyUiSchemaValidationResult validation = new YummyUiSchemaValidator().validate(candidate);
        assertTrue(validation.getErrors().toString(), validation.isValid());

        YummyUiSchemaResolver.ResolutionResult result =
            new YummyUiSchemaResolver().resolve(candidate, defaults);
        assertTrue(result.isUsable());
        assertEquals("#112233", result.get("sidebar.background").getValue());
        assertEquals("#112233", result.get("sidebar.foreground").getValue());
        assertEquals(12, result.get("sidebar.padding").getValue());
        assertEquals("dp", result.get("sidebar.padding").getUnit());
        assertEquals("#000000", result.get("sidebar.border").getValue());
    }

    @Test
    public void rejectsMissingInheritanceTargetsAndCycles() throws Exception {
        JSONObject components = new JSONObject()
            .put("sidebar.background", YummyComponentProperty.inheritedDefinition(
                YummyComponentProperty.TYPE_COLOR, "sidebar.foreground", null, null))
            .put("sidebar.foreground", YummyComponentProperty.inheritedDefinition(
                YummyComponentProperty.TYPE_COLOR, "sidebar.background", null, null));

        YummyUiSchemaValidationResult result =
            new YummyUiSchemaValidator().validate(document(components));
        assertFalse(result.isValid());
        assertNotNull(result.getErrors());
        assertTrue(result.getErrors().toString().contains("inheritance_cycle"));
    }

    @Test
    public void unresolvedVscodeValueUsesExplicitFallback() throws Exception {
        JSONObject definition = YummyComponentProperty.vscodeDefinition(
            YummyComponentProperty.TYPE_COLOR,
            "colors",
            "future.unknownColor",
            YummyComponentProperty.literalSource("#445566", null),
            null);
        JSONObject candidate = document(new JSONObject().put("sidebar.background", definition));

        YummyUiSchemaResolver.ResolutionResult result =
            new YummyUiSchemaResolver().resolve(candidate, Collections.emptyMap());
        assertTrue(result.isUsable());
        assertEquals("#445566", result.get("sidebar.background").getValue());
    }

    @Test
    public void invalidWrapperIsNotUsable() throws Exception {
        YummyUiSchemaResolver.ResolutionResult result =
            new YummyUiSchemaResolver().resolve(new JSONObject(), Collections.emptyMap());
        assertFalse(result.isUsable());
        assertFalse(result.getDiagnostics().isEmpty());
    }
}
