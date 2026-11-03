package com.termux.app.filemanager.storage.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** A mounted root shown by the future File Manager's storage locations screen. */
public final class StorageLocation {
    public enum Type {
        APP_PRIVATE,
        SAF_TREE
    }

    @NonNull private final String id;
    @NonNull private final String name;
    @Nullable private final String description;
    @NonNull private final Type type;
    private final boolean readOnly;

    public StorageLocation(@NonNull String id,
                           @NonNull String name,
                           @Nullable String description,
                           @NonNull Type type,
                           boolean readOnly) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.type = type;
        this.readOnly = readOnly;
    }

    @NonNull public String getId() { return id; }
    @NonNull public String getName() { return name; }
    @Nullable public String getDescription() { return description; }
    @NonNull public Type getType() { return type; }
    public boolean isReadOnly() { return readOnly; }
}
