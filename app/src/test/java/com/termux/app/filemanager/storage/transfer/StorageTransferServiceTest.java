package com.termux.app.filemanager.storage.transfer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;

import com.termux.app.filemanager.storage.model.StorageCapability;
import com.termux.app.filemanager.storage.model.StorageEntry;
import com.termux.app.filemanager.storage.model.StorageLocation;
import com.termux.app.filemanager.storage.model.StorageProvider;

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

public class StorageTransferServiceTest {

    @Test
    public void copiesDirectoryBetweenDifferentProviders() throws Exception {
        MemoryProvider source = new MemoryProvider("source");
        MemoryProvider destination = new MemoryProvider("destination");
        StorageEntry project = source.createDirectory(source.getRootEntry(), "project");
        StorageEntry src = source.createDirectory(project, "src");
        source.putFile(src, "Main.java", new byte[] {1, 2, 3, 4});
        source.putFile(project, "README.md", "hello".getBytes("UTF-8"));

        TransferResult result = new StorageTransferService().transfer(
            source, project, destination, destination.getRootEntry(), null,
            TransferOptions.builder(TransferOptions.Operation.COPY).build()
        );

        assertEquals(TransferResult.Status.SUCCESS, result.getStatus());
        assertNotNull(result.getDestinationEntry());
        StorageEntry copiedProject = result.getDestinationEntry();
        StorageEntry copiedSrc = destination.findChild(copiedProject, "src");
        assertArrayEquals(new byte[] {1, 2, 3, 4},
            destination.readBytes(destination.findChild(copiedSrc, "Main.java")));
        assertArrayEquals("hello".getBytes("UTF-8"),
            destination.readBytes(destination.findChild(copiedProject, "README.md")));
        assertTrue(source.exists(project));
    }

    @Test
    public void moveDeletesSourceOnlyAfterSuccessfulCopy() throws Exception {
        MemoryProvider source = new MemoryProvider("source");
        MemoryProvider destination = new MemoryProvider("destination");
        StorageEntry folder = source.createDirectory(source.getRootEntry(), "notes");
        source.putFile(folder, "note.txt", "data".getBytes("UTF-8"));

        TransferResult result = new StorageTransferService().transfer(
            source, folder, destination, destination.getRootEntry(), null,
            TransferOptions.builder(TransferOptions.Operation.MOVE).build()
        );

        assertEquals(TransferResult.Status.SUCCESS, result.getStatus());
        assertFalse(source.exists(folder));
        assertNotNull(destination.findChild(destination.getRootEntry(), "notes"));
    }

    @Test
    public void keepBothCreatesDeterministicName() throws Exception {
        MemoryProvider source = new MemoryProvider("source");
        MemoryProvider destination = new MemoryProvider("destination");
        StorageEntry sourceFile = source.putFile(source.getRootEntry(), "main.py", new byte[] {7});
        destination.putFile(destination.getRootEntry(), "main.py", new byte[] {1});

        TransferResult result = new StorageTransferService().transfer(
            source, sourceFile, destination, destination.getRootEntry(), null,
            TransferOptions.builder(TransferOptions.Operation.COPY)
                .conflictPolicy(TransferOptions.ConflictPolicy.KEEP_BOTH)
                .build()
        );

        assertEquals(TransferResult.Status.SUCCESS, result.getStatus());
        assertNotNull(destination.findChild(destination.getRootEntry(), "main (copy).py"));
    }

    @Test
    public void moveRetainsSourceWhenFileChangesAfterItWasCopied() throws Exception {
        MemoryProvider source = new MemoryProvider("source");
        MemoryProvider destination = new MemoryProvider("destination");
        StorageEntry file = source.putFile(source.getRootEntry(), "config.json", new byte[] {1, 2, 3});
        AtomicBoolean changed = new AtomicBoolean(false);

        TransferResult result = new StorageTransferService().transfer(
            source, file, destination, destination.getRootEntry(), null,
            TransferOptions.builder(TransferOptions.Operation.MOVE)
                .progressListener(progress -> {
                    if (progress.getPhase() == TransferProgress.Phase.COPYING
                        && progress.getBytesCopied() > 0
                        && changed.compareAndSet(false, true)) {
                        source.replaceContents(file, new byte[] {9, 9, 9});
                    }
                })
                .build()
        );

        assertEquals(TransferResult.Status.SOURCE_RETAINED, result.getStatus());
        assertTrue(source.exists(file));
        assertNotNull(destination.findChild(destination.getRootEntry(), "config.json"));
        assertArrayEquals(new byte[] {9, 9, 9}, source.readBytes(file));
        assertArrayEquals(new byte[] {1, 2, 3},
            destination.readBytes(destination.findChild(destination.getRootEntry(), "config.json")));
    }

    @Test
    public void rejectsCopyingDirectoryIntoItselfOrDescendant() throws Exception {
        MemoryProvider provider = new MemoryProvider("local");
        StorageEntry project = provider.createDirectory(provider.getRootEntry(), "project");
        StorageEntry nested = provider.createDirectory(project, "nested");

        try {
            new StorageTransferService().transfer(
                provider, project, provider, nested, "project-copy",
                TransferOptions.builder(TransferOptions.Operation.COPY).build()
            );
            fail("Expected a directory-descendant validation error");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("descendants"));
        }
    }

    @Test
    public void cancellationRollsBackPartialDestination() throws Exception {
        MemoryProvider source = new MemoryProvider("source");
        MemoryProvider destination = new MemoryProvider("destination");
        byte[] payload = new byte[150_000];
        StorageEntry file = source.putFile(source.getRootEntry(), "large.bin", payload);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        TransferResult result = new StorageTransferService().transfer(
            source, file, destination, destination.getRootEntry(), null,
            TransferOptions.builder(TransferOptions.Operation.COPY)
                .bufferSize(1024)
                .cancellationToken(cancelled::get)
                .progressListener(progress -> {
                    if (progress.getBytesCopied() > 0) cancelled.set(true);
                })
                .build()
        );

        assertEquals(TransferResult.Status.CANCELLED, result.getStatus());
        assertNull(result.getDestinationEntry());
        assertTrue(destination.list(destination.getRootEntry()).isEmpty());
        assertTrue(source.exists(file));
    }

    private static final class MemoryProvider implements StorageProvider {
        private static final String DIRECTORY_MIME = "vnd.android.document/directory";
        private final String id;
        private final StorageLocation location;
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private int nextId = 1;

        MemoryProvider(String id) {
            this.id = id;
            this.location = new StorageLocation(id, id, null,
                StorageLocation.Type.APP_PRIVATE, false);
            nodes.put("root", new Node("root", null, id, true, new byte[0]));
        }

        @NonNull @Override public String getId() { return id; }
        @NonNull @Override public StorageLocation getLocation() { return location; }
        @Override public boolean isAvailable() { return true; }

        @NonNull @Override
        public StorageEntry getRootEntry() {
            return toEntry(nodes.get("root"));
        }

        @NonNull @Override
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
                {
                    if (append) write(prefix, 0, prefix.length);
                }
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
            requireUnique(parentNode, name);
            String childId = "n" + nextId++;
            Node node = new Node(childId, parentNode.id, name, false, new byte[0]);
            node.mimeType = mimeType;
            nodes.put(childId, node);
            return toEntry(node);
        }

        @NonNull @Override
        public StorageEntry createDirectory(@NonNull StorageEntry parent,
                                             @NonNull String name) throws IOException {
            Node parentNode = require(parent);
            requireUnique(parentNode, name);
            String childId = "n" + nextId++;
            Node node = new Node(childId, parentNode.id, name, true, new byte[0]);
            nodes.put(childId, node);
            return toEntry(node);
        }

        @NonNull @Override
        public StorageEntry rename(@NonNull StorageEntry entry,
                                   @NonNull String newName) throws IOException {
            Node node = require(entry);
            Node parent = nodes.get(node.parentId);
            requireUnique(parent, newName);
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

        StorageEntry putFile(StorageEntry parent, String name, byte[] bytes) throws IOException {
            StorageEntry file = createFile(parent, name, "application/octet-stream");
            Node node = nodes.get(file.getDocumentId());
            node.data = bytes.clone();
            return toEntry(node);
        }

        StorageEntry findChild(StorageEntry parent, String name) throws IOException {
            for (StorageEntry child : list(parent)) {
                if (name.equals(child.getName())) return child;
            }
            return null;
        }

        byte[] readBytes(StorageEntry entry) throws IOException {
            Node node = require(entry);
            return node.data.clone();
        }

        void replaceContents(StorageEntry entry, byte[] bytes) {
            Node node = nodes.get(entry.getDocumentId());
            if (node == null) throw new IllegalStateException("Not found");
            node.data = bytes.clone();
        }

        private Node require(StorageEntry entry) throws IOException {
            if (!id.equals(entry.getProviderId())) throw new IOException("Wrong provider");
            Node node = nodes.get(entry.getDocumentId());
            if (node == null) throw new IOException("Not found");
            return node;
        }

        private void requireUnique(Node parent, String name) throws IOException {
            for (Node node : nodes.values()) {
                if (parent.id.equals(node.parentId) && name.equalsIgnoreCase(node.name)) {
                    throw new IOException("Already exists");
                }
            }
        }

        private StorageEntry toEntry(Node node) {
            int capabilities = StorageCapability.READ | StorageCapability.RENAME | StorageCapability.DELETE;
            if (node.directory) capabilities |= StorageCapability.LIST | StorageCapability.CREATE_CHILD;
            else capabilities |= StorageCapability.WRITE;
            return new StorageEntry(id, node.id, node.parentId, node.name,
                node.directory ? DIRECTORY_MIME : node.mimeType,
                node.directory, node.data.length, 1L, capabilities);
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
