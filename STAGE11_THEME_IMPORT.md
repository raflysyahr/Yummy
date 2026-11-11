# Yummy Theme System — Stage 11: VS Code JSON Theme Import

## Delivered components

- `VSCodeThemeJsonImporter` reads a standalone VS Code Color Theme `.json` document, enforces a 2 MiB input limit, validates UTF-8, accepts JSONC comments/trailing commas, and validates the normalized document through `ThemeDocumentParser`.
- The importer creates a separate Yummy `metadata.json` sidecar. The selected source JSON is copied into the package as `theme.json`; the original document is not edited.
- `ThemeImportController` opens Android's Storage Access Framework document picker, shows a pre-install preview, asks for a light/dark/high-contrast appearance when the JSON omitted `type`, and requires confirmation before installing.
- `ThemeSettingsController` adds an **Import VS Code Theme JSON** action and forwards the picker result to the importer.
- Imported packages are installed through `ThemePackageStorage`, which retains its existing staging, validation, private-storage, and replacement protections. The registry is reloaded after a successful install.

## Supported source format and capabilities

This stage imports one standalone Color Theme `.json` file. It does not import `.vsix` archives or a complete extension directory.

- A non-empty `colors` object enables Application UI Color Scheme support.
- A non-empty inline `tokenColors` array enables Editor Syntax Theme support.
- A string-valued `tokenColors` reference is retained in the JSON but does not enable Editor Syntax support in this single-file workflow because the referenced sibling resource is not imported.
- `semanticTokenColors` and `semanticHighlighting` remain in the imported source JSON. They do not enable Sora syntax support by themselves because the current Sora integration consumes TextMate scopes, not a semantic-token producer.
- The import is rejected if it has neither usable App UI colors nor inline TextMate token rules.
- If the source JSON has no `type`, the user must choose Dark, Light, High Contrast Dark, or High Contrast Light. An unsupported declared type is rejected rather than guessed.

The importer keeps the App UI and Editor Syntax capability flags independent. It does not automatically select or apply the imported theme. The user chooses it later in the corresponding Stage 10 selector. Terminal settings are not read or modified.

## Duplicate IDs

- An imported theme with the same ID prompts before replacement.
- A collision with a built-in theme is never replaced. The importer assigns a collision-free imported ID instead.
- The import storage operation is performed only after parsing/validation and user confirmation.

The standalone JSON file does not contain verified license metadata. The preview warns the user to review the original license before redistribution. License files and external TextMate resources are not bundled by this single-file importer.

## Host lifecycle integration

The Stage 10 `ThemeSettingsController` now exposes:

```java
boolean handled = themeSettingsController.onActivityResult(requestCode, resultCode, data);
```

Forward the Activity result from the existing `onActivityResult` implementation:

```java
@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (themeSettingsController != null
        && themeSettingsController.onActivityResult(requestCode, resultCode, data)) {
        return;
    }
    // Keep existing result handling below this point.
}
```

Call `themeSettingsController.dismiss()` from the owning Activity's `onDestroy()`. This also releases the import worker and any active import dialog. The controller receives the existing Nunito typeface and App UI theme controller; Terminal Views are not involved.

## Validation boundary

- Java 17 source/type smoke compilation against local Android, JSON, and storage API stubs: passed.
- JSONC normalizer test: 5 assertions passed, including preserving URL/comment markers inside strings and rejecting unterminated comments/strings.
- Full Android Gradle build: not performed in this environment.
- Android document-picker, package installation, replacement, and device UI tests: not performed in an actual Yummy checkout/device.

Apply Stage 11 after Stage 10. This patch adds the import flow but does not guess the current host Activity's `onActivityResult` or `onDestroy` implementation; forward the result and lifecycle call as shown above.
