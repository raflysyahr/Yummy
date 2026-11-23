package com.termux.app.theme.registry;

import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;
import com.termux.app.theme.model.VSCodeColorTheme;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Regression coverage for Editor UI target discovery independent of App UI and syntax. */
public class ThemeRegistryEditorUiTargetTest {
    @Test
    public void editorUiRegistryIncludesOnlyThemesDeclaringEditorUiSupport() throws Exception {
        LoadedTheme editorUiOnly = theme("editor-ui-only", EnumSet.of(ThemeTarget.EDITOR_UI),
            EnumSet.of(ThemeTarget.EDITOR_UI));
        LoadedTheme appOnly = theme("app-only", EnumSet.of(ThemeTarget.APP_UI),
            EnumSet.of(ThemeTarget.APP_UI));
        LoadedTheme syntaxOnly = theme("syntax-only", EnumSet.of(ThemeTarget.EDITOR_SYNTAX),
            EnumSet.of(ThemeTarget.EDITOR_SYNTAX));

        ThemeRegistrySnapshot snapshot = new ThemeRegistrySnapshot(
            Arrays.asList(editorUiOnly, appOnly, syntaxOnly), Collections.emptyList());

        assertEquals(1, snapshot.getEditorUiThemes().size());
        assertEquals("editor-ui-only", snapshot.getEditorUiThemes().get(0).getMetadata().getId());
        assertTrue(snapshot.getEditorUiThemes().get(0).getMetadata().isDefaultFor(ThemeTarget.EDITOR_UI));
    }

    private static LoadedTheme theme(String id, java.util.Set<ThemeTarget> supported,
                                     java.util.Set<ThemeTarget> defaults) throws Exception {
        ThemeMetadata metadata = new ThemeMetadata(id, id, ThemeAppearance.DARK,
            ThemeSource.IMPORTED, "theme.json", null, null, null, null, supported, defaults);
        return new LoadedTheme(metadata, new VSCodeColorTheme(
            new JSONObject().put("colors", new JSONObject().put("editor.background", "#101010")),
            id, ThemeAppearance.DARK));
    }
}
