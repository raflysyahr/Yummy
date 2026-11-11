# Yummy Theme System — Stage 10: Settings UI & Theme Preview

## Delivered components

- `ThemeSelectionStore` persists `app_ui_theme_id` and `editor_syntax_theme_id` in separate
  SharedPreferences keys. Updating one target does not write the other target's key.
- `ThemeSettingsController` provides independent Application UI Color Scheme and Editor Syntax
  Theme selectors, package metadata, a preview step, and an explicit Use Theme action.
- App UI preview resolves VS Code workbench colors through `AppThemeAdapter` and the existing
  Yummy resource fallbacks. It displays semantic palette swatches without applying them.
- Editor Syntax preview creates a lightweight highlighted code sample from common inline
  TextMate scope categories. It does not modify Sora's process-wide ThemeRegistry or the active
  editor. Sora remains the authoritative renderer after the user applies the theme.

## Host integration

Create the controller from the current editor/settings owner, passing the already initialized
`YummyThemeRegistry`, the Stage 8 `AppThemeController`, the Stage 9 editor-theme callback, and
Yummy's existing Nunito Typeface:

```java
ThemeSettingsController themeSettings = new ThemeSettingsController(
    this,
    yummyThemeRegistry,
    appThemeController,
    loadedTheme -> {
        ResolvedEditorTheme resolved = new EditorThemeAdapter().resolve(loadedTheme);
        String baseThemeJson = new AssetThemeResourceReader(getAssets())
            .readUtf8("textmate/yummy-dark.json");
        new SoraEditorThemeApplier().apply(
            codeEditor, resolved, loadedTheme.getResourceReader(), baseThemeJson
        );
    },
    nunitoTypeface
);

// Open from Yummy's existing editor/settings entry point:
themeSettings.show();

// After AppThemeController View bindings and Sora TextMate setup are ready:
themeSettings.applySavedThemes();

// Release the dialog from the owning Activity's onDestroy():
themeSettings.dismiss();
```

`applySavedThemes()` restores the persisted App UI and Editor Syntax selections independently.
It attempts both targets separately; a failure in one does not undo a successfully applied other
target. The controller's selection action persists a target only after its applier succeeds.

## Preview limitations

The syntax sample is intentionally an approximation for inline `tokenColors`: it recognizes
common keyword, function, variable, string, number, and comment scope names. It does not evaluate
the full TextMate selector grammar, embedded-language scopes, or semantic tokens. External
`.tmTheme` / plist / XML references are identified in the preview, while Sora loads the actual
package resource when the theme is applied. The preview never temporarily changes Sora's global
ThemeRegistry, avoiding a flash or unintended theme change in the editor behind the dialog.

## Boundaries

- App UI and Editor Syntax selection IDs are stored independently.
- Terminal theme settings are neither read nor written.
- Previewing is read-only; only `Use this theme` applies a palette/theme.
- The controller receives the host Nunito Typeface rather than changing Yummy's font policy.
- This patch does not guess or rewrite a specific Settings Activity or editor layout. Connect
  `show()` to the current Yummy settings entry point after checking that branch's latest UI source.
