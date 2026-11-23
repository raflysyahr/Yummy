package com.termux.app.theme.adapter;

import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ResolvedEditorUiTheme;
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

public class EditorUiThemeAdapterTest {
    private final EditorUiThemeAdapter adapter = new EditorUiThemeAdapter();

    @Test
    public void resolvesEditorSurfaceColorsWithoutReadingSyntaxTokens() throws Exception {
        LoadedTheme theme = theme(
            "{\"name\":\"Editor UI\",\"type\":\"dark\","
                + "\"colors\":{\"editor.background\":\"#112233\","
                + "\"editor.foreground\":\"#AABBCC\","
                + "\"editorLineNumber.foreground\":\"#445566\","
                + "\"editorCursor.foreground\":\"#ABCDEF\"},"
                + "\"tokenColors\":[{\"scope\":\"keyword\","
                + "\"settings\":{\"foreground\":\"#FF0000\"}}]}"
        );

        ResolvedEditorUiTheme resolved = adapter.resolve(theme);

        assertEquals(0xFF112233,
            (int) resolved.getColorsBySoraField().get("WHOLE_BACKGROUND"));
        assertEquals(0xFFAABBCC,
            (int) resolved.getColorsBySoraField().get("TEXT_NORMAL"));
        assertEquals(0xFF445566,
            (int) resolved.getColorsBySoraField().get("LINE_NUMBER"));
        assertEquals(0xFFABCDEF,
            (int) resolved.getColorsBySoraField().get("SELECTION_INSERT"));
        assertEquals("editor.background",
            resolved.getSourceIdentifiers().get("WHOLE_BACKGROUND"));
        assertFalse(resolved.getColorsBySoraField().containsKey("KEYWORD"));
    }

    @Test
    public void missingAndDefaultColorsAreOmittedSoRuntimeCanRestoreBasePalette()
        throws Exception {
        LoadedTheme theme = theme(
            "{\"name\":\"Sparse\",\"type\":\"dark\","
                + "\"colors\":{\"editor.background\":\"default\","
                + "\"editor.foreground\":\"not-a-color\"}}"
        );

        ResolvedEditorUiTheme resolved = adapter.resolve(theme);

        assertTrue(resolved.getColorsBySoraField().isEmpty());
        assertTrue(resolved.getSourceIdentifiers().isEmpty());
    }

    @Test
    public void rejectsThemesWithoutEditorUiCapability() throws Exception {
        LoadedTheme theme = theme(
            "{\"name\":\"Syntax only\",\"type\":\"dark\","
                + "\"tokenColors\":[{\"scope\":\"keyword\","
                + "\"settings\":{\"foreground\":\"#FF0000\"}}]}",
            EnumSet.of(ThemeTarget.EDITOR_SYNTAX)
        );

        try {
            adapter.resolve(theme);
            org.junit.Assert.fail("Expected unsupported Editor UI target to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Editor UI"));
        }
    }

    private static LoadedTheme theme(String json) throws Exception {
        return theme(json, EnumSet.of(ThemeTarget.EDITOR_UI));
    }

    private static LoadedTheme theme(String json, java.util.Set<ThemeTarget> targets)
        throws Exception {
        JSONObject source = new JSONObject(json);
        ThemeMetadata metadata = new ThemeMetadata(
            "editor-ui-test", "Editor UI Test", ThemeAppearance.DARK,
            ThemeSource.IMPORTED, "theme.json", null, null, null, null,
            targets, Collections.<ThemeTarget>emptySet()
        );
        return new LoadedTheme(
            metadata, new VSCodeColorTheme(source, "Editor UI Test", ThemeAppearance.DARK));
    }
}
