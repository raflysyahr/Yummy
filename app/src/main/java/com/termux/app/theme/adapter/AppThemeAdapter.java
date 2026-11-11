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
        put(mappings, AppThemeColorRole.APP_BACKGROUND,
            "sideBar.background", "editor.background", "panel.background");
        put(mappings, AppThemeColorRole.TOOLBAR_BACKGROUND,
            "titleBar.activeBackground", "activityBar.background",
            "sideBar.background", "editor.background");
        put(mappings, AppThemeColorRole.TOOLBAR_FOREGROUND,
            "titleBar.activeForeground", "activityBar.foreground", "foreground");
        put(mappings, AppThemeColorRole.SIDEBAR_BACKGROUND,
            "sideBar.background", "panel.background", "editor.background");
        put(mappings, AppThemeColorRole.SIDEBAR_FOREGROUND,
            "sideBar.foreground", "foreground");
        put(mappings, AppThemeColorRole.EDITOR_BACKGROUND, "editor.background");
        put(mappings, AppThemeColorRole.EDITOR_FOREGROUND,
            "editor.foreground", "foreground");
        put(mappings, AppThemeColorRole.TAB_BAR_BACKGROUND,
            "editorGroupHeader.tabsBackground", "editor.background");
        put(mappings, AppThemeColorRole.TAB_ACTIVE_BACKGROUND,
            "tab.activeBackground", "editorGroupHeader.tabsBackground", "sideBar.background");
        put(mappings, AppThemeColorRole.TAB_ACTIVE_FOREGROUND,
            "tab.activeForeground", "foreground");
        put(mappings, AppThemeColorRole.TAB_INACTIVE_BACKGROUND,
            "tab.inactiveBackground", "editorGroupHeader.tabsBackground", "editor.background");
        put(mappings, AppThemeColorRole.TAB_INACTIVE_FOREGROUND,
            "tab.inactiveForeground", "disabledForeground", "foreground");
        put(mappings, AppThemeColorRole.STATUS_BAR_BACKGROUND,
            "statusBar.background", "statusBar.noFolderBackground", "activityBar.background");
        put(mappings, AppThemeColorRole.STATUS_BAR_FOREGROUND,
            "statusBar.foreground", "statusBar.noFolderForeground", "foreground");
        put(mappings, AppThemeColorRole.SURFACE_BACKGROUND,
            "panel.background", "sideBar.background", "editorWidget.background",
            "editor.background");
        put(mappings, AppThemeColorRole.SURFACE_FOREGROUND,
            "panel.foreground", "sideBar.foreground", "editor.foreground", "foreground");
        put(mappings, AppThemeColorRole.PRIMARY_TEXT,
            "foreground", "editor.foreground", "sideBar.foreground");
        put(mappings, AppThemeColorRole.SECONDARY_TEXT,
            "descriptionForeground", "disabledForeground", "tab.inactiveForeground");
        put(mappings, AppThemeColorRole.DIVIDER,
            "contrastBorder", "panel.border", "sideBar.border", "editorGroup.border",
            "input.border");
        put(mappings, AppThemeColorRole.ACCENT,
            "focusBorder", "button.background", "activityBarBadge.background",
            "progressBar.background");
        put(mappings, AppThemeColorRole.ACCENT_FOREGROUND,
            "button.foreground", "activityBarBadge.foreground");
        put(mappings, AppThemeColorRole.SELECTION_BACKGROUND,
            "list.activeSelectionBackground", "list.inactiveSelectionBackground",
            "editor.selectionBackground");
        put(mappings, AppThemeColorRole.SELECTION_FOREGROUND,
            "list.activeSelectionForeground", "list.inactiveSelectionForeground",
            "editor.selectionForeground");
        put(mappings, AppThemeColorRole.HOVER_BACKGROUND,
            "list.hoverBackground", "list.focusBackground", "list.activeSelectionBackground");
        put(mappings, AppThemeColorRole.INPUT_BACKGROUND,
            "input.background", "dropdown.background", "quickInput.background");
        put(mappings, AppThemeColorRole.INPUT_FOREGROUND,
            "input.foreground", "dropdown.foreground", "quickInput.foreground", "foreground");
        put(mappings, AppThemeColorRole.INPUT_BORDER,
            "input.border", "contrastBorder", "panel.border");
        put(mappings, AppThemeColorRole.BUTTON_BACKGROUND,
            "button.background", "activityBarBadge.background");
        put(mappings, AppThemeColorRole.BUTTON_FOREGROUND,
            "button.foreground", "activityBarBadge.foreground");
        put(mappings, AppThemeColorRole.POPUP_BACKGROUND,
            "editorWidget.background", "quickInput.background", "menu.background",
            "dropdown.background", "sideBar.background");
        put(mappings, AppThemeColorRole.POPUP_FOREGROUND,
            "editorWidget.foreground", "quickInput.foreground", "menu.foreground",
            "dropdown.foreground", "foreground");
        put(mappings, AppThemeColorRole.ICON_FOREGROUND, "icon.foreground", "foreground");
        put(mappings, AppThemeColorRole.ERROR,
            "errorForeground", "editorError.foreground", "problemsErrorIcon.foreground");
        put(mappings, AppThemeColorRole.WARNING,
            "editorWarning.foreground", "problemsWarningIcon.foreground");
        put(mappings, AppThemeColorRole.INFO,
            "editorInfo.foreground", "problemsInfoIcon.foreground");
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
