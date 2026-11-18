package com.termux.app.theme.schema;

import android.view.View;

import java.lang.ref.WeakReference;

/**
 * One explicit link between a stable schema property ID and a concrete Android View.
 *
 * <p>The binding holds the View weakly so a registry cannot keep a destroyed Activity hierarchy
 * alive. It remembers the last successfully applied value to avoid redundant View updates.</p>
 */
public final class YummyUiComponentBinding {
    private final String propertyId;
    private final String expectedType;
    private final WeakReference<View> target;
    private final YummyUiPropertyApplier applier;
    private String lastAppliedSignature;
    private boolean hasAppliedValue;

    YummyUiComponentBinding(
        String propertyId,
        String expectedType,
        View target,
        YummyUiPropertyApplier applier
    ) {
        this.propertyId = propertyId;
        this.expectedType = expectedType;
        this.target = new WeakReference<>(target);
        this.applier = applier;
    }

    public String getPropertyId() {
        return propertyId;
    }

    public String getExpectedType() {
        return expectedType;
    }

    /** Returns the target while it is still reachable, otherwise null. */
    public View getTarget() {
        return target.get();
    }

    public boolean isAlive() {
        return target.get() != null;
    }

    /** True when a schema value was successfully applied and has not yet been reset. */
    public boolean hasAppliedValue() {
        return hasAppliedValue;
    }

    /** Applies only changed values. Returns false when the target has already been collected. */
    boolean apply(YummyUiSchemaResolver.ResolvedValue value) throws Exception {
        View view = target.get();
        if (view == null) return false;
        if (value == null) throw new IllegalArgumentException("Resolved value is required");
        if (!expectedType.equals(value.getType())) {
            throw new IllegalArgumentException(
                "Property '" + propertyId + "' expects type '" + expectedType
                    + "' but resolver supplied '" + value.getType() + "'"
            );
        }

        String signature = signature(value);
        if (hasAppliedValue && signature.equals(lastAppliedSignature)) return true;
        applier.apply(view, value);
        lastAppliedSignature = signature;
        hasAppliedValue = true;
        return true;
    }

    /** Whether the supplied value differs from the last successfully applied value. */
    boolean wouldChange(YummyUiSchemaResolver.ResolvedValue value) {
        if (value == null) return hasAppliedValue;
        return !hasAppliedValue || !signature(value).equals(lastAppliedSignature);
    }

    /** Restores the component's built-in value only if this binding previously applied a value. */
    boolean reset() throws Exception {
        View view = target.get();
        if (view == null) return false;
        if (!hasAppliedValue) return true;
        applier.reset(view);
        lastAppliedSignature = null;
        hasAppliedValue = false;
        return true;
    }

    private static String signature(YummyUiSchemaResolver.ResolvedValue value) {
        Object raw = value.getValue();
        String canonical;
        if (raw == null) {
            canonical = "null";
        } else if (raw instanceof org.json.JSONObject || raw instanceof org.json.JSONArray) {
            canonical = raw.toString();
        } else {
            canonical = raw.getClass().getName() + ":" + String.valueOf(raw);
        }
        return value.getType() + "|" + String.valueOf(value.getUnit()) + "|" + canonical;
    }
}
