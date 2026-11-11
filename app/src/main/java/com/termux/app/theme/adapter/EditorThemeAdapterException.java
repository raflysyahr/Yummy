package com.termux.app.theme.adapter;

/** Raised when a theme cannot be prepared for Yummy's editor syntax renderer. */
public final class EditorThemeAdapterException extends Exception {
    public EditorThemeAdapterException(String message) {
        super(message);
    }

    public EditorThemeAdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
