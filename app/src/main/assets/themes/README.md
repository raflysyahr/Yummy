# Yummy Theme Packages

Yummy keeps the VS Code Color Theme file separate from Yummy-owned package metadata.

- `schema/` contains validation schemas. `vscode-color-theme.schema.json` describes the
  VS Code-compatible theme document; `yummy-theme-metadata.schema.json` describes the
  Yummy sidecar metadata.
- `builtin/<theme-id>/metadata.json` describes a built-in theme package.
- The referenced `themeFile` is a path relative to the app's `assets/` root for built-in
  packages. Imported package paths will be resolved relative to that imported package root.
- The VS Code theme JSON itself is not extended with Yummy-specific fields.

## Independent selections

App UI and Editor Syntax are independent targets. A theme may support either target or
both, but the selected IDs are stored separately. Changing one selection must not
implicitly change the other.

The initial default keeps the current Yummy appearance and the existing TextMate theme:

- App UI default ID: `yummy-default`
- Editor Syntax default ID: `yummy-default`
- Existing editor theme asset: `textmate/yummy-dark.json`

The current theme file contains the VS Code-compatible `colors` and `tokenColors`
sections. A future App UI adapter will only map identifiers it explicitly supports;
missing identifiers must retain the existing Yummy UI colors. This prevents the first
integration from unexpectedly changing components that do not yet have a color mapping.
The existing asset and UI colors are intentionally not modified. Stage 3 adds package
loading and parsing; registry and adapters remain later stages.

## Metadata fields

`metadata.json` contains the stable theme ID, display label, VS Code appearance type,
origin, theme document path, optional author/license/source attribution, supported
capabilities, and the targets for which the theme is the default.

Third-party themes must not be bundled into a public or commercial Yummy build until
their individual licenses and required notices have been checked.

## Theme loader and parser (Stage 3)

`ThemePackageLoader` reads metadata and the referenced VS Code Color Theme JSON without
applying it. Built-in metadata is loaded from the APK assets root:

```java
ThemePackageLoader loader = new ThemePackageLoader();
LoadedTheme theme = loader.loadBuiltin(
    activity.getAssets(),
    "themes/builtin/yummy-default/metadata.json"
);
```

For imported packages, `metadata.json` is expected at the package directory root and
`themeFile` is relative to that same root:

```text
my-theme/
├── metadata.json
├── theme.json
└── LICENSE
```

```java
LoadedTheme theme = loader.loadImported(packageDirectory);
```

The parser reads `colors`, TextMate `tokenColors`, `semanticHighlighting`, and
`semanticTokenColors`. It preserves unknown workbench color identifiers for future
adapters. A string-valued `tokenColors` reference is retained but an external `.tmTheme`
file is not resolved in this stage.

Theme resources are limited to 2 MiB each and metadata to 128 KiB. Imported paths are
canonicalized so a resource cannot escape its package directory. Strict JSON is accepted;
JSONC comments and trailing commas are not supported. Loading does not register a theme,
persist a selection, or apply colors to Android/Sora views.

## Theme registry (Stage 4)

`YummyThemeRegistry` discovers valid packages from two sources:

- Built-in packages: `assets/themes/builtin/<theme-id>/metadata.json`.
- Imported packages: one directory level below the imported themes directory supplied
  to the registry, with `metadata.json` at each package root.

Example initialization:

```java
File importedThemes = new File(context.getFilesDir(), "themes/imported");
YummyThemeRegistry registry = new YummyThemeRegistry(context.getAssets(), importedThemes);
ThemeRegistrySnapshot snapshot = registry.reload();
```

The registry does not create the imported directory, change settings, or apply themes.
The caller owns the imported storage lifecycle and should invoke `reload()` after adding
or removing a package. Each successful refresh publishes an immutable snapshot. Invalid
packages are skipped and exposed through `snapshot.getIssues()` so one broken theme does
not hide other valid themes.

Built-in themes are registered before imported themes. IDs are globally unique; when an
imported package duplicates a built-in ID, the built-in package remains active and the
imported package is reported as a duplicate. Package directory names must match their
metadata IDs. Discovery is intentionally non-recursive, and imported package paths are
canonicalized to prevent package directories from escaping the configured import root.

Use `snapshot.getAppUiThemes()` and `snapshot.getEditorSyntaxThemes()` to populate the
separate App UI and Editor Syntax theme lists. `snapshot.getDefaultThemeFor(target)`
returns the declared default, preferring a built-in default when conflicting defaults
exist. The current `yummy-default` package remains the declared default for both targets.
