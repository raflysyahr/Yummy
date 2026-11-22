package com.termux.app.editor;

import com.termux.app.theme.schema.YummyColorSchemeDefaults;
import com.termux.app.theme.schema.YummyColorSchemeResolver;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Regression coverage for Editor session-save state color mapping. */
public class YummyEditorSessionIndicatorColorSchemeTest {

    @Test
    public void eachSessionStateSelectsItsOwnBackgroundAndForegroundProperties() {
        assertEquals(YummyEditorSessionIndicatorColorProperties.SAVING_BACKGROUND,
            YummyEditorSessionIndicatorColorProperties.backgroundProperty(true, false));
        assertEquals(YummyEditorSessionIndicatorColorProperties.SAVING_FOREGROUND,
            YummyEditorSessionIndicatorColorProperties.foregroundProperty(true, false));
        assertEquals(YummyEditorSessionIndicatorColorProperties.FAILED_BACKGROUND,
            YummyEditorSessionIndicatorColorProperties.backgroundProperty(false, true));
        assertEquals(YummyEditorSessionIndicatorColorProperties.FAILED_FOREGROUND,
            YummyEditorSessionIndicatorColorProperties.foregroundProperty(false, true));
        assertEquals(YummyEditorSessionIndicatorColorProperties.SAVED_BACKGROUND,
            YummyEditorSessionIndicatorColorProperties.backgroundProperty(false, false));
        assertEquals(YummyEditorSessionIndicatorColorProperties.SAVED_FOREGROUND,
            YummyEditorSessionIndicatorColorProperties.foregroundProperty(false, false));
    }

    @Test
    public void builtInFallbackColorsPreserveExistingSessionIndicatorAppearance() {
        assertEquals(0xFF16A34A, YummyEditorSessionIndicatorColorProperties
            .fallbackColor(YummyEditorSessionIndicatorColorProperties.SAVING_BACKGROUND));
        assertEquals(0xFFFFFFFF, YummyEditorSessionIndicatorColorProperties
            .fallbackColor(YummyEditorSessionIndicatorColorProperties.SAVING_FOREGROUND));
        assertEquals(0xFFDC2626, YummyEditorSessionIndicatorColorProperties
            .fallbackColor(YummyEditorSessionIndicatorColorProperties.FAILED_BACKGROUND));
        assertEquals(0xFFFFFFFF, YummyEditorSessionIndicatorColorProperties
            .fallbackColor(YummyEditorSessionIndicatorColorProperties.FAILED_FOREGROUND));
        assertEquals(0x00000000, YummyEditorSessionIndicatorColorProperties
            .fallbackColor(YummyEditorSessionIndicatorColorProperties.SAVED_BACKGROUND));
        assertEquals(0xFF89919B, YummyEditorSessionIndicatorColorProperties
            .fallbackColor(YummyEditorSessionIndicatorColorProperties.SAVED_FOREGROUND));
    }

    @Test
    public void defaultsResolveAllSixSessionIndicatorColors() throws Exception {
        JSONObject defaults = new JSONObject(YummyColorSchemeDefaults.toJson());
        JSONObject session = defaults.getJSONObject("editor")
            .getJSONObject("statusBar").getJSONObject("session");
        assertEquals(8, defaults.getInt("version"));
        assertEquals("#FF16A34A", session.getJSONObject("saving").getString("background"));
        assertEquals("#FFFFFFFF", session.getJSONObject("saving").getString("foreground"));
        assertEquals("#FFDC2626", session.getJSONObject("failed").getString("background"));
        assertEquals("#FFFFFFFF", session.getJSONObject("failed").getString("foreground"));
        assertEquals("#00000000", session.getJSONObject("saved").getString("background"));
        assertEquals("#FF89919B", session.getJSONObject("saved").getString("foreground"));

        YummyColorSchemeResolver.ResolutionResult result =
            new YummyColorSchemeResolver().resolve(defaults, null);
        assertTrue(result.getDiagnostics().toString(), result.isUsable());
        assertEquals("#FF16A34A", result.getColors().get(
            YummyEditorSessionIndicatorColorProperties.SAVING_BACKGROUND));
        assertEquals("#FFDC2626", result.getColors().get(
            YummyEditorSessionIndicatorColorProperties.FAILED_BACKGROUND));
        assertEquals("#FF89919B", result.getColors().get(
            YummyEditorSessionIndicatorColorProperties.SAVED_FOREGROUND));
    }
}
