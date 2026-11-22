package com.termux.app.theme.ui;

import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;
import com.termux.app.theme.model.VSCodeColorTheme;
import com.termux.app.theme.schema.YummyColorSchemeDefaults;
import com.termux.app.theme.schema.YummyColorSchemeResolver;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Regression tests for Theme Settings and Theme Import dialog Color Scheme properties. */
public class YummyThemeManagementColorSchemeTest {

    @Test
    public void defaultsExposeDialogSurfaceBordersAndExplicitButtonColors() throws Exception {
        JSONObject defaults = new JSONObject(YummyColorSchemeDefaults.toJson());
        JSONObject dialog = defaults.getJSONObject("settings").getJSONObject("dialog");
        JSONObject surface = dialog.getJSONObject("surface");
        JSONObject buttons = dialog.getJSONObject("button");
        JSONObject primary = buttons.getJSONObject("primary");
        JSONObject secondary = buttons.getJSONObject("secondary");

        assertEquals(7, defaults.getInt("version"));
        assertEquals("theme:widget.border", dialog.getString("border"));
        assertEquals("theme:widget.border", surface.getString("border"));
        assertEquals("theme:button.foreground", primary.getString("foreground"));
        assertEquals("theme:button.border", primary.getString("border"));
        assertEquals("theme:foreground", secondary.getString("foreground"));
        assertEquals("theme:widget.border", secondary.getString("border"));
        assertNotNull(primary.getString("background"));
        assertNotNull(secondary.getString("background"));
    }

    @Test
    public void themeManagementDefaultsResolveToExpectedVscodeColors() throws Exception {
        JSONObject colors = new JSONObject()
            .put("widget.border", "#010203")
            .put("button.background", "#111213")
            .put("button.foreground", "#212223")
            .put("button.border", "#313233")
            .put("foreground", "#414243")
            .put("editorWidget.background", "#515253");
        ThemeMetadata metadata = new ThemeMetadata(
            "theme-management-test", "Theme Management Test", ThemeAppearance.DARK,
            ThemeSource.IMPORTED, "theme.json", null, null, null, null,
            EnumSet.of(ThemeTarget.APP_UI), Collections.emptySet());
        LoadedTheme theme = new LoadedTheme(metadata,
            new VSCodeColorTheme(new JSONObject().put("colors", colors),
                "Theme Management Test", ThemeAppearance.DARK));

        JSONObject defaults = new JSONObject(YummyColorSchemeDefaults.toJson());
        YummyColorSchemeResolver.ResolutionResult result =
            new YummyColorSchemeResolver().resolve(defaults, theme);

        assertTrue(result.getDiagnostics().toString(), result.isUsable());
        assertEquals("#010203", result.getColors().get("settings.dialog.border"));
        assertEquals("#010203", result.getColors().get("settings.dialog.surface.border"));
        assertEquals("#212223",
            result.getColors().get("settings.dialog.button.primary.foreground"));
        assertEquals("#313233",
            result.getColors().get("settings.dialog.button.primary.border"));
        assertEquals("#414243",
            result.getColors().get("settings.dialog.button.secondary.foreground"));
        assertEquals("#010203",
            result.getColors().get("settings.dialog.button.secondary.border"));
    }
}
