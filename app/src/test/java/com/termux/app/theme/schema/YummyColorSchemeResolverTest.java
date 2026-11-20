package com.termux.app.theme.schema;

import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;
import com.termux.app.theme.model.VSCodeColorTheme;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class YummyColorSchemeResolverTest {
    private final YummyColorSchemeResolver resolver = new YummyColorSchemeResolver();

    private LoadedTheme theme(JSONObject colors) throws Exception {
        ThemeMetadata metadata = new ThemeMetadata(
            "resolver-test", "Resolver Test", ThemeAppearance.DARK, ThemeSource.IMPORTED,
            "theme.json", null, null, null, null,
            EnumSet.of(ThemeTarget.APP_UI), Collections.emptySet());
        return new LoadedTheme(metadata,
            new VSCodeColorTheme(new JSONObject().put("colors", colors), "Resolver Test",
                ThemeAppearance.DARK));
    }

    @Test
    public void resolvesThemeHexAndLocalReferenceSources() throws Exception {
        LoadedTheme theme = theme(new JSONObject().put("sideBar.foreground", "#AABBCC"));
        JSONObject document = new JSONObject()
            .put("version", YummyColorSchemeDefaults.VERSION)
            .put("sidebar", new JSONObject()
                .put("foreground", "theme:sideBar.foreground")
                .put("manual", "#112233")
                .put("alias", "this.sidebar.manual"));

        YummyColorSchemeResolver.ResolutionResult result = resolver.resolve(document, theme);

        assertTrue(result.getDiagnostics().toString(), result.isUsable());
        assertEquals("#AABBCC", result.getColors().get("sidebar.foreground"));
        assertEquals("#112233", result.getColors().get("sidebar.manual"));
        assertEquals("#112233", result.getColors().get("sidebar.alias"));
    }

    @Test
    public void mapsStorageBarPublicPathsToSidebarStorageRuntimeIds() throws Exception {
        JSONObject document = new JSONObject()
            .put("version", YummyColorSchemeDefaults.VERSION)
            .put("storage_bar", new JSONObject()
                .put("foreground", "#123456"));
        YummyColorSchemeResolver.ResolutionResult result = resolver.resolve(document, null);

        assertTrue(result.getDiagnostics().toString(), result.isUsable());
        assertEquals("#123456", result.getColors().get("sidebar.storage.foreground"));
    }

    @Test
    public void rejectsUnsupportedVersionAndUnknownSource() throws Exception {
        JSONObject unsupportedVersion = new JSONObject()
            .put("version", YummyColorSchemeDefaults.VERSION + 1)
            .put("sidebar", new JSONObject().put("foreground", "#112233"));
        YummyColorSchemeResolver.ResolutionResult versionResult =
            resolver.resolve(unsupportedVersion, null);
        assertFalse(versionResult.isUsable());
        assertTrue(versionResult.getDiagnostics().toString().contains("version_invalid"));

        JSONObject unknownSource = new JSONObject()
            .put("version", YummyColorSchemeDefaults.VERSION)
            .put("sidebar", new JSONObject().put("foreground", "palette:foreground"));
        YummyColorSchemeResolver.ResolutionResult sourceResult =
            resolver.resolve(unknownSource, null);
        assertFalse(sourceResult.isUsable());
        assertTrue(sourceResult.getDiagnostics().toString().contains("source_unknown"));
    }

    @Test
    public void rejectsInvalidHexAndMissingLocalReference() throws Exception {
        JSONObject document = new JSONObject()
            .put("version", YummyColorSchemeDefaults.VERSION)
            .put("sidebar", new JSONObject()
                .put("foreground", "#12GG00")
                .put("secondary", "this.sidebar.notDefined"));

        YummyColorSchemeResolver.ResolutionResult result = resolver.resolve(document, null);

        assertFalse(result.isUsable());
        assertTrue(result.getDiagnostics().toString().contains("manual_color_invalid"));
        assertTrue(result.getDiagnostics().toString().contains("local_reference_missing"));
    }

    @Test
    public void rejectsLocalReferenceCycles() throws Exception {
        JSONObject document = new JSONObject()
            .put("version", YummyColorSchemeDefaults.VERSION)
            .put("sidebar", new JSONObject()
                .put("foreground", "this.sidebar.secondary")
                .put("secondary", "this.sidebar.foreground"));

        YummyColorSchemeResolver.ResolutionResult result = resolver.resolve(document, null);

        assertFalse(result.isUsable());
        assertTrue(result.getDiagnostics().toString().contains("reference_cycle"));
    }

    @Test
    public void missingVscodeThemeKeyProducesWarningAndLeavesFallbackUnchanged() throws Exception {
        LoadedTheme theme = theme(new JSONObject());
        JSONObject document = new JSONObject()
            .put("version", YummyColorSchemeDefaults.VERSION)
            .put("sidebar", new JSONObject().put("foreground", "theme:future.missingKey"));

        YummyColorSchemeResolver.ResolutionResult result = resolver.resolve(document, theme);

        assertTrue(result.getDiagnostics().toString(), result.isUsable());
        assertTrue(result.getColors().isEmpty());
        assertTrue(result.getDiagnostics().toString().contains("theme_key_missing"));
    }

    @Test
    public void rejectsRuntimeIdCollisionsAfterStorageBarAliasMapping() throws Exception {
        JSONObject document = new JSONObject()
            .put("version", YummyColorSchemeDefaults.VERSION)
            .put("sidebar", new JSONObject().put("storage", new JSONObject()
                .put("foreground", "#111111")))
            .put("storage_bar", new JSONObject().put("foreground", "#222222"));

        YummyColorSchemeResolver.ResolutionResult result = resolver.resolve(document, null);

        assertFalse(result.isUsable());
        assertTrue(result.getDiagnostics().toString().contains("runtime_property_collision"));
    }

    @Test
    public void bundledDefaultsResolveAndAllLocalAliasesPointToExistingKeys() throws Exception {
        JSONObject defaults = new JSONObject(YummyColorSchemeDefaults.toJson());
        JSONObject colors = new JSONObject();
        collectThemeKeys(defaults, colors);
        YummyColorSchemeResolver.ResolutionResult result = resolver.resolve(defaults, theme(colors));

        assertTrue(result.getDiagnostics().toString(), result.isUsable());
        assertEquals(YummyColorSchemeDefaults.VERSION, defaults.getInt("version"));
        assertTrue("Expected a broad component mapping", result.getColors().size() >= 60);
    }

    private static void collectThemeKeys(JSONObject object, JSONObject colors) throws Exception {
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.opt(key);
            if (value instanceof JSONObject) {
                collectThemeKeys((JSONObject) value, colors);
            } else if (value instanceof String) {
                String expression = ((String) value).trim();
                if (expression.startsWith("theme:")) {
                    colors.put(expression.substring("theme:".length()), "#123456");
                }
            }
        }
    }
}
