package com.termux.app.theme.io;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads an imported theme package while preventing paths from escaping its package root. */
public final class DirectoryThemeResourceReader implements ThemeResourceReader {
    private static final int MAX_RESOURCE_BYTES = 2 * 1024 * 1024;

    private final File canonicalRoot;
    private final String canonicalRootPrefix;

    public DirectoryThemeResourceReader(File packageRoot) throws IOException {
        if (packageRoot == null) throw new IllegalArgumentException("Theme package directory is required");
        if (!packageRoot.isDirectory()) {
            throw new IOException("Theme package directory does not exist: " + packageRoot);
        }
        canonicalRoot = packageRoot.getCanonicalFile();
        canonicalRootPrefix = canonicalRoot.getPath() + File.separator;
    }

    @Override
    public String readUtf8(String relativePath) throws IOException {
        String safePath = ThemeResourcePaths.requireSafeRelativePath(relativePath);
        File target = new File(canonicalRoot, safePath).getCanonicalFile();
        if (!target.getPath().startsWith(canonicalRootPrefix)) {
            throw new IOException("Theme resource escapes its package directory: " + relativePath);
        }
        if (!target.isFile()) {
            throw new IOException("Theme resource does not exist: " + relativePath);
        }
        if (target.length() > MAX_RESOURCE_BYTES) {
            throw new IOException("Theme resource exceeds 2 MiB: " + relativePath);
        }

        try (InputStream input = new FileInputStream(target);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESOURCE_BYTES) {
                    throw new IOException("Theme resource exceeds 2 MiB: " + relativePath);
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
