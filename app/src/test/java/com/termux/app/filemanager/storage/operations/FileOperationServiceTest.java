package com.termux.app.filemanager.storage.operations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.termux.app.filemanager.storage.model.StorageCapability;
import com.termux.app.filemanager.storage.model.StorageEntry;
import com.termux.app.filemanager.storage.model.StorageLocation;
import com.termux.app.filemanager.storage.model.StorageProvider;
import com.termux.app.filemanager.storage.selection.StorageSelection;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class FileOperationServiceTest {

    @Test
    public void createsFilesAndDirectoriesAndRenamesEntries() throws Exception {
        MemoryProvider provider = new MemoryProvider("local");
        FileOperationService service = service(provider);

        StorageEntry folder = service.createDirectory("local", provider.getRootEntry(), "project");
        StorageEntry file = service.createFile("local", folder, "main.py", "text/x-python");
        StorageEntry renamed = service.rename("local", file, "app.py");

        assertEquals("project", folder.getName());
        assertEquals("app.py", renamed.getName());
        assertTrue(provider.exists(renamed));
        assertEquals("text/x-python", renamed.getMimeType());
    }

    @Test
    public void infersMimeTypeForCommonSourceExtensions() throws Exception {
        MemoryProvider provider = new MemoryProvider("local");
        StorageEntry file = service(provider).createFile(
            "local", provider.getRootEntry(), "main.py"
        );
        assertEquals("text/x-python", file.getMimeType());
    }

    @Test
    public void rejectsDuplicateAndInvalidNamesBeforeCreating() throws Exception {
        MemoryProvider provider = new MemoryProvider("local");
        FileOperationService service = service(provider);
        StorageEntry root = provider.getRootEntry();
        service.createFile("local", root, "main.py", "text/x-python");

        try {
            service.createFile("local", root, "MAIN.py", "text/x-python");
            throw new AssertionError("Expected duplicate-name rejection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("already exists"));
        }

        try {
            service.createDirectory("local", root, "../outside");
            throw new AssertionError("Expected invalid-name rejection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("invalid")
                || expected.getMessage().contains("Invalid"));
        }
    }

    @Test
    public void recursivelyDeletesDirectoryBottomUp() throws Exception {
        MemoryProvider provider = new MemoryProvider("local");
        FileOperationService service = service(provider);
        StorageEntry project = provider.createDirectory(provider.getRootEntry(), "project");
        StorageEntry src = provider.createDirectory(project, "src");
        StorageEntry main = provider.putFile(src, "main.py", new byte[] {1, 2, 3});
        provider.putFile(project, "README.md", new byte[] {4, 5});

        FileOperationResult result = service.delete("local", project, null);

        assertEquals(FileOperationResult.Status.SUCCESS, result.getStatus());
        assertEquals(4, result.getDeletedEntries());
        assertFalse(provider.exists(project));
        assertFalse(provider.exists(src));
        assertFalse(provider.exists(main));
    }

    @Test
    public void bulkDeleteSupportsMultipleProvidersAndOverlappingSelection() throws Exception {
        MemoryProvider first = new MemoryProvider("first");
        MemoryProvider second = new MemoryProvider("second");
        StorageEntry folder = first.createDirectory(first.getRootEntry(), "folder");
        StorageEntry child = first.putFile(folder, "child.txt", new byte[] {1});
        StorageEntry other = second.putFile(second.getRootEntry(), "other.txt", new byte[] {2});

        StorageSelection selection = new StorageSelection();
        selection.add(child);
        selection.add(folder); // Parent also contains the selected child.
        selection.add(other);

        FileOperationResult result = new FileOperationService(id -> {
            if ("first".equals(id)) return first;
            if ("second".equals(id)) return second;
            return null;
        }).deleteSelected(selection, null);

        assertEquals(FileOperationResult.Status.SUCCESS, result.getStatus());
        assertEquals(3, result.getDeletedEntries());
        assertFalse(first.exists(folder));
        assertFalse(first.exists(child));
        assertFalse(second.exists(other));
    }

    @Test
    public void nonRecursiveDeleteRefusesNonEmptyDirectory() throws Exception {
        MemoryProvider provider = new MemoryProvider("local");
        StorageEntry folder = provider.createDirectory(provider.getRootEntry(), "folder");
        provider.putFile(folder, "child.txt", new byte[] {1});

        FileOperationResult result = service(provider).delete("local", folder,
            FileOperationOptions.builder().recursiveDelete(false).build());

        assertEquals(FileOperationResult.Status.FAILED, result.getStatus());
        assertTrue(provider.exists(folder));
    }

    @Test
    public void refusesToDeleteStorageRoot() throws Exception {
        MemoryProvider provider = new MemoryProvider("local");
        FileOperationResult result = service(provider).delete("local", provider.getRootEntry(), null);

        assertEquals(FileOperationResult.Status.FAILED, result.getStatus());
        assertTrue(provider.exists(provider.getRootEntry()));
    }

    @Test
    public void enforcesDeleteEntrySafetyLimitBeforeMutation() throws Exception {
        MemoryProvider provider = new MemoryProvider("local");
        StorageEntry folder = provider.createDirectory(provider.getRootEntry(), "folder");
        provider.putFile(folder, "one.txt", new byte[] {1});
        provider.putFile(folder, "two.txt", new byte[] {2});

        FileOperationResult result = service(provider).delete("local", folder,
            FileOperationOptions.builder().maxEntries(2).build());

        assertEquals(FileOperationResult.Status.FAILED, result.getStatus());
        assertTrue(provider.exists(folder));
        assertEquals(2, provider.list(folder).size());
    }

    @Test
    public void cancellationDuringPreflightDoesNotDeleteAnything() throws Exception {
        MemoryProvider provider = new MemoryProvider("local");
        StorageEntry folder = provider.createDirectory(provider.getRootEntry(), "folder");
        provider.putFile(folder, "one.txt", new byte[] {1});
        AtomicBoolean cancelled = new AtomicBoolean(false);

        FileOperationResult result = service(provider).delete("local", folder,
            FileOperationOptions.builder()
                .cancellationToken(cancelled::get)
                .progressListener(progress -> {
                    if (progress.getPhase() == FileOperationProgress.Phase.PREPARING) {
                        cancelled.set(true);
                    }
                })
                .build());

        assertEquals(FileOperationResult.Status.CANCELLED, result.getStatus());
        assertTrue(provider.exists(folder));
    }

    @Test
    public void selectionUsesProviderAndDocumentIdTogether() throws Exception {
        MemoryProvider first = new MemoryProvider("first");
        MemoryProvider second = new MemoryProvider("second");
        StorageEntry firstEntry = first.putFile(first.getRootEntry(), "a.txt", new byte[] {1});
        StorageEntry secondEntry = second.putFile(second.getRootEntry(), "a.txt", new byte[] {2});
        StorageSelection selection = new StorageSelection();

        assertTrue(selection.add(firstEntry));
        assertTrue(selection.add(secondEntry));
        assertEquals(2, selection.size());
        assertTrue(selection.contains(firstEntry));
        assertTrue(selection.contains(secondEntry));
        assertFalse(selection.add(firstEntry));
        selection.clear();
        assertTrue(selection.isEmpty());
    }

    private static FileOperationService service(MemoryProvider provider) {
        return new FileOperationService(id -> provider.getId().equals(id) ? provider : null);
    }

    private static final class MemoryProvider implements StorageProvider {
        private static final String DIRECTORY_MIME = "vnd.android.document/directory";
        private final String id;
        private final StorageLocation location;
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private int nextId = 1;

        MemoryProvider(String id) {
            this.id = id;
            location = new StorageLocation(id, id, null, StorageLocation.Type.APP_PRIVATE, false);
            nodes.put("root", new Node("root", null, id, true, new byte[0]));
        }

        @NonNull @Override public String getId() { return id; }
        @NonNull @Override public StorageLocation getLocation() { return location; }
        @Override public boolean isAvailable() { return true; }
        @NonNull @Override public StorageEntry getRootEntry() { return toEntry(nodes.get("root")); }

        @NonNull
        @Override
        public List<StorageEntry> list(@NonNull StorageEntry directory) throws IOException {
            Node parent = require(directory);
            if (!parent.directory) throw new IOException("Not a directory");
            List<StorageEntry> result = new ArrayList<>();
            for (Node node : nodes.values()) {
                if (directory.getDocumentId().equals(node.parentId)) result.add(toEntry(node));
            }
            return result;
        }

        @NonNull @Override
        public InputStream openRead(@NonNull StorageEntry file) throws IOException {
            Node node = require(file);
            if (node.directory) throw new IOException("Is a directory");
            return new ByteArrayInputStream(node.data);
        }

        @NonNull @Override
        public OutputStream openWrite(@NonNull StorageEntry file, boolean append) throws IOException {
            Node node = require(file);
            if (node.directory) throw new IOException("Is a directory");
            byte[] prefix = append ? node.data : new byte[0];
            return new ByteArrayOutputStream() {
                { if (append) write(prefix, 0, prefix.length); }
                @Override public void close() throws IOException {
                    super.close();
                    node.data = toByteArray();
                }
            };
        }

        @NonNull @Override
        public StorageEntry createFile(@NonNull StorageEntry parent,
                                       @NonNull String name,
                                       @NonNull String mimeType) throws IOException {
            Node parentNode = require(parent);
            ensureUnique(parentNode, name);
            Node node = new Node("n" + nextId++, parentNode.id, name, false, new byte[0]);
            node.mimeType = mimeType;
            nodes.put(node.id, node);
            return toEntry(node);
        }

        @NonNull @Override
        public StorageEntry createDirectory(@NonNull StorageEntry parent,
                                            @NonNull String name) throws IOException {
            Node parentNode = require(parent);
            ensureUnique(parentNode, name);
            Node node = new Node("n" + nextId++, parentNode.id, name, true, new byte[0]);
            nodes.put(node.id, node);
            return toEntry(node);
        }

        @NonNull @Override
        public StorageEntry rename(@NonNull StorageEntry entry, @NonNull String newName) throws IOException {
            Node node = require(entry);
            Node parent = nodes.get(node.parentId);
            ensureUnique(parent, newName);
            node.name = newName;
            return toEntry(node);
        }

        @Override
        public void delete(@NonNull StorageEntry entry) throws IOException {
            Node node = require(entry);
            if ("root".equals(node.id)) throw new IOException("Cannot delete root");
            if (node.directory) {
                for (Node candidate : nodes.values()) {
                    if (node.id.equals(candidate.parentId)) throw new IOException("Directory not empty");
                }
            }
            nodes.remove(node.id);
        }

        @Override
        public boolean exists(@NonNull StorageEntry entry) {
            return id.equals(entry.getProviderId()) && nodes.containsKey(entry.getDocumentId());
        }

        StorageEntry putFile(StorageEntry parent, String name, byte[] contents) throws IOException {
            StorageEntry file = createFile(parent, name, "application/octet-stream");
            Node node = require(file);
            node.data = contents.clone();
            return toEntry(node);
        }

        private Node require(StorageEntry entry) throws IOException {
            if (!id.equals(entry.getProviderId())) throw new IOException("Wrong provider");
            Node node = nodes.get(entry.getDocumentId());
            if (node == null) throw new IOException("Missing entry");
            return node;
        }

        private void ensureUnique(Node parent, String name) throws IOException {
            for (Node node : nodes.values()) {
                if (parent.id.equals(node.parentId) && node.name.equalsIgnoreCase(name)) {
                    throw new IOException("Duplicate name");
                }
            }
        }

        private StorageEntry toEntry(Node node) {
            int caps = StorageCapability.READ | StorageCapability.RENAME | StorageCapability.DELETE;
            if (node.directory) caps |= StorageCapability.LIST | StorageCapability.CREATE_CHILD;
            else caps |= StorageCapability.WRITE;
            if (node.parentId == null) caps = StorageCapability.READ | StorageCapability.LIST
                | StorageCapability.CREATE_CHILD;
            return new StorageEntry(id, node.id, node.parentId, node.name,
                node.directory ? DIRECTORY_MIME : node.mimeType, node.directory,
                node.data.length, 0L, caps);
        }

        private static final class Node {
            final String id;
            final String parentId;
            String name;
            final boolean directory;
            byte[] data;
            String mimeType = "application/octet-stream";
            Node(String id, String parentId, String name, boolean directory, byte[] data) {
                this.id = id;
                this.parentId = parentId;
                this.name = name;
                this.directory = directory;
                this.data = data;
            }
        }
    }
}
