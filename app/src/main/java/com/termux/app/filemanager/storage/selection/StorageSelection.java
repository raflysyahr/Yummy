package com.termux.app.filemanager.storage.selection;

import androidx.annotation.NonNull;

import com.termux.app.filemanager.storage.model.StorageEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe multi-selection model for File Manager entries.
 *
 * Entries are identified by provider ID + opaque document ID. A plain document ID is not
 * sufficient because two different storage providers may return the same document ID.
 */
public final class StorageSelection {
    private final Map<String, StorageEntry> selected = new LinkedHashMap<>();

    /** Adds an entry. Returns false when it was already selected. */
    public synchronized boolean add(@NonNull StorageEntry entry) {
        String key = keyOf(entry);
        if (selected.containsKey(key)) return false;
        selected.put(key, entry);
        return true;
    }

    /** Removes an entry. Returns true when it was selected. */
    public synchronized boolean remove(@NonNull StorageEntry entry) {
        return selected.remove(keyOf(entry)) != null;
    }

    /** Toggles an entry and returns true when it is selected after the call. */
    public synchronized boolean toggle(@NonNull StorageEntry entry) {
        String key = keyOf(entry);
        if (selected.containsKey(key)) {
            selected.remove(key);
            return false;
        }
        selected.put(key, entry);
        return true;
    }

    public synchronized boolean contains(@NonNull StorageEntry entry) {
        return selected.containsKey(keyOf(entry));
    }

    public synchronized int size() {
        return selected.size();
    }

    public synchronized boolean isEmpty() {
        return selected.isEmpty();
    }

    public synchronized void clear() {
        selected.clear();
    }

    /** Returns an immutable snapshot preserving selection order. */
    @NonNull
    public synchronized List<StorageEntry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(selected.values()));
    }

    private static String keyOf(StorageEntry entry) {
        return entry.getProviderId() + '\u0000' + entry.getDocumentId();
    }
}
