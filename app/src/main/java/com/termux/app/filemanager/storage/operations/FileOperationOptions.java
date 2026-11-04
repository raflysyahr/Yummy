package com.termux.app.filemanager.storage.operations;

import androidx.annotation.NonNull;

/** Immutable options for delete and bulk-delete operations. */
public final class FileOperationOptions {
    public interface CancellationToken {
        CancellationToken NONE = () -> false;
        boolean isCancellationRequested();
    }

    public interface ProgressListener {
        void onProgress(@NonNull FileOperationProgress progress);
    }

    private final boolean recursiveDelete;
    private final int maxEntries;
    private final CancellationToken cancellationToken;
    private final ProgressListener progressListener;

    private FileOperationOptions(Builder builder) {
        recursiveDelete = builder.recursiveDelete;
        maxEntries = builder.maxEntries;
        cancellationToken = builder.cancellationToken;
        progressListener = builder.progressListener;
    }

    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    public boolean isRecursiveDelete() { return recursiveDelete; }
    public int getMaxEntries() { return maxEntries; }
    @NonNull public CancellationToken getCancellationToken() { return cancellationToken; }
    public ProgressListener getProgressListener() { return progressListener; }

    public static final class Builder {
        private boolean recursiveDelete = true;
        private int maxEntries = 100_000;
        private CancellationToken cancellationToken = CancellationToken.NONE;
        private ProgressListener progressListener;

        private Builder() {
        }

        /** When false, deleting a non-empty directory fails during preflight. */
        @NonNull
        public Builder recursiveDelete(boolean value) {
            recursiveDelete = value;
            return this;
        }

        /** Safety limit across the complete selected operation. */
        @NonNull
        public Builder maxEntries(int value) {
            if (value < 1 || value > 1_000_000) {
                throw new IllegalArgumentException("maxEntries must be between 1 and 1,000,000");
            }
            maxEntries = value;
            return this;
        }

        @NonNull
        public Builder cancellationToken(@NonNull CancellationToken value) {
            if (value == null) throw new IllegalArgumentException("cancellationToken is required");
            cancellationToken = value;
            return this;
        }

        @NonNull
        public Builder progressListener(ProgressListener value) {
            progressListener = value;
            return this;
        }

        @NonNull
        public FileOperationOptions build() {
            return new FileOperationOptions(this);
        }
    }
}
