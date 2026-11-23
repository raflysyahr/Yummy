package com.termux.app.theme.ui;

import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.VSCodeColorTheme;

import android.content.res.ColorStateList;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
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
    public void resolvesDialogActionForegroundFromVscodeButtonForeground() throws Exception {
        VSCodeColorTheme theme = theme(
            "{\"name\":\"Dialog\",\"type\":\"dark\",\"colors\":{"
                + "\"editorWidget.foreground\":\"#E1E2E3\","
                + "\"button.foreground\":\"#123456\"}}"
        );

        EditorUiComponentThemeController.Palette palette =
            EditorUiComponentThemeController.resolve(theme);

        assertEquals(Integer.valueOf(0xFF123456), palette.buttonForeground);
        assertEquals(Integer.valueOf(0xFFE1E2E3), palette.popupForeground);
    }

    @Test
    public void dialogActionForegroundCanFallBackToPopupForeground() throws Exception {
        VSCodeColorTheme theme = theme(
            "{\"name\":\"Dialog\",\"type\":\"dark\",\"colors\":{"
                + "\"editorWidget.foreground\":\"#ABCDEF\"}}"
        );

        EditorUiComponentThemeController.Palette palette =
            EditorUiComponentThemeController.resolve(theme);

        assertNull(palette.buttonForeground);
        assertEquals(Integer.valueOf(0xFFABCDEF), palette.popupForeground);
    }

    @Test
    public void resolvesEditorBordersAndInteractiveWorkbenchColors() throws Exception {
        VSCodeColorTheme theme = theme(
            "{\"name\":\"Interactive\",\"type\":\"dark\",\"colors\":{"
                + "\"titleBar.border\":\"#101010\","
                + "\"editorGroupHeader.border\":\"#202020\","
                + "\"tab.border\":\"#252525\","
                + "\"tab.activeBorder\":\"#303030\","
                + "\"statusBar.border\":\"#404040\","
                + "\"editorWidget.border\":\"#505050\","
                + "\"tab.hoverBackground\":\"#606060\","
                + "\"tab.hoverForeground\":\"#707070\","
                + "\"list.hoverBackground\":\"#808080\","
                + "\"list.hoverForeground\":\"#909090\","
                + "\"button.background\":\"#A0A0A0\","
                + "\"button.hoverBackground\":\"#B0B0B0\","
                + "\"button.border\":\"#C0C0C0\"}}"
        );

        EditorUiComponentThemeController.Palette palette =
            EditorUiComponentThemeController.resolve(theme);

        assertEquals(Integer.valueOf(0xFF101010), palette.toolbarBorder);
        assertEquals(Integer.valueOf(0xFF202020), palette.headerBorder);
        assertEquals(Integer.valueOf(0xFF252525), palette.tabBorder);
        assertEquals(Integer.valueOf(0xFF303030), palette.activeTabBorder);
        assertEquals(Integer.valueOf(0xFF404040), palette.statusBorder);
        assertEquals(Integer.valueOf(0xFF505050), palette.popupBorder);
        assertEquals(Integer.valueOf(0xFF606060), palette.tabHoverBackground);
        assertEquals(Integer.valueOf(0xFF707070), palette.tabHoverForeground);
        assertEquals(Integer.valueOf(0xFF808080), palette.listHoverBackground);
        assertEquals(Integer.valueOf(0xFF909090), palette.listHoverForeground);
        assertEquals(Integer.valueOf(0xFFA0A0A0), palette.buttonBackground);
        assertEquals(Integer.valueOf(0xFFB0B0B0), palette.buttonHoverBackground);
        assertEquals(Integer.valueOf(0xFFC0C0C0), palette.buttonBorder);
    }

    @Test
    public void borderIdentifiersUseDocumentedFallbacks() throws Exception {
        VSCodeColorTheme theme = theme(
            "{\"name\":\"Fallback Borders\",\"type\":\"dark\",\"colors\":{"
                + "\"editorGroup.border\":\"#111111\","
                + "\"tab.unfocusedActiveBorder\":\"#222222\","
                + "\"menu.border\":\"#333333\"}}"
        );

        EditorUiComponentThemeController.Palette palette =
            EditorUiComponentThemeController.resolve(theme);

        assertEquals(Integer.valueOf(0xFF111111), palette.headerBorder);
        assertEquals(Integer.valueOf(0xFF222222), palette.activeTabBorder);
        assertEquals(Integer.valueOf(0xFF333333), palette.popupBorder);
    }

    @Test
    public void hoverForegroundIsOnlyUsedForInteractiveStates() {
        ColorStateList colors = EditorUiComponentThemeController.createForegroundColorStateList(
            0xFF112233, 0xFFAABBCC);

        assertEquals(0xFF112233, colors.getDefaultColor());
        assertEquals(0xFFAABBCC, colors.getColorForState(
            new int[] { android.R.attr.state_pressed }, 0));
        assertEquals(0xFFAABBCC, colors.getColorForState(
            new int[] { android.R.attr.state_hovered }, 0));
        assertEquals(0xFF112233, colors.getColorForState(new int[0], 0));
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
