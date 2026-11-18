package com.termux.app.theme.schema;

import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Watches one imported theme's {@code ui-schema.json} and activates only complete, valid edits.
 *
 * <p>The observer watches the containing directory rather than the file itself because the
 * storage layer replaces schema files through temporary-file renames. Events are filtered to the
 * configured filename, debounced on a dedicated worker thread, parsed with a size limit, and
 * validated before the runtime is asked to activate the candidate.</p>
 *
 * <p>A missing, partially written, malformed, or invalid file never clears the runtime's active
 * snapshot. The runtime itself also rejects unresolved/unsafe candidates, preserving the last
 * known-good UI configuration.</p>
 *
 * <p>Call {@link #close()} when the owning theme/runtime lifecycle ends. Listener callbacks are
 * delivered on the Android main thread.</p>
 */
public final class YummyUiSchemaFileObserver implements AutoCloseable {
    public static final long DEFAULT_DEBOUNCE_MILLIS = 350L;
    public static final long MAX_SCHEMA_BYTES = 4L * 1024L * 1024L;

    private static final int WATCH_MASK = FileObserver.CLOSE_WRITE
        | FileObserver.MOVED_TO
        | FileObserver.CREATE
        | FileObserver.DELETE
        | FileObserver.MOVED_FROM
        | FileObserver.ATTRIB;
    private static final int DIRECTORY_GONE_MASK = FileObserver.DELETE_SELF | FileObserver.MOVE_SELF;
    // Linux inotify IN_Q_OVERFLOW; Android FileObserver does not expose this flag on all API levels.
    private static final int IN_Q_OVERFLOW = 0x00004000;

    private final Object lock = new Object();
    private final File requestedSchemaFile;
    private final YummyUiSchemaRuntime runtime;
    private final YummyUiSchemaValidator validator;
    private final String expectedThemeId;
    private final long debounceMillis;
    private final HandlerThread workerThread;
    private final Handler workerHandler;
    private final Handler mainHandler;

    private File schemaFile;
    private FileObserver directoryObserver;
    private Runnable pendingReload;
    private Listener listener;
    private long watchEpoch;
    private boolean watching;
    private boolean closed;

    public YummyUiSchemaFileObserver(File schemaFile, YummyUiSchemaRuntime runtime) {
        this(schemaFile, runtime, new YummyUiSchemaValidator(), DEFAULT_DEBOUNCE_MILLIS);
    }

    /** Constructor exposed for deterministic debounce configuration in integration tests. */
    public YummyUiSchemaFileObserver(
        File schemaFile,
        YummyUiSchemaRuntime runtime,
        YummyUiSchemaValidator validator,
        long debounceMillis
    ) {
        this(schemaFile, runtime, validator, debounceMillis, null);
    }

    /** Constructor that also pins edits to the ID of the selected imported theme package. */
    public YummyUiSchemaFileObserver(
        File schemaFile,
        YummyUiSchemaRuntime runtime,
        YummyUiSchemaValidator validator,
        long debounceMillis,
        String expectedThemeId
    ) {
        if (schemaFile == null) throw new IllegalArgumentException("UI Schema file is required");
        if (runtime == null) throw new IllegalArgumentException("UI Schema runtime is required");
        if (validator == null) throw new IllegalArgumentException("UI Schema validator is required");
        if (debounceMillis < 0L || debounceMillis > 5000L) {
            throw new IllegalArgumentException("Debounce must be between 0 and 5000 milliseconds");
        }
        this.requestedSchemaFile = schemaFile;
        this.runtime = runtime;
        this.validator = validator;
        this.expectedThemeId = expectedThemeId == null ? null : expectedThemeId.trim();
        if (this.expectedThemeId != null && this.expectedThemeId.isEmpty()) {
            throw new IllegalArgumentException("Expected theme ID cannot be empty");
        }
        this.debounceMillis = debounceMillis;
        this.workerThread = new HandlerThread("YummyUiSchemaObserver");
        this.workerThread.start();
        this.workerHandler = new Handler(workerThread.getLooper());
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setListener(Listener listener) {
        synchronized (lock) {
            ensureOpenLocked();
            this.listener = listener;
        }
    }

    /** Starts watching the schema's parent directory and schedules an initial load. */
    public void startWatching() throws IOException {
        final File resolvedFile = validateAndResolveSchemaPath(requestedSchemaFile);
        final File parent = resolvedFile.getParentFile();
        final long epoch;
        synchronized (lock) {
            ensureOpenLocked();
            if (watching) return;
            schemaFile = resolvedFile;
            directoryObserver = new FileObserver(parent.getAbsolutePath(), WATCH_MASK | DIRECTORY_GONE_MASK) {
                @Override
                public void onEvent(int event, String path) {
                    handleFileEvent(event, path);
                }
            };
            try {
                directoryObserver.startWatching();
            } catch (RuntimeException error) {
                directoryObserver = null;
                throw new IOException("Could not start watching the UI Schema directory", error);
            }
            watching = true;
            epoch = ++watchEpoch;
            scheduleReloadLocked(epoch, 0L);
        }
    }

    /** Stops watching and cancels a pending debounce/reload. The active runtime snapshot is kept. */
    public void stopWatching() {
        synchronized (lock) {
            if (!watching) return;
            watching = false;
            watchEpoch++;
            if (pendingReload != null) workerHandler.removeCallbacks(pendingReload);
            pendingReload = null;
            if (directoryObserver != null) directoryObserver.stopWatching();
            directoryObserver = null;
        }
    }

    /** Requests an immediate reload while watching. Returns false when the observer is stopped. */
    public boolean reloadNow() {
        synchronized (lock) {
            if (closed || !watching) return false;
            scheduleReloadLocked(watchEpoch, 0L);
            return true;
        }
    }

    public boolean isWatching() {
        synchronized (lock) {
            return watching && !closed;
        }
    }

    public File getSchemaFile() {
        synchronized (lock) {
            return schemaFile == null ? requestedSchemaFile : schemaFile;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            watching = false;
            watchEpoch++;
            if (pendingReload != null) workerHandler.removeCallbacks(pendingReload);
            pendingReload = null;
            if (directoryObserver != null) directoryObserver.stopWatching();
            directoryObserver = null;
            listener = null;
        }
        workerThread.quit();
    }

    private void handleFileEvent(int event, String path) {
        if ((event & DIRECTORY_GONE_MASK) != 0) {
            handleDirectoryLost();
            return;
        }

        if (path == null) {
            // IN_Q_OVERFLOW has no filename. Reload once to reconcile the current on-disk state.
            if ((event & IN_Q_OVERFLOW) != 0) reloadNow();
            return;
        }
        File target;
        synchronized (lock) {
            if (closed || !watching || schemaFile == null) return;
            target = schemaFile;
        }
        if (target.getName().equals(path)) {
            synchronized (lock) {
                if (closed || !watching) return;
                scheduleReloadLocked(watchEpoch, debounceMillis);
            }
        }
    }


    private void handleDirectoryLost() {
        final Listener target;
        final File lostFile;
        synchronized (lock) {
            if (closed || !watching) return;
            watching = false;
            watchEpoch++;
            if (pendingReload != null) workerHandler.removeCallbacks(pendingReload);
            pendingReload = null;
            if (directoryObserver != null) directoryObserver.stopWatching();
            directoryObserver = null;
            target = listener;
            lostFile = schemaFile == null ? requestedSchemaFile : schemaFile;
        }
        if (target == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (closed || listener != target) return;
                }
                target.onReloadFailed(lostFile, "watch_directory_lost",
                    "The UI Schema directory was moved or deleted; restore it and call startWatching() again");
            }
        });
    }

    private void scheduleReloadLocked(long epoch, long delayMillis) {
        if (pendingReload != null) workerHandler.removeCallbacks(pendingReload);
        pendingReload = new Runnable() {
            @Override
            public void run() {
                reloadAndActivate(epoch);
            }
        };
        workerHandler.postDelayed(pendingReload, delayMillis);
    }

    private void reloadAndActivate(long epoch) {
        File source;
        synchronized (lock) {
            if (closed || !watching || watchEpoch != epoch) return;
            source = schemaFile;
        }

        final JSONObject candidate;
        try {
            candidate = readDocument(source);
        } catch (IOException | JSONException error) {
            notifyFailure(epoch, "schema_read_failed", safeMessage(error));
            return;
        }

        YummyUiSchemaValidationResult validation;
        try {
            validation = validator.validate(candidate);
        } catch (RuntimeException error) {
            notifyFailure(epoch, "schema_validation_failed", safeMessage(error));
            return;
        }
        if (validation == null || !validation.isValid()) {
            notifyRejected(epoch, validation,
                validation == null ? "Schema validator returned no result" : summarizeErrors(validation));
            return;
        }
        if (expectedThemeId != null) {
            JSONObject metadata = candidate.optJSONObject("metadata");
            String actualId = metadata == null ? null : metadata.optString("id", null);
            if (!expectedThemeId.equals(actualId)) {
                notifyRejected(epoch, validation,
                    "Schema metadata.id does not match the selected theme package");
                return;
            }
        }

        final YummyUiSchemaRuntime.ActivationResult activation;
        try {
            // Serialize activation with stopWatching()/close(). Once a stop has completed, this
            // observer can no longer replace the active runtime snapshot.
            synchronized (lock) {
                if (closed || !watching || watchEpoch != epoch) return;
                activation = runtime.activate(candidate);
            }
        } catch (RuntimeException error) {
            notifyFailure(epoch, "runtime_activation_failed", safeMessage(error));
            return;
        }
        if (activation == null || !activation.isAccepted()) {
            notifyRejected(epoch, validation, "Runtime rejected the validated UI Schema candidate");
            return;
        }
        notifyAccepted(epoch, activation.getGeneration(), validation);
    }

    private void notifyAccepted(long epoch, long generation, YummyUiSchemaValidationResult validation) {
        dispatch(epoch, new Callback() {
            @Override
            public void call(Listener target) {
                target.onReloadAccepted(getSchemaFile(), generation, validation);
            }
        });
    }

    private void notifyRejected(long epoch, YummyUiSchemaValidationResult validation, String message) {
        dispatch(epoch, new Callback() {
            @Override
            public void call(Listener target) {
                target.onReloadRejected(getSchemaFile(), validation, message);
            }
        });
    }

    private void notifyFailure(long epoch, String code, String message) {
        dispatch(epoch, new Callback() {
            @Override
            public void call(Listener target) {
                target.onReloadFailed(getSchemaFile(), code, message);
            }
        });
    }

    private void dispatch(long epoch, Callback callback) {
        final Listener target;
        synchronized (lock) {
            if (closed || !watching) return;
            target = listener;
        }
        if (target == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (closed || !watching || watchEpoch != epoch || listener != target) return;
                }
                callback.call(target);
            }
        });
    }

    private static JSONObject readDocument(File source) throws IOException, JSONException {
        if (source == null || !source.isFile()) {
            throw new IOException("The UI Schema file does not currently exist");
        }
        if (source.length() <= 0L || source.length() > MAX_SCHEMA_BYTES) {
            throw new IOException("The UI Schema must contain between 1 byte and 4 MiB");
        }

        byte[] bytes;
        try (FileInputStream input = new FileInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_SCHEMA_BYTES) {
                    throw new IOException("The UI Schema exceeds the 4 MiB read limit");
                }
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }

        final String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IOException("The UI Schema is not valid UTF-8", error);
        }
        return new JSONObject(json);
    }

    private static File validateAndResolveSchemaPath(File requested) throws IOException {
        File parent = requested.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("The UI Schema parent directory does not exist");
        }
        File canonicalParent = parent.getCanonicalFile();
        File canonicalTarget = requested.getCanonicalFile();
        File expectedTarget = new File(canonicalParent, requested.getName());
        if (!canonicalTarget.equals(expectedTarget)) {
            throw new IOException("The UI Schema path is redirected outside its parent directory");
        }
        return expectedTarget;
    }

    private static String summarizeErrors(YummyUiSchemaValidationResult result) {
        StringBuilder message = new StringBuilder();
        List<YummyUiSchemaDiagnostic> errors = result.getErrors();
        for (int index = 0; index < errors.size(); index++) {
            YummyUiSchemaDiagnostic diagnostic = errors.get(index);
            if (index > 0) message.append('\n');
            message.append('[').append(diagnostic.getCode()).append("] ");
            if (diagnostic.getPropertyId() != null) {
                message.append(diagnostic.getPropertyId()).append(": ");
            }
            message.append(diagnostic.getMessage());
        }
        return message.length() == 0 ? "UI Schema validation failed" : message.toString();
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName() : message;
    }

    private void ensureOpenLocked() {
        if (closed) throw new IllegalStateException("UI Schema file observer has been closed");
    }

    private interface Callback {
        void call(Listener target);
    }

    public interface Listener {
        void onReloadAccepted(
            File schemaFile,
            long runtimeGeneration,
            YummyUiSchemaValidationResult validation
        );

        void onReloadRejected(
            File schemaFile,
            YummyUiSchemaValidationResult validation,
            String reason
        );

        void onReloadFailed(File schemaFile, String code, String message);
    }
}
