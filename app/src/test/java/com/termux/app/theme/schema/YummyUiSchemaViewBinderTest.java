package com.termux.app.theme.schema;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.Resources;
import android.content.res.ColorStateList;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.view.View;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Exercises the real Android View adapters used by Color Scheme component integrations. */
@RunWith(RobolectricTestRunner.class)
public class YummyUiSchemaViewBinderTest {

    /** Test drawable that exposes the stroke value through a stable test-only accessor. */
    private static final class InspectableGradientDrawable extends GradientDrawable {
        private int recordedStrokeColor = Color.TRANSPARENT;
        private int recordedStrokeWidth;

        @Override
        public void setStroke(int width, int color) {
            super.setStroke(width, color);
            recordedStrokeWidth = width;
            recordedStrokeColor = color;
        }

        int recordedStrokeColor() { return recordedStrokeColor; }
        int recordedStrokeWidth() { return recordedStrokeWidth; }

        @Override
        public ConstantState getConstantState() {
            final int fill = getColor() == null ? Color.TRANSPARENT : getColor().getDefaultColor();
            final int stroke = recordedStrokeColor;
            final int width = recordedStrokeWidth;
            return new ConstantState() {
                @Override public Drawable newDrawable() {
                    InspectableGradientDrawable drawable = new InspectableGradientDrawable();
                    drawable.setColor(fill);
                    if (width > 0) drawable.setStroke(width, stroke);
                    return drawable;
                }
                @Override public Drawable newDrawable(Resources resources) { return newDrawable(); }
                @Override public int getChangingConfigurations() { return 0; }
            };
        }
    }

    /** Test ripple that records setColor calls without depending on hidden framework fields. */
    private static final class InspectableRippleDrawable extends RippleDrawable {
        private ColorStateList recordedRippleColor;

        InspectableRippleDrawable(ColorStateList color, Drawable content) {
            super(color, content, null);
            recordedRippleColor = color;
        }

        @Override
        public void setColor(ColorStateList color) {
            super.setColor(color);
            recordedRippleColor = color;
        }

        ColorStateList recordedRippleColor() { return recordedRippleColor; }

        @Override
        public ConstantState getConstantState() {
            final ColorStateList color = recordedRippleColor;
            final Drawable.ConstantState contentState = getDrawable(0).getConstantState();
            return new ConstantState() {
                @Override public Drawable newDrawable() {
                    Drawable content = contentState == null ? null : contentState.newDrawable();
                    return new InspectableRippleDrawable(color, content);
                }
                @Override public Drawable newDrawable(Resources resources) {
                    Drawable content = contentState == null ? null : contentState.newDrawable(resources);
                    return new InspectableRippleDrawable(color, content);
                }
                @Override public int getChangingConfigurations() { return 0; }
            };
        }
    }

    private YummyUiSchemaRuntime newRuntime() {
        return new YummyUiSchemaRuntime(
            new YummyUiSchemaResolver(),
            new YummyUiComponentRegistry(),
            task -> {
                task.run();
                return true;
            }
        );
    }

    private JSONObject schema(String... propertyColorPairs) throws Exception {
        JSONObject components = new JSONObject();
        for (int i = 0; i < propertyColorPairs.length; i += 2) {
            components.put(propertyColorPairs[i],
                YummyComponentProperty.literalDefinition(
                    YummyComponentProperty.TYPE_COLOR, propertyColorPairs[i + 1], null, null));
        }
        return new JSONObject()
            .put("schemaVersion", 1)
            .put("metadata", new JSONObject()
                .put("id", "view-binder-test")
                .put("name", "View Binder Test")
                .put("source", new JSONObject().put("kind", "imported")))
            .put("vscode", new JSONObject()
                .put("colorTheme", new JSONObject().put("colors", new JSONObject()))
                .put("configuration", new JSONObject())
                .put("extension", new JSONObject()))
            .put("yummy", new JSONObject().put("components", components));
    }

    @Test
    public void backgroundTintTracksColorSchemeAndRestoresUiSchemaValue() throws Exception {
        JSONObject defaults = new JSONObject(YummyColorSchemeDefaults.toJson());
        assertEquals("theme:input.background",
            defaults.getJSONObject("sidebar").getJSONObject("dialog")
                .getJSONObject("input").getString("background"));

        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            View view = new View(org.robolectric.RuntimeEnvironment.getApplication());
            YummyUiSchemaViewBinder binder = new YummyUiSchemaViewBinder(runtime);
            binder.bindBackgroundTint("sidebar.dialog.input.background", view);

            YummyUiSchemaRuntime.ActivationResult result =
                runtime.activate(schema("sidebar.dialog.input.background", "#112233"));
            assertTrue(result.getResolution().getDiagnostics().toString(), result.isAccepted());
            assertEquals(Color.rgb(17, 34, 51), view.getBackgroundTintList().getDefaultColor());

            Map<String, String> override = new LinkedHashMap<>();
            override.put("sidebar.dialog.input.background", "#AABBCC");
            runtime.setColorSchemeOverrides(override);
            assertEquals(Color.rgb(170, 187, 204), view.getBackgroundTintList().getDefaultColor());

            runtime.clearColorSchemeOverrides();
            assertEquals(Color.rgb(17, 34, 51), view.getBackgroundTintList().getDefaultColor());
            binder.unregisterAll();
            runtime.deactivate();
        } finally {
            runtime.dispose();
        }
    }


    @Test
    public void fileManagerOverlayAndDialogCloseButtonFollowColorScheme() throws Exception {
        JSONObject defaults = new JSONObject(YummyColorSchemeDefaults.toJson());
        JSONObject sidebar = defaults.getJSONObject("sidebar");
        assertEquals("#66000000", sidebar.getJSONObject("overlay").getString("background"));
        assertEquals("#14FFFFFF", sidebar.getJSONObject("dialog")
            .getJSONObject("closeButton").getString("background"));

        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            View scrim = new View(org.robolectric.RuntimeEnvironment.getApplication());
            View closeButton = new View(org.robolectric.RuntimeEnvironment.getApplication());
            YummyUiSchemaViewBinder binder = new YummyUiSchemaViewBinder(runtime);
            binder.bindBackgroundTint("sidebar.overlay.background", scrim);
            binder.bindBackgroundTint("sidebar.dialog.closeButton.background", closeButton);

            YummyUiSchemaRuntime.ActivationResult result = runtime.activate(schema(
                "sidebar.overlay.background", "#33010203",
                "sidebar.dialog.closeButton.background", "#445566"));
            assertTrue(result.getResolution().getDiagnostics().toString(), result.isAccepted());
            assertEquals(Color.argb(0x33, 1, 2, 3),
                scrim.getBackgroundTintList().getDefaultColor());
            assertEquals(Color.rgb(0x44, 0x55, 0x66),
                closeButton.getBackgroundTintList().getDefaultColor());

            binder.unregisterAll();
            assertEquals(0, runtime.getBindingCount());
            runtime.deactivate();
        } finally {
            runtime.dispose();
        }
    }

    @Test
    public void successiveColorSchemeRefreshesRestoreOriginallyNullBackgroundTint() throws Exception {
        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            View view = new View(org.robolectric.RuntimeEnvironment.getApplication());
            assertNull(view.getBackgroundTintList());
            YummyUiSchemaViewBinder binder = new YummyUiSchemaViewBinder(runtime);
            binder.bindBackgroundTint("sidebar.background", view);

            Map<String, String> first = new LinkedHashMap<>();
            first.put("sidebar.background", "#112233");
            runtime.setColorSchemeOverrides(first);
            assertEquals(Color.rgb(17, 34, 51), view.getBackgroundTintList().getDefaultColor());

            Map<String, String> second = new LinkedHashMap<>();
            second.put("sidebar.background", "#AABBCC");
            runtime.setColorSchemeOverrides(second);
            assertEquals(Color.rgb(170, 187, 204), view.getBackgroundTintList().getDefaultColor());

            runtime.clearColorSchemeOverrides();
            assertNull(view.getBackgroundTintList());
            binder.unregisterAll();
        } finally {
            runtime.dispose();
        }
    }

    @Test
    public void textAndHintAdaptersApplyIndependentPropertiesToSameInput() throws Exception {
        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            EditText input = new EditText(org.robolectric.RuntimeEnvironment.getApplication());
            YummyUiSchemaViewBinder binder = new YummyUiSchemaViewBinder(runtime);
            binder.bindTextColor("sidebar.dialog.input.foreground", input);
            binder.bindHintTextColor("sidebar.dialog.input.hint", input);

            YummyUiSchemaRuntime.ActivationResult result = runtime.activate(schema(
                "sidebar.dialog.input.foreground", "#223344",
                "sidebar.dialog.input.hint", "#556677"));
            assertTrue(result.getResolution().getDiagnostics().toString(), result.isAccepted());
            assertEquals(Color.rgb(34, 51, 68), input.getTextColors().getDefaultColor());
            assertEquals(Color.rgb(85, 102, 119), input.getHintTextColors().getDefaultColor());

            binder.unregisterTree(input);
            assertEquals(0, runtime.getBindingCount());
        } finally {
            runtime.dispose();
        }
    }


    @Test
    public void unregisterTreeRestoresOriginalTintBeforeViewCanBeReused() throws Exception {
        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            View view = new View(org.robolectric.RuntimeEnvironment.getApplication());
            assertNull(view.getBackgroundTintList());
            YummyUiSchemaViewBinder binder = new YummyUiSchemaViewBinder(runtime);
            binder.bindBackgroundTint("sidebar.tree.row.background", view);

            Map<String, String> selected = new LinkedHashMap<>();
            selected.put("sidebar.tree.row.background", "#112233");
            runtime.setColorSchemeOverrides(selected);
            assertEquals(Color.rgb(17, 34, 51), view.getBackgroundTintList().getDefaultColor());

            binder.unregisterTree(view);
            assertNull(view.getBackgroundTintList());
            assertEquals(0, runtime.getBindingCount());

            binder.bindBackgroundTint("sidebar.selection.background", view);
            Map<String, String> normal = new LinkedHashMap<>();
            normal.put("sidebar.selection.background", "#AABBCC");
            runtime.setColorSchemeOverrides(normal);
            assertEquals(Color.rgb(170, 187, 204), view.getBackgroundTintList().getDefaultColor());
        } finally {
            runtime.dispose();
        }
    }

    @Test
    public void unregisterAllRestoresCapturedTextColors() throws Exception {
        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            android.widget.TextView view = new android.widget.TextView(
                org.robolectric.RuntimeEnvironment.getApplication());
            int original = view.getCurrentTextColor();
            YummyUiSchemaViewBinder binder = new YummyUiSchemaViewBinder(runtime);
            binder.bindTextColor("sidebar.foreground", view);
            Map<String, String> colors = new LinkedHashMap<>();
            colors.put("sidebar.foreground", "#123456");
            runtime.setColorSchemeOverrides(colors);
            assertEquals(Color.rgb(18, 52, 86), view.getCurrentTextColor());

            binder.unregisterAll();
            assertEquals(original, view.getCurrentTextColor());
            assertEquals(0, runtime.getBindingCount());
        } finally {
            runtime.dispose();
        }
    }

    @Test
    public void progressAndSwitchAdaptersApplyTheirDedicatedTintLists() throws Exception {
        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            ProgressBar progress = new ProgressBar(org.robolectric.RuntimeEnvironment.getApplication());
            Switch toggle = new Switch(org.robolectric.RuntimeEnvironment.getApplication());
            YummyUiSchemaViewBinder binder = new YummyUiSchemaViewBinder(runtime);
            binder.bindProgressTint("sidebar.progress.foreground", progress);
            binder.bindSwitchThumbTint("settings.editor.control.accent", toggle);
            binder.bindSwitchTrackTint("settings.editor.control.track", toggle);

            YummyUiSchemaRuntime.ActivationResult result = runtime.activate(schema(
                "sidebar.progress.foreground", "#123456",
                "settings.editor.control.accent", "#234567",
                "settings.editor.control.track", "#345678"));
            assertTrue(result.getResolution().getDiagnostics().toString(), result.isAccepted());
            assertNotNull(progress.getProgressTintList());
            assertEquals(Color.rgb(18, 52, 86), progress.getProgressTintList().getDefaultColor());
            assertEquals(Color.rgb(35, 69, 103), toggle.getThumbTintList().getDefaultColor());
            assertEquals(Color.rgb(52, 86, 120), toggle.getTrackTintList().getDefaultColor());
            binder.unregisterAll();
        } finally {
            runtime.dispose();
        }
    }

    @Test
    public void drawableFillAndStrokeRefreshTogetherAndRestoreOriginalBackground() throws Exception {
        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            View view = new View(org.robolectric.RuntimeEnvironment.getApplication());
            InspectableGradientDrawable original = new InspectableGradientDrawable();
            original.setColor(Color.WHITE);
            original.setStroke(3, Color.MAGENTA);
            view.setBackground(original);

            YummyUiSchemaViewBinder binder = new YummyUiSchemaViewBinder(runtime);
            binder.bindBackgroundFill("sidebar.dialog.background", view);
            binder.bindBackgroundStroke("sidebar.dialog.border", view, 2);
            YummyUiSchemaRuntime.ActivationResult result = runtime.activate(schema(
                "sidebar.dialog.background", "#112233",
                "sidebar.dialog.border", "#445566"));
            assertTrue(result.getResolution().getDiagnostics().toString(), result.isAccepted());

            InspectableGradientDrawable applied = (InspectableGradientDrawable) view.getBackground();
            assertEquals(Color.rgb(17, 34, 51), applied.getColor().getDefaultColor());
            assertEquals(Color.rgb(68, 85, 102), applied.recordedStrokeColor());

            Map<String, String> updated = new LinkedHashMap<>();
            updated.put("sidebar.dialog.background", "#AABBCC");
            updated.put("sidebar.dialog.border", "#DDEEFF");
            runtime.setColorSchemeOverrides(updated);
            applied = (GradientDrawable) view.getBackground();
            assertEquals(Color.rgb(170, 187, 204), applied.getColor().getDefaultColor());
            assertEquals(Color.rgb(221, 238, 255), applied.recordedStrokeColor());

            runtime.clearColorSchemeOverrides();
            applied = (GradientDrawable) view.getBackground();
            assertEquals(Color.rgb(17, 34, 51), applied.getColor().getDefaultColor());
            assertEquals(Color.rgb(68, 85, 102), applied.recordedStrokeColor());

            binder.unregisterAll();
            InspectableGradientDrawable restored = (InspectableGradientDrawable) view.getBackground();
            assertEquals(Color.WHITE, restored.getColor().getDefaultColor());
            assertEquals(Color.MAGENTA, restored.recordedStrokeColor());
        } finally {
            runtime.dispose();
        }
    }

    @Test
    public void rippleColorFollowsThemeAndRestoresOriginalColor() throws Exception {
        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            View view = new View(org.robolectric.RuntimeEnvironment.getApplication());
            ColorStateList originalRipple = ColorStateList.valueOf(Color.GRAY);
            GradientDrawable content = new GradientDrawable();
            content.setColor(Color.BLACK);
            view.setBackground(new InspectableRippleDrawable(originalRipple, content));

            YummyUiSchemaViewBinder binder = new YummyUiSchemaViewBinder(runtime);
            binder.bindBackgroundRipple("sidebar.dialog.closeButton.ripple", view);
            YummyUiSchemaRuntime.ActivationResult result = runtime.activate(schema(
                "sidebar.dialog.closeButton.ripple", "#123456"));
            assertTrue(result.getResolution().getDiagnostics().toString(), result.isAccepted());
            assertEquals(Color.rgb(18, 52, 86),
                ((InspectableRippleDrawable) view.getBackground()).recordedRippleColor().getDefaultColor());

            binder.unregisterAll();
            assertEquals(Color.GRAY,
                ((InspectableRippleDrawable) view.getBackground()).recordedRippleColor().getDefaultColor());
        } finally {
            runtime.dispose();
        }
    }

    @Test
    public void compoundDrawableTintTracksThemeAndRestoresOriginalTint() throws Exception {
        YummyUiSchemaRuntime runtime = newRuntime();
        try {
            android.widget.TextView view = new android.widget.TextView(
                org.robolectric.RuntimeEnvironment.getApplication());
            ColorStateList original = ColorStateList.valueOf(Color.GRAY);
            view.setCompoundDrawableTintList(original);
            YummyUiSchemaViewBinder binder = new YummyUiSchemaViewBinder(runtime);
            binder.bindCompoundDrawableTint("sidebar.icon.foreground", view);

            YummyUiSchemaRuntime.ActivationResult result = runtime.activate(schema(
                "sidebar.icon.foreground", "#ABCDEF"));
            assertTrue(result.getResolution().getDiagnostics().toString(), result.isAccepted());
            assertEquals(Color.rgb(171, 205, 239),
                view.getCompoundDrawableTintList().getDefaultColor());

            binder.unregisterAll();
            assertEquals(Color.GRAY, view.getCompoundDrawableTintList().getDefaultColor());
        } finally {
            runtime.dispose();
        }
    }

}
