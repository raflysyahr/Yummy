package com.termux.app.theme.schema;

import android.util.Log;

import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.storage.ThemePackageStorage;
import com.termux.app.theme.storage.ThemeColorSchemeStorage;
import com.termux.app.theme.storage.ThemeStorageException;
import com.termux.app.theme.storage.ThemeUiSchemaStorage;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/**
 * Coordinates the UI Schema and Color Scheme sidecars belonging to the currently selected App UI
 * Color Theme. Imported themes load and watch both package-local JSON files. Color Scheme values
 * override matching UI Schema component properties; unresolved theme keys fall back to the
 * underlying UI Schema/default values. Editor Syntax and terminal themes are not managed here.
 */
public final class YummyUiSchemaThemeManager implements AutoCloseable {
    private static final String TAG = "YummyUiSchemaTheme";

    private final YummyUiSchemaRuntime runtime;
    private final ThemePackageStorage packageStorage;
    private final ThemeUiSchemaStorage schemaStorage;
    private final ThemeColorSchemeStorage colorSchemeStorage;
    private final YummyColorSchemeResolver colorSchemeResolver;
    private YummyUiSchemaFileObserver observer;
    private YummyColorSchemeFileObserver colorSchemeObserver;
    private LoadedTheme activeTheme;
    private String activeThemeId;
    /** Monotonically changes whenever the active theme session is replaced or stopped. */
    private long activationSequence;
    private boolean closed;

    public YummyUiSchemaThemeManager(
        YummyUiSchemaRuntime runtime, ThemePackageStorage packageStorage
    ) {
        if (runtime == null) throw new IllegalArgumentException("UI Schema runtime is required");
        if (packageStorage == null) throw new IllegalArgumentException("Theme package storage is required");
        this.runtime = runtime;
        this.packageStorage = packageStorage;
        this.schemaStorage = new ThemeUiSchemaStorage();
        this.colorSchemeStorage = new ThemeColorSchemeStorage();
        this.colorSchemeResolver = new YummyColorSchemeResolver();
    }

    /** Stops the previous package observer and restores schema-owned values to their base values. */
    public synchronized void deactivate() {
        ensureOpen();
        activationSequence++;
        closeObserver();
        closeColorSchemeObserver();
        activeThemeId = null;
        activeTheme = null;
        runtime.clearColorSchemeOverrides();
        runtime.deactivate();
    }

    /** Activates the selected theme's schema; built-in themes retain the original Yummy UI. */
    public synchronized ActivationStatus activate(LoadedTheme theme) throws ThemeStorageException {
        ensureOpen();
        if (theme == null) throw new IllegalArgumentException("Selected App UI theme is required");
        final long activationToken = ++activationSequence;
        closeObserver();
        closeColorSchemeObserver();
        activeThemeId = null;
        activeTheme = null;
        // Do not leave the previous imported theme active if the new package fails validation.
        runtime.clearColorSchemeOverrides();
        runtime.deactivate();
        String themeId = theme.getMetadata().getId();
        if (theme.getMetadata().getSource() != ThemeSource.IMPORTED) {
            activeThemeId = themeId;
            activeTheme = theme;
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
        activeTheme = theme;
        activateColorScheme(themeId, theme, activationToken);
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

    private void activateColorScheme(String themeId, LoadedTheme theme, long activationToken) {
        try {
            File colorSchemeFile = packageStorage.ensureColorSchemeFile(themeId);
            loadAndApplyColorScheme(colorSchemeFile, theme);
            colorSchemeObserver = new YummyColorSchemeFileObserver(
                colorSchemeFile, colorSchemeStorage);
            colorSchemeObserver.setListener(new YummyColorSchemeFileObserver.Listener() {
                @Override public void onReload(JSONObject document) {
                    synchronized (YummyUiSchemaThemeManager.this) {
                        if (!isCurrentActivation(activationToken, activationSequence,
                            themeId, activeThemeId, closed) || activeTheme == null) return;
                        applyColorSchemeDocument(document, theme);
                    }
                }

                @Override public void onReloadFailed(String code, String message) {
                    synchronized (YummyUiSchemaThemeManager.this) {
                        if (!isCurrentActivation(activationToken, activationSequence,
                            themeId, activeThemeId, closed)) return;
                        Log.w(TAG, "Could not reload Color Scheme for " + themeId
                            + " [" + code + "]: " + message);
                    }
                }
            });
            colorSchemeObserver.startWatching();
        } catch (ThemeStorageException | IOException | RuntimeException error) {
            closeColorSchemeObserver();
            Log.w(TAG, "Color Scheme is unavailable for " + themeId, error);
        }
    }

    private void loadAndApplyColorScheme(File file, LoadedTheme theme) {
        try {
            JSONObject document = colorSchemeStorage.load(file.getParentFile());
            applyColorSchemeDocument(document, theme);
        } catch (ThemeStorageException error) {
            Log.w(TAG, "Could not load Color Scheme for " + theme.getMetadata().getId(), error);
        }
    }

    private void applyColorSchemeDocument(JSONObject document, LoadedTheme theme) {
        YummyColorSchemeResolver.ResolutionResult result =
            colorSchemeResolver.resolve(document, theme);
        if (!result.isUsable()) {
            Log.w(TAG, "Rejected Color Scheme for " + theme.getMetadata().getId()
                + ": " + summarizeDiagnostics(result.getDiagnostics()));
            return;
        }
        runtime.setColorSchemeOverrides(result.getColors());
        Log.i(TAG, "Applied Color Scheme for " + theme.getMetadata().getId()
            + " (" + result.getColors().size() + " color properties)");
    }

    private void closeColorSchemeObserver() {
        if (colorSchemeObserver != null) {
            colorSchemeObserver.close();
            colorSchemeObserver = null;
        }
    }

    private static String summarizeDiagnostics(
        java.util.List<YummyUiSchemaDiagnostic> diagnostics
    ) {
        StringBuilder message = new StringBuilder();
        for (YummyUiSchemaDiagnostic diagnostic : diagnostics) {
            if (message.length() > 0) message.append("; ");
            message.append(diagnostic.getCode()).append(": ").append(diagnostic.getMessage());
        }
        return message.length() == 0 ? "Unknown Color Scheme error" : message.toString();
    }

    /** Returns false for queued callbacks that belong to a replaced or closed theme session. */
    static boolean isCurrentActivation(long callbackToken, long currentToken,
                                       String callbackThemeId, String currentThemeId,
                                       boolean managerClosed) {
        return !managerClosed && callbackToken == currentToken
            && callbackThemeId != null && callbackThemeId.equals(currentThemeId);
    }

    public synchronized String getActiveThemeId() {
        return activeThemeId;
    }

    public synchronized boolean isWatching() {
        return observer != null && observer.isWatching();
    }

    public synchronized boolean isColorSchemeWatching() {
        return colorSchemeObserver != null && colorSchemeObserver.isWatching();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        activationSequence++;
        closeObserver();
        closeColorSchemeObserver();
        activeThemeId = null;
        activeTheme = null;
        try {
            runtime.clearColorSchemeOverrides();
        } catch (IllegalStateException ignored) {
            // The owning Activity may already have disposed the runtime.
        }
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
