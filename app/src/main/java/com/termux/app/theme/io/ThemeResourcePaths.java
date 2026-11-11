package com.termux.app.theme.io;

/** Shared path validation for assets and imported theme packages. */
public final class ThemeResourcePaths {
    private ThemeResourcePaths() { }

    public static String requireSafeRelativePath(String value) {
        if (value == null) throw new IllegalArgumentException("Theme resource path is required");
        String path = value.trim().replace('\\', '/');
        if (path.isEmpty() || path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Theme resource path must be relative");
        }
        if (!path.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("Theme resource path contains unsupported characters");
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Theme resource path contains an unsafe segment");
            }
        }
        return path;
    }
}
