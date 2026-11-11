# VS Code Color Theme Schema

`vscode-color-theme.schema.json` is the bundled JSON Schema used as the structural reference for VS Code-compatible Color Theme JSON files in Yummy.

## Upstream references

The schema is based on Microsoft's implementation:

- [`colorThemeSchema.ts`](https://github.com/microsoft/vscode/blob/main/src/vs/workbench/services/themes/common/colorThemeSchema.ts) — root Color Theme properties and TextMate token rules.
- [`tokenClassificationRegistry.ts`](https://github.com/microsoft/vscode/blob/main/src/vs/platform/theme/common/tokenClassificationRegistry.ts) — semantic token selector syntax and semantic style properties.
- [Color Theme documentation](https://code.visualstudio.com/api/extension-guides/color-theme) — the public theme format and its `colors`, `tokenColors`, and semantic highlighting sections.

Microsoft's TypeScript schema registers references to internal, dynamically-built workbench-color and semantic-token schemas. Those internal registry objects cannot be copied as standalone JSON by copying `colorThemeSchema.ts` alone. This JSON Schema therefore models their portable structure directly.

## Yummy consumption rules

A Color Theme file can contain both UI and syntax colors, but Yummy keeps the two settings independent:

- **Application UI Color Scheme** reads only `colors`.
- **Editor Syntax Theme** reads `tokenColors`, `semanticHighlighting`, and `semanticTokenColors`.
- Changing one setting must not implicitly change the other.
- The terminal theme remains independently configured.

The `colors` object intentionally accepts unrecognized color identifiers. This keeps imported themes forward-compatible with newer VS Code identifiers. Values may be hexadecimal colors or the VS Code `"default"` sentinel. At runtime, Yummy should apply identifiers it supports and safely ignore the rest. The schema validates color values, but it does not claim that Yummy implements every VS Code workbench color.

TextMate `scope` selectors are accepted as arbitrary strings (or arrays of strings), matching Microsoft's schema behavior. Whether a scope produces a visible result depends on the TextMate grammar installed in Yummy. A `tokenColors` string is retained for format compatibility; loading an external `.tmTheme` path requires a separate resource resolver.

## Validation boundary

This is a standalone Draft-07 JSON Schema. It validates strict JSON. VS Code's editor can additionally accept comments and trailing commas in JSONC files; those syntax extensions are handled by a JSONC parser and are outside JSON Schema validation.

The schema describes the format. It does not implement theme loading, inheritance, `include` resolution, TextMate grammar matching, semantic-token production, or Android/Sora color application. Those belong to later Theme Manager and adapter stages.

## Upstream license notice

The upstream Microsoft VS Code schema implementation referenced above is copyright Microsoft Corporation and licensed under the MIT License. This derived schema preserves portions of the upstream structure and selector/style conventions.

> Copyright (c) Microsoft Corporation. All rights reserved.
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
