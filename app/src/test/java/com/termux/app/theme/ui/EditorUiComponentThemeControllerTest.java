package com.termux.app.theme.ui;

import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.VSCodeColorTheme;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EditorUiComponentThemeControllerTest {

    @Test
    public void resolvesToolbarTabsStatusPanelAndPopupWorkbenchColors() throws Exception {
        VSCodeColorTheme theme = theme(
            "{\"name\":\"Workbench\",\"type\":\"dark\",\"colors\":{"
                + "\"titleBar.activeBackground\":\"#101112\","
                + "\"titleBar.activeForeground\":\"#F1F2F3\","
                + "\"editorGroupHeader.tabsBackground\":\"#202122\","
                + "\"tab.activeBackground\":\"#303132\","
                + "\"tab.activeForeground\":\"#E1E2E3\","
                + "\"tab.inactiveBackground\":\"#404142\","
                + "\"tab.inactiveForeground\":\"#A1A2A3\","
                + "\"statusBar.background\":\"#505152\","
                + "\"statusBar.foreground\":\"#B1B2B3\","
                + "\"panel.background\":\"#606162\","
                + "\"panel.foreground\":\"#C1C2C3\","
                + "\"editorGroup.border\":\"#707172\","
                + "\"editorWidget.background\":\"#808182\","
                + "\"editorWidget.foreground\":\"#D1D2D3\","
                + "\"list.activeSelectionBackground\":\"#909192\","
                + "\"list.activeSelectionForeground\":\"#E1E2E3\"}}"
        );

        EditorUiComponentThemeController.Palette palette =
            EditorUiComponentThemeController.resolve(theme);

        assertEquals(Integer.valueOf(0xFF101112), palette.toolbarBackground);
        assertEquals(Integer.valueOf(0xFFF1F2F3), palette.toolbarForeground);
        assertEquals(Integer.valueOf(0xFF202122), palette.tabsBackground);
        assertEquals(Integer.valueOf(0xFF303132), palette.activeTabBackground);
        assertEquals(Integer.valueOf(0xFFE1E2E3), palette.activeTabForeground);
        assertEquals(Integer.valueOf(0xFF404142), palette.inactiveTabBackground);
        assertEquals(Integer.valueOf(0xFFA1A2A3), palette.inactiveTabForeground);
        assertEquals(Integer.valueOf(0xFF505152), palette.statusBackground);
        assertEquals(Integer.valueOf(0xFFB1B2B3), palette.statusForeground);
        assertEquals(Integer.valueOf(0xFF606162), palette.panelBackground);
        assertEquals(Integer.valueOf(0xFFC1C2C3), palette.panelForeground);
        assertEquals(Integer.valueOf(0xFF707172), palette.divider);
        assertEquals(Integer.valueOf(0xFF808182), palette.popupBackground);
        assertEquals(Integer.valueOf(0xFFD1D2D3), palette.popupForeground);
        assertEquals(Integer.valueOf(0xFF909192), palette.popupSelectedBackground);
        assertEquals(Integer.valueOf(0xFFE1E2E3), palette.popupSelectedForeground);
    }

    @Test
    public void missingDefaultAndInvalidColorsRemainUnmapped() throws Exception {
        VSCodeColorTheme theme = theme(
            "{\"name\":\"Sparse\",\"type\":\"dark\",\"colors\":{"
                + "\"titleBar.activeBackground\":\"default\","
                + "\"titleBar.activeForeground\":\"not-a-color\","
                + "\"menu.background\":\"#123456\"}}"
        );

        EditorUiComponentThemeController.Palette palette =
            EditorUiComponentThemeController.resolve(theme);

        assertNull(palette.toolbarBackground);
        assertNull(palette.toolbarForeground);
        assertEquals(Integer.valueOf(0xFF123456), palette.popupBackground);
    }

    private static VSCodeColorTheme theme(String json) throws Exception {
        return new VSCodeColorTheme(
            new JSONObject(json), "Test", ThemeAppearance.DARK);
    }
}
