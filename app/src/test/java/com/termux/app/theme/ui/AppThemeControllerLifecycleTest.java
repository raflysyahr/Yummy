package com.termux.app.theme.ui;

import android.app.Activity;

import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ResolvedAppTheme;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Ensures App UI consumers can remove Editor UI overlays before palette mutations. */
@RunWith(RobolectricTestRunner.class)
public class AppThemeControllerLifecycleTest {

    @Test
    public void paletteWillChangeAlwaysPrecedesAppliedOrClearedPalette() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AppThemeController controller = new AppThemeController(activity);
        List<String> events = new ArrayList<>();
        controller.setPaletteListener(new AppThemeController.PaletteListener() {
            @Override
            public void onPaletteWillChange() {
                events.add("before");
            }

            @Override
            public void onPaletteChanged(ResolvedAppTheme theme) {
                events.add(theme == null ? "after:null" : "after:" + theme.getThemeId());
            }
        });

        controller.clearTheme();
        controller.applyTheme(appTheme());

        assertEquals(java.util.Arrays.asList(
            "after:null", "before", "after:null", "before", "after:app-theme"), events);
        controller.release();
        activity.finish();
    }

    private static LoadedTheme appTheme() throws Exception {
        ThemeMetadata metadata = new ThemeMetadata(
            "app-theme", "App Theme", ThemeAppearance.DARK, ThemeSource.IMPORTED,
            "theme.json", null, null, null, null,
            EnumSet.of(ThemeTarget.APP_UI), Collections.<ThemeTarget>emptySet());
        return new LoadedTheme(metadata,
            new VSCodeColorTheme(new JSONObject(
                "{\"name\":\"App Theme\",\"type\":\"dark\",\"colors\":{}}"),
                "App Theme", ThemeAppearance.DARK));
    }
}
