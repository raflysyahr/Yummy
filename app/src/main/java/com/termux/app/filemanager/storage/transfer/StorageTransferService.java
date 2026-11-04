package com.termux.app.filemanager.storage.transfer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.filemanager.storage.model.StorageCapability;
import com.termux.app.filemanager.storage.model.StorageEntry;
import com.termux.app.filemanager.storage.model.StorageProvider;
import com.termux.app.filemanager.storage.model.StorageLocation;
import com.termux.app.filemanager.storage.provider.LocalFileStorageProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Copies and moves entries between any two StorageProvider implementations.
 *
 * <p>All methods perform blocking I/O. Invoke {@link #transfer} on a worker thread,
 * never on the Android main thread.</p>
 *
 * <p>Move is intentionally copy-then-delete: the source is not deleted until the
 * complete destination tree has been written and all output streams have closed.
 * Providers do not offer a common atomic move operation, so a failed source cleanup
 * is reported as SOURCE_DELETE_INCOMPLETE and the completed destination is preserved.</p>
 */
public final class StorageTransferService {
    private static final int MAX_PLAN_ENTRIES = 100_000;
    private static final long UNKNOWN_TOTAL_BYTES = -1L;

    /**
     * Transfers a file or directory to a destination directory.
     *
     * @param sourceProvider provider which owns source
     * @param source source file or directory; storage roots are deliberately rejected
     * @param destinationProvider provider which owns destinationDirectory
     * @param destinationDirectory existing writable destination directory
     * @param requestedName optional destination name; source name is used when null/blank
     * @param options operation, conflict, cancellation and progress settings
     * @throws IOException when validation or copying fails. A failed partial destination
     *                     is removed on a best-effort basis before this method returns.
     */
    @NonNull
    public TransferResult transfer(@NonNull StorageProvider sourceProvider,
                                   @NonNull StorageEntry source,
                                   @NonNull StorageProvider destinationProvider,
                                   @NonNull StorageEntry destinationDirectory,
                                   @Nullable String requestedName,
                                   @NonNull TransferOptions options) throws IOException {
        if (sourceProvider == null || source == null || destinationProvider == null
            || destinationDirectory == null || options == null) {
            throw new IllegalArgumentException("Providers, entries and options are required");
        }

        validateProviderOwnership(sourceProvider, source, "source");
        validateProviderOwnership(destinationProvider, destinationDirectory, "destination directory");
        validateAvailable(sourceProvider, "source");
        validateAvailable(destinationProvider, "destination");
        validateDestination(destinationDirectory);

        if (source.isRoot()) {
            throw new IOException("A storage root cannot be copied or moved. Select an item inside it.");
        }
        if (sameEntry(sourceProvider, source, destinationProvider, destinationDirectory)
            || isAppPrivateDestinationInsideSource(sourceProvider, source,
                destinationProvider, destinationDirectory)) {
            throw new IOException("The source cannot be transferred into itself or one of its descendants");
        }

        String destinationName = requestedName == null || requestedName.trim().isEmpty()
            ? source.getName() : requestedName;
        validateName(destinationName);

        OperationState state = new OperationState(options, sourceProvider, destinationProvider);
        final PlanNode planRoot;
        try {
            planRoot = buildPlan(sourceProvider, source, destinationProvider,
                destinationDirectory, options, state);
        } catch (TransferCancelledException e) {
            return new TransferResult(
                TransferResult.Status.CANCELLED, null, 0L, 0, 0,
                "Transfer cancelled during preparation; no destination entries were created"
            );
        }

        if (options.getOperation() == TransferOptions.Operation.MOVE) {
            validateDeletablePlan(planRoot);
        }

        try {
            checkCancelled(options);
        } catch (TransferCancelledException e) {
            return new TransferResult(
                TransferResult.Status.CANCELLED, null, 0L, 0, 0,
                "Transfer cancelled before copying; no destination entries were created"
            );
        }
        state.phase = TransferProgress.Phase.COPYING;
        StorageEntry destinationRoot = null;
        try {
            destinationRoot = copyPlan(planRoot, destinationDirectory, destinationName, state);
            state.destinationRoot = destinationRoot;
            notifyProgress(state, destinationName);
        } catch (TransferCancelledException e) {
            state.phase = TransferProgress.Phase.ROLLING_BACK;
            IOException cleanupFailure = rollbackCreatedEntries(destinationProvider, state.createdEntries, state);
            String message = cleanupFailure == null
                ? "Transfer cancelled; temporary destination entries were removed"
                : "Transfer cancelled, but some temporary destination entries could not be removed: "
                    + cleanupFailure.getMessage();
            return new TransferResult(
                TransferResult.Status.CANCELLED,
                cleanupFailure == null ? null : state.destinationRoot,
                state.bytesCopied,
                state.filesCopied,
                state.directoriesCopied,
                message
            );
        } catch (IOException | RuntimeException e) {
            state.phase = TransferProgress.Phase.ROLLING_BACK;
            IOException cleanupFailure = rollbackCreatedEntries(destinationProvider, state.createdEntries, state);
            IOException failure = e instanceof IOException
                ? (IOException) e : new IOException("Storage provider failed during transfer", e);
            if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
            throw failure;
        }

        if (options.getOperation() == TransferOptions.Operation.MOVE) {
            if (isCancellationRequested(options)) {
                state.phase = TransferProgress.Phase.ROLLING_BACK;
                IOException cleanupFailure = rollbackCreatedEntries(destinationProvider, state.createdEntries, state);
                String message = cleanupFailure == null
                    ? "Move cancelled before source deletion; original source was kept"
                    : "Move cancelled before source deletion; temporary destination cleanup was incomplete: "
                        + cleanupFailure.getMessage();
                return new TransferResult(
                    TransferResult.Status.CANCELLED,
                    cleanupFailure == null ? null : destinationRoot,
                    state.bytesCopied,
                    state.filesCopied,
                    state.directoriesCopied,
                    message
                );
            }

            state.phase = TransferProgress.Phase.VERIFYING_SOURCE;
            notifyProgress(state, source.getName());
            try {
                verifySourceTree(sourceProvider, planRoot, options, state);
                checkCancelled(options);
            } catch (TransferCancelledException e) {
                state.phase = TransferProgress.Phase.ROLLING_BACK;
                IOException cleanupFailure = rollbackCreatedEntries(destinationProvider, state.createdEntries, state);
                String message = cleanupFailure == null
                    ? "Move cancelled before source deletion; original source was kept"
                    : "Move cancelled before source deletion; destination cleanup was incomplete: "
                        + cleanupFailure.getMessage();
                return new TransferResult(
                    TransferResult.Status.CANCELLED,
                    cleanupFailure == null ? null : destinationRoot,
                    state.bytesCopied,
                    state.filesCopied,
                    state.directoriesCopied,
                    message
                );
            } catch (IOException | RuntimeException e) {
                return new TransferResult(
                    TransferResult.Status.SOURCE_RETAINED,
                    destinationRoot,
                    state.bytesCopied,
                    state.filesCopied,
                    state.directoriesCopied,
                    "Destination copy completed, but the source could not be verified. "
                        + "No source deletion was attempted; inspect both locations before retrying: "
                        + safeMessage(e)
                );
            }

            // Once source deletion starts, finish it rather than stopping halfway on cancellation.
            state.phase = TransferProgress.Phase.DELETING_SOURCE;
            notifyProgress(state, source.getName());
            try {
                deletePlan(sourceProvider, planRoot, state);
            } catch (IOException | RuntimeException e) {
                String message = "Destination was copied successfully, but source cleanup was incomplete: "
                    + safeMessage(e);
                return new TransferResult(
                    TransferResult.Status.SOURCE_DELETE_INCOMPLETE,
                    destinationRoot,
                    state.bytesCopied,
                    state.filesCopied,
                    state.directoriesCopied,
                    message
                );
            }
        }

        StorageEntry refreshed = refreshEntry(destinationProvider, destinationDirectory, destinationRoot);
        state.phase = TransferProgress.Phase.COPYING;
        notifyProgress(state, destinationRoot.getName());
        return new TransferResult(
            TransferResult.Status.SUCCESS,
            refreshed,
            state.bytesCopied,
            state.filesCopied,
            state.directoriesCopied,
            options.getOperation() == TransferOptions.Operation.MOVE
                ? "Move completed" : "Copy completed"
        );
    }

    private PlanNode buildPlan(StorageProvider sourceProvider,
                               StorageEntry source,
                               StorageProvider destinationProvider,
                               StorageEntry destinationDirectory,
                               TransferOptions options,
                               OperationState state) throws IOException {
        PlanNode root = new PlanNode(source);
        Deque<PlanNode> pending = new ArrayDeque<>();
        pending.push(root);
        Set<String> visited = new HashSet<>();
        long totalBytes = 0L;
        int fileCount = 0;
        int directoryCount = 0;
        int entries = 0;
        boolean destinationInsideSource = false;
        boolean documentIdsComparable = haveComparableDocumentIds(sourceProvider, destinationProvider);

        while (!pending.isEmpty()) {
            checkCancelled(options);
            PlanNode node = pending.pop();
            StorageEntry entry = node.entry;
            validateReadable(sourceProvider, entry);

            if (++entries > MAX_PLAN_ENTRIES) {
                throw new IOException("Transfer contains more than " + MAX_PLAN_ENTRIES
                    + " entries; split it into smaller operations");
            }

            String identity = entry.getProviderId() + '\u0000' + entry.getDocumentId();
            if (!visited.add(identity)) {
                throw new IOException("The source provider returned a repeated document ID; transfer stopped to avoid a directory cycle");
            }

            if (documentIdsComparable
                && entry.isDirectory()
                && entry.getDocumentId().equals(destinationDirectory.getDocumentId())) {
                destinationInsideSource = true;
            }

            if (entry.isDirectory()) {
                directoryCount++;
                List<StorageEntry> children = sourceProvider.list(entry);
                for (StorageEntry child : children) {
                    PlanNode childNode = new PlanNode(child);
                    node.children.add(childNode);
                }
                for (int i = node.children.size() - 1; i >= 0; i--) {
                    pending.push(node.children.get(i));
                }
            } else {
                fileCount++;
                if (entry.getSize() > 0 && totalBytes <= Long.MAX_VALUE - entry.getSize()) {
                    totalBytes += entry.getSize();
                }
            }

            state.planFiles = fileCount;
            state.planDirectories = directoryCount;
            state.planBytes = totalBytes;
            state.phase = TransferProgress.Phase.PREPARING;
            notifyProgress(state, entry.getName());
        }

        if (destinationInsideSource) {
            throw new IOException("A directory cannot be copied or moved into one of its own descendants");
        }

        // A total of zero is ambiguous for document providers: they may not report sizes.
        state.planBytes = totalBytes > 0 ? totalBytes : UNKNOWN_TOTAL_BYTES;
        state.planFiles = fileCount;
        state.planDirectories = directoryCount;
        return root;
    }

    private void validateDeletablePlan(PlanNode root) throws IOException {
        Deque<PlanNode> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            PlanNode node = pending.pop();
            if (!node.entry.hasCapability(StorageCapability.DELETE)) {
                throw new IOException("Move is not supported: cannot delete source entry "
                    + node.entry.getName());
            }
            if (node.entry.isDirectory()) {
                if (!node.entry.hasCapability(StorageCapability.LIST)) {
                    throw new IOException("Move is not supported: cannot list source directory "
                        + node.entry.getName());
                }
                for (PlanNode child : node.children) pending.push(child);
            }
        }
    }

    private StorageEntry copyPlan(PlanNode root,
                                  StorageEntry destinationDirectory,
                                  String requestedName,
                                  OperationState state) throws IOException {
        Deque<CopyTask> pending = new ArrayDeque<>();
        pending.push(new CopyTask(root, destinationDirectory, requestedName, true));
        StorageEntry destinationRoot = null;

        while (!pending.isEmpty()) {
            checkCancelled(state.options);
            CopyTask task = pending.pop();
            String actualName = state.nameAllocator.allocate(
                task.destinationParent, task.requestedName, state.options.getConflictPolicy()
            );

            StorageEntry created;
            if (task.sourceNode.entry.isDirectory()) {
                created = state.destinationProvider.createDirectory(task.destinationParent, actualName);
                state.createdEntries.add(created);
                state.directoriesCopied++;
            } else {
                created = state.destinationProvider.createFile(
                    task.destinationParent, actualName, task.sourceNode.entry.getMimeType()
                );
                state.createdEntries.add(created);
            }

            if (task.isRoot) {
                destinationRoot = created;
                state.destinationRoot = created;
            }
            state.nameAllocator.record(task.destinationParent, created.getName());

            if (!task.sourceNode.entry.isDirectory()) {
                copyFileContents(task.sourceNode, created, state);
                state.filesCopied++;
            }
            state.phase = TransferProgress.Phase.COPYING;
            notifyProgress(state, task.sourceNode.entry.getName());

            if (task.sourceNode.entry.isDirectory()) {
                for (int i = task.sourceNode.children.size() - 1; i >= 0; i--) {
                    PlanNode child = task.sourceNode.children.get(i);
                    pending.push(new CopyTask(child, created, child.entry.getName(), false));
                }
            }
        }

        if (destinationRoot == null) throw new IOException("Transfer did not create a destination entry");
        return destinationRoot;
    }

    private void copyFileContents(PlanNode sourceNode,
                                  StorageEntry destination,
                                  OperationState state) throws IOException {
        StorageEntry source = sourceNode.entry;
        byte[] buffer = new byte[state.options.getBufferSize()];
        MessageDigest digest = state.options.getOperation() == TransferOptions.Operation.MOVE
            ? newSha256Digest() : null;
        try (InputStream input = state.sourceProvider.openRead(source);
             OutputStream output = state.destinationProvider.openWrite(destination, false)) {
            while (true) {
                checkCancelled(state.options);
                int read = input.read(buffer);
                if (read < 0) break;
                if (read == 0) continue;
                output.write(buffer, 0, read);
                if (digest != null) digest.update(buffer, 0, read);
                state.bytesCopied += read;
                state.phase = TransferProgress.Phase.COPYING;
                notifyProgress(state, source.getName());
            }
            output.flush();
        }
        if (digest != null) sourceNode.sha256 = digest.digest();
    }

    /** Re-check the source tree and hashes immediately before a move deletes anything. */
    private void verifySourceTree(StorageProvider provider,
                                  PlanNode root,
                                  TransferOptions options,
                                  OperationState state) throws IOException {
        Deque<PlanNode> pending = new ArrayDeque<>();
        pending.push(root);

        while (!pending.isEmpty()) {
            checkCancelled(options);
            PlanNode node = pending.pop();
            if (!provider.exists(node.entry)) {
                throw new IOException("Source entry disappeared: " + node.entry.getName());
            }

            if (!node.entry.isDirectory()) {
                if (node.sha256 == null) {
                    throw new IOException("No verification checksum was recorded for " + node.entry.getName());
                }
                byte[] currentHash = hashSourceFile(provider, node.entry, options);
                if (!MessageDigest.isEqual(node.sha256, currentHash)) {
                    throw new IOException("Source file changed during transfer: " + node.entry.getName());
                }
            } else {
                List<StorageEntry> currentChildren = provider.list(node.entry);
                if (currentChildren.size() != node.children.size()) {
                    throw new IOException("Source directory contents changed during transfer: "
                        + node.entry.getName());
                }

                Map<String, StorageEntry> currentById = new HashMap<>();
                for (StorageEntry current : currentChildren) {
                    currentById.put(current.getDocumentId(), current);
                }
                for (PlanNode plannedChild : node.children) {
                    StorageEntry current = currentById.get(plannedChild.entry.getDocumentId());
                    if (current == null
                        || !current.getName().equals(plannedChild.entry.getName())
                        || current.isDirectory() != plannedChild.entry.isDirectory()) {
                        throw new IOException("Source directory contents changed during transfer: "
                            + node.entry.getName());
                    }
                    pending.push(plannedChild);
                }
            }
            state.phase = TransferProgress.Phase.VERIFYING_SOURCE;
            notifyProgress(state, node.entry.getName());
        }
    }

    private byte[] hashSourceFile(StorageProvider provider,
                                  StorageEntry file,
                                  TransferOptions options) throws IOException {
        MessageDigest digest = newSha256Digest();
        byte[] buffer = new byte[options.getBufferSize()];
        try (InputStream input = provider.openRead(file)) {
            while (true) {
                checkCancelled(options);
                int read = input.read(buffer);
                if (read < 0) break;
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available on this Android runtime", e);
        }
    }

    private void deletePlan(StorageProvider provider,
                            PlanNode root,
                            OperationState state) throws IOException {
        Deque<DeleteTask> pending = new ArrayDeque<>();
        pending.push(new DeleteTask(root, false));
        while (!pending.isEmpty()) {
            DeleteTask task = pending.pop();
            if (task.expanded) {
                provider.delete(task.node.entry);
                notifyProgress(state, task.node.entry.getName());
                continue;
            }

            if (task.node.entry.isDirectory()) {
                pending.push(new DeleteTask(task.node, true));
                for (int i = task.node.children.size() - 1; i >= 0; i--) {
                    pending.push(new DeleteTask(task.node.children.get(i), false));
                }
            } else {
                provider.delete(task.node.entry);
                notifyProgress(state, task.node.entry.getName());
            }
        }
    }

    @Nullable
    private IOException rollbackCreatedEntries(StorageProvider provider,
                                               List<StorageEntry> createdEntries,
                                               OperationState state) {
        IOException firstFailure = null;
        for (int i = createdEntries.size() - 1; i >= 0; i--) {
            StorageEntry entry = createdEntries.get(i);
            try {
                provider.delete(entry);
            } catch (IOException | RuntimeException e) {
                IOException wrapped = e instanceof IOException
                    ? (IOException) e : new IOException("Could not remove temporary entry "
                        + entry.getName(), e);
                if (firstFailure == null) firstFailure = wrapped;
                else firstFailure.addSuppressed(wrapped);
            }
        }
        return firstFailure;
    }

    private StorageEntry refreshEntry(StorageProvider provider,
                                      StorageEntry parent,
                                      StorageEntry entry) {
        try {
            for (StorageEntry child : provider.list(parent)) {
                if (child.getDocumentId().equals(entry.getDocumentId())) return child;
            }
        } catch (IOException | RuntimeException ignored) {
            // The original entry remains a valid handle even when metadata cannot be refreshed.
        }
        return entry;
    }

    private void validateReadable(StorageProvider provider, StorageEntry entry) throws IOException {
        validateProviderOwnership(provider, entry, "source entry");
        if (!entry.hasCapability(StorageCapability.READ)) {
            throw new IOException("Source is not readable: " + entry.getName());
        }
        if (entry.isDirectory() && !entry.hasCapability(StorageCapability.LIST)) {
            throw new IOException("Source directory cannot be listed: " + entry.getName());
        }
    }

    private void validateDestination(StorageEntry destinationDirectory) throws IOException {
        if (!destinationDirectory.isDirectory()) {
            throw new IOException("Destination is not a directory");
        }
        if (!destinationDirectory.hasCapability(StorageCapability.CREATE_CHILD)) {
            throw new IOException("Destination does not allow creating files or folders");
        }
    }

    private static void validateProviderOwnership(StorageProvider provider,
                                                  StorageEntry entry,
                                                  String role) throws IOException {
        if (!provider.getId().equals(entry.getProviderId())) {
            throw new IOException("The " + role + " belongs to a different storage provider");
        }
    }

    private static void validateAvailable(StorageProvider provider, String role) throws IOException {
        if (!provider.isAvailable()) throw new IOException("The " + role + " storage is unavailable");
    }


    /** Local providers expose canonical absolute paths; SAF document IDs are comparable only
     * when both mounts came from the same DocumentsProvider authority. */
    private static boolean isAppPrivateDestinationInsideSource(StorageProvider sourceProvider,
                                                                StorageEntry source,
                                                                StorageProvider destinationProvider,
                                                                StorageEntry destinationDirectory) {
        if (!source.isDirectory()
            || !(sourceProvider instanceof LocalFileStorageProvider)
            || !(destinationProvider instanceof LocalFileStorageProvider)) {
            return false;
        }
        File sourceFile = new File(source.getDocumentId());
        File destinationFile = new File(destinationDirectory.getDocumentId());
        if (!sourceFile.isAbsolute() || !destinationFile.isAbsolute()) return false;
        String sourcePath = sourceFile.getAbsolutePath();
        String destinationPath = destinationFile.getAbsolutePath();
        return destinationPath.equals(sourcePath)
            || destinationPath.startsWith(sourcePath + File.separator);
    }

    private static boolean haveComparableDocumentIds(StorageProvider sourceProvider,
                                                       StorageProvider destinationProvider) {
        if (sourceProvider.getId().equals(destinationProvider.getId())) return true;
        if (sourceProvider.getLocation().getType() == StorageLocation.Type.SAF_TREE
            && destinationProvider.getLocation().getType() == StorageLocation.Type.SAF_TREE) {
            String sourceAuthority = sourceProvider.getLocation().getDescription();
            String destinationAuthority = destinationProvider.getLocation().getDescription();
            return sourceAuthority != null && sourceAuthority.equals(destinationAuthority);
        }
        return false;
    }

    private static boolean sameEntry(StorageProvider firstProvider,
                                     StorageEntry first,
                                     StorageProvider secondProvider,
                                     StorageEntry second) {
        return firstProvider.getId().equals(secondProvider.getId())
            && first.getDocumentId().equals(second.getDocumentId());
    }

    private static void validateName(String name) throws IOException {
        if (name == null || name.trim().isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw new IOException("A valid destination name is required");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == '\0' || Character.isISOControl(c)) {
                throw new IOException("The destination name contains an invalid character");
            }
        }
    }

    private static void checkCancelled(TransferOptions options) throws TransferCancelledException {
        if (isCancellationRequested(options)) throw new TransferCancelledException();
    }

    private static boolean isCancellationRequested(TransferOptions options) {
        try {
            return options.getCancellationToken().isCancellationRequested();
        } catch (RuntimeException ignored) {
            // A faulty cancellation callback must not corrupt an in-progress transfer.
            return false;
        }
    }

    private static void notifyProgress(OperationState state, String currentName) {
        TransferOptions.ProgressListener listener = state.options.getProgressListener();
        if (listener == null) return;
        TransferProgress progress = new TransferProgress(
            state.phase,
            currentName,
            state.bytesCopied,
            state.planBytes,
            state.filesCopied,
            state.directoriesCopied,
            state.planFiles,
            state.planDirectories
        );
        try {
            listener.onProgress(progress);
        } catch (RuntimeException ignored) {
            // UI callback errors should not interrupt storage I/O or leave half-written files.
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName() : message;
    }

    private static final class PlanNode {
        final StorageEntry entry;
        final List<PlanNode> children = new ArrayList<>();
        byte[] sha256;

        PlanNode(StorageEntry entry) {
            this.entry = entry;
        }
    }

    private static final class CopyTask {
        final PlanNode sourceNode;
        final StorageEntry destinationParent;
        final String requestedName;
        final boolean isRoot;

        CopyTask(PlanNode sourceNode,
                 StorageEntry destinationParent,
                 String requestedName,
                 boolean isRoot) {
            this.sourceNode = sourceNode;
            this.destinationParent = destinationParent;
            this.requestedName = requestedName;
            this.isRoot = isRoot;
        }
    }

    private static final class DeleteTask {
        final PlanNode node;
        final boolean expanded;

        DeleteTask(PlanNode node, boolean expanded) {
            this.node = node;
            this.expanded = expanded;
        }
    }

    private static final class OperationState {
        final TransferOptions options;
        final StorageProvider sourceProvider;
        final StorageProvider destinationProvider;
        final List<StorageEntry> createdEntries = new ArrayList<>();
        final NameAllocator nameAllocator;
        TransferProgress.Phase phase = TransferProgress.Phase.PREPARING;
        StorageEntry destinationRoot;
        long bytesCopied;
        long planBytes = UNKNOWN_TOTAL_BYTES;
        int filesCopied;
        int directoriesCopied;
        int planFiles;
        int planDirectories;

        OperationState(TransferOptions options,
                       StorageProvider sourceProvider,
                       StorageProvider destinationProvider) {
            this.options = options;
            this.sourceProvider = sourceProvider;
            this.destinationProvider = destinationProvider;
            this.nameAllocator = new NameAllocator(destinationProvider);
        }
    }

    private static final class NameAllocator {
        private final StorageProvider provider;
        private final Map<String, Set<String>> namesByDirectory = new HashMap<>();

        NameAllocator(StorageProvider provider) {
            this.provider = provider;
        }

        String allocate(StorageEntry parent,
                        String requestedName,
                        TransferOptions.ConflictPolicy policy) throws IOException {
            validateName(requestedName);
            Set<String> names = getNames(parent);
            if (!containsIgnoreCase(names, requestedName)) return requestedName;
            if (policy == TransferOptions.ConflictPolicy.FAIL) {
                throw new IOException("An entry named '" + requestedName + "' already exists");
            }

            String candidate = makeCopyName(requestedName, 1);
            int suffix = 2;
            while (containsIgnoreCase(names, candidate)) {
                if (suffix > 10_000) throw new IOException("Could not generate a unique destination name");
                candidate = makeCopyName(requestedName, suffix++);
            }
            return candidate;
        }

        void record(StorageEntry parent, String actualName) throws IOException {
            getNames(parent).add(actualName.toLowerCase(Locale.ROOT));
        }

        private Set<String> getNames(StorageEntry parent) throws IOException {
            String key = parent.getProviderId() + '\u0000' + parent.getDocumentId();
            Set<String> existing = namesByDirectory.get(key);
            if (existing != null) return existing;

            Set<String> names = new HashSet<>();
            for (StorageEntry entry : provider.list(parent)) {
                names.add(entry.getName().toLowerCase(Locale.ROOT));
            }
            namesByDirectory.put(key, names);
            return names;
        }

        private static boolean containsIgnoreCase(Set<String> names, String candidate) {
            return names.contains(candidate.toLowerCase(Locale.ROOT));
        }

        private static String makeCopyName(String original, int number) {
            int dot = original.lastIndexOf('.');
            String base = dot > 0 ? original.substring(0, dot) : original;
            String extension = dot > 0 ? original.substring(dot) : "";
            String suffix = number == 1 ? " (copy)" : " (copy " + number + ")";
            return base + suffix + extension;
        }
    }

    private static final class TransferCancelledException extends IOException {
        private static final long serialVersionUID = 1L;

        TransferCancelledException() {
            super("Transfer cancelled");
        }
    }
}
