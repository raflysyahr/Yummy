package com.termux.app.theme.schema;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Guards Color Scheme callbacks against theme activation and teardown races. */
public class YummyUiSchemaThemeManagerLifecycleTest {

    @Test
    public void acceptsCallbackForCurrentActivation() {
        assertTrue(YummyUiSchemaThemeManager.isCurrentActivation(
            12L, 12L, "dracula", "dracula", false));
    }

    @Test
    public void rejectsQueuedCallbackFromPreviousActivationOfSameTheme() {
        // Theme ID equality alone is insufficient after deactivate/reactivate of the same package.
        assertFalse(YummyUiSchemaThemeManager.isCurrentActivation(
            12L, 13L, "dracula", "dracula", false));
    }

    @Test
    public void rejectsCallbackFromPreviouslySelectedTheme() {
        assertFalse(YummyUiSchemaThemeManager.isCurrentActivation(
            12L, 13L, "dracula", "one-dark-pro", false));
    }

    @Test
    public void rejectsCallbackAfterManagerIsClosed() {
        assertFalse(YummyUiSchemaThemeManager.isCurrentActivation(
            12L, 12L, "dracula", "dracula", true));
    }

    @Test
    public void rejectsCallbackWhenNoThemeIsActive() {
        assertFalse(YummyUiSchemaThemeManager.isCurrentActivation(
            12L, 13L, "dracula", null, false));
    }
}
