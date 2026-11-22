package com.termux.app.theme.schema;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.VectorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Switch;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small, code-owned adapter layer for registering real Android Views with the Yummy UI Schema
 * runtime. It deliberately supports only color properties in this integration stage.
 *
 * <p>Simple color tints and explicit GradientDrawable/RippleDrawable adapters are supported.
 * File/folder artwork is never registered as a generic image or compound-drawable tint; File
 * Icon Theme assets stay independent.</p>
 */
public final class YummyUiSchemaViewBinder {
    private final YummyUiSchemaRuntime runtime;
    private final Map<View, Map<String, YummyUiPropertyApplier>> registered = new IdentityHashMap<>();
    private final Map<View, DrawableBindingState> drawableStates = new IdentityHashMap<>();

    public YummyUiSchemaViewBinder(YummyUiSchemaRuntime runtime) {
        if (runtime == null) throw new IllegalArgumentException("UI Schema runtime is required");
        this.runtime = runtime;
    }

    public void bindBackgroundTint(String propertyId, View view) {
        if (view == null) return;
        register(propertyId, view, new ColorPropertyApplier(ColorPropertyApplier.BACKGROUND));
    }

    /** Applies a color to a GradientDrawable used as the View background. */
    public void bindBackgroundFill(String propertyId, View view) {
        if (view == null) return;
        register(propertyId, view, new DrawableColorPropertyApplier(
            drawableState(view), DrawableColorPropertyApplier.FILL, 0));
    }

    /** Applies a color to a GradientDrawable stroke; width is supplied in pixels by code. */
    public void bindBackgroundStroke(String propertyId, View view, int strokeWidthPx) {
        if (view == null) return;
        if (strokeWidthPx < 0) throw new IllegalArgumentException("Stroke width cannot be negative");
        register(propertyId, view, new DrawableColorPropertyApplier(
            drawableState(view), DrawableColorPropertyApplier.STROKE, strokeWidthPx));
    }

    /** Applies a color to the ripple layer of a RippleDrawable background. */
    public void bindBackgroundRipple(String propertyId, View view) {
        if (view == null) return;
        register(propertyId, view, new DrawableColorPropertyApplier(
            drawableState(view), DrawableColorPropertyApplier.RIPPLE, 0));
    }

    /** Only for monochrome app-owned compound icons, never file/folder artwork. */
    public void bindCompoundDrawableTint(String propertyId, TextView view) {
        if (view == null) return;
        register(propertyId, view, new ColorPropertyApplier(ColorPropertyApplier.COMPOUND));
    }

    public void bindTextColor(String propertyId, TextView view) {
        if (view == null) return;
        register(propertyId, view, new ColorPropertyApplier(ColorPropertyApplier.TEXT));
    }

    public void bindHintTextColor(String propertyId, TextView view) {
        if (view == null) return;
        register(propertyId, view, new ColorPropertyApplier(ColorPropertyApplier.HINT));
    }

    public void bindProgressTint(String propertyId, ProgressBar view) {
        if (view == null) return;
        register(propertyId, view, new ColorPropertyApplier(ColorPropertyApplier.PROGRESS));
    }

    public void bindSwitchThumbTint(String propertyId, Switch view) {
        if (view == null) return;
        register(propertyId, view, new ColorPropertyApplier(ColorPropertyApplier.SWITCH_THUMB));
    }

    public void bindSwitchTrackTint(String propertyId, Switch view) {
        if (view == null) return;
        register(propertyId, view, new ColorPropertyApplier(ColorPropertyApplier.SWITCH_TRACK));
    }

    /** Only call for monochrome UI vectors, never file/folder artwork from an icon theme. */
    public void bindVectorTint(String propertyId, ImageView view) {
        if (view == null || !(view.getDrawable() instanceof VectorDrawable)) return;
        register(propertyId, view, new ColorPropertyApplier(ColorPropertyApplier.IMAGE));
    }

    public void refresh() {
        runtime.refreshBindings();
    }

    private DrawableBindingState drawableState(View view) {
        DrawableBindingState state = drawableStates.get(view);
        if (state == null) {
            state = new DrawableBindingState(view);
            drawableStates.put(view, state);
        }
        return state;
    }

    private void register(String propertyId, View view, YummyUiPropertyApplier applier) {
        if (propertyId == null || propertyId.trim().isEmpty()) {
            throw new IllegalArgumentException("UI Schema property ID is required");
        }
        Map<String, YummyUiPropertyApplier> properties = registered.get(view);
        if (properties == null) {
            properties = new LinkedHashMap<>();
            registered.put(view, properties);
        }
        YummyUiPropertyApplier existing = properties.get(propertyId);
        if (existing != null) {
            // AppTheme may rebind/recycle the same View. Reapply the current schema value after
            // that palette operation without registering a duplicate binding.
            YummyUiSchemaResolver.ResolvedValue current = runtime.getResolvedValue(propertyId);
            if (current != null) {
                try {
                    existing.apply(view, current);
                } catch (Exception error) {
                    android.util.Log.w("YummyUiSchema", "Could not reapply " + propertyId, error);
                }
            }
            return;
        }
        properties.put(propertyId, applier);
        runtime.register(propertyId, "color", view, applier);
    }

    /** Removes one recycled/removed View and all of its child bindings. */
    public void unregisterTree(View view) {
        if (view == null) return;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                unregisterTree(group.getChildAt(i));
            }
        }
        Map<String, YummyUiPropertyApplier> properties = registered.remove(view);
        if (properties != null) {
            for (YummyUiPropertyApplier applier : properties.values()) {
                try {
                    applier.reset(view);
                } catch (Exception error) {
                    android.util.Log.w("YummyUiSchema", "Could not restore recycled View", error);
                }
            }
            runtime.unregister(view);
        }
        drawableStates.remove(view);
    }

    /** Detaches every View owned by this binder. Call when the owning dialog is destroyed. */
    public void unregisterAll() {
        for (View view : new java.util.ArrayList<>(registered.keySet())) {
            unregisterTree(view);
        }
    }

    private static final class ColorPropertyApplier implements YummyUiPropertyApplier {
        static final int BACKGROUND = 1;
        static final int TEXT = 2;
        static final int HINT = 3;
        static final int IMAGE = 4;
        static final int SWITCH_THUMB = 5;
        static final int SWITCH_TRACK = 6;
        static final int PROGRESS = 7;
        static final int COMPOUND = 8;

        private final int property;
        private ColorStateList original;
        private boolean originalCaptured;

        ColorPropertyApplier(int property) {
            this.property = property;
        }

        @Override
        public void apply(View target, YummyUiSchemaResolver.ResolvedValue value) {
            int color = parseColor(value.getValue());
            ColorStateList state = ColorStateList.valueOf(color);
            switch (property) {
                case BACKGROUND:
                    if (!originalCaptured) {
                        original = target.getBackgroundTintList();
                        originalCaptured = true;
                    }
                    target.setBackgroundTintList(state);
                    break;
                case TEXT:
                    TextView text = (TextView) target;
                    if (!originalCaptured) {
                        original = text.getTextColors();
                        originalCaptured = true;
                    }
                    text.setTextColor(state);
                    break;
                case HINT:
                    TextView input = (TextView) target;
                    if (!originalCaptured) {
                        original = input.getHintTextColors();
                        originalCaptured = true;
                    }
                    input.setHintTextColor(state);
                    break;
                case IMAGE:
                    ImageView image = (ImageView) target;
                    if (!(image.getDrawable() instanceof VectorDrawable)) return;
                    if (!originalCaptured) {
                        original = image.getImageTintList();
                        originalCaptured = true;
                    }
                    image.setImageTintList(state);
                    break;
                case SWITCH_THUMB:
                    Switch thumbSwitch = (Switch) target;
                    if (!originalCaptured) {
                        original = thumbSwitch.getThumbTintList();
                        originalCaptured = true;
                    }
                    thumbSwitch.setThumbTintList(state);
                    break;
                case SWITCH_TRACK:
                    Switch trackSwitch = (Switch) target;
                    if (!originalCaptured) {
                        original = trackSwitch.getTrackTintList();
                        originalCaptured = true;
                    }
                    trackSwitch.setTrackTintList(state);
                    break;
                case COMPOUND:
                    TextView compound = (TextView) target;
                    if (!originalCaptured) {
                        original = compound.getCompoundDrawableTintList();
                        originalCaptured = true;
                    }
                    compound.setCompoundDrawableTintList(state);
                    break;
                case PROGRESS:
                    ProgressBar progress = (ProgressBar) target;
                    if (!originalCaptured) {
                        original = progress.getProgressTintList();
                        originalCaptured = true;
                    }
                    progress.setProgressTintList(state);
                    break;
                default:
                    throw new IllegalStateException("Unsupported UI Schema color property");
            }
        }

        @Override
        public void reset(View target) {
            if (!originalCaptured) return;
            switch (property) {
                case BACKGROUND:
                    target.setBackgroundTintList(original);
                    break;
                case TEXT:
                    ((TextView) target).setTextColor(original);
                    break;
                case HINT:
                    ((TextView) target).setHintTextColor(original);
                    break;
                case IMAGE:
                    ((ImageView) target).setImageTintList(original);
                    break;
                case SWITCH_THUMB:
                    ((Switch) target).setThumbTintList(original);
                    break;
                case SWITCH_TRACK:
                    ((Switch) target).setTrackTintList(original);
                    break;
                case COMPOUND:
                    ((TextView) target).setCompoundDrawableTintList(original);
                    break;
                case PROGRESS:
                    ((ProgressBar) target).setProgressTintList(original);
                    break;
                default:
                    throw new IllegalStateException("Unsupported UI Schema color property");
            }
            original = null;
            originalCaptured = false;
        }

        static int parseColor(Object raw) {
            if (raw instanceof Number) return ((Number) raw).intValue();
            if (!(raw instanceof String)) {
                throw new IllegalArgumentException("Color value must be an ARGB integer or #RRGGBB/#AARRGGBB string");
            }
            String text = ((String) raw).trim();
            try {
                return Color.parseColor(text);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Invalid UI Schema color: " + text, error);
            }
        }
    }

    /** Shared snapshot lets fill, stroke and ripple bindings coexist on one background. */
    private static final class DrawableBindingState {
        private final View target;
        private Drawable.ConstantState originalState;
        private boolean captured;
        private Integer fillColor;
        private Integer strokeColor;
        private Integer rippleColor;
        private int strokeWidthPx;

        DrawableBindingState(View target) {
            this.target = target;
        }

        void apply(int property, int color, int widthPx) {
            captureIfNeeded();
            if (property == DrawableColorPropertyApplier.FILL) fillColor = color;
            else if (property == DrawableColorPropertyApplier.STROKE) {
                strokeColor = color;
                strokeWidthPx = widthPx;
            } else if (property == DrawableColorPropertyApplier.RIPPLE) rippleColor = color;
            render();
        }

        void reset(int property) {
            if (!captured) return;
            if (property == DrawableColorPropertyApplier.FILL) fillColor = null;
            else if (property == DrawableColorPropertyApplier.STROKE) strokeColor = null;
            else if (property == DrawableColorPropertyApplier.RIPPLE) rippleColor = null;
            render();
        }

        private void captureIfNeeded() {
            if (captured) return;
            Drawable background = target.getBackground();
            if (background == null || background.getConstantState() == null) {
                throw new IllegalStateException(
                    "Drawable color binding requires a restorable background drawable");
            }
            originalState = background.getConstantState();
            captured = true;
        }

        private void render() {
            if (originalState == null) {
                throw new IllegalStateException("Original background cannot be cloned");
            }
            Drawable rendered = originalState.newDrawable(target.getResources()).mutate();
            if (fillColor != null || strokeColor != null) {
                GradientDrawable shape = findGradientDrawable(rendered);
                if (shape == null) {
                    throw new IllegalArgumentException(
                        "Fill and stroke bindings require a GradientDrawable background");
                }
                if (fillColor != null) shape.setColor(fillColor);
                if (strokeColor != null) shape.setStroke(strokeWidthPx, strokeColor);
            }
            if (rippleColor != null) {
                if (!(rendered instanceof RippleDrawable)) {
                    throw new IllegalArgumentException(
                        "Ripple binding requires a RippleDrawable background");
                }
                ((RippleDrawable) rendered).setColor(ColorStateList.valueOf(rippleColor));
            }
            target.setBackground(rendered);
        }

        private static GradientDrawable findGradientDrawable(Drawable drawable) {
            if (drawable instanceof GradientDrawable) return (GradientDrawable) drawable;
            if (drawable instanceof RippleDrawable) {
                RippleDrawable ripple = (RippleDrawable) drawable;
                for (int i = 0; i < ripple.getNumberOfLayers(); i++) {
                    GradientDrawable found = findGradientDrawable(ripple.getDrawable(i));
                    if (found != null) return found;
                }
            } else if (drawable instanceof LayerDrawable) {
                LayerDrawable layers = (LayerDrawable) drawable;
                for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                    GradientDrawable found = findGradientDrawable(layers.getDrawable(i));
                    if (found != null) return found;
                }
            }
            return null;
        }
    }

    private static final class DrawableColorPropertyApplier implements YummyUiPropertyApplier {
        static final int FILL = 1;
        static final int STROKE = 2;
        static final int RIPPLE = 3;

        private final DrawableBindingState state;
        private final int property;
        private final int strokeWidthPx;

        DrawableColorPropertyApplier(DrawableBindingState state, int property, int strokeWidthPx) {
            this.state = state;
            this.property = property;
            this.strokeWidthPx = strokeWidthPx;
        }

        @Override
        public void apply(View target, YummyUiSchemaResolver.ResolvedValue value) {
            state.apply(property, ColorPropertyApplier.parseColor(value.getValue()), strokeWidthPx);
        }

        @Override
        public void reset(View target) {
            state.reset(property);
        }
    }

}
