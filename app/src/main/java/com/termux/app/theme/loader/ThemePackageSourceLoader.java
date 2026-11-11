package com.termux.app.theme.loader;

import android.content.res.AssetManager;

import com.termux.app.theme.model.LoadedTheme;

import java.io.File;

/** Source-loading contract consumed by the theme registry. */
public interface ThemePackageSourceLoader {
    LoadedTheme loadBuiltin(AssetManager assets, String metadataAssetPath) throws ThemeLoadException;

    LoadedTheme loadImported(File packageDirectory) throws ThemeLoadException;
}
