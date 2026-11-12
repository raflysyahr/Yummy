package com.termux.app.theme.loader;

import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;
import com.termux.app.theme.model.VSCodeColorTheme;

import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
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
