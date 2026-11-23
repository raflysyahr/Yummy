package com.termux.app.theme.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import java.io.File;
import java.util.Map;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.view.View;

import androidx.annotation.Nullable;

import com.termux.app.theme.adapter.AppThemeAdapter;
import com.termux.app.theme.model.AppThemeColorRole;
import com.termux.app.theme.model.AppThemeFallbacks;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ResolvedAppTheme;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.VSCodeIconTheme;

/**
 * Runtime coordinator for the Application UI theme.
 *
 * <p>It resolves only the App UI capability and applies the resulting palette to Views
 * explicitly registered by Yummy UI controllers. It does not persist the selected theme;
 * independent App UI, Editor UI, and Editor Syntax selections are handled by the theme selection layer.</p>
 *
 * <p>Terminal Views must never be registered here.</p>
 */
public final class AppThemeController {
    /** Receives the active App UI palette so non-View surfaces can follow the same theme. */
    public interface PaletteListener {
        /** Called before App UI-bound Views are restored or recolored. */
        default void onPaletteWillChange() { }

        /** Called after the new App UI palette has been applied to registered Views. */
        void onPaletteChanged(ResolvedAppTheme theme);
    }

    private final AppThemeAdapter adapter;
    private final AppThemeFallbacks fallbacks;
    private final AppThemeViewRegistry viewRegistry;
    private final YummyIconThemeController iconThemeController;
    private ResolvedAppTheme currentTheme;
    private PaletteListener paletteListener;

    public AppThemeController(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;
        this.adapter = new AppThemeAdapter();
        this.fallbacks = AppThemeFallbacks.fromCurrentYummyResources(
            appContext.getResources(), appContext.getPackageName());
        this.viewRegistry = new AppThemeViewRegistry();
        this.iconThemeController = new YummyIconThemeController(appContext);
    }

    /** Resolves and applies a theme package that declares App UI support. */
    public ResolvedAppTheme applyTheme(LoadedTheme loadedTheme) {
        ResolvedAppTheme resolved = adapter.resolve(loadedTheme, fallbacks);
        notifyPaletteWillChange();
        viewRegistry.apply(resolved);
        currentTheme = resolved;
        iconThemeController.refreshBindings(viewRegistry);
        notifyPaletteChanged();
        return resolved;
    }

    /** Reverts bound Views to their original colors while keeping bindings ready for reapply. */
    public void clearTheme() {
        notifyPaletteWillChange();
        viewRegistry.apply(null);
        currentTheme = null;
        iconThemeController.refreshBindings(viewRegistry);
        notifyPaletteChanged();
    }

    public ResolvedAppTheme getCurrentTheme() {
        return currentTheme;
    }

    /**
     * Registers an owner for themed surfaces that are not ordinary Android View properties,
     * such as the Sora editor canvas. The current palette is delivered immediately.
     */
    public void setPaletteListener(PaletteListener listener) {
        paletteListener = listener;
        notifyPaletteChanged();
    }

    private void notifyPaletteWillChange() {
        PaletteListener listener = paletteListener;
        if (listener != null) listener.onPaletteWillChange();
    }

    private void notifyPaletteChanged() {
        PaletteListener listener = paletteListener;
        if (listener != null) listener.onPaletteChanged(currentTheme);
    }

    public void bindBackground(View view, AppThemeColorRole role) {
        viewRegistry.bindBackgroundTint(view, role);
    }

    public void bindText(TextView view, AppThemeColorRole role) {
        viewRegistry.bindTextColor(view, role);
    }

    public void bindHint(TextView view, AppThemeColorRole role) {
        viewRegistry.bindHintTextColor(view, role);
    }

    public void bindIcon(ImageView view, AppThemeColorRole role) {
        viewRegistry.bindImageTint(view, role);
    }

    /** Explicit tint binding for bitmap artwork whose UI role is intentionally monochrome. */
    public void bindIconTintIncludingBitmap(ImageView view, AppThemeColorRole role) {
        viewRegistry.bindImageTintIncludingBitmap(view, role);
    }

    /** Activates a parsed VS Code File Icon Theme for Yummy UI and file/folder associations. */
    public void setIconTheme(@Nullable VSCodeIconTheme theme, @Nullable File extensionRoot,
                             @Nullable String themeDocumentPath,
                             @Nullable ThemeAppearance appearance,
                             @Nullable Map<String, String> explicitMappings) {
        iconThemeController.setTheme(theme, extensionRoot, themeDocumentPath, appearance,
            explicitMappings, viewRegistry);
    }

    /** Binds a fixed Yummy UI icon slot with automatic custom-icon and fallback handling. */
    public void bindUiIcon(ImageView view, String slotId, AppThemeColorRole fallbackTintRole) {
        iconThemeController.bindUiIcon(view, slotId, fallbackTintRole, viewRegistry);
    }

    /** Binds a UI slot while retaining the component's existing fallback artwork. */
    public void bindUiIcon(ImageView view, String slotId, AppThemeColorRole fallbackTintRole,
                           Drawable fallbackOverride) {
        iconThemeController.bindUiIcon(view, slotId, fallbackTintRole,
            fallbackOverride, viewRegistry);
    }

    /** Binds a Yummy UI icon displayed as a TextView start compound drawable. */
    public void bindCompoundUiIcon(TextView view, String slotId) {
        iconThemeController.bindCompoundUiIcon(view, slotId);
    }

    /** Binds a file association and keeps it updated when the active Icon Theme changes. */
    public void bindFileIcon(ImageView view, String fileName, String parentFolderName,
                             String languageId, Drawable fallback) {
        iconThemeController.bindFileIcon(view, fileName, parentFolderName, languageId, fallback);
    }

    /** Binds a folder association and keeps it updated when the active Icon Theme changes. */
    public void bindFolderIcon(ImageView view, String folderName, String parentFolderName,
                               boolean expanded, boolean root, Drawable fallback) {
        iconThemeController.bindFolderIcon(view, folderName, parentFolderName,
            expanded, root, fallback);
    }

    /** Removes Icon Theme and color-tint bindings from a recycled icon View. */
    public void unbindIcon(ImageView view) {
        iconThemeController.unbind(view, viewRegistry);
    }

    /** Clears the independent Icon Theme selection without changing the App UI color theme. */
    public void clearIconTheme() {
        iconThemeController.clearTheme(viewRegistry);
    }

    public boolean hasActiveIconTheme() {
        return iconThemeController.hasActiveTheme();
    }

    public void bindButton(CompoundButton view, AppThemeColorRole role) {
        viewRegistry.bindButtonTint(view, role);
    }

    public void bindProgress(ProgressBar view, AppThemeColorRole role) {
        viewRegistry.bindProgressTint(view, role);
    }

    public void unbind(View view) {
        viewRegistry.unbind(view);
    }

    /** Restores all currently registered Views; call when the owning Activity is destroyed. */
    public void release() {
        iconThemeController.clearTheme(viewRegistry);
        viewRegistry.clear();
        currentTheme = null;
        paletteListener = null;
    }
}
