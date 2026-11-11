package com.termux.app.theme.loader;

/** Describes a malformed, inaccessible, or incompatible Yummy theme package. */
public class ThemeLoadException extends Exception {
    private static final long serialVersionUID = 1L;

    public ThemeLoadException(String message) {
        super(message);
    }

    public ThemeLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
