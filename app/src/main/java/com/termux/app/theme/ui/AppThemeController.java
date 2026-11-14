package com.termux.app.theme.ui;

import android.content.Context;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.view.View;

import com.termux.app.theme.adapter.AppThemeAdapter;
import com.termux.app.theme.model.AppThemeColorRole;
import com.termux.app.theme.model.AppThemeFallbacks;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ResolvedAppTheme;

/**
 * Runtime coordinator for the Application UI theme.
 *
 * <p>It resolves only the App UI capability and applies the resulting palette to Views
 * explicitly registered by Yummy UI controllers. It does not persist the selected theme;
 * independent App UI and Editor Syntax selections are handled by the theme selection layer.</p>
 *
 * <p>Terminal Views must never be registered here.</p>
 */
public final class AppThemeController {
    /** Receives the active App UI palette so non-View surfaces can follow the same theme. */
    public interface PaletteListener {
        void onPaletteChanged(ResolvedAppTheme theme);
    }

    private final AppThemeAdapter adapter;
    private final AppThemeFallbacks fallbacks;
    private final AppThemeViewRegistry viewRegistry;
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
    }

    /** Resolves and applies a theme package that declares App UI support. */
    public ResolvedAppTheme applyTheme(LoadedTheme loadedTheme) {
        ResolvedAppTheme resolved = adapter.resolve(loadedTheme, fallbacks);
        viewRegistry.apply(resolved);
        currentTheme = resolved;
        notifyPaletteChanged();
        return resolved;
    }

    /** Reverts bound Views to their original colors while keeping bindings ready for reapply. */
    public void clearTheme() {
        viewRegistry.apply(null);
        currentTheme = null;
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
        viewRegistry.clear();
        currentTheme = null;
        paletteListener = null;
    }
}
