package com.termux.app.theme.schema;

import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.termux.app.theme.storage.ThemeColorSchemeStorage;
import com.termux.app.theme.storage.ThemeStorageException;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/** Watches color-scheme.json in its package directory and reloads complete JSON documents. */
public final class YummyColorSchemeFileObserver implements AutoCloseable {
    private static final long DEBOUNCE_MILLIS = 350L;
    private static final int WATCH_MASK = FileObserver.CLOSE_WRITE | FileObserver.MODIFY
        | FileObserver.MOVED_TO | FileObserver.CREATE | FileObserver.DELETE
        | FileObserver.MOVED_FROM | FileObserver.ATTRIB;
    private static final int DIRECTORY_GONE_MASK = FileObserver.DELETE_SELF | FileObserver.MOVE_SELF;

    private final Object lock = new Object();
    private final File requestedFile;
    private final ThemeColorSchemeStorage storage;
    private File watchedFile;
    private final HandlerThread workerThread = new HandlerThread("YummyColorSchemeObserver");
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Handler workerHandler;
    private FileObserver directoryObserver;
    private Runnable pendingReload;
    private Listener listener;
    private boolean watching;
    private boolean closed;

    public YummyColorSchemeFileObserver(File file, ThemeColorSchemeStorage storage) {
        if (file == null) throw new IllegalArgumentException("Color Scheme file is required");
        if (storage == null) throw new IllegalArgumentException("Color Scheme storage is required");
        requestedFile = file;
        this.storage = storage;
    }

    public void setListener(Listener listener) {
        synchronized (lock) {
            ensureOpen();
            this.listener = listener;
        }
    }

    public void startWatching() throws IOException {
        final File resolved = validatePath(requestedFile);
        File parent = resolved.getParentFile();
        synchronized (lock) {
            ensureOpen();
            if (watching) return;
            watchedFile = resolved;
            workerThread.start();
            workerHandler = new Handler(workerThread.getLooper());
            directoryObserver = new FileObserver(parent.getAbsolutePath(), WATCH_MASK | DIRECTORY_GONE_MASK) {
                @Override public void onEvent(int event, String path) {
                    handleDirectoryEvent(event, path);
                }
            };
            try {
                directoryObserver.startWatching();
            } catch (RuntimeException error) {
                directoryObserver = null;
                workerThread.quit();
                throw new IOException("Could not watch Color Scheme directory", error);
            }
            watching = true;
            scheduleReloadLocked(0L);
        }
    }

    public boolean isWatching() {
        synchronized (lock) { return watching && !closed; }
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            watching = false;
            if (pendingReload != null && workerHandler != null) workerHandler.removeCallbacks(pendingReload);
            pendingReload = null;
            if (directoryObserver != null) directoryObserver.stopWatching();
            directoryObserver = null;
            listener = null;
        }
        workerThread.quitSafely();
    }

    void handleDirectoryEvent(int event, String path) {
        if ((event & DIRECTORY_GONE_MASK) != 0) {
            notifyFailure("directory_unavailable", "Color Scheme directory is unavailable");
        } else if (path == null || ThemeColorSchemeStorage.FILE_NAME.equals(path)) {
            scheduleReload();
        }
    }

    private void scheduleReload() {
        synchronized (lock) {
            if (!closed && watching) scheduleReloadLocked(DEBOUNCE_MILLIS);
        }
    }

    private void scheduleReloadLocked(long delay) {
        if (pendingReload != null) workerHandler.removeCallbacks(pendingReload);
        pendingReload = new Runnable() {
            @Override public void run() {
                try {
                    File currentFile;
                    synchronized (lock) {
                        if (closed || !watching) return;
                        currentFile = watchedFile;
                    }
                    if (currentFile == null) return;
                    JSONObject document = storage.load(currentFile.getParentFile());
                    final Listener target;
                    synchronized (lock) {
                        if (closed || !watching) return;
                        target = listener;
                    }
                    if (target != null) mainHandler.post(() -> target.onReload(document));
                } catch (ThemeStorageException error) {
                    notifyFailure(error.getCode().name(), error.getMessage());
                }
            }
        };
        workerHandler.postDelayed(pendingReload, delay);
    }

    private void notifyFailure(String code, String message) {
        final Listener target;
        synchronized (lock) {
            if (closed) return;
            target = listener;
        }
        if (target != null) mainHandler.post(() -> target.onReloadFailed(code, message));
    }

    private static File validatePath(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent == null || !parent.isDirectory()) throw new IOException("Color Scheme directory is missing");
        File canonicalParent = parent.getCanonicalFile();
        File canonicalFile = file.getCanonicalFile();
        if (!canonicalParent.equals(canonicalFile.getParentFile())
            || !ThemeColorSchemeStorage.FILE_NAME.equals(canonicalFile.getName())) {
            throw new IOException("Color Scheme path is unsafe");
        }
        return canonicalFile;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Color Scheme observer is closed");
    }

    public interface Listener {
        void onReload(JSONObject document);
        void onReloadFailed(String code, String message);
    }
}
