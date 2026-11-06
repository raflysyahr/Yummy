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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persists workspace metadata and text-only recovery patches.
 *
 * Clean, storage-backed documents only persist their tab metadata. Dirty documents persist
 * a line patch relative to the exact source-file fingerprint captured when editing began.
 * The original file is never modified by session recovery.
 */
public final class WorkspaceSessionManager {

    public interface Listener {
        void onSessionSavingChanged(boolean saving, boolean failed);
    }

    private static final String EDITOR_FILE = "editor.json";
    private static final String FILE_MANAGER_FILE = "filemanager.json";
    private static final String RECOVERY_DIRECTORY = "recovery";
    private static final int MAX_TRACE_CELLS = 4_000_000;

    private final File sessionDirectory;
    private final File legacyDocumentsDirectory;
    private final File recoveryDirectory;
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
        legacyDocumentsDirectory = new File(sessionDirectory, "documents");
        recoveryDirectory = new File(sessionDirectory, RECOVERY_DIRECTORY);
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
                    String id = restored.optString("id", "");
                    boolean storageBacked = restored.optBoolean(
                        "storageBacked", id.contains("::")
                    );
                    restored.put("storageBacked", storageBacked);

                    String recoveryFile = restored.optString("recoveryFile", "");
                    if (!recoveryFile.isEmpty()) {
                        try {
                            JSONObject patch = readJson(new File(recoveryDirectory, recoveryFile));
                            if (patch != null) restored.put("recoveryPatch", patch);
                        } catch (RuntimeException error) {
                            android.util.Log.e("YummySession",
                                "Could not load recovery patch for " + id, error);
                        }
                    }

                    // Backward compatibility with the earlier full-document cache format.
                    // Storage-backed legacy text is not automatically replayed because the
                    // old format did not record a source fingerprint and therefore cannot
                    // prove that the underlying file is unchanged.
                    String legacyContentFile = restored.optString("contentFile", "");
                    if (!legacyContentFile.isEmpty()) {
                        try {
                            String legacyText = readText(
                                new File(legacyDocumentsDirectory, legacyContentFile)
                            );
                            if (storageBacked) {
                                if (restored.optLong("revision", 0L)
                                    != restored.optLong("savedRevision", 0L)) {
                                    restored.put("legacyRecoveryText", legacyText);
                                    restored.put("legacyRecoveryUnverified", true);
                                }
                            } else if (!restored.has("baseText")) {
                                restored.put("baseText", legacyText);
                            }
                        } catch (IOException error) {
                            android.util.Log.e("YummySession",
                                "Could not read legacy document cache for " + id, error);
                        }
                    }
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

    /** A detached JSON snapshot is captured by the caller before this asynchronous write. */
    public void saveEditorSession(@NonNull JSONObject snapshot) {
        final JSONObject detached = copy(snapshot);
        submitWrite(() -> writeEditorSession(detached));
    }

    /** Final editor checkpoint; waits behind all earlier writes. */
    public boolean saveEditorSessionBlocking(@NonNull JSONObject snapshot) {
        final JSONObject detached = copy(snapshot);
        return submitWriteBlocking(() -> writeEditorSession(detached),
            "Timed out waiting for final editor session write");
    }

    @Nullable
    public JSONObject loadFileManagerSession() {
        return readJson(fileManagerManifest);
    }

    public void saveFileManagerSession(@NonNull JSONObject snapshot) {
        final JSONObject detached = copy(snapshot);
        submitWrite(() -> writeJsonAtomically(fileManagerManifest, detached));
    }

    /** Final File Manager checkpoint so tab-to-provider references cannot lag behind editor.json. */
    public boolean saveFileManagerSessionBlocking(@NonNull JSONObject snapshot) {
        final JSONObject detached = copy(snapshot);
        return submitWriteBlocking(() -> writeJsonAtomically(fileManagerManifest, detached),
            "Timed out waiting for final File Manager session write");
    }

    private boolean submitWriteBlocking(@NonNull IoWrite write, @NonNull String timeoutMessage) {
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean(false);
        submitWrite(() -> {
            try {
                write.run();
                success.set(true);
            } finally {
                completed.countDown();
            }
        });
        try {
            if (!completed.await(15, TimeUnit.SECONDS)) {
                android.util.Log.e("YummySession", timeoutMessage);
                return false;
            }
            return success.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            android.util.Log.e("YummySession", "Interrupted while committing workspace session", error);
            return false;
        }
    }

    /**
     * Converts in-memory document text into a compact patch before writing the manifest.
     * For storage-backed documents, neither the base text nor current full text is persisted.
     */
    private void writeEditorSession(JSONObject snapshot) throws IOException {
        ensureDirectory(sessionDirectory);
        ensureDirectory(recoveryDirectory);

        JSONArray documents = snapshot.optJSONArray("documents");
        JSONArray manifestDocuments = new JSONArray();
        List<String> keepRecoveryFiles = new ArrayList<>();

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

                boolean storageBacked = metadata.optBoolean("storageBacked", id.contains("::"));
                long revision = metadata.optLong("revision", 0L);
                long savedRevision = metadata.optLong("savedRevision", revision);
                boolean dirty = revision != savedRevision;
                String currentText = metadata.optString("text", "");
                String baseText = metadata.optString("baseText", "");
                String baseSha256 = metadata.optString("baseSha256", "");

                JSONObject recoveryPatch = null;
                if (dirty && metadata.optBoolean("preserveRecoveryPatch", false)) {
                    recoveryPatch = metadata.optJSONObject("recoveryPatch");
                }
                if (dirty && recoveryPatch == null) {
                    recoveryPatch = createTextPatch(baseText, currentText);
                }

                if (dirty && recoveryPatch != null) {
                    if (baseSha256.isEmpty()) {
                        baseSha256 = sha256(baseText.getBytes(StandardCharsets.UTF_8));
                    }
                    String recoveryName = recoveryFileName(id);
                    recoveryName = recoveryName.substring(0, recoveryName.length() - 5)
                        + "_" + UUID.randomUUID() + ".json";
                    File recoveryFile = new File(recoveryDirectory, recoveryName);
                    try {
                        recoveryPatch.put("baseSha256", baseSha256);
                        recoveryPatch.put("version", 1);
                    } catch (JSONException error) {
                        throw new IOException("Could not attach patch fingerprint", error);
                    }
                    writeJsonAtomically(recoveryFile, recoveryPatch);
                    try {
                        metadata.put("recoveryFile", recoveryName);
                    } catch (JSONException error) {
                        throw new IOException("Could not reference recovery patch", error);
                    }
                    keepRecoveryFiles.add(recoveryName);
                } else {
                    metadata.remove("recoveryFile");
                }

                metadata.remove("recoveryPatch");
                metadata.remove("preserveRecoveryPatch");
                metadata.remove("legacyRecoveryText");
                metadata.remove("legacyRecoveryUnverified");
                metadata.remove("contentFile");
                metadata.remove("text");
                try {
                    metadata.put("storageBacked", storageBacked);
                    metadata.put("baseSha256", baseSha256);
                    if (storageBacked) {
                        metadata.remove("baseText");
                    } else {
                        // Non-storage-backed starter/untitled documents have no external source
                        // to reload; retain their baseline, while still storing only a patch.
                        metadata.put("baseText", baseText);
                    }
                    manifestDocuments.put(metadata);
                } catch (JSONException error) {
                    throw new IOException("Could not write document session metadata", error);
                }
            }
        }

        snapshot.remove("documents");
        try {
            snapshot.put("documents", manifestDocuments);
        } catch (JSONException error) {
            throw new IOException("Could not build editor session manifest", error);
        }

        // Commit the manifest before pruning old patch generations. If the process stops
        // before the manifest commit, its previously referenced patch files remain intact.
        writeJsonAtomically(editorManifest, snapshot);
        pruneRecoveryFiles(keepRecoveryFiles);
    }

    private void pruneRecoveryFiles(List<String> keepFiles) {
        File[] files = recoveryDirectory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && !keepFiles.contains(file.getName())) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private String recoveryFileName(String documentId) {
        return sha256(documentId.getBytes(StandardCharsets.UTF_8)) + ".json";
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

    private void ensureDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory.getAbsolutePath());
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

    /** SHA-256 helper shared with File Manager when a source file is opened or saved. */
    @NonNull
    public static String sha256(@NonNull byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] hex = new char[digest.length * 2];
            final char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                hex[i * 2] = digits[value >>> 4];
                hex[i * 2 + 1] = digits[value & 0x0f];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    /** Applies a patch only to a base whose SHA-256 has already been validated by the caller. */
    @Nullable
    public static String applyTextPatch(@NonNull String baseText, @NonNull JSONObject patch) {
        if (!"yummy-line-patch-v1".equals(patch.optString("format", ""))) return null;
        JSONArray hunks = patch.optJSONArray("hunks");
        if (hunks == null) return null;
        List<String> baseLines = splitLines(baseText);
        StringBuilder result = new StringBuilder(baseText.length());
        int cursor = 0;
        for (int i = 0; i < hunks.length(); i++) {
            JSONObject hunk = hunks.optJSONObject(i);
            if (hunk == null) return null;
            int start = hunk.optInt("start", -1);
            int deleteCount = hunk.optInt("delete", -1);
            JSONArray insert = hunk.optJSONArray("insert");
            if (start < cursor || deleteCount < 0 || start > baseLines.size()
                || start + deleteCount > baseLines.size() || insert == null) return null;
            for (int line = cursor; line < start; line++) result.append(baseLines.get(line));
            for (int line = 0; line < insert.length(); line++) {
                Object value = insert.opt(line);
                if (!(value instanceof String)) return null;
                result.append((String) value);
            }
            cursor = start + deleteCount;
        }
        for (int line = cursor; line < baseLines.size(); line++) result.append(baseLines.get(line));
        return result.toString();
    }

    private JSONObject createTextPatch(String baseText, String currentText) throws IOException {
        List<String> baseLines = splitLines(baseText);
        List<String> currentLines = splitLines(currentText);
        List<LineEdit> edits = myersDiff(baseLines, currentLines);
        JSONArray hunks = new JSONArray();
        int baseIndex = 0;
        JSONObject activeHunk = null;
        JSONArray insertedLines = null;
        int deleteCount = 0;

        for (LineEdit edit : edits) {
            if (edit.type == LineEdit.EQUAL) {
                if (activeHunk != null) {
                    finishHunk(activeHunk, insertedLines, deleteCount, hunks);
                    activeHunk = null;
                    insertedLines = null;
                    deleteCount = 0;
                }
                baseIndex++;
            } else {
                if (activeHunk == null) {
                    activeHunk = new JSONObject();
                    insertedLines = new JSONArray();
                    try { activeHunk.put("start", baseIndex); }
                    catch (JSONException error) { throw new IOException("Could not create patch hunk", error); }
                }
                if (edit.type == LineEdit.DELETE) {
                    deleteCount++;
                    baseIndex++;
                } else if (edit.type == LineEdit.INSERT) {
                    insertedLines.put(edit.line);
                }
            }
        }
        if (activeHunk != null) finishHunk(activeHunk, insertedLines, deleteCount, hunks);

        JSONObject patch = new JSONObject();
        try {
            patch.put("format", "yummy-line-patch-v1");
            patch.put("hunks", hunks);
        } catch (JSONException error) {
            throw new IOException("Could not serialize text patch", error);
        }
        return patch;
    }

    private void finishHunk(JSONObject hunk, JSONArray inserted, int deleted, JSONArray target)
        throws IOException {
        try {
            hunk.put("delete", deleted);
            hunk.put("insert", inserted);
            target.put(hunk);
        } catch (JSONException error) {
            throw new IOException("Could not finish patch hunk", error);
        }
    }

    private static final class LineEdit {
        static final int EQUAL = 0;
        static final int DELETE = 1;
        static final int INSERT = 2;
        final int type;
        final String line;
        LineEdit(int type, String line) { this.type = type; this.line = line; }
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) lines.add(text.substring(start));
        return lines;
    }

    /** Myers line diff with a bounded trace; huge/highly divergent files use one safe hunk. */
    private List<LineEdit> myersDiff(List<String> before, List<String> after) {
        int n = before.size();
        int m = after.size();
        int max = n + m;
        if (max == 0) return new ArrayList<>();
        int offset = max + 1;
        int[] v = new int[(2 * max) + 3];
        v[offset + 1] = 0;
        List<int[]> trace = new ArrayList<>();
        int endD = -1;

        for (int d = 0; d <= max; d++) {
            if ((long) (d + 1) * v.length > MAX_TRACE_CELLS) {
                return singleHunkDiff(before, after);
            }
            for (int k = -d; k <= d; k += 2) {
                int index = offset + k;
                int x;
                if (k == -d || (k != d && v[index - 1] < v[index + 1])) {
                    x = v[index + 1];
                } else {
                    x = v[index - 1] + 1;
                }
                int y = x - k;
                while (x < n && y < m && before.get(x).equals(after.get(y))) {
                    x++;
                    y++;
                }
                v[index] = x;
                if (x >= n && y >= m) {
                    endD = d;
                    break;
                }
            }
            trace.add(v.clone());
            if (endD >= 0) break;
        }
        if (endD < 0) return singleHunkDiff(before, after);

        List<LineEdit> reversed = new ArrayList<>();
        int x = n;
        int y = m;
        for (int d = endD; d > 0; d--) {
            int[] previous = trace.get(d - 1);
            int k = x - y;
            int previousK;
            if (k == -d || (k != d && previous[offset + k - 1] < previous[offset + k + 1])) {
                previousK = k + 1;
            } else {
                previousK = k - 1;
            }
            int previousX = previous[offset + previousK];
            int previousY = previousX - previousK;
            while (x > previousX && y > previousY) {
                reversed.add(new LineEdit(LineEdit.EQUAL, before.get(x - 1)));
                x--;
                y--;
            }
            if (x == previousX) {
                reversed.add(new LineEdit(LineEdit.INSERT, after.get(y - 1)));
                y--;
            } else {
                reversed.add(new LineEdit(LineEdit.DELETE, before.get(x - 1)));
                x--;
            }
        }
        while (x > 0 && y > 0) {
            reversed.add(new LineEdit(LineEdit.EQUAL, before.get(x - 1)));
            x--;
            y--;
        }
        while (x > 0) {
            reversed.add(new LineEdit(LineEdit.DELETE, before.get(x - 1)));
            x--;
        }
        while (y > 0) {
            reversed.add(new LineEdit(LineEdit.INSERT, after.get(y - 1)));
            y--;
        }
        List<LineEdit> result = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) result.add(reversed.get(i));
        return result;
    }

    private interface IoWrite {
        void run() throws IOException;
    }

    private List<LineEdit> singleHunkDiff(List<String> before, List<String> after) {
        int prefix = 0;
        while (prefix < before.size() && prefix < after.size()
            && before.get(prefix).equals(after.get(prefix))) prefix++;
        int suffix = 0;
        while (suffix < before.size() - prefix && suffix < after.size() - prefix
            && before.get(before.size() - 1 - suffix)
                .equals(after.get(after.size() - 1 - suffix))) suffix++;
        List<LineEdit> result = new ArrayList<>();
        for (int i = 0; i < prefix; i++) result.add(new LineEdit(LineEdit.EQUAL, before.get(i)));
        for (int i = prefix; i < before.size() - suffix; i++) {
            result.add(new LineEdit(LineEdit.DELETE, before.get(i)));
        }
        for (int i = prefix; i < after.size() - suffix; i++) {
            result.add(new LineEdit(LineEdit.INSERT, after.get(i)));
        }
        for (int i = suffix; i > 0; i--) {
            result.add(new LineEdit(LineEdit.EQUAL, before.get(before.size() - i)));
        }
        return result;
    }
}
