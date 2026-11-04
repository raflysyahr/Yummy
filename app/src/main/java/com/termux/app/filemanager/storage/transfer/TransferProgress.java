package com.termux.app.filemanager.storage.transfer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Immutable progress snapshot. totalBytesEstimate is an estimate from provider metadata. */
public final class TransferProgress {
    public enum Phase {
        PREPARING,
        COPYING,
        VERIFYING_SOURCE,
        DELETING_SOURCE,
        ROLLING_BACK
    }

    @NonNull private final Phase phase;
    @Nullable private final String currentName;
    private final long bytesCopied;
    private final long totalBytesEstimate;
    private final int filesCompleted;
    private final int directoriesCreated;
    private final int totalFiles;
    private final int totalDirectories;

    public TransferProgress(@NonNull Phase phase,
                            @Nullable String currentName,
                            long bytesCopied,
                            long totalBytesEstimate,
                            int filesCompleted,
                            int directoriesCreated,
                            int totalFiles,
                            int totalDirectories) {
        this.phase = phase;
        this.currentName = currentName;
        this.bytesCopied = bytesCopied;
        this.totalBytesEstimate = totalBytesEstimate;
        this.filesCompleted = filesCompleted;
        this.directoriesCreated = directoriesCreated;
        this.totalFiles = totalFiles;
        this.totalDirectories = totalDirectories;
    }

    @NonNull public Phase getPhase() { return phase; }
    @Nullable public String getCurrentName() { return currentName; }
    public long getBytesCopied() { return bytesCopied; }
    /** Returns -1 when provider metadata cannot provide a useful total. */
    public long getTotalBytesEstimate() { return totalBytesEstimate; }
    public int getFilesCompleted() { return filesCompleted; }
    public int getDirectoriesCreated() { return directoriesCreated; }
    public int getTotalFiles() { return totalFiles; }
    public int getTotalDirectories() { return totalDirectories; }
}
