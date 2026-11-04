package com.termux.app.filemanager.storage.transfer;

import androidx.annotation.NonNull;

/** Immutable settings for a copy or move operation. */
public final class TransferOptions {
    public enum Operation {
        COPY,
        MOVE
    }

    public enum ConflictPolicy {
        /** Stop before writing when the destination name already exists. */
        FAIL,
        /** Create a deterministic name such as "file (copy 2).ext" instead. */
        KEEP_BOTH
    }

    /** A cancellation token supplied by the caller. Check it from a worker thread. */
    public interface CancellationToken {
        CancellationToken NONE = () -> false;

        boolean isCancellationRequested();
    }

    /** Receives progress callbacks from the worker performing the transfer. */
    public interface ProgressListener {
        void onProgress(@NonNull TransferProgress progress);
    }

    private final Operation operation;
    private final ConflictPolicy conflictPolicy;
    private final CancellationToken cancellationToken;
    private final ProgressListener progressListener;
    private final int bufferSize;

    private TransferOptions(Builder builder) {
        operation = builder.operation;
        conflictPolicy = builder.conflictPolicy;
        cancellationToken = builder.cancellationToken;
        progressListener = builder.progressListener;
        bufferSize = builder.bufferSize;
    }

    @NonNull
    public static Builder builder(@NonNull Operation operation) {
        return new Builder(operation);
    }

    @NonNull public Operation getOperation() { return operation; }
    @NonNull public ConflictPolicy getConflictPolicy() { return conflictPolicy; }
    @NonNull public CancellationToken getCancellationToken() { return cancellationToken; }
    public ProgressListener getProgressListener() { return progressListener; }
    public int getBufferSize() { return bufferSize; }

    public static final class Builder {
        private final Operation operation;
        private ConflictPolicy conflictPolicy = ConflictPolicy.FAIL;
        private CancellationToken cancellationToken = CancellationToken.NONE;
        private ProgressListener progressListener;
        private int bufferSize = 64 * 1024;

        private Builder(Operation operation) {
            if (operation == null) throw new IllegalArgumentException("operation is required");
            this.operation = operation;
        }

        @NonNull
        public Builder conflictPolicy(@NonNull ConflictPolicy value) {
            if (value == null) throw new IllegalArgumentException("conflictPolicy is required");
            conflictPolicy = value;
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
        public Builder bufferSize(int value) {
            if (value < 1024 || value > 4 * 1024 * 1024) {
                throw new IllegalArgumentException("bufferSize must be between 1 KiB and 4 MiB");
            }
            bufferSize = value;
            return this;
        }

        @NonNull
        public TransferOptions build() {
            return new TransferOptions(this);
        }
    }
}
