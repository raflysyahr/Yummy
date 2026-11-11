package com.termux.app.theme.io;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads theme resources from the APK assets directory. */
public final class AssetThemeResourceReader implements ThemeResourceReader {
    private static final int MAX_RESOURCE_BYTES = 2 * 1024 * 1024;

    private final AssetManager assets;

    public AssetThemeResourceReader(AssetManager assets) {
        if (assets == null) throw new IllegalArgumentException("AssetManager is required");
        this.assets = assets;
    }

    @Override
    public String readUtf8(String relativePath) throws IOException {
        String safePath = ThemeResourcePaths.requireSafeRelativePath(relativePath);
        try (InputStream input = assets.open(safePath);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyLimited(input, output, MAX_RESOURCE_BYTES, safePath);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void copyLimited(InputStream input, ByteArrayOutputStream output,
                                    int limit, String path) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("Theme resource exceeds 2 MiB: " + path);
            }
            output.write(buffer, 0, read);
        }
    }
}
