package com.termux.app.theme.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.ColorDrawable;
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
