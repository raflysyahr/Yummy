package com.termux.app.theme.settings;

import com.termux.app.theme.loader.ThemePackageLoader;
import com.termux.app.theme.model.LoadedTheme;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Securely extracts and validates a Yummy theme package ZIP in the app cache directory. */
public final class ThemePackageArchiveImporter {
    private static final int MAX_ARCHIVE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENTRIES = 128;
    private static final int MAX_DEPTH = 8;
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_PACKAGE_BYTES = 8L * 1024L * 1024L;

    public static final class Result {
        private final File packageDirectory;
        private final LoadedTheme theme;

        public Result(File packageDirectory, LoadedTheme theme) {
            this.packageDirectory = packageDirectory;
            this.theme = theme;
        }

        public File getPackageDirectory() { return packageDirectory; }
        public LoadedTheme getTheme() { return theme; }
    }

    /** Extracts a root-level Yummy package and validates it before returning it. */
    public Result extract(InputStream source, File cacheDirectory) throws IOException {
        if (source == null) throw new IOException("Theme package input is unavailable");
        if (cacheDirectory == null) throw new IOException("Cache directory is unavailable");
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) {
            throw new IOException("Could not create theme package cache");
        }

        File packageDirectory = new File(cacheDirectory, "restore-" + UUID.randomUUID());
        if (!packageDirectory.mkdir()) throw new IOException("Could not create temporary package directory");

        boolean success = false;
        try (ZipInputStream zip = new ZipInputStream(
            new BufferedInputStream(new BoundedInputStream(source, MAX_ARCHIVE_BYTES)))) {
            Set<String> seen = new HashSet<>();
            byte[] buffer = new byte[8192];
            int entries = 0;
            long packageBytes = 0L;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("Theme package contains too many entries");
                String relative = validateEntryName(entry.getName(), entry.isDirectory());
                if (!seen.add(relative)) throw new IOException("Theme package contains a duplicate path");
                File output = new File(packageDirectory, relative);
                ensureInside(packageDirectory, output);

                if (entry.isDirectory()) {
                    if (!output.isDirectory() && !output.mkdirs()) {
                        throw new IOException("Could not create package directory");
                    }
                    if (zip.read() != -1) {
                        throw new IOException("Theme package directory entry contains unexpected data");
                    }
                } else {
                    File parent = output.getParentFile();
                    if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                        throw new IOException("Could not create package resource directory");
                    }
                    if (!output.createNewFile()) throw new IOException("Duplicate package file");
                    long fileBytes = 0L;
                    try (FileOutputStream file = new FileOutputStream(output)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            fileBytes += count;
                            packageBytes += count;
                            if (fileBytes > MAX_FILE_BYTES) {
                                throw new IOException("A theme package file exceeds the 2 MiB limit");
                            }
                            if (packageBytes > MAX_PACKAGE_BYTES) {
                                throw new IOException("Expanded theme package exceeds the 8 MiB limit");
                            }
                            file.write(buffer, 0, count);
                        }
                    }
                }
                zip.closeEntry();
            }

            File metadata = new File(packageDirectory, "metadata.json");
            if (!metadata.isFile()) throw new IOException("ZIP does not contain root metadata.json");
            LoadedTheme loaded;
            try {
                loaded = new ThemePackageLoader().loadImported(packageDirectory);
            } catch (Exception error) {
                throw new IOException("Invalid Yummy theme package: " + safeMessage(error), error);
            }
            success = true;
            return new Result(packageDirectory, loaded);
        } finally {
            if (!success) deleteTree(packageDirectory);
        }
    }

    private static String validateEntryName(String name, boolean directory) throws IOException {
        if (name == null || name.isEmpty() || name.length() > 512) {
            throw new IOException("Theme package contains an invalid entry name");
        }
        if (name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
            || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("Theme package contains an absolute or unsafe path");
        }
        String path = directory && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        String[] segments = path.split("/", -1);
        if (segments.length > MAX_DEPTH) throw new IOException("Theme package path is too deep");
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("Theme package contains an unsafe path segment");
            }
        }
        return path;
    }

    private static void ensureInside(File root, File candidate) throws IOException {
        String rootPath = root.getCanonicalPath();
        String candidatePath = candidate.getCanonicalPath();
        if (!candidatePath.startsWith(rootPath + File.separator)) {
            throw new IOException("Theme package entry escapes its destination");
        }
    }

    public static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        // Best effort: cache cleanup must not mask the original import result.
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error == null ? "Unknown error" : error.getClass().getSimpleName()
            : message;
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maximum;
        private long consumed;

        BoundedInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) account(1);
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) account(count);
            return count;
        }

        private void account(int count) throws IOException {
            consumed += count;
            if (consumed > maximum) throw new IOException("Theme archive exceeds the 16 MiB limit");
        }
    }
}
