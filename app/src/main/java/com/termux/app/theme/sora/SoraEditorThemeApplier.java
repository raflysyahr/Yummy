package com.termux.app.theme.sora;

import android.os.Looper;

import com.termux.app.theme.io.ThemeResourcePaths;
import com.termux.app.theme.io.ThemeResourceReader;
import com.termux.app.theme.model.ResolvedEditorTheme;

import org.eclipse.tm4e.core.registry.IThemeSource;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Bridges a resolved Yummy Editor Syntax theme to Sora Editor's process-wide TextMate registry.
 *
 * <p>For inline VS Code {@code tokenColors}, the workbench {@code colors} object is deliberately
 * ignored. Only the existing Yummy TextMate base colors are copied into the generated document,
 * so an imported syntax theme cannot unexpectedly change the editor background, foreground,
 * selection, or gutter colors.</p>
 *
 * <p>Sora's ThemeRegistry is global. The registry key includes a content fingerprint so replacing
 * an imported package with the same Yummy theme ID cannot reuse a stale cached ThemeModel.</p>
 */
public final class SoraEditorThemeApplier {
    private static final String REGISTRY_PREFIX = "yummy-theme:";

    /**
     * Registers and activates a syntax theme for an editor that already has a
     * {@code TextMateColorScheme} attached to {@link ThemeRegistry#getInstance()}.
     * Call this on the Android main thread after Sora's TextMate registry has been initialized.
     *
     * @param editor target Sora editor
     * @param resolvedTheme result from {@code EditorThemeAdapter.resolve(...)}
     * @param resourceReader package-scoped reader, required for external token theme references
     * @param baseThemeJson contents of {@code textmate/yummy-dark.json}
     */
    public void apply(
        CodeEditor editor,
        ResolvedEditorTheme resolvedTheme,
        ThemeResourceReader resourceReader,
        String baseThemeJson
    ) throws SoraEditorThemeException {
        requireMainThread();
        if (editor == null) throw new SoraEditorThemeException("Sora CodeEditor is required");
        if (resolvedTheme == null) {
            throw new SoraEditorThemeException("Resolved Editor Syntax theme is required");
        }

        try {
            JSONObject baseDocument = readBaseDocument(baseThemeJson);
            PreparedTheme prepared = prepareTheme(
                resolvedTheme,
                resourceReader,
                baseDocument.optJSONObject("colors")
            );

            ThemeModel model = new ThemeModel(prepared.source, prepared.registryName);
            model.setDark(resolveDarkAppearance(resolvedTheme, baseDocument));

            ThemeRegistry registry = ThemeRegistry.getInstance();
            registry.loadTheme(model);
            // loadTheme() only registers the model. setTheme() is the operation that activates it
            // and notifies the TextMateColorScheme already attached to the editor.
            registry.setTheme(prepared.registryName);
        } catch (SoraEditorThemeException error) {
            throw error;
        } catch (Exception error) {
            throw new SoraEditorThemeException(
                "Could not apply Editor Syntax theme '" + resolvedTheme.getThemeId() + "': "
                    + error.getMessage(),
                error
            );
        }
    }

    private static PreparedTheme prepareTheme(
        ResolvedEditorTheme theme,
        ThemeResourceReader resourceReader,
        JSONObject baseColors
    ) throws IOException, JSONException, SoraEditorThemeException {
        final String content;
        final String sourcePath;

        if (theme.hasInlineTokenColors()) {
            JSONObject document = theme.toTextMateThemeDocument();
            if (baseColors != null) {
                document.put("colors", new JSONObject(baseColors.toString()));
            }
            content = document.toString();
            sourcePath = "yummy-generated-theme.json";
        } else {
            if (resourceReader == null) {
                throw new SoraEditorThemeException(
                    "Theme '" + theme.getThemeId()
                        + "' references an external TextMate theme but has no package resource reader"
                );
            }
            String reference;
            try {
                reference = ThemeResourcePaths.requireSafeRelativePath(
                    theme.getTokenColorsReference());
            } catch (IllegalArgumentException error) {
                throw new SoraEditorThemeException(
                    "Theme '" + theme.getThemeId() + "' has an unsafe TextMate theme reference",
                    error
                );
            }
            content = resourceReader.readUtf8(reference);
            sourcePath = sourcePathFor(reference);
        }

        String fingerprint = sha256(
            theme.getThemeId() + "\n" + theme.getAppearance().name() + "\n" + content);
        String registryName = registryName(theme, fingerprint);
        IThemeSource source = IThemeSource.fromInputStream(
            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
            sourcePath,
            null
        );
        return new PreparedTheme(source, registryName);
    }

    private static JSONObject readBaseDocument(String baseThemeJson) throws JSONException {
        if (baseThemeJson == null || baseThemeJson.trim().isEmpty()) {
            throw new JSONException("Yummy base TextMate theme JSON is empty");
        }
        JSONObject base = new JSONObject(baseThemeJson);
        if (base.optJSONObject("colors") == null) {
            throw new JSONException("Yummy base TextMate theme does not define a colors object");
        }
        return base;
    }

    private static String sourcePathFor(String reference) throws SoraEditorThemeException {
        String lower = reference.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) return "yummy-imported-theme.json";
        if (lower.endsWith(".tmtheme")) return "yummy-imported-theme.tmTheme";
        if (lower.endsWith(".plist")) return "yummy-imported-theme.plist";
        if (lower.endsWith(".xml")) return "yummy-imported-theme.xml";
        throw new SoraEditorThemeException(
            "Unsupported external TextMate theme format: " + reference
                + " (supported: .json, .tmTheme, .plist, .xml)"
        );
    }

    private static boolean resolveDarkAppearance(
        ResolvedEditorTheme theme,
        JSONObject baseDocument
    ) {
        if (theme.isDarkAppearance()) return true;
        if (theme.isLightAppearance()) return false;
        return "dark".equalsIgnoreCase(baseDocument.optString("type", "dark"));
    }

    private static String registryName(ResolvedEditorTheme theme, String fingerprint) {
        String safeId = theme.getThemeId().replaceAll("[^A-Za-z0-9._-]", "_");
        return REGISTRY_PREFIX + safeId + ":" + fingerprint.substring(0, 16);
    }

    private static String sha256(String value) throws SoraEditorThemeException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item & 0xFF));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new SoraEditorThemeException("SHA-256 is unavailable", error);
        }
    }

    private static void requireMainThread() throws SoraEditorThemeException {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new SoraEditorThemeException(
                "Sora Editor Syntax themes must be applied on the Android main thread");
        }
    }

    private static final class PreparedTheme {
        final IThemeSource source;
        final String registryName;

        PreparedTheme(IThemeSource source, String registryName) {
            this.source = source;
            this.registryName = registryName;
        }
    }
}
