package com.termux.app.theme.io;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ThemeResourcePathsTest {
    @Test
    public void acceptsSafePackageRelativePaths() {
        assertEquals("textmate/python.tmTheme",
            ThemeResourcePaths.requireSafeRelativePath("textmate/python.tmTheme"));
    }

    @Test
    public void rejectsTraversalAbsoluteAndEmptySegments() {
        String[] unsafe = {"../outside.json", "textmate/../../outside", "/absolute.json",
            "C:/outside.json", "textmate//theme.json", "textmate/./theme.json"};
        for (String path : unsafe) {
            try {
                ThemeResourcePaths.requireSafeRelativePath(path);
                fail("Expected unsafe path to be rejected: " + path);
            } catch (IllegalArgumentException expected) {
                // Expected.
            }
        }
    }

    @Test
    public void normalizesWindowsSeparators() {
        assertEquals("textmate/theme.tmTheme",
            ThemeResourcePaths.requireSafeRelativePath("textmate\\theme.tmTheme"));
    }
}
