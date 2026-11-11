package com.termux.app.theme.model;

/**
 * Semantic color roles consumed by Yummy's application UI.
 *
 * <p>These roles are intentionally independent from Android resource names and VS Code
 * identifiers. The adapter maps VS Code workbench color identifiers to these roles. Terminal
 * colors are deliberately not represented here so the terminal keeps its own color settings.</p>
 */
public enum AppThemeColorRole {
    APP_BACKGROUND,
    TOOLBAR_BACKGROUND,
    TOOLBAR_FOREGROUND,
    SIDEBAR_BACKGROUND,
    SIDEBAR_FOREGROUND,
    EDITOR_BACKGROUND,
    EDITOR_FOREGROUND,
    TAB_BAR_BACKGROUND,
    TAB_ACTIVE_BACKGROUND,
    TAB_ACTIVE_FOREGROUND,
    TAB_INACTIVE_BACKGROUND,
    TAB_INACTIVE_FOREGROUND,
    STATUS_BAR_BACKGROUND,
    STATUS_BAR_FOREGROUND,
    SURFACE_BACKGROUND,
    SURFACE_FOREGROUND,
    PRIMARY_TEXT,
    SECONDARY_TEXT,
    DIVIDER,
    ACCENT,
    ACCENT_FOREGROUND,
    SELECTION_BACKGROUND,
    SELECTION_FOREGROUND,
    HOVER_BACKGROUND,
    INPUT_BACKGROUND,
    INPUT_FOREGROUND,
    INPUT_BORDER,
    BUTTON_BACKGROUND,
    BUTTON_FOREGROUND,
    POPUP_BACKGROUND,
    POPUP_FOREGROUND,
    ICON_FOREGROUND,
    ERROR,
    WARNING,
    INFO
}
