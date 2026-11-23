package com.termux.app.theme.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Regression tests for repeated Editor UI activation and dynamic view lifecycle. */
@RunWith(RobolectricTestRunner.class)
public class EditorUiComponentThemeLifecycleTest {

    @Test
    public void repeatedSwitchesRestoreLatestAppUiBaseForMissingWorkbenchColors() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout root = new LinearLayout(activity);
        TextView toolbar = new TextView(activity);
        toolbar.setId(R.id.yummy_app_bar);
        toolbar.setText("Toolbar");
        toolbar.setTextColor(0xFF101112);
        toolbar.setBackgroundColor(0xFF010203);
        root.addView(toolbar, new LinearLayout.LayoutParams(240, 48));
        View divider = new View(activity);
        divider.setId(R.id.yummy_app_bar_divider);
        root.addView(divider, new LinearLayout.LayoutParams(240, 1));
        View status = new View(activity);
        status.setId(R.id.yummy_editor_status_bar);
        status.setBackgroundColor(0xFF040506);
        root.addView(status, new LinearLayout.LayoutParams(240, 24));
        activity.setContentView(root);

        EditorUiComponentThemeController controller =
            new EditorUiComponentThemeController(activity);
        controller.apply(theme("chrome-a",
            "{\"name\":\"Chrome A\",\"type\":\"dark\",\"colors\":{"
                + "\"titleBar.activeBackground\":\"#AABBCC\"}}"));
        assertEquals(0xFFAABBCC, background(toolbar));

        controller.prepareForAppThemeChange();
        assertEquals(0xFF010203, background(toolbar));
        toolbar.setBackgroundColor(0xFF223344);
        toolbar.setBackgroundTintList(ColorStateList.valueOf(0xFF556677));
        toolbar.setTextColor(0xFF334455);
        status.setBackgroundColor(0xFF445566);
        controller.refreshBaseAndReapply();
        assertEquals(0xFFAABBCC, background(toolbar));
        assertNull("Explicit Editor UI background must not be tinted by App UI",
            toolbar.getBackgroundTintList());

        // This Editor UI theme omits titleBar.activeBackground. The App UI color must now be
        // the fallback, not the old Editor UI color or the Activity-creation color.
        controller.apply(theme("chrome-b",
            "{\"name\":\"Chrome B\",\"type\":\"dark\",\"colors\":{"
                + "\"statusBar.background\":\"#667788\"}}"));
        assertEquals(0xFF223344, background(toolbar));
        assertEquals(0xFF556677, toolbar.getBackgroundTintList().getDefaultColor());
        assertEquals(0xFF334455, toolbar.getCurrentTextColor());
        assertEquals(0xFF667788, background(status));

        controller.clear();
        assertEquals(0xFF223344, background(toolbar));
        assertEquals(0xFF556677, toolbar.getBackgroundTintList().getDefaultColor());
        assertEquals(0xFF334455, toolbar.getCurrentTextColor());
        assertEquals(0xFF445566, background(status));
        activity.finish();
    }

    @Test
    public void recreatedTabsCaptureTheirOwnBaseAndUseTheLatestTheme() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout root = new LinearLayout(activity);
        LinearLayout tabBar = new LinearLayout(activity);
        tabBar.setId(R.id.yummy_tabs_container);
        root.addView(tabBar, new LinearLayout.LayoutParams(240, 40));
        activity.setContentView(root);

        LinearLayout firstTab = createTab(activity, 0xFF010203, 0xFF111213);
        tabBar.addView(firstTab);
        EditorUiComponentThemeController controller =
            new EditorUiComponentThemeController(activity);
        controller.apply(theme("tabs-a",
            "{\"name\":\"Tabs A\",\"type\":\"dark\",\"colors\":{"
                + "\"tab.activeBackground\":\"#AABBCC\","
                + "\"tab.activeForeground\":\"#DDEEFF\"}}"));
        assertEquals(0xFFAABBCC, background(firstTab));
        assertEquals(0xFFDDEEFF, ((TextView) firstTab.getChildAt(0))
            .getCurrentTextColor());

        tabBar.removeAllViews();
        LinearLayout recreatedTab = createTab(activity, 0xFF223344, 0xFF334455);
        tabBar.addView(recreatedTab);
        controller.refreshDynamicViews();
        assertEquals(0xFFAABBCC, background(recreatedTab));
        assertEquals(0xFFDDEEFF, ((TextView) recreatedTab.getChildAt(0))
            .getCurrentTextColor());

        controller.apply(theme("tabs-b",
            "{\"name\":\"Tabs B\",\"type\":\"dark\",\"colors\":{"
                + "\"tab.activeBackground\":\"#556677\","
                + "\"tab.activeForeground\":\"#8899AA\"}}"));
        assertEquals(0xFF556677, background(recreatedTab));
        assertEquals(0xFF8899AA, ((TextView) recreatedTab.getChildAt(0))
            .getCurrentTextColor());

        controller.clear();
        assertEquals(0xFF223344, background(recreatedTab));
        assertEquals(0xFF334455, ((TextView) recreatedTab.getChildAt(0))
            .getCurrentTextColor());
        activity.finish();
    }

    @Test
    public void visiblePopupIsRethemedOnSwitchAndRestoredOnRelease() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(new LinearLayout(activity));
        EditorUiComponentThemeController controller =
            new EditorUiComponentThemeController(activity);
        controller.apply(theme("popup-a",
            "{\"name\":\"Popup A\",\"type\":\"dark\",\"colors\":{"
                + "\"editorWidget.background\":\"#AABBCC\","
                + "\"editorWidget.foreground\":\"#DDEEFF\"}}"));

        LinearLayout popup = new LinearLayout(activity);
        popup.setBackgroundColor(0xFF010203);
        TextView item = new TextView(activity);
        item.setText("UTF-8");
        item.setTextColor(0xFF111213);
        popup.addView(item);
        controller.applyPopup(popup);
        assertEquals(0xFFAABBCC, background(popup));
        assertEquals(0xFFDDEEFF, item.getCurrentTextColor());

        controller.apply(theme("popup-b",
            "{\"name\":\"Popup B\",\"type\":\"dark\",\"colors\":{"
                + "\"editorWidget.background\":\"#223344\","
                + "\"editorWidget.foreground\":\"#556677\"}}"));
        assertEquals(0xFF223344, background(popup));
        assertEquals(0xFF556677, item.getCurrentTextColor());
        assertTrue(popup.getBackground() instanceof ColorDrawable);

        controller.releasePopup(popup);
        assertEquals(0xFF010203, background(popup));
        assertEquals(0xFF111213, item.getCurrentTextColor());
        activity.finish();
    }

    private static LinearLayout createTab(Activity activity, int background, int foreground) {
        LinearLayout tab = new LinearLayout(activity);
        tab.setTag("yummy-editor-tab-active");
        tab.setBackgroundColor(background);
        TextView label = new TextView(activity);
        label.setText("document.py");
        label.setTextColor(foreground);
        tab.addView(label);
        return tab;
    }

    private static int background(View view) {
        return ((ColorDrawable) view.getBackground()).getColor();
    }

    private static LoadedTheme theme(String id, String document) throws Exception {
        ThemeMetadata metadata = new ThemeMetadata(
            id, id, ThemeAppearance.DARK, ThemeSource.IMPORTED, "theme.json",
            null, null, null, null, EnumSet.of(ThemeTarget.EDITOR_UI),
            Collections.<ThemeTarget>emptySet());
        return new LoadedTheme(metadata,
            new com.termux.app.theme.model.VSCodeColorTheme(
                new JSONObject(document), id, ThemeAppearance.DARK));
    }
}
