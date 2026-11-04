package com.termux.app.filemanager.storage.operations;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Immutable progress snapshot emitted by FileOperationService on its worker thread. */
public final class FileOperationProgress {
    public enum Phase {
        PREPARING,
        DELETING
    }

    @NonNull private final Phase phase;
    @Nullable private final String currentName;
    private final int completedEntries;
    private final int totalEntries;

    public FileOperationProgress(@NonNull Phase phase,
                                  @Nullable String currentName,
                                  int completedEntries,
                                  int totalEntries) {
        this.phase = phase;
        this.currentName = currentName;
        this.completedEntries = completedEntries;
        this.totalEntries = totalEntries;
    }

    @NonNull public Phase getPhase() { return phase; }
    @Nullable public String getCurrentName() { return currentName; }
    public int getCompletedEntries() { return completedEntries; }
    public int getTotalEntries() { return totalEntries; }
}
