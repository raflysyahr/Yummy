package com.termux.app.theme.schema;

import android.view.View;

/**
 * Explicit, code-owned adapter for applying one resolved UI Schema property to one Android View.
 *
 * <p>Theme JSON never selects a Java class, method, or arbitrary expression. The application
 * registers this adapter in code for a known property ID. Implementations should update only the
 * supplied property and must not modify terminal views or terminal preferences.</p>
 */
public interface YummyUiPropertyApplier {
    /** Applies a resolved value to the target view. Called on the configured UI dispatcher. */
    void apply(View target, YummyUiSchemaResolver.ResolvedValue value) throws Exception;

    /**
     * Restores the component's built-in value when a later valid schema no longer resolves this
     * property. The default is a no-op for adapters whose built-in value is naturally preserved.
     */
    default void reset(View target) throws Exception {
        // Optional. Components with mutable values should override this to restore their baseline.
    }
}
