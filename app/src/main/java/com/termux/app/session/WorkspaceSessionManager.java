package com.termux.app.session;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persists editor documents and File Manager navigation independently from the
 * Activity lifecycle. Writes run on one background thread and use AtomicFile so
 * a process kill cannot leave a half-written session manifest.
 */
public final class WorkspaceSessionManager {

    public interface Listener {
        void onSessionSavingChanged(boolean saving, boolean failed);
    }

    private static final String EDITOR_FILE = "editor.json";
    private static final String FILE_MANAGER_FILE = "filemanager.json";

    private final File sessionDirectory;
    private final File documentsDirectory;
    private final File editorManifest;
    private final File fileManagerManifest;
    private final ExecutorService ioExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private final Object stateLock = new Object();
    private int pendingWrites;
    private boolean hadWriteFailure;
    private long savingStartedAt;
    private long writeGeneration;

    public WorkspaceSessionManager(@NonNull Context context, @Nullable Listener listener) {
        Context appContext = context.getApplicationContext();
        this.listener = listener;
        sessionDirectory = new File(appContext.getFilesDir(), "yummy-session");
        documentsDirectory = new File(sessionDirectory, "documents");
        editorManifest = new File(sessionDirectory, EDITOR_FILE);
        fileManagerManifest = new File(sessionDirectory, FILE_MANAGER_FILE);
        ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "YummyWorkspaceSession");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Returns null when no editor session has ever been saved. */
    @Nullable
    public JSONObject loadEditorSession() {
        JSONObject manifest = readJson(editorManifest);
        if (manifest == null) return null;

        JSONArray storedDocuments = manifest.optJSONArray("documents");
        JSONArray restoredDocuments = new JSONArray();
        if (storedDocuments != null) {
            for (int i = 0; i < storedDocuments.length(); i++) {
                JSONObject stored = storedDocuments.optJSONObject(i);
                if (stored == null) continue;
                try {
                    JSONObject restored = new JSONObject(stored.toString());
                    String contentFile = restored.optString("contentFile", "");
                    String text = restored.optString("text", "");
                    if (!contentFile.isEmpty()) {
                        try {
                            text = readText(new File(documentsDirectory, contentFile));
                        } catch (IOException error) {
                            // Keep the tab metadata even if its cached content cannot be read.
                            // This prevents one damaged/missing document cache from aborting
                            // restoration of every other open tab.
                            android.util.Log.e("YummySession",
                                "Could not restore cached content for document "
                                    + restored.optString("id", ""), error);
                        }
                    }
                    restored.put("text", text);
                    restoredDocuments.put(restored);
                } catch (JSONException error) {
                    android.util.Log.e("YummySession", "Invalid editor document metadata", error);
                }
            }
        }
        try {
            manifest.put("documents", restoredDocuments);
        } catch (JSONException error) {
            android.util.Log.e("YummySession", "Could not restore editor manifest", error);
            return null;
        }
        return manifest;
    }

    /** A detached JSON snapshot is captured by the caller before this async write. */
    public void saveEditorSession(@NonNull JSONObject snapshot) {
        final JSONObject detached = copy(snapshot);
        submitWrite(() -> writeEditorSession(detached));
    }

    /**
     * Enqueues a final editor checkpoint behind all earlier writes and waits until
     * it is durably committed. Use only at Activity stop/destroy, never while typing.
     * This closes the window where Android kills the process before the async writer
     * has had a chance to persist the last unsaved edits.
     */
    public boolean saveEditorSessionBlocking(@NonNull JSONObject snapshot) {
        final JSONObject detached = copy(snapshot);
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean(false);
        submitWrite(() -> {
            try {
                writeEditorSession(detached);
                success.set(true);
            } finally {
                completed.countDown();
            }
        });
        try {
            // Editor documents are bounded by the File Manager's text-file limit.
            // Wait for the serial queue so an older queued snapshot cannot overwrite
            // this final, newest snapshot after the Activity has stopped.
            if (!completed.await(15, TimeUnit.SECONDS)) {
                android.util.Log.e("YummySession", "Timed out waiting for final editor session write");
                return false;
            }
            return success.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            android.util.Log.e("YummySession", "Interrupted while committing final editor session", error);
            return false;
        }
    }

    @Nullable
    public JSONObject loadFileManagerSession() {
        return readJson(fileManagerManifest);
    }

    public void saveFileManagerSession(@NonNull JSONObject snapshot) {
        final JSONObject detached = copy(snapshot);
        submitWrite(() -> writeJsonAtomically(fileManagerManifest, detached));
    }

    private void writeEditorSession(JSONObject snapshot) throws IOException {
        if (!sessionDirectory.exists() && !sessionDirectory.mkdirs()) {
            throw new IOException("Could not create workspace session directory");
        }
        if (!documentsDirectory.exists() && !documentsDirectory.mkdirs()) {
            throw new IOException("Could not create workspace documents directory");
        }

        JSONArray documents = snapshot.optJSONArray("documents");
        JSONArray manifestDocuments = new JSONArray();
        if (documents != null) {
            for (int i = 0; i < documents.length(); i++) {
                JSONObject source = documents.optJSONObject(i);
                if (source == null) continue;

                JSONObject metadata;
                try {
                    metadata = new JSONObject(source.toString());
                } catch (JSONException error) {
                    throw new IOException("Could not copy editor document metadata", error);
                }

                String id = metadata.optString("id", "");
                if (id.isEmpty()) continue;
                String content = metadata.optString("text", "");
                String contentFile = UUID.nameUUIDFromBytes(
                    id.getBytes(StandardCharsets.UTF_8)
                ).toString() + ".txt";
                writeAtomically(new File(documentsDirectory, contentFile),
                    content.getBytes(StandardCharsets.UTF_8));

                metadata.remove("text");
                try {
                    metadata.put("contentFile", contentFile);
                } catch (JSONException error) {
                    throw new IOException("Could not write editor document metadata", error);
                }
                manifestDocuments.put(metadata);
            }
        }

        snapshot.remove("documents");
        try {
            snapshot.put("documents", manifestDocuments);
        } catch (JSONException error) {
            throw new IOException("Could not build editor session manifest", error);
        }
        writeJsonAtomically(editorManifest, snapshot);
    }

    private void submitWrite(@NonNull IoWrite write) {
        synchronized (stateLock) {
            pendingWrites++;
            if (pendingWrites == 1) {
                writeGeneration++;
                savingStartedAt = SystemClock.uptimeMillis();
                if (listener != null) {
                    mainHandler.post(() -> listener.onSessionSavingChanged(true, false));
                }
            }
        }
        try {
            ioExecutor.execute(() -> {
                boolean success = false;
                try {
                    write.run();
                    success = true;
                } catch (Exception error) {
                    android.util.Log.e("YummySession", "Workspace session save failed", error);
                } finally {
                    finishWrite(success);
                }
            });
        } catch (RuntimeException error) {
            android.util.Log.e("YummySession", "Could not schedule workspace session save", error);
            finishWrite(false);
        }
    }

    private void finishWrite(boolean success) {
        final boolean failed;
        final boolean finished;
        final long minimumVisibleDelay;
        final long completedGeneration;
        synchronized (stateLock) {
            if (!success) hadWriteFailure = true;
            pendingWrites = Math.max(0, pendingWrites - 1);
            finished = pendingWrites == 0;
            failed = finished && hadWriteFailure;
            minimumVisibleDelay = finished
                ? Math.max(0L, 350L - (SystemClock.uptimeMillis() - savingStartedAt)) : 0L;
            completedGeneration = writeGeneration;
            if (finished) hadWriteFailure = false;
        }
        if (listener == null || !finished) return;

        // Keep the green rotating state visible briefly even when a small session
        // finishes in a few milliseconds. A later write cancels this idle transition.
        mainHandler.postDelayed(() -> {
            synchronized (stateLock) {
                if (pendingWrites != 0 || completedGeneration != writeGeneration) return;
            }
            listener.onSessionSavingChanged(false, failed);
            if (failed) {
                mainHandler.postDelayed(() -> {
                    synchronized (stateLock) {
                        if (pendingWrites == 0 && completedGeneration == writeGeneration) {
                            listener.onSessionSavingChanged(false, false);
                        }
                    }
                }, 2200L);
            }
        }, minimumVisibleDelay);
    }

    private void writeJsonAtomically(File file, JSONObject value) throws IOException {
        writeAtomically(file, value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void writeAtomically(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent.getAbsolutePath());
        }
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(bytes);
            output.flush();
            atomicFile.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            if (output != null) atomicFile.failWrite(output);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Atomic session write failed", error);
        }
    }

    @Nullable
    private JSONObject readJson(File file) {
        if (!file.isFile()) return null;
        try {
            return new JSONObject(readText(file));
        } catch (IOException | JSONException error) {
            android.util.Log.e("YummySession", "Could not read " + file.getName(), error);
            return null;
        }
    }

    @NonNull
    private String readText(File file) throws IOException {
        if (!file.isFile()) return "";
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private JSONObject copy(JSONObject source) {
        try {
            return new JSONObject(source.toString());
        } catch (JSONException error) {
            android.util.Log.e("YummySession", "Could not snapshot session", error);
            return new JSONObject();
        }
    }

    private interface IoWrite {
        void run() throws IOException;
    }
}
