package com.termux.app.filemanager.storage.transfer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.filemanager.storage.model.StorageEntry;

/** Result of a transfer. A move can report a successful copy with incomplete source cleanup. */
public final class TransferResult {
    public enum Status {
        SUCCESS,
        CANCELLED,
        /** Destination copy is complete, but source deletion was not attempted because verification failed. */
        SOURCE_RETAINED,
        /** Source deletion started but did not finish; destination copy remains complete. */
        SOURCE_DELETE_INCOMPLETE
    }

    @NonNull private final Status status;
    @Nullable private final StorageEntry destinationEntry;
    private final long bytesCopied;
    private final int filesCopied;
    private final int directoriesCopied;
    @Nullable private final String message;

    public TransferResult(@NonNull Status status,
                          @Nullable StorageEntry destinationEntry,
                          long bytesCopied,
                          int filesCopied,
                          int directoriesCopied,
                          @Nullable String message) {
        this.status = status;
        this.destinationEntry = destinationEntry;
        this.bytesCopied = bytesCopied;
        this.filesCopied = filesCopied;
        this.directoriesCopied = directoriesCopied;
        this.message = message;
    }

    @NonNull public Status getStatus() { return status; }
    @Nullable public StorageEntry getDestinationEntry() { return destinationEntry; }
    public long getBytesCopied() { return bytesCopied; }
    public int getFilesCopied() { return filesCopied; }
    public int getDirectoriesCopied() { return directoriesCopied; }
    @Nullable public String getMessage() { return message; }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
