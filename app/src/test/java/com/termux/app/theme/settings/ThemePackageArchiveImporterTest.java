package com.termux.app.theme.settings;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ThemePackageArchiveImporterTest {
    @Test
    public void extractsAndValidatesWellFormedPackage() throws Exception {
        File cache = Files.createTempDirectory("yummy-theme-import-test").toFile();
        ThemePackageArchiveImporter.Result result = new ThemePackageArchiveImporter()
            .extract(new ByteArrayInputStream(validPackage()), cache);
        try {
            assertNotNull(result.getTheme());
            assertTrue(new File(result.getPackageDirectory(), "metadata.json").isFile());
            assertTrue(new File(result.getPackageDirectory(), "theme.json").isFile());
        } finally {
            ThemePackageArchiveImporter.deleteTree(cache);
        }
    }

    @Test
    public void rejectsZipSlipEntryAndCleansTemporaryDirectory() throws Exception {
        File cache = Files.createTempDirectory("yummy-theme-slip-test").toFile();
        try {
            new ThemePackageArchiveImporter().extract(
                new ByteArrayInputStream(zipEntry("../escape.txt", "unsafe")), cache);
            fail("Expected ZIP Slip entry to be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().length() > 0);
        } finally {
            File[] children = cache.listFiles();
            assertTrue(children == null || children.length == 0);
            assertFalse(new File(cache.getParentFile(), "escape.txt").exists());
            ThemePackageArchiveImporter.deleteTree(cache);
        }
    }

    private static byte[] validPackage() throws Exception {
        String metadata = "{\"schemaVersion\":1,\"id\":\"stage18-test\","
            + "\"label\":\"Stage 18 Test\",\"type\":\"dark\",\"source\":\"imported\","
            + "\"themeFile\":\"theme.json\",\"capabilities\":{\"appUi\":true,"
            + "\"editorSyntax\":true},\"defaultFor\":[],\"version\":\"1.0.0\"}";
        String theme = "{\"name\":\"Stage 18 Test\",\"type\":\"dark\","
            + "\"colors\":{\"editor.background\":\"#101010\"},"
            + "\"tokenColors\":[{\"scope\":\"comment\","
            + "\"settings\":{\"foreground\":\"#808080\"}}]}";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            add(zip, "metadata.json", metadata);
            add(zip, "theme.json", theme);
        }
        return output.toByteArray();
    }

    private static byte[] zipEntry(String name, String value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            add(zip, name, value);
        }
        return output.toByteArray();
    }

    private static void add(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
