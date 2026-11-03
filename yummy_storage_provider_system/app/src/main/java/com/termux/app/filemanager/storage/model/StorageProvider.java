package com.termux.app.filemanager.storage.model;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Common contract for app-private files and Android Storage Access Framework locations.
 * Paths/document IDs are opaque to callers; only a provider can interpret its own entries.
 * All methods perform blocking I/O and must be called from a worker thread, not the UI thread.
 */
public interface StorageProvider {
    @NonNull String getId();

    @NonNull StorageLocation getLocation();

    /** Returns false when a removable volume is disconnected or its URI grant was revoked. */
    boolean isAvailable();

    @NonNull StorageEntry getRootEntry() throws IOException;

    @NonNull List<StorageEntry> list(@NonNull StorageEntry directory) throws IOException;

    @NonNull InputStream openRead(@NonNull StorageEntry file) throws IOException;

    @NonNull OutputStream openWrite(@NonNull StorageEntry file, boolean append) throws IOException;

    @NonNull StorageEntry createFile(@NonNull StorageEntry parent,
                                     @NonNull String name,
                                     @NonNull String mimeType) throws IOException;

    @NonNull StorageEntry createDirectory(@NonNull StorageEntry parent,
                                          @NonNull String name) throws IOException;

    @NonNull StorageEntry rename(@NonNull StorageEntry entry,
                                 @NonNull String newName) throws IOException;

    void delete(@NonNull StorageEntry entry) throws IOException;

    boolean exists(@NonNull StorageEntry entry);
}
