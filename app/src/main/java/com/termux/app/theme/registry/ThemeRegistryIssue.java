package com.termux.app.theme.registry;

/** A non-fatal problem encountered while discovering or registering a theme package. */
public final class ThemeRegistryIssue {
    public enum Code {
        BUILTIN_SCAN_FAILED,
        IMPORTED_SCAN_FAILED,
        PACKAGE_LOAD_FAILED,
        PACKAGE_ID_MISMATCH,
        UNSAFE_PACKAGE_DIRECTORY,
        DUPLICATE_THEME_ID,
        MULTIPLE_DEFAULTS
    }

    private final Code code;
    private final String packageName;
    private final String message;

    public ThemeRegistryIssue(Code code, String packageName, String message) {
        if (code == null) throw new IllegalArgumentException("Issue code is required");
        this.code = code;
        this.packageName = packageName;
        this.message = message == null ? "Unknown theme registry issue" : message;
    }

    public Code getCode() {
        return code;
    }

    /** Directory or asset package name, when known. */
    public String getPackageName() {
        return packageName;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return code + (packageName == null ? "" : " [" + packageName + "]") + ": " + message;
    }
}
