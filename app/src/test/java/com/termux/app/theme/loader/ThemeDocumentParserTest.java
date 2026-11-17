package com.termux.app.theme.loader;

import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;
import com.termux.app.theme.model.VSCodeColorTheme;

import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ThemeDocumentParserTest {
    private final ThemeDocumentParser parser = new ThemeDocumentParser();

    @Test
    public void parsesWorkbenchAndTextMateSections() throws Exception {
        VSCodeColorTheme theme = parser.parse(
            "{\"name\":\"Test\",\"type\":\"dark\","
                + "\"colors\":{\"editor.background\":\"#101010\"},"
                + "\"tokenColors\":[{\"scope\":\"comment\","
                + "\"settings\":{\"foreground\":\"#808080\"}}]}"
        );
        assertEquals("Test", theme.getName());
        assertEquals(ThemeAppearance.DARK, theme.getAppearance());
        assertTrue(theme.hasColors());
        assertTrue(theme.hasTokenColors());
        assertEquals("#101010", theme.getColor("editor.background"));
    }


    @Test
    public void preservesUnknownSectionsAndNestedVscodeThemeData() throws Exception {
        String json = "{"
            + "\"name\":\"Forward Compatible\","
            + "\"type\":\"dark\","
            + "\"colors\":{"
            + "\"editor.background\":\"#101010\","
            + "\"future.workbenchColor\":\"color(display-p3 1 0 0)\","
            + "\"symbolIcon.palette\":[\"#111111\",\"#222222\"]},"
            + "\"tokenColors\":[{"
            + "\"name\":\"future rule metadata\","
            + "\"scope\":[\"comment\",\"future.scope\"],"
            + "\"settings\":{\"foreground\":\"#808080\","
            + "\"futureSetting\":{\"enabled\":true}} ,"
            + "\"futureRuleField\":[1,2]}],"
            + "\"semanticHighlighting\":true,"
            + "\"semanticTokenColors\":{"
            + "\"*.declaration\":{\"fontStyle\":\"bold\","
            + "\"futureStyle\":\"preserve-me\"}},"
            + "\"futureThemeSection\":{\"nested\":[1,{\"keep\":true}]}}";

        VSCodeColorTheme theme = parser.parse(json);

        assertEquals("color(display-p3 1 0 0)", theme.getColor("future.workbenchColor"));
        assertEquals(2, ((org.json.JSONArray) theme.getColorValue("symbolIcon.palette")).length());
        assertNotNull(theme.getRawSection("tokenColors"));
        assertTrue(theme.getAdditionalProperties()
            .getJSONObject("futureThemeSection").getJSONArray("nested")
            .getJSONObject(1).getBoolean("keep"));

        org.json.JSONObject copiedColors = theme.getColors();
        copiedColors.put("editor.background", "#ffffff");
        assertEquals("#101010", theme.getColor("editor.background"));

        org.json.JSONObject copiedAdditional = theme.getAdditionalProperties();
        copiedAdditional.remove("futureThemeSection");
        assertNotNull(theme.getRawSection("futureThemeSection"));
    }

    @Test
    public void parsesJsonObjectThroughTheSameValidationPath() throws Exception {
        org.json.JSONObject source = new org.json.JSONObject()
            .put("type", "dark")
            .put("colors", new org.json.JSONObject()
                .put("editor.background", "#101010"))
            .put("futureField", "future-value");

        VSCodeColorTheme theme = parser.parse(source);

        assertEquals("future-value", theme.getAdditionalProperties().getString("futureField"));
        assertEquals("#101010", theme.getColor("editor.background"));
        assertEquals("future-value", theme.toJson().getString("futureField"));
    }

    @Test
    public void rejectsAppearanceMismatchWithMetadata() throws Exception {
        ThemeMetadata metadata = metadata(ThemeAppearance.LIGHT);
        try {
            parser.parse("{\"name\":\"Test\",\"type\":\"dark\","
                + "\"colors\":{\"editor.background\":\"#000000\"}}", metadata);
            fail("Expected appearance mismatch to be rejected");
        } catch (ThemeLoadException expected) {
            assertTrue(expected.getMessage().contains("does not match metadata"));
        }
    }

    @Test
    public void rejectsInvalidWorkbenchColor() throws Exception {
        try {
            parser.parse("{\"colors\":{\"editor.background\":\"black\"}}");
            fail("Expected invalid color to be rejected");
        } catch (ThemeLoadException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    @Test
    public void rejectsEmptyAndOversizedDocuments() throws Exception {
        for (String value : new String[] {"", "   ", repeat(' ', 2 * 1024 * 1024 + 1)}) {
            try {
                parser.parse(value);
                fail("Expected invalid document to be rejected");
            } catch (ThemeLoadException expected) {
                assertTrue(expected.getMessage().length() > 0);
            }
        }
    }

    private static ThemeMetadata metadata(ThemeAppearance appearance) {
        return new ThemeMetadata("test-theme", "Test Theme", appearance,
            ThemeSource.IMPORTED, "theme.json", null, null, null, null,
            EnumSet.of(ThemeTarget.APP_UI), EnumSet.noneOf(ThemeTarget.class));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
