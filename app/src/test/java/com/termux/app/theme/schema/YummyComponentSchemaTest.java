package com.termux.app.theme.schema;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class YummyComponentSchemaTest {
    @Test
    public void buildsTypedLiteralVscodeAndInheritanceDefinitions() throws Exception {
        JSONObject dimension = YummyComponentProperty.literalDefinition(
            YummyComponentProperty.TYPE_DIMENSION,
            16,
            "dp",
            "File tree icon size"
        );
        assertEquals("dimension", dimension.getString("type"));
        assertEquals("literal", dimension.getJSONObject("value").getString("source"));
        assertEquals(16, dimension.getJSONObject("value").getInt("value"));
        assertEquals("dp", dimension.getJSONObject("value").getString("unit"));

        JSONObject fallback = YummyComponentProperty.defaultSource("sidebar.background");
        JSONObject reference = YummyComponentProperty.vscodeDefinition(
            YummyComponentProperty.TYPE_COLOR,
            "colors",
            "sideBar.background",
            fallback,
            "Sidebar background"
        );
        assertEquals("vscode", reference.getJSONObject("value").getString("source"));
        assertEquals("sideBar.background", reference.getJSONObject("value").getString("key"));
        assertEquals("default", reference.getJSONObject("fallback").getString("source"));

        JSONObject inherited = YummyComponentProperty.inheritedDefinition(
            YummyComponentProperty.TYPE_COLOR,
            "sidebar.background",
            null,
            "Storage inherits sidebar background"
        );
        assertEquals("inherit", inherited.getJSONObject("value").getString("source"));
        assertEquals("sidebar.background", inherited.getJSONObject("value").getString("from"));
    }

    @Test
    public void keepsUnknownPropertiesAndExtensionFieldsWhenOnePropertyChanges() throws Exception {
        JSONObject original = new JSONObject()
            .put("future.component.property", new JSONObject()
                .put("type", "object")
                .put("value", new JSONObject().put("source", "literal").put("value", true))
                .put("futureDefinitionFlag", "preserve"));
        YummyComponentSchema schema = new YummyComponentSchema(original);
        schema.putProperty("sidebar.fileTree.iconSize", YummyComponentProperty.literalDefinition(
            YummyComponentProperty.TYPE_DIMENSION, 16, "dp", null));

        JSONObject stored = schema.toJson();
        assertEquals("preserve", stored.getJSONObject("future.component.property")
            .getString("futureDefinitionFlag"));
        assertEquals(16, stored.getJSONObject("sidebar.fileTree.iconSize")
            .getJSONObject("value").getInt("value"));
        assertEquals(Arrays.asList("future.component.property", "sidebar.fileTree.iconSize"),
            schema.getPropertyIds());

        stored.getJSONObject("sidebar.fileTree.iconSize").getJSONObject("value")
            .put("value", 99);
        assertEquals(16, schema.getProperty("sidebar.fileTree.iconSize")
            .getJSONObject("value").getInt("value"));
    }

    @Test
    public void removesOnlyTheRequestedProperty() throws Exception {
        YummyComponentSchema schema = YummyComponentSchema.empty();
        schema.putProperty("sidebar.background", YummyComponentProperty.literalDefinition(
            YummyComponentProperty.TYPE_COLOR, "#101010", null, null));
        schema.putProperty("sidebar.foreground", YummyComponentProperty.literalDefinition(
            YummyComponentProperty.TYPE_COLOR, "#ffffff", null, null));

        assertTrue(schema.removeProperty("sidebar.background"));
        assertFalse(schema.removeProperty("sidebar.background"));
        assertNull(schema.getProperty("sidebar.background"));
        assertNotNull(schema.getProperty("sidebar.foreground"));
    }

    @Test
    public void rejectsInvalidIdsAndMalformedPropertyDefinitions() throws Exception {
        YummyComponentSchema schema = YummyComponentSchema.empty();
        try {
            schema.putProperty("Sidebar Background", new JSONObject());
            throw new AssertionError("Expected invalid component-property ID to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("component-property ID"));
        }

        try {
            schema.putProperty("sidebar.background", new JSONObject()
                .put("type", "dimension")
                .put("value", new JSONObject().put("source", "literal").put("value", 12)));
            throw new AssertionError("Expected dimension without unit to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("explicit unit"));
        }
    }
}
