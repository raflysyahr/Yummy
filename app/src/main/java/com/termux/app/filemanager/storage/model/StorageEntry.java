package com.termux.app.filemanager.storage.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Provider-neutral description of a file or directory.
 * documentId is opaque: local providers use a canonical path, SAF providers use a document ID.
 */
public final class StorageEntry {
    @NonNull private final String providerId;
    @NonNull private final String documentId;
    @Nullable private final String parentDocumentId;
    @NonNull private final String name;
    @NonNull private final String mimeType;
    private final boolean directory;
    private final long size;
    private final long lastModified;
    private final int capabilities;

    public StorageEntry(@NonNull String providerId,
                        @NonNull String documentId,
                        @Nullable String parentDocumentId,
                        @NonNull String name,
                        @NonNull String mimeType,
                        boolean directory,
                        long size,
                        long lastModified,
                        int capabilities) {
        this.providerId = providerId;
        this.documentId = documentId;
        this.parentDocumentId = parentDocumentId;
        this.name = name;
        this.mimeType = mimeType;
        this.directory = directory;
        this.size = size;
        this.lastModified = lastModified;
        this.capabilities = capabilities;
    }

    @NonNull public String getProviderId() { return providerId; }
    @NonNull public String getDocumentId() { return documentId; }
    @Nullable public String getParentDocumentId() { return parentDocumentId; }
    @NonNull public String getName() { return name; }
    @NonNull public String getMimeType() { return mimeType; }
    public boolean isDirectory() { return directory; }
    public long getSize() { return size; }
    public long getLastModified() { return lastModified; }
    public int getCapabilities() { return capabilities; }
    public boolean hasCapability(int capability) {
        return StorageCapability.has(capabilities, capability);
    }
    public boolean isRoot() { return parentDocumentId == null; }
}
