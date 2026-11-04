package com.termux.app.filemanager.storage.operations;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.filemanager.storage.StorageRegistry;
import com.termux.app.filemanager.storage.model.StorageCapability;
import com.termux.app.filemanager.storage.model.StorageEntry;
import com.termux.app.filemanager.storage.model.StorageProvider;
import com.termux.app.filemanager.storage.selection.StorageSelection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Provider-neutral file operations used by the future Yummy File Manager UI.
 *
 * All methods perform blocking storage I/O and must be called on a worker thread.
 * The service never converts SAF document IDs into filesystem paths.
 */
public final class FileOperationService {
    public interface ProviderLookup {
        @Nullable StorageProvider getProvider(@NonNull String providerId);
    }

    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    private final ProviderLookup providerLookup;

    public FileOperationService(@NonNull StorageRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry is required");
        this.providerLookup = registry::getProvider;
    }

    /** Testable constructor and alternative integration point for a custom provider registry. */
    public FileOperationService(@NonNull ProviderLookup providerLookup) {
        if (providerLookup == null) throw new IllegalArgumentException("providerLookup is required");
        this.providerLookup = providerLookup;
    }

    @NonNull
    public StorageEntry createFile(@NonNull String providerId,
                                   @NonNull StorageEntry parent,
                                   @NonNull String name) throws IOException {
        return createFile(providerId, parent, name, inferMimeType(name));
    }

    @NonNull
    public StorageEntry createFile(@NonNull String providerId,
                                   @NonNull StorageEntry parent,
                                   @NonNull String name,
                                   @Nullable String mimeType) throws IOException {
        validateName(name);
        StorageProvider provider = requireProvider(providerId);
        requireOwned(provider, parent);
        requireCreatableParent(parent);
        ensureNameAvailable(provider, parent, name);
        String safeMime = mimeType == null || mimeType.trim().isEmpty()
            ? DEFAULT_MIME_TYPE : mimeType.trim();
        return provider.createFile(parent, name, safeMime);
    }

    @NonNull
    public StorageEntry createDirectory(@NonNull String providerId,
                                        @NonNull StorageEntry parent,
                                        @NonNull String name) throws IOException {
        validateName(name);
        StorageProvider provider = requireProvider(providerId);
        requireOwned(provider, parent);
        requireCreatableParent(parent);
        ensureNameAvailable(provider, parent, name);
        return provider.createDirectory(parent, name);
    }

    @NonNull
    public StorageEntry rename(@NonNull String providerId,
                               @NonNull StorageEntry entry,
                               @NonNull String newName) throws IOException {
        validateName(newName);
        StorageProvider provider = requireProvider(providerId);
        requireOwned(provider, entry);
        if (entry.isRoot()) throw new IOException("The storage root cannot be renamed");
        if (!entry.hasCapability(StorageCapability.RENAME)) {
            throw new IOException("This storage provider does not allow renaming this entry");
        }
        if (!provider.exists(entry)) throw new IOException("Entry no longer exists: " + entry.getName());
        if (entry.getName().equals(newName)) return entry;

        // The provider remains the final authority for rename semantics and name conflicts.
        // Do not walk from the storage root to find the parent: remote/OTG trees can be large.
        return provider.rename(entry, newName);
    }

    /** Deletes one entry. Directories are recursively deleted by default. */
    @NonNull
    public FileOperationResult delete(@NonNull String providerId,
                                      @NonNull StorageEntry entry,
                                      @Nullable FileOperationOptions options) {
        StorageSelection selection = new StorageSelection();
        if (!providerId.equals(entry.getProviderId())) {
            List<FileOperationResult.Failure> failures = new ArrayList<>();
            failures.add(failure(providerId, entry, "Entry belongs to another provider"));
            return buildResult(FileOperationResult.Status.FAILED, 0, 0, 0, failures, null);
        }
        selection.add(entry);
        return deleteSelected(selection, options);
    }

    /**
     * Deletes a snapshot of all selected entries. A selected directory includes its descendants;
     * selecting a child as well does not cause it to be deleted twice. Failed entries are reported
     * individually and do not prevent unrelated selected entries from being processed.
     */
    @NonNull
    public FileOperationResult deleteSelected(@NonNull StorageSelection selection,
                                              @Nullable FileOperationOptions requestedOptions) {
        FileOperationOptions options = requestedOptions == null
            ? FileOperationOptions.builder().build() : requestedOptions;
        List<FileOperationResult.Failure> failures = new ArrayList<>();
        List<PlannedEntry> deletePlan = new ArrayList<>();
        Set<String> scheduledKeys = new LinkedHashSet<>();
        int skipped = 0;
        boolean cancelled = false;

        List<StorageEntry> selectedEntries = selection.getEntries();
        for (StorageEntry selected : selectedEntries) {
            if (isCancelled(options)) {
                cancelled = true;
                break;
            }
            notifyProgress(options, new FileOperationProgress(
                FileOperationProgress.Phase.PREPARING, selected.getName(),
                deletePlan.size(), -1
            ));

            StorageProvider provider = providerLookup.getProvider(selected.getProviderId());
            if (provider == null) {
                failures.add(failure(selected, "Storage location is no longer mounted"));
                continue;
            }
            if (!provider.isAvailable()) {
                failures.add(failure(selected, "Storage location is unavailable"));
                continue;
            }
            if (!provider.exists(selected)) {
                skipped++;
                continue;
            }
            if (selected.isRoot()) {
                failures.add(failure(selected, "The storage root cannot be deleted"));
                continue;
            }

            try {
                List<StorageEntry> tree = new ArrayList<>();
                Set<String> recursionStack = new HashSet<>();
                collectPostOrder(provider, selected, options, tree, recursionStack,
                    scheduledKeys.size());

                // Commit a root's plan only after its complete subtree passed preflight.
                for (StorageEntry item : tree) {
                    String key = keyOf(item);
                    if (scheduledKeys.add(key)) deletePlan.add(new PlannedEntry(provider, item));
                }
            } catch (OperationCancelledException e) {
                cancelled = true;
                break;
            } catch (IOException | RuntimeException e) {
                failures.add(failure(selected, safeMessage(e)));
            }
        }

        int deleted = 0;
        StorageEntry lastDeleted = null;
        for (int i = 0; i < deletePlan.size(); i++) {
            if (isCancelled(options)) {
                cancelled = true;
                break;
            }
            PlannedEntry planned = deletePlan.get(i);
            try {
                if (!planned.provider.isAvailable()) {
                    throw new IOException("Storage location became unavailable during deletion");
                }
                // A previously selected parent may already have removed this selected child.
                if (!planned.provider.exists(planned.entry)) {
                    skipped++;
                    continue;
                }
                planned.provider.delete(planned.entry);
                deleted++;
                lastDeleted = planned.entry;
            } catch (IOException | RuntimeException e) {
                failures.add(failure(planned.entry, safeMessage(e)));
            }
            notifyProgress(options, new FileOperationProgress(
                FileOperationProgress.Phase.DELETING,
                planned.entry.getName(), i + 1, deletePlan.size()
            ));
        }

        FileOperationResult.Status status;
        if (cancelled) {
            status = FileOperationResult.Status.CANCELLED;
        } else if (!failures.isEmpty() && deleted > 0) {
            status = FileOperationResult.Status.PARTIAL_FAILURE;
        } else if (!failures.isEmpty()) {
            status = FileOperationResult.Status.FAILED;
        } else {
            status = FileOperationResult.Status.SUCCESS;
        }
        return buildResult(status, deleted, skipped, deletePlan.size(), failures, lastDeleted);
    }

    /** Infers a best-effort MIME type from a file extension. */
    @NonNull
    public static String inferMimeType(@NonNull String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) return DEFAULT_MIME_TYPE;
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        String knownMime = getKnownMimeType(extension);
        if (knownMime != null) return knownMime;
        String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime == null || mime.trim().isEmpty() ? DEFAULT_MIME_TYPE : mime;
    }


    @Nullable
    private static String getKnownMimeType(String extension) {
        switch (extension) {
            case "py": return "text/x-python";
            case "js": case "mjs": case "cjs": return "text/javascript";
            case "jsx": return "text/jsx";
            case "ts": return "text/typescript";
            case "tsx": return "text/tsx";
            case "java": return "text/x-java-source";
            case "kt": case "kts": return "text/x-kotlin";
            case "html": case "htm": return "text/html";
            case "css": return "text/css";
            case "scss": return "text/x-scss";
            case "php": return "application/x-httpd-php";
            case "json": return "application/json";
            case "xml": return "application/xml";
            case "md": case "markdown": return "text/markdown";
            case "sh": case "bash": return "text/x-shellscript";
            case "sql": return "application/sql";
            case "yaml": case "yml": return "application/yaml";
            case "toml": return "application/toml";
            case "gradle": return "text/x-gradle";
            case "properties": return "text/plain";
            default: return null;
        }
    }

    private void collectPostOrder(StorageProvider provider,
                                  StorageEntry entry,
                                  FileOperationOptions options,
                                  List<StorageEntry> result,
                                  Set<String> recursionStack,
                                  int alreadyPlanned) throws IOException {
        checkCancelled(options);
        if (entry.isRoot()) throw new IOException("The storage root cannot be deleted");
        if (!entry.hasCapability(StorageCapability.DELETE)) {
            throw new IOException("Delete is not permitted for: " + entry.getName());
        }

        String key = keyOf(entry);
        if (!recursionStack.add(key)) {
            throw new IOException("Storage provider returned a cyclic directory tree");
        }

        if (entry.isDirectory()) {
            if (!options.isRecursiveDelete()) {
                if (!entry.hasCapability(StorageCapability.LIST)) {
                    throw new IOException("Cannot verify whether the directory is empty: " + entry.getName());
                }
                List<StorageEntry> children = provider.list(entry);
                if (!children.isEmpty()) throw new IOException("Directory is not empty: " + entry.getName());
            } else {
                if (!entry.hasCapability(StorageCapability.LIST)) {
                    throw new IOException("Cannot list directory before deleting it: " + entry.getName());
                }
                List<StorageEntry> children = provider.list(entry);
                for (StorageEntry child : children) {
                    if (!provider.getId().equals(child.getProviderId())) {
                        throw new IOException("Storage provider returned an entry owned by another provider");
                    }
                    if (result.size() + alreadyPlanned >= options.getMaxEntries()) {
                        throw new IOException("Delete operation exceeds the " + options.getMaxEntries()
                            + " entry safety limit");
                    }
                    collectPostOrder(provider, child, options, result, recursionStack, alreadyPlanned);
                }
            }
        }

        recursionStack.remove(key);
        if (result.size() + alreadyPlanned >= options.getMaxEntries()) {
            throw new IOException("Delete operation exceeds the " + options.getMaxEntries()
                + " entry safety limit");
        }
        result.add(entry);
    }

    private StorageProvider requireProvider(String providerId) throws IOException {
        if (providerId == null || providerId.trim().isEmpty()) {
            throw new IOException("Storage provider ID is required");
        }
        StorageProvider provider = providerLookup.getProvider(providerId);
        if (provider == null) throw new IOException("Storage location is not mounted: " + providerId);
        if (!provider.isAvailable()) throw new IOException("Storage location is unavailable: " + providerId);
        return provider;
    }

    private static void requireOwned(StorageProvider provider, StorageEntry entry) throws IOException {
        if (entry == null || !provider.getId().equals(entry.getProviderId())) {
            throw new IOException("Entry belongs to another storage provider");
        }
    }

    private static void requireCreatableParent(StorageEntry parent) throws IOException {
        if (!parent.isDirectory()) throw new IOException("The selected parent is not a directory");
        if (!parent.hasCapability(StorageCapability.CREATE_CHILD)) {
            throw new IOException("This directory does not allow creating entries");
        }
    }

    private static void ensureNameAvailable(StorageProvider provider,
                                            StorageEntry parent,
                                            String name) throws IOException {
        if (!parent.hasCapability(StorageCapability.LIST)) return;
        for (StorageEntry child : provider.list(parent)) {
            if (child.getName().equalsIgnoreCase(name)) {
                throw new IOException("An entry named '" + name + "' already exists");
            }
        }
    }

    private static void validateName(String name) throws IOException {
        if (name == null || name.trim().isEmpty()) throw new IOException("File name cannot be empty");
        if (".".equals(name) || "..".equals(name)) throw new IOException("Invalid file name");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == '\u0000' || Character.isISOControl(c)) {
                throw new IOException("File name contains an invalid character");
            }
        }
    }

    private static void checkCancelled(FileOperationOptions options) throws OperationCancelledException {
        if (isCancelled(options)) throw new OperationCancelledException();
    }

    private static boolean isCancelled(FileOperationOptions options) {
        try {
            return options.getCancellationToken().isCancellationRequested();
        } catch (RuntimeException ignored) {
            // A broken cancellation callback must not interrupt an in-progress filesystem mutation.
            return false;
        }
    }

    private static void notifyProgress(FileOperationOptions options, FileOperationProgress progress) {
        FileOperationOptions.ProgressListener listener = options.getProgressListener();
        if (listener == null) return;
        try {
            listener.onProgress(progress);
        } catch (RuntimeException ignored) {
            // UI callback failures must not corrupt a storage operation.
        }
    }

    private static FileOperationResult.Failure failure(StorageEntry entry, String message) {
        return failure(entry.getProviderId(), entry, message);
    }

    private static FileOperationResult.Failure failure(String providerId,
                                                        StorageEntry entry,
                                                        String message) {
        return new FileOperationResult.Failure(providerId, entry.getName(), message);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName() : message;
    }

    private static FileOperationResult buildResult(FileOperationResult.Status status,
                                                   int deleted,
                                                   int skipped,
                                                   int planned,
                                                   List<FileOperationResult.Failure> failures,
                                                   StorageEntry lastDeleted) {
        return new FileOperationResult(status, deleted, skipped, planned, failures, lastDeleted);
    }

    private static String keyOf(StorageEntry entry) {
        return entry.getProviderId() + '\u0000' + entry.getDocumentId();
    }

    private static final class PlannedEntry {
        final StorageProvider provider;
        final StorageEntry entry;
        PlannedEntry(StorageProvider provider, StorageEntry entry) {
            this.provider = provider;
            this.entry = entry;
        }
    }

    private static final class OperationCancelledException extends IOException {
        private static final long serialVersionUID = 1L;
        OperationCancelledException() { super("Operation cancelled"); }
    }
}
