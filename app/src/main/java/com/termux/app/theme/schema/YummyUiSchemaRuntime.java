package com.termux.app.theme.schema;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the currently accepted UI Schema snapshot and applies resolved properties to explicitly
 * registered Android Views.
 *
 * <p>Candidate documents are copied, validated, and resolved before they can replace the active
 * snapshot. Invalid candidates leave the previous snapshot untouched. View callbacks are always
 * dispatched through the configured UI dispatcher; theme JSON cannot execute code or choose an
 * adapter.</p>
 *
 * <p>This class is the runtime foundation only. The active-theme loader/file observer and actual
 * Yummy component registrations are connected in later roadmap steps.</p>
 */
public final class YummyUiSchemaRuntime {
    private static final int MAX_RUNTIME_DIAGNOSTICS = 200;
    private final Object lock = new Object();
    private final YummyUiSchemaResolver resolver;
    private final YummyUiComponentRegistry registry;
    private final UiDispatcher dispatcher;
    private final List<YummyUiRuntimeDiagnostic> runtimeDiagnostics = new ArrayList<>();

    private Map<String, YummyUiSchemaResolver.DefaultValue> defaults = Collections.emptyMap();
    private JSONObject activeDocument;
    private YummyUiSchemaResolver.ResolutionResult activeResolution;
    private Listener listener;
    private long generation;
    private long candidateSequence;
    private boolean disposed;

    /** Creates a runtime that dispatches View updates to Android's main thread. */
    public YummyUiSchemaRuntime() {
        this(new YummyUiSchemaResolver(), new YummyUiComponentRegistry(), new MainThreadDispatcher());
    }

    /** Dependency-injection constructor for the app integration layer and deterministic testing. */
    public YummyUiSchemaRuntime(
        YummyUiSchemaResolver resolver,
        YummyUiComponentRegistry registry,
        UiDispatcher dispatcher
    ) {
        if (resolver == null) throw new IllegalArgumentException("Schema resolver is required");
        if (registry == null) throw new IllegalArgumentException("Component registry is required");
        if (dispatcher == null) throw new IllegalArgumentException("UI dispatcher is required");
        this.resolver = resolver;
        this.registry = registry;
        this.dispatcher = dispatcher;
    }

    /**
     * Attempts to activate a complete candidate document.
     *
     * <p>A false {@link ActivationResult#isAccepted()} means the previous active snapshot remains
     * in place. A true result means the new immutable resolution snapshot was accepted; View
     * updates are then queued on the UI dispatcher.</p>
     */
    public ActivationResult activate(JSONObject candidate) {
        if (candidate == null) throw new IllegalArgumentException("UI Schema document is required");
        JSONObject copy = copyDocument(candidate);
        Map<String, YummyUiSchemaResolver.DefaultValue> defaultSnapshot;
        final long requestSequence;
        synchronized (lock) {
            ensureNotDisposedLocked();
            requestSequence = ++candidateSequence;
            defaultSnapshot = defaults;
        }

        YummyUiSchemaResolver.ResolutionResult resolved = resolver.resolve(copy, defaultSnapshot);
        if (!resolved.isUsable()) {
            synchronized (lock) {
                return new ActivationResult(false, generation, resolved);
            }
        }

        final long acceptedGeneration;
        final List<YummyUiComponentBinding> bindings;
        synchronized (lock) {
            ensureNotDisposedLocked();
            if (requestSequence != candidateSequence) {
                return new ActivationResult(false, generation, resolved);
            }
            activeDocument = copy;
            activeResolution = resolved;
            generation++;
            acceptedGeneration = generation;
            runtimeDiagnostics.clear();
            bindings = registry.getBindingsSnapshot();
        }
        scheduleBindings(bindings, acceptedGeneration);
        return new ActivationResult(true, acceptedGeneration, resolved);
    }

    /**
     * Deactivates the current theme schema and restores every property previously applied by it.
     * Bindings remain registered so the next selected theme can be applied without rebuilding UI.
     */
    public void deactivate() {
        final List<YummyUiComponentBinding> bindings;
        final long deactivatedGeneration;
        synchronized (lock) {
            ensureNotDisposedLocked();
            candidateSequence++;
            if (activeResolution == null) return;
            activeDocument = null;
            activeResolution = null;
            generation++;
            deactivatedGeneration = generation;
            runtimeDiagnostics.clear();
            bindings = registry.getBindingsSnapshot();
        }
        scheduleBindings(bindings, deactivatedGeneration);
    }

    /**
     * Re-resolves the active document with a new code-owned defaults map. This is useful when a
     * component adapter set changes; it never modifies the imported JSON document.
     */
    public ActivationResult setBuiltInDefaults(
        Map<String, YummyUiSchemaResolver.DefaultValue> builtInDefaults
    ) {
        JSONObject document;
        synchronized (lock) {
            ensureNotDisposedLocked();
            candidateSequence++; // invalidate any candidate resolving against the previous defaults
            defaults = immutableDefaultsCopy(builtInDefaults);
            document = activeDocument == null ? null : copyDocument(activeDocument);
        }
        if (document == null) return new ActivationResult(false, getGeneration(), null);
        return activate(document);
    }

    /** Registers a View property and immediately schedules the current value, if available. */
    public YummyUiComponentBinding register(
        String propertyId,
        String expectedType,
        View target,
        YummyUiPropertyApplier applier
    ) {
        final YummyUiComponentBinding binding;
        final long currentGeneration;
        final boolean hasSnapshot;
        synchronized (lock) {
            ensureNotDisposedLocked();
            binding = registry.register(propertyId, expectedType, target, applier);
            currentGeneration = generation;
            hasSnapshot = activeResolution != null;
        }
        if (hasSnapshot) scheduleBinding(binding, currentGeneration);
        return binding;
    }

    /** Removes all schema bindings from a View, normally when its owning UI is destroyed. */
    public void unregister(View target) {
        registry.unregister(target);
    }

    /** Removes one schema property binding from a View. */
    public void unregister(String propertyId, View target) {
        registry.unregister(propertyId, target);
    }

    public void setListener(Listener listener) {
        synchronized (lock) {
            ensureNotDisposedLocked();
            this.listener = listener;
        }
    }

    public long getGeneration() {
        synchronized (lock) {
            return generation;
        }
    }

    public boolean hasActiveSchema() {
        synchronized (lock) {
            return activeResolution != null;
        }
    }

    public YummyUiSchemaResolver.ResolvedValue getResolvedValue(String propertyId) {
        synchronized (lock) {
            return activeResolution == null ? null : activeResolution.get(propertyId);
        }
    }

    public YummyUiSchemaResolver.ResolutionResult getActiveResolution() {
        synchronized (lock) {
            return activeResolution;
        }
    }

    public int getBindingCount() {
        return registry.getBindingCount();
    }

    /** Reapplies the current snapshot to all live bindings, useful after a component rebuild. */
    public void refreshBindings() {
        final List<YummyUiComponentBinding> bindings;
        final long currentGeneration;
        synchronized (lock) {
            ensureNotDisposedLocked();
            if (activeResolution == null) return;
            bindings = registry.getBindingsSnapshot();
            currentGeneration = generation;
        }
        scheduleBindings(bindings, currentGeneration);
    }

    public List<YummyUiRuntimeDiagnostic> getRuntimeDiagnostics() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(runtimeDiagnostics));
        }
    }

    public void clearRuntimeDiagnostics() {
        synchronized (lock) {
            runtimeDiagnostics.clear();
        }
    }

    /** Stops future callbacks and releases the registry's View references. */
    public void dispose() {
        synchronized (lock) {
            if (disposed) return;
            disposed = true;
            candidateSequence++;
            activeDocument = null;
            activeResolution = null;
            listener = null;
            runtimeDiagnostics.clear();
            registry.clear();
        }
    }

    private void scheduleBindings(List<YummyUiComponentBinding> bindings, long targetGeneration) {
        for (YummyUiComponentBinding binding : bindings) scheduleBinding(binding, targetGeneration);
    }

    private void scheduleBinding(YummyUiComponentBinding binding, long targetGeneration) {
        boolean queued;
        try {
            queued = dispatcher.post(new Runnable() {
                @Override
                public void run() {
                    applyBindingIfCurrent(binding, targetGeneration);
                }
            });
        } catch (RuntimeException error) {
            recordRuntimeError("dispatch_failed", binding.getPropertyId(),
                safeMessage(error), targetGeneration);
            return;
        }
        if (!queued) {
            recordRuntimeError("dispatch_rejected", binding.getPropertyId(),
                "UI dispatcher rejected the property update", targetGeneration);
        }
    }

    private void applyBindingIfCurrent(YummyUiComponentBinding binding, long targetGeneration) {
        YummyUiSchemaResolver.ResolvedValue value;
        Listener currentListener;
        synchronized (lock) {
            if (disposed || generation != targetGeneration || !registry.isRegistered(binding)) return;
            value = activeResolution == null ? null : activeResolution.get(binding.getPropertyId());
            currentListener = listener;
        }

        try {
            boolean alive;
            if (value == null) {
                boolean hadAppliedValue = binding.hasAppliedValue();
                alive = binding.reset();
                if (alive && hadAppliedValue) {
                    notifyPropertyReset(currentListener, binding.getPropertyId(), targetGeneration);
                }
            } else {
                boolean changed = binding.wouldChange(value);
                alive = binding.apply(value);
                if (alive && changed) {
                    notifyPropertyApplied(currentListener, binding.getPropertyId(), value, targetGeneration);
                }
            }
            if (!alive) registry.unregister(binding.getPropertyId(), binding.getTarget());
        } catch (Exception error) {
            recordRuntimeError("property_apply_failed", binding.getPropertyId(),
                safeMessage(error), targetGeneration);
            notifyPropertyApplyFailed(currentListener, binding.getPropertyId(),
                safeMessage(error), targetGeneration);
        }
    }


    private void notifyPropertyApplied(Listener currentListener, String propertyId,
                                        YummyUiSchemaResolver.ResolvedValue value,
                                        long targetGeneration) {
        if (currentListener == null) return;
        try {
            currentListener.onPropertyApplied(propertyId, value, targetGeneration);
        } catch (RuntimeException error) {
            recordRuntimeError("listener_callback_failed", propertyId, safeMessage(error), targetGeneration);
        }
    }

    private void notifyPropertyReset(Listener currentListener, String propertyId, long targetGeneration) {
        if (currentListener == null) return;
        try {
            currentListener.onPropertyReset(propertyId, targetGeneration);
        } catch (RuntimeException error) {
            recordRuntimeError("listener_callback_failed", propertyId, safeMessage(error), targetGeneration);
        }
    }

    private void notifyPropertyApplyFailed(Listener currentListener, String propertyId,
                                           String message, long targetGeneration) {
        if (currentListener == null) return;
        try {
            currentListener.onPropertyApplyFailed(propertyId, message, targetGeneration);
        } catch (RuntimeException error) {
            recordRuntimeError("listener_callback_failed", propertyId, safeMessage(error), targetGeneration);
        }
    }

    private void recordRuntimeError(String code, String propertyId, String message, long targetGeneration) {
        synchronized (lock) {
            if (disposed || generation != targetGeneration) return;
            if (runtimeDiagnostics.size() >= MAX_RUNTIME_DIAGNOSTICS) {
                runtimeDiagnostics.remove(0);
            }
            runtimeDiagnostics.add(new YummyUiRuntimeDiagnostic(
                code, propertyId, message, targetGeneration));
        }
    }

    private static Map<String, YummyUiSchemaResolver.DefaultValue> immutableDefaultsCopy(
        Map<String, YummyUiSchemaResolver.DefaultValue> source
    ) {
        if (source == null || source.isEmpty()) return Collections.emptyMap();
        Map<String, YummyUiSchemaResolver.DefaultValue> copy = new LinkedHashMap<>();
        for (Map.Entry<String, YummyUiSchemaResolver.DefaultValue> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getKey().trim().isEmpty() || entry.getValue() == null) {
                throw new IllegalArgumentException("Built-in default keys and values must be non-empty");
            }
            YummyUiSchemaResolver.DefaultValue value = entry.getValue();
            copy.put(entry.getKey(), YummyUiSchemaResolver.DefaultValue.of(value.getValue(), value.getUnit()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static JSONObject copyDocument(JSONObject document) {
        try {
            return new JSONObject(document.toString());
        } catch (JSONException error) {
            throw new IllegalArgumentException("Could not copy UI Schema document", error);
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName() : message;
    }

    private void ensureNotDisposedLocked() {
        if (disposed) throw new IllegalStateException("UI Schema runtime has been disposed");
    }

    /** Simple main-thread dispatcher backed by Android's Looper/Handler. */
    private static final class MainThreadDispatcher implements UiDispatcher {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public boolean post(Runnable task) {
            if (task == null) return false;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                task.run();
                return true;
            }
            return handler.post(task);
        }
    }

    /** Allows tests or specialized hosts to supply a deterministic UI task queue. */
    public interface UiDispatcher {
        boolean post(Runnable task);
    }

    public interface Listener {
        void onPropertyApplied(String propertyId,
                               YummyUiSchemaResolver.ResolvedValue value,
                               long generation);
        void onPropertyReset(String propertyId, long generation);
        void onPropertyApplyFailed(String propertyId, String message, long generation);
    }

    public static final class ActivationResult {
        private final boolean accepted;
        private final long generation;
        private final YummyUiSchemaResolver.ResolutionResult resolution;

        ActivationResult(boolean accepted, long generation,
                         YummyUiSchemaResolver.ResolutionResult resolution) {
            this.accepted = accepted;
            this.generation = generation;
            this.resolution = resolution;
        }

        public boolean isAccepted() { return accepted; }
        public long getGeneration() { return generation; }
        public YummyUiSchemaResolver.ResolutionResult getResolution() { return resolution; }
    }
}
