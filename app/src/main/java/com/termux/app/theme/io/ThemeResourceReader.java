package com.termux.app.theme.io;

import java.io.IOException;

/** Reads a UTF-8 file from the root of a theme package. Paths are package-relative. */
public interface ThemeResourceReader {
    String readUtf8(String relativePath) throws IOException;
}
