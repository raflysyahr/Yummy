package com.termux.app.theme.schema;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small, code-owned adapter layer for registering real Android Views with the Yummy UI Schema
 * runtime. It deliberately supports only color properties in this integration stage.
 *
 * <p>Backgrounds are applied as tints so shape/ripple drawables remain intact. File/folder
 * artwork is not registered as a generic image tint; File Icon Theme assets stay independent.</p>
 */
public final class YummyUiSchemaViewBinder {
    private final YummyUiSchemaRuntime runtime;
    private final Map<View, Map<String, YummyUiPropertyApplier>> registered = new IdentityHashMap<>();

    public YummyUiSchemaViewBinder(YummyUiSchemaRuntime runtime) {
        if (runtime == null) throw new IllegalArgumentException("UI Schema runtime is required");
        this.runtime = runtime;
    }

    public void bindBackgroundTint(String propertyId, View view) {
        if (view == null) return;
        register(propertyId, view, new ColorPropertyApplier(ColorPropertyApplier.BACKGROUND));
    }

    public void bindTextColor(String propertyId, TextView view) {
        if (view == null) return;
        register(propertyId, view, new ColorPropertyApplier(ColorPropertyApplier.TEXT));
    }

    public void bindHintTextColor(String propertyId, TextView view) {
        if (view == null) return;
        register(propertyId, view, new ColorPropertyApplier(ColorPropertyApplier.HINT));
    }

    /** Only call for monochrome UI vectors, never file/folder artwork from an icon theme. */
    public void bindVectorTint(String propertyId, ImageView view) {
        if (view == null || !(view.getDrawable() instanceof VectorDrawable)) return;
        register(propertyId, view, new ColorPropertyApplier(ColorPropertyApplier.IMAGE));
    }

    public void refresh() {
        runtime.refreshBindings();
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
        if (registered.remove(view) != null) runtime.unregister(view);
    }

    /** Detaches every View owned by this binder. Call when the owning dialog is destroyed. */
    public void unregisterAll() {
        for (View view : registered.keySet()) {
            runtime.unregister(view);
        }
        registered.clear();
    }

    private static final class ColorPropertyApplier implements YummyUiPropertyApplier {
        static final int BACKGROUND = 1;
        static final int TEXT = 2;
        static final int HINT = 3;
        static final int IMAGE = 4;

        private final int property;
        private ColorStateList original;

        ColorPropertyApplier(int property) {
            this.property = property;
        }

        @Override
        public void apply(View target, YummyUiSchemaResolver.ResolvedValue value) {
            int color = parseColor(value.getValue());
            ColorStateList state = ColorStateList.valueOf(color);
            switch (property) {
                case BACKGROUND:
                    if (original == null) original = target.getBackgroundTintList();
                    target.setBackgroundTintList(state);
                    break;
                case TEXT:
                    TextView text = (TextView) target;
                    if (original == null) original = text.getTextColors();
                    text.setTextColor(state);
                    break;
                case HINT:
                    TextView input = (TextView) target;
                    if (original == null) original = input.getHintTextColors();
                    input.setHintTextColor(state);
                    break;
                case IMAGE:
                    ImageView image = (ImageView) target;
                    if (!(image.getDrawable() instanceof VectorDrawable)) return;
                    if (original == null) original = image.getImageTintList();
                    image.setImageTintList(state);
                    break;
                default:
                    throw new IllegalStateException("Unsupported UI Schema color property");
            }
        }

        @Override
        public void reset(View target) {
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
                default:
                    throw new IllegalStateException("Unsupported UI Schema color property");
            }
            original = null;
        }

        private static int parseColor(Object raw) {
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
}
