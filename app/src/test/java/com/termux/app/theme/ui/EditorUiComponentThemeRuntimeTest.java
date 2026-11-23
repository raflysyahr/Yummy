package com.termux.app.theme.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;
import com.termux.app.theme.model.VSCodeColorTheme;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Verifies Editor UI workbench colors reach editor-owned Android confirmation dialogs. */
@RunWith(RobolectricTestRunner.class)
public class EditorUiComponentThemeRuntimeTest {

    @Test
    public void dialogUsesWidgetSurfaceAndButtonForeground() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        EditorUiComponentThemeController controller =
            new EditorUiComponentThemeController(activity);
        controller.apply(theme(
            "{\"name\":\"Dialog Theme\",\"type\":\"dark\",\"colors\":{"
                + "\"editorWidget.background\":\"#112233\","
                + "\"editorWidget.foreground\":\"#DDEEFF\","
                + "\"button.foreground\":\"#AABBCC\"}}"
        ));

        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("Unsaved changes")
            .setMessage("Save before closing?")
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create();
        dialog.show();
        controller.applyDialog(dialog);

        assertTrue(dialog.getWindow().getDecorView().getBackground() instanceof ColorDrawable);
        assertEquals(0xFF112233, ((ColorDrawable) dialog.getWindow().getDecorView()
            .getBackground()).getColor());
        TextView message = dialog.findViewById(android.R.id.message);
        assertEquals(0xFFDDEEFF, message.getCurrentTextColor());
        assertEquals(0xFFAABBCC,
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).getCurrentTextColor());
        assertEquals(0xFFAABBCC,
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getCurrentTextColor());

        dialog.dismiss();
        activity.finish();
    }

    @Test
    public void encodingPopupUsesListHoverForegroundAndBackground() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        EditorUiComponentThemeController controller =
            new EditorUiComponentThemeController(activity);
        controller.apply(theme(
            "{\"name\":\"Popup Theme\",\"type\":\"dark\",\"colors\":{"
                + "\"editorWidget.background\":\"#101112\","
                + "\"editorWidget.foreground\":\"#202122\","
                + "\"list.hoverBackground\":\"#303132\","
                + "\"list.hoverForeground\":\"#404142\"}}"
        ));

        LinearLayout popup = new LinearLayout(activity);
        TextView item = new TextView(activity);
        item.setText("UTF-8");
        popup.addView(item);
        controller.applyPopup(popup);

        ColorStateList itemColors = item.getTextColors();
        assertEquals(0xFF202122, itemColors.getDefaultColor());
        assertEquals(0xFF404142, itemColors.getColorForState(
            new int[] { android.R.attr.state_hovered }, 0));
        assertTrue(item.getBackground() instanceof StateListDrawable);

        activity.finish();
    }

    @Test
    public void dialogAppliesPopupAndButtonBordersAndInteractiveBackgrounds() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        EditorUiComponentThemeController controller =
            new EditorUiComponentThemeController(activity);
        controller.apply(theme(
            "{\"name\":\"Border Theme\",\"type\":\"dark\",\"colors\":{"
                + "\"editorWidget.background\":\"#112233\","
                + "\"editorWidget.border\":\"#445566\","
                + "\"editorWidget.foreground\":\"#DDEEFF\","
                + "\"button.background\":\"#223344\","
                + "\"button.hoverBackground\":\"#334455\","
                + "\"button.border\":\"#556677\","
                + "\"button.foreground\":\"#AABBCC\"}}"
        ));

        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("Unsaved changes")
            .setMessage("Save before closing?")
            .setPositiveButton("Save", null)
            .create();
        dialog.show();
        controller.applyDialog(dialog);

        assertTrue(dialog.getWindow().getDecorView().getBackground() instanceof LayerDrawable);
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).getBackground()
            instanceof StateListDrawable);
        assertEquals(0xFFAABBCC,
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).getCurrentTextColor());

        dialog.dismiss();
        activity.finish();
    }

    private static LoadedTheme theme(String document) throws Exception {
        ThemeMetadata metadata = new ThemeMetadata(
            "editor-dialog-test",
            "Editor Dialog Test",
            ThemeAppearance.DARK,
            ThemeSource.IMPORTED,
            "theme.json",
            null,
            null,
            null,
            null,
            EnumSet.of(ThemeTarget.EDITOR_UI),
            EnumSet.noneOf(ThemeTarget.class)
        );
        VSCodeColorTheme colors = new VSCodeColorTheme(
            new JSONObject(document), "Editor Dialog Test", ThemeAppearance.DARK);
        return new LoadedTheme(metadata, colors);
    }
}
