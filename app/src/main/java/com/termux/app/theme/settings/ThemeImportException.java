package com.termux.app.theme.settings;

/** Raised when a VS Code Color Theme JSON or VSIX package cannot be imported safely. */
public final class ThemeImportException extends Exception {
    private static final long serialVersionUID = 1L;

    public ThemeImportException(String message) {
        super(message);
    }

    public ThemeImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
