# Yummy Theme System — Stage 8: Application UI Integration

Stage 8 adds a runtime bridge from `ResolvedAppTheme` to Android Views.

## Classes

- `AppThemeController` resolves a `LoadedTheme` with `AppThemeAdapter` and the existing
  Yummy resource fallback palette.
- `AppThemeViewRegistry` applies colors only to Views explicitly registered by a UI owner.

## Example

```java
AppThemeController appThemeController = new AppThemeController(this);

appThemeController.bindBackground(toolbar, AppThemeColorRole.TOOLBAR_BACKGROUND);
appThemeController.bindText(title, AppThemeColorRole.TOOLBAR_FOREGROUND);
appThemeController.bindBackground(sidebar, AppThemeColorRole.SIDEBAR_BACKGROUND);
appThemeController.bindIcon(menuIcon, AppThemeColorRole.ICON_FOREGROUND);

// After loading the selected package from YummyThemeRegistry:
appThemeController.applyTheme(loadedTheme);

// When the Activity is destroyed:
appThemeController.release();
```

Bindings are explicit; no automatic view-tree scan or view-ID assumptions are made.
Backgrounds are tinted rather than replaced, which preserves existing drawable geometry and
ripple backgrounds. When a theme omits a role, the registry restores the color captured when
the View was bound. Reapplying another palette updates all still-live bindings.

All registry operations must run on the Android main thread. Do not register terminal panel,
terminal transcript, or terminal toolbar Views: terminal colors remain separately configured.

## Integration boundary

This stage provides the runtime application layer, but does not choose a theme or persist a
selection. Settings UI and independent App UI / Editor Syntax selection persistence remain
later work. Existing Activity/controller layouts should be wired explicitly after inspecting
the current `feature/theme-schema` UI source so that only the intended Yummy surfaces are
bound and terminal UI is excluded.
