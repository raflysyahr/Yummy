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
        // VS Code extensions may use spaces or Unicode characters in theme resource names.
        // Keep path traversal checks strict, but do not reject otherwise safe file names just
        // because they are outside the old ASCII-only package naming convention.
        for (int i = 0; i < path.length(); i++) {
            char character = path.charAt(i);
            if (Character.isISOControl(character) || character == ':') {
                throw new IllegalArgumentException("Theme resource path contains unsupported characters");
            }
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
