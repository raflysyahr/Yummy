package com.termux.app.theme.ui;

import android.content.res.ColorStateList;
import android.os.Looper;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.widget.TextViewCompat;

import com.termux.app.theme.model.AppThemeColorRole;
import com.termux.app.theme.model.ResolvedAppTheme;

import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Applies semantic App UI colors to explicitly registered Android Views.
 *
 * <p>Bindings are deliberately explicit: this class never walks the view tree, changes
 * arbitrary backgrounds, or guesses a component's role. Backgrounds are tinted rather
 * than replaced, preserving existing shapes, ripples, and drawable geometry.</p>
 *
 * <p>All methods must be called on the main thread. Weak keys avoid keeping detached
 * Views alive when an owner forgets to unbind them.</p>
 */
public final class AppThemeViewRegistry {
    private final WeakHashMap<View, EnumMap<Property, Binding>> bindings = new WeakHashMap<>();
    private ResolvedAppTheme currentTheme;

    public void bindBackgroundTint(View view, AppThemeColorRole role) {
        bind(view, role, Property.BACKGROUND_TINT);
    }

    public void bindTextColor(TextView view, AppThemeColorRole role) {
        bind(view, role, Property.TEXT_COLOR);
    }

    /** Binds hint/placeholder text independently from the input's entered text. */
    public void bindHintTextColor(TextView view, AppThemeColorRole role) {
        bind(view, role, Property.HINT_TEXT_COLOR);
    }

    public void bindImageTint(ImageView view, AppThemeColorRole role) {
        bind(view, role, Property.IMAGE_TINT);
    }

    /** Tints all compound drawables on a TextView without changing its text color. */
    public void bindCompoundDrawableTint(TextView view, AppThemeColorRole role) {
        bind(view, role, Property.COMPOUND_DRAWABLE_TINT);
    }

    public void bindButtonTint(CompoundButton view, AppThemeColorRole role) {
        bind(view, role, Property.BUTTON_TINT);
    }

    public void bindProgressTint(ProgressBar view, AppThemeColorRole role) {
        bind(view, role, Property.PROGRESS_TINT);
    }

    /** Applies the palette to all live bindings. A missing role restores the original color. */
    public void apply(ResolvedAppTheme theme) {
        requireMainThread();
        currentTheme = theme;
        for (Map.Entry<View, EnumMap<Property, Binding>> entry : bindings.entrySet()) {
            View view = entry.getKey();
            EnumMap<Property, Binding> viewBindings = entry.getValue();
            if (view == null || viewBindings == null) continue;
            for (Binding binding : viewBindings.values()) {
                if (binding != null) binding.apply(view, currentTheme);
            }
        }
    }

    /** Removes one binding and restores the View's color state captured at bind time. */
    public void unbind(View view) {
        requireMainThread();
        if (view == null) return;
        EnumMap<Property, Binding> viewBindings = bindings.remove(view);
        if (viewBindings != null) {
            for (Binding binding : viewBindings.values()) {
                if (binding != null) binding.restore(view);
            }
        }
    }

    /** Restores all bound Views and forgets every binding. */
    public void clear() {
        requireMainThread();
        for (Map.Entry<View, EnumMap<Property, Binding>> entry : bindings.entrySet()) {
            View view = entry.getKey();
            EnumMap<Property, Binding> viewBindings = entry.getValue();
            if (view == null || viewBindings == null) continue;
            for (Binding binding : viewBindings.values()) {
                if (binding != null) binding.restore(view);
            }
        }
        bindings.clear();
        currentTheme = null;
    }

    public int getBindingCount() {
        requireMainThread();
        int count = 0;
        for (EnumMap<Property, Binding> viewBindings : bindings.values()) {
            if (viewBindings != null) count += viewBindings.size();
        }
        return count;
    }

    private void bind(View view, AppThemeColorRole role, Property property) {
        requireMainThread();
        if (view == null) throw new IllegalArgumentException("View is required");
        if (role == null) throw new IllegalArgumentException("Theme color role is required");

        EnumMap<Property, Binding> viewBindings = bindings.get(view);
        if (viewBindings == null) {
            viewBindings = new EnumMap<>(Property.class);
            bindings.put(view, viewBindings);
        }

        Binding previous = viewBindings.remove(property);
        if (previous != null) previous.restore(view);

        Binding binding = new Binding(role, property, readOriginal(view, property));
        viewBindings.put(property, binding);
        binding.apply(view, currentTheme);
    }

    private static ColorStateList readOriginal(View view, Property property) {
        switch (property) {
            case BACKGROUND_TINT:
                return view.getBackgroundTintList();
            case TEXT_COLOR:
                return ((TextView) view).getTextColors();
            case HINT_TEXT_COLOR:
                return ((TextView) view).getHintTextColors();
            case IMAGE_TINT:
                return ((ImageView) view).getImageTintList();
            case COMPOUND_DRAWABLE_TINT:
                return TextViewCompat.getCompoundDrawableTintList((TextView) view);
            case BUTTON_TINT:
                return ((CompoundButton) view).getButtonTintList();
            case PROGRESS_TINT:
                return ((ProgressBar) view).getProgressTintList();
            default:
                throw new IllegalStateException("Unsupported theme binding property: " + property);
        }
    }

    private static void writeColor(View view, Property property, ColorStateList color) {
        switch (property) {
            case BACKGROUND_TINT:
                view.setBackgroundTintList(color);
                break;
            case TEXT_COLOR:
                ((TextView) view).setTextColor(color);
                break;
            case HINT_TEXT_COLOR:
                ((TextView) view).setHintTextColor(color);
                break;
            case IMAGE_TINT:
                ((ImageView) view).setImageTintList(color);
                break;
            case COMPOUND_DRAWABLE_TINT:
                TextViewCompat.setCompoundDrawableTintList((TextView) view, color);
                break;
            case BUTTON_TINT:
                ((CompoundButton) view).setButtonTintList(color);
                break;
            case PROGRESS_TINT:
                ((ProgressBar) view).setProgressTintList(color);
                break;
            default:
                throw new IllegalStateException("Unsupported theme binding property: " + property);
        }
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("App theme View bindings must be used on the main thread");
        }
    }

    private enum Property {
        BACKGROUND_TINT,
        TEXT_COLOR,
        HINT_TEXT_COLOR,
        IMAGE_TINT,
        COMPOUND_DRAWABLE_TINT,
        BUTTON_TINT,
        PROGRESS_TINT
    }

    /** Does not retain a reference to its View, allowing the registry's weak key to work. */
    private static final class Binding {
        final AppThemeColorRole role;
        final Property property;
        final ColorStateList originalColor;

        Binding(AppThemeColorRole role, Property property, ColorStateList originalColor) {
            this.role = role;
            this.property = property;
            this.originalColor = originalColor;
        }

        void apply(View view, ResolvedAppTheme theme) {
            Integer color = theme == null ? null : theme.getColor(role);
            writeColor(view, property,
                color == null ? originalColor : ColorStateList.valueOf(color));
        }

        void restore(View view) {
            writeColor(view, property, originalColor);
        }
    }
}
