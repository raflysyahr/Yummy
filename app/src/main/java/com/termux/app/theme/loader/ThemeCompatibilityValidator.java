package com.termux.app.theme.loader;

import com.termux.app.theme.io.ThemeResourcePaths;
import com.termux.app.theme.io.ThemeResourceReader;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeTarget;
import com.termux.app.theme.model.VSCodeColorTheme;

import org.json.JSONException;
import org.json.JSONObject;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Performs runtime compatibility checks that require access to the complete package.
 *
 * <p>The VS Code document parser validates inline structures. This validator additionally
 * verifies referenced TextMate resources, so a package cannot enter the registry while
 * pointing at a missing or unsupported resource.</p>
 */
public final class ThemeCompatibilityValidator {
    private static final int MAX_REFERENCED_RESOURCE_CHARS = 2 * 1024 * 1024;

    public void validate(
        ThemeMetadata metadata,
        VSCodeColorTheme theme,
        ThemeResourceReader resources
    ) throws ThemeLoadException {
        if (metadata == null) throw new ThemeLoadException("Theme metadata is required for compatibility validation");
        if (theme == null) throw new ThemeLoadException("Color Theme document is required for compatibility validation");
        if (resources == null) throw new ThemeLoadException("Theme resources are required for compatibility validation");

        if (metadata.supports(ThemeTarget.APP_UI) && !theme.hasColors()) {
            throw new ThemeLoadException(
                "Theme '" + metadata.getId() + "' declares appUi support but has no colors object");
        }
        if (metadata.supports(ThemeTarget.EDITOR_SYNTAX)
            && !theme.hasTokenColors() && !theme.hasSemanticTokenColors()) {
            throw new ThemeLoadException(
                "Theme '" + metadata.getId()
                    + "' declares editorSyntax support but has no token rules");
        }

        String reference = theme.getTokenColorsReference();
        if (reference != null) validateTokenColorsResource(metadata, reference, resources);
    }

    /**
     * Normalizes the common VS Code "./resource.tmTheme" spelling while rejecting traversal,
     * absolute paths, empty segments, and unsupported file types.
     */
    public static String normalizeTokenColorsReference(String value) throws ThemeLoadException {
        if (value == null || value.trim().isEmpty()) {
            throw new ThemeLoadException("External tokenColors reference is empty");
        }
        String path = value.trim().replace('\\', '/');
        while (path.startsWith("./")) path = path.substring(2);
        try {
            path = ThemeResourcePaths.requireSafeRelativePath(path);
        } catch (IllegalArgumentException error) {
            throw new ThemeLoadException("Unsafe external tokenColors reference: " + value, error);
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".json") || lower.endsWith(".tmtheme")
            || lower.endsWith(".plist") || lower.endsWith(".xml"))) {
            throw new ThemeLoadException(
                "Unsupported external TextMate resource format: " + path
                    + " (supported: .json, .tmTheme, .plist, .xml)");
        }
        return path;
    }

    private static void validateTokenColorsResource(
        ThemeMetadata metadata,
        String rawReference,
        ThemeResourceReader resources
    ) throws ThemeLoadException {
        String reference = normalizeTokenColorsReference(rawReference);
        final String content;
        try {
            content = resources.readUtf8(reference);
        } catch (Exception error) {
            throw new ThemeLoadException(
                "Theme '" + metadata.getId() + "' is missing referenced TextMate resource '"
                    + reference + "'", error);
        }
        if (content == null || content.trim().isEmpty()) {
            throw new ThemeLoadException(
                "Referenced TextMate resource is empty: " + reference);
        }
        if (content.length() > MAX_REFERENCED_RESOURCE_CHARS) {
            throw new ThemeLoadException(
                "Referenced TextMate resource exceeds the 2 MiB limit: " + reference);
        }

        String lower = reference.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            try {
                new JSONObject(content);
            } catch (JSONException error) {
                throw new ThemeLoadException(
                    "Referenced TextMate JSON is invalid: " + reference, error);
            }
        } else {
            validateXmlResource(reference, content);
        }
    }

    private static void validateXmlResource(String reference, String content)
        throws ThemeLoadException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.newDocumentBuilder().parse(new InputSource(new StringReader(content)));
        } catch (Exception error) {
            throw new ThemeLoadException(
                "Referenced TextMate XML is invalid or unsafe: " + reference, error);
        }
    }
}
