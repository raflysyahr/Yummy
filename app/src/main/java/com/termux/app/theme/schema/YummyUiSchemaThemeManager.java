package com.termux.app.theme.schema;

import android.util.Log;

import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.storage.ThemePackageStorage;
import com.termux.app.theme.storage.ThemeStorageException;
import com.termux.app.theme.storage.ThemeUiSchemaStorage;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/**
 * Coordinates the UI Schema sidecar belonging to the currently selected App UI Color Theme.
 * Built-in themes deliberately use the existing Yummy UI defaults; imported themes load their
 * package-local ui-schema.json and watch it for atomic edits. Editor Syntax and terminal themes
 * are not managed here.
 */
public final class YummyUiSchemaThemeManager implements AutoCloseable {
    private static final String TAG = "YummyUiSchemaTheme";

    private final YummyUiSchemaRuntime runtime;
    private final ThemePackageStorage packageStorage;
    private final ThemeUiSchemaStorage schemaStorage;
    private YummyUiSchemaFileObserver observer;
    private String activeThemeId;
    private boolean closed;

    public YummyUiSchemaThemeManager(
        YummyUiSchemaRuntime runtime, ThemePackageStorage packageStorage
    ) {
        if (runtime == null) throw new IllegalArgumentException("UI Schema runtime is required");
        if (packageStorage == null) throw new IllegalArgumentException("Theme package storage is required");
        this.runtime = runtime;
        this.packageStorage = packageStorage;
        this.schemaStorage = new ThemeUiSchemaStorage();
    }

    /** Stops the previous package observer and restores schema-owned values to their base values. */
    public synchronized void deactivate() {
        ensureOpen();
        closeObserver();
        activeThemeId = null;
        runtime.deactivate();
    }

    /** Activates the selected theme's schema; built-in themes retain the original Yummy UI. */
    public synchronized ActivationStatus activate(LoadedTheme theme) throws ThemeStorageException {
        ensureOpen();
        if (theme == null) throw new IllegalArgumentException("Selected App UI theme is required");
        closeObserver();
        String themeId = theme.getMetadata().getId();
        if (theme.getMetadata().getSource() != ThemeSource.IMPORTED) {
            runtime.deactivate();
            activeThemeId = themeId;
            return new ActivationStatus(themeId, false, false);
        }

        File schemaFile = packageStorage.ensureUiSchemaFile(themeId);
        JSONObject document = schemaStorage.load(schemaFile.getParentFile(), theme);
        YummyUiSchemaValidationResult validation = new YummyUiSchemaValidator().validate(document);
        if (!validation.isValid()) {
            throw new ThemeStorageException(ThemeStorageException.Code.INVALID_PACKAGE,
                "Selected theme UI Schema failed validation: " + summarize(validation));
        }
        YummyUiSchemaRuntime.ActivationResult result = runtime.activate(document);
        if (result == null || !result.isAccepted()) {
            throw new ThemeStorageException(ThemeStorageException.Code.INVALID_PACKAGE,
                "Selected theme UI Schema could not be activated");
        }

        activeThemeId = themeId;
        observer = new YummyUiSchemaFileObserver(schemaFile, runtime,
            new YummyUiSchemaValidator(), YummyUiSchemaFileObserver.DEFAULT_DEBOUNCE_MILLIS, themeId);
        observer.setListener(new YummyUiSchemaFileObserver.Listener() {
            @Override
            public void onReloadAccepted(File file, long generation, YummyUiSchemaValidationResult result) {
                Log.i(TAG, "Activated edited UI Schema for " + themeId + " (generation " + generation + ")");
            }

            @Override
            public void onReloadRejected(File file, YummyUiSchemaValidationResult result, String reason) {
                Log.w(TAG, "Rejected UI Schema edit for " + themeId + ": " + reason);
            }

            @Override
            public void onReloadFailed(File file, String code, String message) {
                Log.w(TAG, "Could not reload UI Schema for " + themeId + " [" + code + "]: " + message);
            }
        });
        try {
            observer.startWatching();
        } catch (IOException | RuntimeException error) {
            closeObserver();
            Log.w(TAG, "UI Schema activated, but live file watching could not start for " + themeId, error);
            return new ActivationStatus(themeId, true, false);
        }
        return new ActivationStatus(themeId, true, true);
    }

    public synchronized String getActiveThemeId() {
        return activeThemeId;
    }

    public synchronized boolean isWatching() {
        return observer != null && observer.isWatching();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        closeObserver();
        activeThemeId = null;
    }

    private void closeObserver() {
        if (observer != null) {
            observer.close();
            observer = null;
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("UI Schema theme manager has been closed");
    }

    private static String summarize(YummyUiSchemaValidationResult result) {
        StringBuilder message = new StringBuilder();
        for (YummyUiSchemaDiagnostic diagnostic : result.getErrors()) {
            if (message.length() > 0) message.append("; ");
            message.append(diagnostic.getCode()).append(": ").append(diagnostic.getMessage());
        }
        return message.length() == 0 ? "Unknown validation error" : message.toString();
    }

    public static final class ActivationStatus {
        private final String themeId;
        private final boolean schemaActive;
        private final boolean watching;

        ActivationStatus(String themeId, boolean schemaActive, boolean watching) {
            this.themeId = themeId;
            this.schemaActive = schemaActive;
            this.watching = watching;
        }

        public String getThemeId() { return themeId; }
        public boolean isSchemaActive() { return schemaActive; }
        public boolean isWatching() { return watching; }
    }
}
