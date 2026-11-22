package com.termux.app.theme.storage;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/** Regression coverage for the Color Scheme sidecar's strict storage boundary. */
public class ThemeColorSchemeStorageTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void loadsValidUtf8DocumentAndPreservesUnicodeValues() throws Exception {
        File directory = temporaryFolder.newFolder("valid-color-scheme");
        write(directory, "{\"version\":3,\"label\":\"café\"}".getBytes(StandardCharsets.UTF_8));

        JSONObject document = new ThemeColorSchemeStorage().load(directory);

        assertEquals(3, document.getInt("version"));
        assertEquals("café", document.getString("label"));
    }

    @Test
    public void rejectsMalformedUtf8InsteadOfReplacingInvalidBytes() throws Exception {
        File directory = temporaryFolder.newFolder("invalid-utf8-color-scheme");
        write(directory, new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xC3, '"', '}'});

        expectCode(directory, ThemeStorageException.Code.INVALID_PACKAGE);
    }

    @Test
    public void rejectsMalformedJsonWithInvalidPackageCode() throws Exception {
        File directory = temporaryFolder.newFolder("malformed-color-scheme");
        write(directory, "{ malformed json".getBytes(StandardCharsets.UTF_8));

        expectCode(directory, ThemeStorageException.Code.INVALID_PACKAGE);
    }

    @Test
    public void rejectsFilesLargerThanTheConfiguredLimit() throws Exception {
        File directory = temporaryFolder.newFolder("oversized-color-scheme");
        byte[] oversized = new byte[(int) ThemeColorSchemeStorage.MAX_BYTES + 1];
        try (FileOutputStream output = new FileOutputStream(
            new File(directory, ThemeColorSchemeStorage.FILE_NAME))) {
            output.write(oversized);
        }

        expectCode(directory, ThemeStorageException.Code.INVALID_PACKAGE);
    }

    @Test
    public void rejectsMissingPackageDirectory() throws Exception {
        File missing = new File(temporaryFolder.getRoot(), "missing-package");

        expectCode(missing, ThemeStorageException.Code.PACKAGE_NOT_FOUND);
    }

    private static void write(File directory, byte[] bytes) throws Exception {
        try (FileOutputStream output = new FileOutputStream(
            new File(directory, ThemeColorSchemeStorage.FILE_NAME))) {
            output.write(bytes);
            output.flush();
        }
    }

    private static void expectCode(File directory, ThemeStorageException.Code expected)
        throws Exception {
        try {
            JSONObject unexpected = new ThemeColorSchemeStorage().load(directory);
            assertNotNull(unexpected);
            fail("Expected ThemeStorageException with code " + expected);
        } catch (ThemeStorageException error) {
            assertEquals(expected, error.getCode());
        }
    }
}
