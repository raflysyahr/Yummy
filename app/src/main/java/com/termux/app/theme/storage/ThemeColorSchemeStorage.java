package com.termux.app.theme.storage;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Strict UTF-8 reader for the editable color-scheme.json package sidecar. */
public final class ThemeColorSchemeStorage {
    public static final String FILE_NAME = "color-scheme.json";
    public static final long MAX_BYTES = 4L * 1024L * 1024L;

    public JSONObject load(File packageDirectory) throws ThemeStorageException {
        if (packageDirectory == null || !packageDirectory.isDirectory()) {
            throw new ThemeStorageException(ThemeStorageException.Code.PACKAGE_NOT_FOUND,
                "Color Scheme package directory is unavailable");
        }
        File target = new File(packageDirectory, FILE_NAME);
        try {
            File canonicalDirectory = packageDirectory.getCanonicalFile();
            File canonicalTarget = target.getCanonicalFile();
            if (!canonicalDirectory.equals(canonicalTarget.getParentFile())
                || !target.getAbsoluteFile().equals(canonicalTarget)) {
                throw new ThemeStorageException(ThemeStorageException.Code.UNSAFE_PATH,
                    "Color Scheme path resolves outside its theme package");
            }
            if (!target.isFile() || !target.canRead()) {
                throw new ThemeStorageException(ThemeStorageException.Code.IO_FAILURE,
                    "Color Scheme JSON is not a readable regular file");
            }
            if (target.length() > MAX_BYTES) {
                throw new ThemeStorageException(ThemeStorageException.Code.INVALID_PACKAGE,
                    "Color Scheme JSON exceeds the maximum supported size");
            }
            byte[] bytes;
            try (FileInputStream input = new FileInputStream(target);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_BYTES) {
                        throw new ThemeStorageException(ThemeStorageException.Code.INVALID_PACKAGE,
                            "Color Scheme JSON exceeds the maximum supported size");
                    }
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            String json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            return new JSONObject(json);
        } catch (ThemeStorageException error) {
            throw error;
        } catch (CharacterCodingException | JSONException error) {
            throw new ThemeStorageException(ThemeStorageException.Code.INVALID_PACKAGE,
                "Color Scheme JSON is not valid UTF-8 JSON", error);
        } catch (IOException error) {
            throw new ThemeStorageException(ThemeStorageException.Code.IO_FAILURE,
                "Could not read Color Scheme JSON", error);
        }
    }
}
