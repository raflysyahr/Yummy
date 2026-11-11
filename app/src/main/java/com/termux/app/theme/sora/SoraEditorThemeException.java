package com.termux.app.theme.sora;

/** Raised when a Yummy Editor Syntax theme cannot be registered with Sora TextMate. */
public final class SoraEditorThemeException extends Exception {
    public SoraEditorThemeException(String message) {
        super(message);
    }

    public SoraEditorThemeException(String message, Throwable cause) {
        super(message, cause);
    }
}
