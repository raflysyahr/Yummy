package com.termux.app.theme.adapter;

import com.termux.app.theme.model.AppThemeColorRole;
import com.termux.app.theme.model.AppThemeFallbacks;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ResolvedAppTheme;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeTarget;

import org.json.JSONObject;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Maps VS Code workbench {@code colors} into Yummy's semantic application color roles.
 *
 * <p>This adapter does not modify Android resources or Views. It produces an immutable palette
 * for the later UI integration stage. TextMate {@code tokenColors}, semantic token colors, and
 * terminal colors are intentionally outside this adapter's responsibility.</p>
 */
public final class AppThemeAdapter {
    private static final Map<AppThemeColorRole, String[]> COLOR_IDENTIFIERS = createMappings();

    /** Resolves a theme using only colors declared by the theme document. */
    public ResolvedAppTheme resolve(LoadedTheme loadedTheme) {
        return resolve(loadedTheme, null);
    }

    /**
     * Resolves a theme and fills missing roles from the supplied current-Yummy fallback palette.
     * A color value of {@code "default"} explicitly skips the theme's aliases and uses fallback.
     */
    public ResolvedAppTheme resolve(
        LoadedTheme loadedTheme,
        AppThemeFallbacks fallbacks
    ) {
        if (loadedTheme == null) {
            throw new IllegalArgumentException("Loaded theme is required");
        }
        ThemeMetadata metadata = loadedTheme.getMetadata();
        if (!metadata.supports(ThemeTarget.APP_UI)) {
            throw new IllegalArgumentException(
                "Theme does not declare App UI support: " + metadata.getId());
        }

        JSONObject sourceColors = loadedTheme.getColorTheme().getColors();
        EnumMap<AppThemeColorRole, Integer> resolved =
            new EnumMap<>(AppThemeColorRole.class);
        EnumMap<AppThemeColorRole, String> sources =
            new EnumMap<>(AppThemeColorRole.class);

        for (AppThemeColorRole role : AppThemeColorRole.values()) {
            ThemeColorMatch match = findThemeColor(sourceColors, COLOR_IDENTIFIERS.get(role));
            if (match.color != null) {
                resolved.put(role, match.color);
                sources.put(role, match.identifier);
                continue;
            }

            if (fallbacks != null) {
                Integer fallbackColor = fallbacks.getColor(role);
                if (fallbackColor != null) resolved.put(role, fallbackColor);
            }
        }

        return new ResolvedAppTheme(
            metadata.getId(),
            metadata.getLabel(),
            metadata.getAppearance(),
            resolved,
            sources
        );
    }

    /** Returns the ordered VS Code identifiers considered for a Yummy semantic role. */
    public String[] getCandidateIdentifiers(AppThemeColorRole role) {
        String[] identifiers = role == null ? null : COLOR_IDENTIFIERS.get(role);
        return identifiers == null ? new String[0] : identifiers.clone();
    }

    private static ThemeColorMatch findThemeColor(JSONObject colors, String[] identifiers) {
        if (colors == null || identifiers == null) return ThemeColorMatch.NONE;
        for (String identifier : identifiers) {
            Object raw = colors.opt(identifier);
            if (!(raw instanceof String)) continue;
            String value = ((String) raw).trim();
            if (value.isEmpty()) continue;

            if ("default".equalsIgnoreCase(value)) {
                // VS Code's "default" sentinel means reset to the host/default color.
                return ThemeColorMatch.DEFAULT;
            }
            try {
                return new ThemeColorMatch(
                    AppThemeColorValueParser.parseHexColor(value), identifier);
            } catch (IllegalArgumentException ignored) {
                // The loader normally rejects invalid colors. Ignore them defensively if a
                // caller constructs a VSCodeColorTheme without going through the package loader.
            }
        }
        return ThemeColorMatch.NONE;
    }

    private static Map<AppThemeColorRole, String[]> createMappings() {
        EnumMap<AppThemeColorRole, String[]> mappings =
            new EnumMap<>(AppThemeColorRole.class);

        // Separate mapping policy: each VS Code workbench identifier belongs to one Yummy
        // semantic role only. Missing identifiers deliberately use AppThemeFallbacks; there
        // are no cross-role aliases here.
        put(mappings, AppThemeColorRole.APP_BACKGROUND);
        put(mappings, AppThemeColorRole.TOOLBAR_BACKGROUND, "titleBar.activeBackground");
        put(mappings, AppThemeColorRole.TOOLBAR_FOREGROUND, "titleBar.activeForeground");
        put(mappings, AppThemeColorRole.SIDEBAR_BACKGROUND, "sideBar.background");
        put(mappings, AppThemeColorRole.SIDEBAR_FOREGROUND, "sideBar.foreground");
        put(mappings, AppThemeColorRole.EDITOR_BACKGROUND, "editor.background");
        put(mappings, AppThemeColorRole.EDITOR_FOREGROUND, "editor.foreground");
        put(mappings, AppThemeColorRole.TAB_BAR_BACKGROUND,
            "editorGroupHeader.tabsBackground");
        put(mappings, AppThemeColorRole.TAB_ACTIVE_BACKGROUND, "tab.activeBackground");
        put(mappings, AppThemeColorRole.TAB_ACTIVE_FOREGROUND, "tab.activeForeground");
        put(mappings, AppThemeColorRole.TAB_INACTIVE_BACKGROUND, "tab.inactiveBackground");
        put(mappings, AppThemeColorRole.TAB_INACTIVE_FOREGROUND, "tab.inactiveForeground");
        put(mappings, AppThemeColorRole.STATUS_BAR_BACKGROUND, "statusBar.background");
        put(mappings, AppThemeColorRole.STATUS_BAR_FOREGROUND, "statusBar.foreground");
        put(mappings, AppThemeColorRole.SURFACE_BACKGROUND, "panel.background");
        put(mappings, AppThemeColorRole.SURFACE_FOREGROUND, "panel.foreground");
        put(mappings, AppThemeColorRole.PRIMARY_TEXT, "foreground");
        put(mappings, AppThemeColorRole.SECONDARY_TEXT, "descriptionForeground");
        put(mappings, AppThemeColorRole.DIVIDER, "contrastBorder");
        put(mappings, AppThemeColorRole.ACCENT, "focusBorder");
        put(mappings, AppThemeColorRole.ACCENT_FOREGROUND, "activityBarBadge.foreground");
        put(mappings, AppThemeColorRole.SELECTION_BACKGROUND,
            "list.activeSelectionBackground");
        put(mappings, AppThemeColorRole.SELECTION_FOREGROUND,
            "list.activeSelectionForeground");
        put(mappings, AppThemeColorRole.HOVER_BACKGROUND, "list.hoverBackground");
        put(mappings, AppThemeColorRole.INPUT_BACKGROUND, "input.background");
        put(mappings, AppThemeColorRole.INPUT_FOREGROUND, "input.foreground");
        put(mappings, AppThemeColorRole.INPUT_BORDER, "input.border");
        put(mappings, AppThemeColorRole.BUTTON_BACKGROUND, "button.background");
        put(mappings, AppThemeColorRole.BUTTON_FOREGROUND, "button.foreground");
        put(mappings, AppThemeColorRole.POPUP_BACKGROUND, "menu.background");
        put(mappings, AppThemeColorRole.POPUP_FOREGROUND, "menu.foreground");
        put(mappings, AppThemeColorRole.ICON_FOREGROUND, "icon.foreground");
        put(mappings, AppThemeColorRole.ERROR, "errorForeground");
        put(mappings, AppThemeColorRole.WARNING, "editorWarning.foreground");
        put(mappings, AppThemeColorRole.INFO, "editorInfo.foreground");

        return Collections.unmodifiableMap(mappings);
    }

    private static void put(
        Map<AppThemeColorRole, String[]> mappings,
        AppThemeColorRole role,
        String... identifiers
    ) {
        mappings.put(role, identifiers);
    }

    private static final class ThemeColorMatch {
        static final ThemeColorMatch NONE = new ThemeColorMatch(null, null);
        static final ThemeColorMatch DEFAULT = new ThemeColorMatch(null, "default");
        final Integer color;
        final String identifier;

        ThemeColorMatch(Integer color, String identifier) {
            this.color = color;
            this.identifier = identifier;
        }
    }
}
