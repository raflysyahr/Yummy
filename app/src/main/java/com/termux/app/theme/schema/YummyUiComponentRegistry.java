package com.termux.app.theme.schema;

import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of code-owned bindings between schema property IDs and Android Views.
 *
 * <p>Registration is explicit. Unknown schema IDs are harmless: they remain in the source
 * document/resolution snapshot but do not acquire a View binding until a future app version adds
 * one. A View can have multiple independent property bindings.</p>
 */
public final class YummyUiComponentRegistry {
    private final Object lock = new Object();
    private final Map<String, List<YummyUiComponentBinding>> byProperty = new LinkedHashMap<>();

    /**
     * Registers or replaces the binding for this (View identity, property ID) pair.
     *
     * @param propertyId stable ID such as {@code sidebar.background}
     * @param expectedType expected UI Schema type, checked before every application
     */
    public YummyUiComponentBinding register(
        String propertyId,
        String expectedType,
        View target,
        YummyUiPropertyApplier applier
    ) {
        String id = YummyComponentSchema.requirePropertyId(propertyId);
        if (!YummyComponentProperty.isSupportedType(expectedType)) {
            throw new IllegalArgumentException("Unsupported expected property type: " + expectedType);
        }
        if (target == null) throw new IllegalArgumentException("Target View is required");
        if (applier == null) throw new IllegalArgumentException("Property applier is required");

        synchronized (lock) {
            pruneDeadBindingsLocked();
            unregisterLocked(id, target);
            YummyUiComponentBinding binding = new YummyUiComponentBinding(
                id, expectedType, target, applier);
            List<YummyUiComponentBinding> propertyBindings = byProperty.get(id);
            if (propertyBindings == null) {
                propertyBindings = new ArrayList<>();
                byProperty.put(id, propertyBindings);
            }
            propertyBindings.add(binding);
            return binding;
        }
    }

    /** Removes every property binding attached to the exact View instance. */
    public void unregister(View target) {
        if (target == null) return;
        synchronized (lock) {
            Iterator<Map.Entry<String, List<YummyUiComponentBinding>>> propertyIterator =
                byProperty.entrySet().iterator();
            while (propertyIterator.hasNext()) {
                List<YummyUiComponentBinding> bindings = propertyIterator.next().getValue();
                Iterator<YummyUiComponentBinding> bindingIterator = bindings.iterator();
                while (bindingIterator.hasNext()) {
                    if (bindingIterator.next().getTarget() == target) bindingIterator.remove();
                }
                if (bindings.isEmpty()) propertyIterator.remove();
            }
        }
    }

    /** Removes one property binding from the exact View instance. */
    public void unregister(String propertyId, View target) {
        if (propertyId == null || target == null) return;
        synchronized (lock) {
            unregisterLocked(propertyId, target);
        }
    }

    public boolean isRegistered(YummyUiComponentBinding binding) {
        if (binding == null) return false;
        if (binding.getTarget() == null) return false;
        synchronized (lock) {
            List<YummyUiComponentBinding> bindings = byProperty.get(binding.getPropertyId());
            if (bindings == null) return false;
            for (YummyUiComponentBinding current : bindings) {
                if (current == binding) return true;
            }
            return false;
        }
    }

    /** Returns a stable copy for runtime iteration; the contained bindings remain live objects. */
    public List<YummyUiComponentBinding> getBindingsSnapshot() {
        synchronized (lock) {
            pruneDeadBindingsLocked();
            List<YummyUiComponentBinding> result = new ArrayList<>();
            for (List<YummyUiComponentBinding> group : byProperty.values()) result.addAll(group);
            return Collections.unmodifiableList(result);
        }
    }

    public int getBindingCount() {
        synchronized (lock) {
            pruneDeadBindingsLocked();
            int count = 0;
            for (List<YummyUiComponentBinding> group : byProperty.values()) count += group.size();
            return count;
        }
    }

    public int getBindingCount(String propertyId) {
        if (propertyId == null) return 0;
        synchronized (lock) {
            pruneDeadBindingsLocked();
            List<YummyUiComponentBinding> bindings = byProperty.get(propertyId);
            return bindings == null ? 0 : bindings.size();
        }
    }

    /** Drops all registrations. It does not mutate Views or restore their prior appearance. */
    public void clear() {
        synchronized (lock) {
            byProperty.clear();
        }
    }

    private void unregisterLocked(String propertyId, View target) {
        List<YummyUiComponentBinding> bindings = byProperty.get(propertyId);
        if (bindings == null) return;
        Iterator<YummyUiComponentBinding> iterator = bindings.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getTarget() == target) iterator.remove();
        }
        if (bindings.isEmpty()) byProperty.remove(propertyId);
    }

    private void pruneDeadBindingsLocked() {
        Iterator<Map.Entry<String, List<YummyUiComponentBinding>>> propertyIterator =
            byProperty.entrySet().iterator();
        while (propertyIterator.hasNext()) {
            List<YummyUiComponentBinding> bindings = propertyIterator.next().getValue();
            Iterator<YummyUiComponentBinding> bindingIterator = bindings.iterator();
            while (bindingIterator.hasNext()) {
                if (!bindingIterator.next().isAlive()) bindingIterator.remove();
            }
            if (bindings.isEmpty()) propertyIterator.remove();
        }
    }
}
