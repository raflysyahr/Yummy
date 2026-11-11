# Yummy Theme System — Stage 9: Sora Editor Integration

## What this stage adds

- `SoraEditorThemeApplier` converts the syntax-only result from `EditorThemeAdapter` into a Sora `ThemeModel`, registers it in Sora's `ThemeRegistry`, and activates it with `ThemeRegistry.setTheme(...)`.
- `LoadedTheme` retains the `ThemeResourceReader` for its package, so a `tokenColors` path can be resolved without escaping the built-in/imported package boundary.
- Inline VS Code `tokenColors` use Yummy's existing `textmate/yummy-dark.json` `colors` object. The VS Code workbench `colors` object is never used by the Sora syntax adapter.
- External TextMate references support `.json`, `.tmTheme`, `.plist`, and `.xml` resources.

## Connect from `EditorController`

Call this only after the current Sora TextMate setup has loaded the `yummy-dark` fallback theme and attached `TextMateColorScheme` to the `CodeEditor`:

```java
LoadedTheme loaded = /* selected theme from YummyThemeRegistry */;
ResolvedEditorTheme resolved = new EditorThemeAdapter().resolve(loaded);
String baseThemeJson = new AssetThemeResourceReader(mActivity.getAssets())
    .readUtf8("textmate/yummy-dark.json");
new SoraEditorThemeApplier().apply(
    mEditor,
    resolved,
    loaded.getResourceReader(),
    baseThemeJson
);
```

The call must run on Android's main thread. Keep the existing `TextMateColorScheme.create(ThemeRegistry.getInstance())` attachment in place; the applier activates a new registry theme and does not recreate the editor, replace its document buffer, or reset undo history.

## Independence guarantees

- App UI colors are not read or applied by this class.
- Terminal views, terminal colors, terminal configuration, and terminal sessions are not accessed.
- For inline VS Code theme JSON, only `tokenColors` and its appearance are used. Workbench `colors` and semantic token selectors do not leak into the Sora editor palette.
- `semanticHighlighting` and `semanticTokenColors` remain preserved by `ResolvedEditorTheme`, but this stage does not implement semantic-token production; Sora consumes TextMate scopes here.
- Grammar selection, language configuration, auto-indent, auto-closing pairs, tabs, document content, and undo history are not changed.

## Registry caching

Sora's `ThemeRegistry` is process-global. Each loaded model receives a registry key built from the Yummy theme ID and a SHA-256 fingerprint of the effective source. A replaced package with the same theme ID therefore receives a different key instead of reusing an older cached model.

## Validation boundary

This patch is the Sora adapter and package-resource bridge. The exact `EditorController` hook is intentionally supplied as an integration snippet rather than guessed against an unavailable current `feature/theme-schema` checkout. Apply the call at the point described above after verifying the current `EditorController` source. Full Android Gradle compilation and device testing remain required in the actual checkout.
