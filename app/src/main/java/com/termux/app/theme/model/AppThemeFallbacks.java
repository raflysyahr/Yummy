package com.termux.app.theme.model;

import android.content.res.Resources;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Existing Yummy UI colors used when a VS Code theme does not define a mapped workbench color.
 *
 * <p>Build this from Yummy's current color resources so the default appearance is preserved.
 * Missing resource names are ignored, allowing the fallback map to remain partial.</p>
 */
public final class AppThemeFallbacks {
    private final Map<AppThemeColorRole, Integer> colors;

    private AppThemeFallbacks(Map<AppThemeColorRole, Integer> colors) {
        EnumMap<AppThemeColorRole, Integer> copy = new EnumMap<>(AppThemeColorRole.class);
        copy.putAll(colors);
        this.colors = Collections.unmodifiableMap(copy);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Reads the color resources already used by Yummy's current editor UI. This is a fallback
     * palette only; it does not modify resources or apply colors to any View.
     */
    public static AppThemeFallbacks fromCurrentYummyResources(
        Resources resources,
        String packageName
    ) {
        if (resources == null) {
            throw new IllegalArgumentException("Resources are required");
        }
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("Package name is required");
        }

        Builder builder = builder();
        addResource(resources, packageName, builder, AppThemeColorRole.APP_BACKGROUND, "yummy_bg");
        addResource(resources, packageName, builder, AppThemeColorRole.TOOLBAR_BACKGROUND, "yummy_bar");
        addResource(resources, packageName, builder, AppThemeColorRole.TOOLBAR_FOREGROUND, "yummy_text");
        addResource(resources, packageName, builder, AppThemeColorRole.SIDEBAR_BACKGROUND, "yummy_bg");
        addResource(resources, packageName, builder, AppThemeColorRole.SIDEBAR_FOREGROUND, "yummy_text");
        addResource(resources, packageName, builder, AppThemeColorRole.EDITOR_BACKGROUND, "yummy_bg");
        addResource(resources, packageName, builder, AppThemeColorRole.EDITOR_FOREGROUND, "yummy_text");
        addResource(resources, packageName, builder, AppThemeColorRole.TAB_BAR_BACKGROUND, "yummy_bar");
        addResource(resources, packageName, builder, AppThemeColorRole.TAB_ACTIVE_BACKGROUND, "yummy_tab_active");
        addResource(resources, packageName, builder, AppThemeColorRole.TAB_ACTIVE_FOREGROUND, "yummy_text");
        addResource(resources, packageName, builder, AppThemeColorRole.TAB_INACTIVE_BACKGROUND, "yummy_bar");
        addResource(resources, packageName, builder, AppThemeColorRole.TAB_INACTIVE_FOREGROUND, "yummy_text_dim");
        addResource(resources, packageName, builder, AppThemeColorRole.STATUS_BAR_BACKGROUND, "yummy_bar");
        addResource(resources, packageName, builder, AppThemeColorRole.STATUS_BAR_FOREGROUND, "yummy_text");
        addResource(resources, packageName, builder, AppThemeColorRole.SURFACE_BACKGROUND, "yummy_sheet_bg");
        addResource(resources, packageName, builder, AppThemeColorRole.SURFACE_FOREGROUND, "yummy_text");
        addResource(resources, packageName, builder, AppThemeColorRole.PRIMARY_TEXT, "yummy_text");
        addResource(resources, packageName, builder, AppThemeColorRole.SECONDARY_TEXT, "yummy_text_dim");
        addResource(resources, packageName, builder, AppThemeColorRole.DIVIDER, "yummy_divider");
        addResource(resources, packageName, builder, AppThemeColorRole.ACCENT, "yummy_accent_green");
        addResource(resources, packageName, builder, AppThemeColorRole.ACCENT_FOREGROUND, "yummy_bg");
        addResource(resources, packageName, builder, AppThemeColorRole.SELECTION_BACKGROUND, "yummy_tab_active");
        addResource(resources, packageName, builder, AppThemeColorRole.SELECTION_FOREGROUND, "yummy_text");
        addResource(resources, packageName, builder, AppThemeColorRole.HOVER_BACKGROUND, "yummy_line_current");
        addResource(resources, packageName, builder, AppThemeColorRole.INPUT_BACKGROUND, "yummy_bg");
        addResource(resources, packageName, builder, AppThemeColorRole.INPUT_FOREGROUND, "yummy_text");
        addResource(resources, packageName, builder, AppThemeColorRole.INPUT_BORDER, "yummy_divider");
        addResource(resources, packageName, builder, AppThemeColorRole.BUTTON_BACKGROUND, "yummy_accent_green");
        addResource(resources, packageName, builder, AppThemeColorRole.BUTTON_FOREGROUND, "yummy_bg");
        addResource(resources, packageName, builder, AppThemeColorRole.POPUP_BACKGROUND, "yummy_sheet_bg");
        addResource(resources, packageName, builder, AppThemeColorRole.POPUP_FOREGROUND, "yummy_text");
        addResource(resources, packageName, builder, AppThemeColorRole.ICON_FOREGROUND, "yummy_text");
        // Yummy currently has no dedicated ERROR/WARNING/INFO color resources. Those roles
        // intentionally remain unresolved instead of borrowing an unrelated semantic color.
        return builder.build();
    }

    private static void addResource(
        Resources resources,
        String packageName,
        Builder builder,
        AppThemeColorRole role,
        String resourceName
    ) {
        int resourceId = resources.getIdentifier(resourceName, "color", packageName);
        if (resourceId == 0) return;
        try {
            builder.put(role, resources.getColor(resourceId));
        } catch (RuntimeException ignored) {
            // A missing/unreadable fallback must not prevent other theme colors from loading.
        }
    }

    public Integer getColor(AppThemeColorRole role) {
        return role == null ? null : colors.get(role);
    }

    public boolean hasColor(AppThemeColorRole role) {
        return role != null && colors.containsKey(role);
    }

    public Map<AppThemeColorRole, Integer> getColors() {
        return colors;
    }

    public static final class Builder {
        private final EnumMap<AppThemeColorRole, Integer> colors =
            new EnumMap<>(AppThemeColorRole.class);

        private Builder() { }

        public Builder put(AppThemeColorRole role, int argbColor) {
            if (role == null) throw new IllegalArgumentException("Color role is required");
            colors.put(role, argbColor);
            return this;
        }

        public AppThemeFallbacks build() {
            return new AppThemeFallbacks(colors);
        }
    }
}
