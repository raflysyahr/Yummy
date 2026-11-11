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

## Theme package storage (Stage 5)

`ThemePackageStorage` owns the lifecycle of imported package directories. Imported packages
are stored in app-private internal storage at:

```text
<filesDir>/themes/imported/<theme-id>/
```

This location is deliberately private to Yummy. It does not require shared-storage permission
and it is the directory that should be supplied to `YummyThemeRegistry` for imported-theme
discovery.

A normalized imported package has this shape:

```text
my-theme/
├── metadata.json
├── theme.json
├── LICENSE                 # optional
└── resources/              # optional theme resources
```

`metadata.json` must declare `source: "imported"`; `themeFile` and optional `licenseFile`
paths are relative to the package root. The Color Theme document remains an unmodified VS Code
Color Theme JSON document. The storage layer copies the complete package, then loads and
validates it before publishing the package directory.

Example:

```java
ThemePackageStorage storage = new ThemePackageStorage(context);
LoadedTheme imported = storage.importPackage(unpackedPackageDirectory);

// Explicit replacement is allowed only for an already-installed imported theme with the
// same ID. Built-in IDs are reserved and can never be replaced.
LoadedTheme updated = storage.replacePackage(unpackedPackageDirectory);

storage.removeImportedTheme("my-theme");
```

Install and replace operations first copy into a hidden `.staging/` directory on the same
filesystem. They validate the staged package before renaming it into its final ID directory.
Replacement temporarily moves the previous package to a backup and restores it if promotion
of the new package fails. Built-in IDs are checked against `assets/themes/builtin/` and cannot
be installed or replaced by imported packages.

Storage limits are intentionally conservative: at most 128 filesystem entries, nesting up to
8 directories, 2 MiB per file, and 8 MiB total per package. Symbolic links, unsafe relative
paths, special filesystem entries, and entries escaping the source package directory are
rejected. The optional `licenseFile` must exist inside the package. Third-party license text is
preserved as part of the package; this storage layer does not assert that a license permits
redistribution.

The storage API accepts an already-unpacked package directory. After a successful import, replace,
or removal, call `YummyThemeRegistry.reload()` so the in-memory snapshot reflects the updated
package directory. Document-picker integration, ZIP/VSIX extraction, and user-facing import UI
remain outside Stage 5 and are reserved for the later import stage. Removing a package does not
rewrite the user's active theme selection; selection fallback will be handled by the Theme
Manager/settings integration.
