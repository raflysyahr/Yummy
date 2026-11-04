package com.termux.app.filemanager.storage.operations;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.filemanager.storage.model.StorageEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Result of a single or multi-entry delete operation. */
public final class FileOperationResult {
    public enum Status {
        SUCCESS,
        PARTIAL_FAILURE,
        FAILED,
        CANCELLED
    }

    public static final class Failure {
        @NonNull private final String providerId;
        @NonNull private final String entryName;
        @NonNull private final String message;

        public Failure(@NonNull String providerId,
                       @NonNull String entryName,
                       @NonNull String message) {
            this.providerId = providerId;
            this.entryName = entryName;
            this.message = message;
        }

        @NonNull public String getProviderId() { return providerId; }
        @NonNull public String getEntryName() { return entryName; }
        @NonNull public String getMessage() { return message; }
    }

    @NonNull private final Status status;
    private final int deletedEntries;
    private final int skippedEntries;
    private final int plannedEntries;
    @NonNull private final List<Failure> failures;
    @Nullable private final StorageEntry lastDeletedEntry;

    public FileOperationResult(@NonNull Status status,
                               int deletedEntries,
                               int skippedEntries,
                               int plannedEntries,
                               @NonNull List<Failure> failures,
                               @Nullable StorageEntry lastDeletedEntry) {
        this.status = status;
        this.deletedEntries = deletedEntries;
        this.skippedEntries = skippedEntries;
        this.plannedEntries = plannedEntries;
        this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
        this.lastDeletedEntry = lastDeletedEntry;
    }

    @NonNull public Status getStatus() { return status; }
    public int getDeletedEntries() { return deletedEntries; }
    public int getSkippedEntries() { return skippedEntries; }
    public int getPlannedEntries() { return plannedEntries; }
    @NonNull public List<Failure> getFailures() { return failures; }
    @Nullable public StorageEntry getLastDeletedEntry() { return lastDeletedEntry; }
    public boolean isSuccess() { return status == Status.SUCCESS; }
}
