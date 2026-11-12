package com.termux.app.theme.adapter;

import com.termux.app.theme.loader.ThemeCompatibilityValidator;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ResolvedEditorTheme;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeTarget;
import com.termux.app.theme.model.VSCodeColorTheme;

import org.json.JSONArray;

/**
 * Resolves the editor-syntax portion of a VS Code Color Theme independently from App UI colors.
 *
 * <p>This adapter does not register a theme with Sora and does not mutate an editor instance.
 * It prepares a syntax-only TextMate document for the later Sora integration stage.</p>
 */
public final class EditorThemeAdapter {

    public ResolvedEditorTheme resolve(LoadedTheme loadedTheme)
        throws EditorThemeAdapterException {
        if (loadedTheme == null) {
            throw new EditorThemeAdapterException("Loaded theme is required");
        }

        ThemeMetadata metadata = loadedTheme.getMetadata();
        if (metadata == null || !metadata.supports(ThemeTarget.EDITOR_SYNTAX)) {
            String id = metadata == null ? "<unknown>" : metadata.getId();
            throw new EditorThemeAdapterException(
                "Theme does not declare Editor Syntax support: " + id);
        }

        VSCodeColorTheme document = loadedTheme.getColorTheme();
        if (document == null) {
            throw new EditorThemeAdapterException(
                "Color Theme document is missing for theme " + metadata.getId());
        }

        JSONArray tokenColors = document.getTokenColors();
        String tokenColorsReference = normalizeReference(document.getTokenColorsReference(), metadata);
        if (tokenColors == null && tokenColorsReference == null) {
            throw new EditorThemeAdapterException(
                "Theme " + metadata.getId()
                    + " declares Editor Syntax support but has no tokenColors rules or reference");
        }
        if (tokenColors != null && tokenColors.length() == 0) {
            throw new EditorThemeAdapterException(
                "Theme " + metadata.getId() + " contains an empty tokenColors array");
        }

        return new ResolvedEditorTheme(
            metadata.getId(),
            metadata.getLabel(),
            document.getAppearance(),
            tokenColors,
            tokenColorsReference,
            document.getSemanticHighlighting(),
            document.getSemanticTokenColors()
        );
    }

    /**
     * Revalidates the path at the adapter boundary because the resolved model may later be
     * supplied by code that did not use ThemeDocumentParser.
     */
    private static String normalizeReference(String reference, ThemeMetadata metadata)
        throws EditorThemeAdapterException {
        if (reference == null) return null;
        try {
            return ThemeCompatibilityValidator.normalizeTokenColorsReference(reference);
        } catch (Exception error) {
            throw new EditorThemeAdapterException(
                "Theme " + metadata.getId()
                    + " has an invalid external TextMate reference: " + reference,
                error
            );
        }
    }
}
