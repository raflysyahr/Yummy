package com.termux.app.theme.storage;

/** A failure while installing, replacing, or removing an imported theme package. */
public final class ThemeStorageException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_SOURCE,
        INVALID_PACKAGE,
        PACKAGE_ALREADY_EXISTS,
        PACKAGE_NOT_FOUND,
        BUILTIN_ID_RESERVED,
        STORAGE_UNAVAILABLE,
        PACKAGE_LIMIT_EXCEEDED,
        UNSAFE_PATH,
        IO_FAILURE
    }

    private final Code code;

    public ThemeStorageException(Code code, String message) {
        super(message);
        if (code == null) throw new IllegalArgumentException("Error code is required");
        this.code = code;
    }

    public ThemeStorageException(Code code, String message, Throwable cause) {
        super(message, cause);
        if (code == null) throw new IllegalArgumentException("Error code is required");
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
