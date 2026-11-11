package com.termux.app.theme.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ThemeVersionTest {
    @Test
    public void comparesCoreVersionNumbersNumerically() {
        assertTrue(ThemeVersion.parse("2.0.0").compareTo(ThemeVersion.parse("1.99.99")) > 0);
        assertTrue(ThemeVersion.parse("1.10.0").compareTo(ThemeVersion.parse("1.9.99")) > 0);
    }

    @Test
    public void releaseHasHigherPrecedenceThanPrerelease() {
        assertTrue(ThemeVersion.parse("1.0.0").compareTo(ThemeVersion.parse("1.0.0-rc.9")) > 0);
        assertTrue(ThemeVersion.parse("1.0.0-rc.2").compareTo(ThemeVersion.parse("1.0.0-rc.10")) < 0);
    }

    @Test
    public void buildMetadataDoesNotAffectPrecedence() {
        assertEquals(0, ThemeVersion.parse("1.2.3+build.4")
            .compareTo(ThemeVersion.parse("1.2.3+build.5")));
    }

    @Test
    public void rejectsMalformedSemanticVersions() {
        String[] invalid = {"1.0", "01.0.0", "1.0.0-rc.01", "1.0.0-", "1.0.0+", "v1.0.0"};
        for (String value : invalid) {
            try {
                ThemeVersion.parse(value);
                fail("Expected invalid version to be rejected: " + value);
            } catch (IllegalArgumentException expected) {
                // Expected.
            }
        }
    }
}
