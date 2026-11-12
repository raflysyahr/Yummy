package com.termux.app.theme.loader;

import com.termux.app.theme.io.ThemeResourceReader;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;

import org.junit.Test;

import java.io.IOException;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ThemeCompatibilityValidatorTest {
    private final ThemeCompatibilityValidator validator = new ThemeCompatibilityValidator();

    @Test
    public void normalizesRelativeTextMateReference() throws Exception {
        assertEquals("textmate/theme.tmTheme",
            ThemeCompatibilityValidator.normalizeTokenColorsReference("./textmate/theme.tmTheme"));
    }

    @Test
    public void rejectsTraversalAndUnsupportedReference() throws Exception {
        String[] invalid = {"../theme.tmTheme", "/theme.tmTheme", "theme.exe"};
        for (String reference : invalid) {
            try {
                ThemeCompatibilityValidator.normalizeTokenColorsReference(reference);
                fail("Expected invalid reference to be rejected: " + reference);
            } catch (ThemeLoadException expected) {
                // Expected.
            }
        }
    }

    @Test
    public void validatesReferencedJsonResource() throws Exception {
        ThemeMetadata metadata = metadata();
        ThemeDocumentParser parser = new ThemeDocumentParser();
        String document = "{\"type\":\"dark\",\"tokenColors\":\"./textmate/tokens.json\"}";
        validator.validate(metadata, parser.parse(document, metadata), new ThemeResourceReader() {
            @Override public String readUtf8(String path) throws IOException {
                if (!"textmate/tokens.json".equals(path)) throw new IOException("Unexpected path");
                return "{\"tokenColors\":[]}";
            }
        });
    }

    @Test
    public void rejectsMissingReferencedResource() throws Exception {
        ThemeMetadata metadata = metadata();
        ThemeDocumentParser parser = new ThemeDocumentParser();
        String document = "{\"type\":\"dark\",\"tokenColors\":\"textmate/missing.tmTheme\"}";
        try {
            validator.validate(metadata, parser.parse(document, metadata), path -> {
                throw new IOException("missing");
            });
            fail("Expected missing resource to be rejected");
        } catch (ThemeLoadException expected) {
            // Expected.
        }
    }

    private static ThemeMetadata metadata() {
        return new ThemeMetadata("test-theme", "Test Theme", ThemeAppearance.DARK,
            ThemeSource.IMPORTED, "theme.json", null, null, null, null,
            EnumSet.of(ThemeTarget.EDITOR_SYNTAX), EnumSet.noneOf(ThemeTarget.class));
    }
}
