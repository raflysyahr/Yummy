package com.termux.app.filemanager.storage.model;

/** Operations that a storage provider says are available for an entry. */
public final class StorageCapability {
    public static final int READ = 1;
    public static final int WRITE = 1 << 1;
    public static final int LIST = 1 << 2;
    public static final int CREATE_CHILD = 1 << 3;
    public static final int RENAME = 1 << 4;
    public static final int DELETE = 1 << 5;

    private StorageCapability() {
    }

    public static boolean has(int capabilities, int capability) {
        return (capabilities & capability) == capability;
    }
}
