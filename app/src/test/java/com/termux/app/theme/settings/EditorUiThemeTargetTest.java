package com.termux.app.theme.settings;

import android.content.Context;

import com.termux.app.theme.loader.ThemePackageLoader;
import com.termux.app.theme.model.ThemeSelection;
import com.termux.app.theme.model.ThemeTarget;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Foundation tests for the independent Editor UI theme target. */
@RunWith(RobolectricTestRunner.class)
public class EditorUiThemeTargetTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(ThemeSelectionStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit();
    }

    @After
    public void tearDown() {
        context.getSharedPreferences(ThemeSelectionStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit();
    }

    @Test
    public void editorUiTargetHasIndependentMetadataIdentity() {
        assertEquals("editor-ui", ThemeTarget.EDITOR_UI.getMetadataValue());
        assertEquals(ThemeTarget.EDITOR_UI, ThemeTarget.fromMetadataValue("editor-ui"));
    }

    @Test
    public void themeSelectionUpdatesPreserveTheOtherTwoTargets() {
        ThemeSelection initial = new ThemeSelection("app-night", "editor-light", "syntax-blue");

        ThemeSelection changed = initial.withEditorUiTheme("editor-solarized");

        assertEquals("app-night", changed.getAppColorSchemeId());
        assertEquals("editor-solarized", changed.getEditorUiThemeId());
        assertEquals("syntax-blue", changed.getEditorSyntaxThemeId());
    }

    @Test
    public void editorUiPreferenceIsStoredIndependentlyAndLegacyPreferenceDefaultsSafely() {
        ThemeSelectionStore store = new ThemeSelectionStore(context);
        store.write(new ThemeSelection("app-night", "editor-light", "syntax-blue"));

        store.selectEditorUiTheme("editor-solarized");
        ThemeSelection selected = store.read();
        assertEquals("app-night", selected.getAppColorSchemeId());
        assertEquals("editor-solarized", selected.getEditorUiThemeId());
        assertEquals("syntax-blue", selected.getEditorSyntaxThemeId());

        context.getSharedPreferences(ThemeSelectionStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().remove(ThemeSelectionStore.KEY_EDITOR_UI_THEME_ID).commit();
        assertEquals(ThemeSelection.DEFAULT_EDITOR_UI_THEME_ID,
            store.read().getEditorUiThemeId());
    }

    @Test
    public void oldMetadataInfersEditorUiSupportFromAppUiCapability() throws Exception {
        String metadata = metadata("\"appUi\":true,\"editorSyntax\":false", "[\"app\"]");
        com.termux.app.theme.model.ThemeMetadata parsed =
            new ThemePackageLoader().parseMetadata(metadata);

        assertTrue(parsed.supports(ThemeTarget.APP_UI));
        assertTrue(parsed.supports(ThemeTarget.EDITOR_UI));
        assertFalse(parsed.supports(ThemeTarget.EDITOR_SYNTAX));
    }

    @Test
    public void editorUiCanBeDeclaredAsItsOwnDefaultTarget() throws Exception {
        String metadata = metadata(
            "\"appUi\":false,\"editorUi\":true,\"editorSyntax\":false",
            "[\"editor-ui\"]");
        com.termux.app.theme.model.ThemeMetadata parsed =
            new ThemePackageLoader().parseMetadata(metadata);

        assertFalse(parsed.supports(ThemeTarget.APP_UI));
        assertTrue(parsed.supports(ThemeTarget.EDITOR_UI));
        assertTrue(parsed.isDefaultFor(ThemeTarget.EDITOR_UI));
    }

    @Test
    public void explicitEditorUiCapabilityCanRemainIndependentFromAppUi() throws Exception {
        String metadata = metadata(
            "\"appUi\":true,\"editorUi\":false,\"editorSyntax\":true",
            "[\"app\",\"editor\"]");
        com.termux.app.theme.model.ThemeMetadata parsed =
            new ThemePackageLoader().parseMetadata(metadata);

        assertTrue(parsed.supports(ThemeTarget.APP_UI));
        assertFalse(parsed.supports(ThemeTarget.EDITOR_UI));
        assertTrue(parsed.supports(ThemeTarget.EDITOR_SYNTAX));
    }

    private static String metadata(String capabilities, String defaults) {
        return "{\"schemaVersion\":1,\"id\":\"editor-ui-test\","
            + "\"label\":\"Editor UI Test\",\"type\":\"dark\","
            + "\"source\":\"imported\",\"themeFile\":\"theme.json\","
            + "\"capabilities\":{" + capabilities + "},\"defaultFor\":" + defaults + "}";
    }
}
