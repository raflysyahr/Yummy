package com.termux.app.filemanager.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class FileTypeResolverTest {

    @Test
    public void resolvesCommonSourceExtensions() {
        assertEquals(FileTypeResolver.Kind.PYTHON,
            FileTypeResolver.resolve("main.py", false, null).getKind());
        assertEquals(FileTypeResolver.Kind.JAVASCRIPT,
            FileTypeResolver.resolve("index.jsx", false, null).getKind());
        assertEquals(FileTypeResolver.Kind.TYPESCRIPT,
            FileTypeResolver.resolve("App.tsx", false, null).getKind());
        assertEquals(FileTypeResolver.Kind.HTML,
            FileTypeResolver.resolve("index.html", false, null).getKind());
    }

    @Test
    public void recognizesSpecialFilesWithoutExtensions() {
        assertEquals(FileTypeResolver.Kind.CONFIG,
            FileTypeResolver.resolve("Dockerfile", false, null).getKind());
        assertEquals(FileTypeResolver.Kind.CONFIG,
            FileTypeResolver.resolve(".gitignore", false, null).getKind());
        assertEquals(FileTypeResolver.Kind.CONFIG,
            FileTypeResolver.resolve(".env", false, null).getKind());
    }

    @Test
    public void foldersAreAlwaysFoldersRegardlessOfName() {
        assertEquals(FileTypeResolver.Kind.FOLDER,
            FileTypeResolver.resolve("archive.zip", true, null).getKind());
    }

    @Test
    public void binaryKindsAreNotOpenedAsText() {
        assertFalse(FileTypeResolver.resolve("photo.png", false, "image/png").isTextEditable());
        assertFalse(FileTypeResolver.resolve("app.apk", false, null).isTextEditable());
        assertTrue(FileTypeResolver.resolve("script.py", false, null).isTextEditable());
    }
}
