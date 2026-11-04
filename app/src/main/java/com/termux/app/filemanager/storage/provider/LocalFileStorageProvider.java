package com.termux.app.filemanager.storage.provider;

import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;

import com.termux.app.filemanager.storage.model.StorageCapability;
import com.termux.app.filemanager.storage.model.StorageEntry;
import com.termux.app.filemanager.storage.model.StorageLocation;
import com.termux.app.filemanager.storage.model.StorageProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Storage provider for a directory inside Yummy's own private app data directory. */
public final class LocalFileStorageProvider implements StorageProvider {
    private final String id;
    private final StorageLocation location;
    private final File root;
    private final String canonicalRoot;

    public LocalFileStorageProvider(@NonNull String id,
                                    @NonNull String name,
                                    @NonNull String description,
                                    @NonNull File root) throws IOException {
        this.id = id;
        this.location = new StorageLocation(
            id, name, description, StorageLocation.Type.APP_PRIVATE, false
        );
        if (!root.exists() && !root.mkdirs() && !root.isDirectory()) {
            throw new IOException("Cannot create storage root: " + root);
        }
        if (!root.isDirectory()) {
            throw new IOException("Storage root is not a directory: " + root);
        }
        this.root = root.getCanonicalFile();
        this.canonicalRoot = this.root.getPath();
    }

    @NonNull @Override public String getId() { return id; }
    @NonNull @Override public StorageLocation getLocation() { return location; }
    @Override public boolean isAvailable() { return root.isDirectory() && root.canRead(); }

    @NonNull
    @Override
    public StorageEntry getRootEntry() throws IOException {
        return toEntry(root, null, location.getName());
    }

    @NonNull
    @Override
    public List<StorageEntry> list(@NonNull StorageEntry directory) throws IOException {
        File parent = resolve(directory);
        if (!parent.isDirectory()) throw new IOException("Not a directory: " + directory.getName());
        File[] children = parent.listFiles();
        if (children == null) throw new IOException("Cannot list directory: " + parent.getPath());

        List<StorageEntry> result = new ArrayList<>(children.length);
        for (File child : children) {
            try {
                File safeChild = resolvePath(child.getPath());
                result.add(toEntry(safeChild, parent.getPath(), safeChild.getName()));
            } catch (IOException ignored) {
                // A symlink that resolves outside this provider's root is not exposed.
            }
        }
        Collections.sort(result, Comparator.comparing(StorageEntry::getName,
            String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    @NonNull
    @Override
    public InputStream openRead(@NonNull StorageEntry file) throws IOException {
        File target = resolve(file);
        if (!target.isFile() || !target.canRead()) {
            throw new IOException("File is not readable: " + file.getName());
        }
        return new FileInputStream(target);
    }

    @NonNull
    @Override
    public OutputStream openWrite(@NonNull StorageEntry file, boolean append) throws IOException {
        File target = resolve(file);
        if (target.isDirectory()) throw new IOException("Cannot write to a directory");
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory() || !parent.canWrite()) {
            throw new IOException("Parent directory is not writable");
        }
        return new FileOutputStream(target, append);
    }

    @NonNull
    @Override
    public StorageEntry createFile(@NonNull StorageEntry parent,
                                   @NonNull String name,
                                   @NonNull String mimeType) throws IOException {
        validateName(name);
        File parentFile = resolve(parent);
        requireDirectory(parentFile);
        if (!parentFile.canWrite()) throw new IOException("Directory is not writable");
        File target = resolvePath(new File(parentFile, name).getPath());
        if (target.exists()) throw new IOException("An entry with that name already exists");
        if (!target.createNewFile()) throw new IOException("Could not create file: " + name);
        // Keep a caller-supplied MIME type for extensionless files.
        return toEntry(target, parentFile.getPath(), target.getName(), mimeType);
    }

    @NonNull
    @Override
    public StorageEntry createDirectory(@NonNull StorageEntry parent,
                                        @NonNull String name) throws IOException {
        validateName(name);
        File parentFile = resolve(parent);
        requireDirectory(parentFile);
        if (!parentFile.canWrite()) throw new IOException("Directory is not writable");
        File target = resolvePath(new File(parentFile, name).getPath());
        if (target.exists()) throw new IOException("An entry with that name already exists");
        if (!target.mkdir()) throw new IOException("Could not create directory: " + name);
        return toEntry(target, parentFile.getPath(), target.getName());
    }

    @NonNull
    @Override
    public StorageEntry rename(@NonNull StorageEntry entry,
                               @NonNull String newName) throws IOException {
        validateName(newName);
        File source = resolve(entry);
        if (source.equals(root)) throw new IOException("The storage root cannot be renamed");
        File parent = source.getParentFile();
        if (parent == null || !parent.canWrite()) throw new IOException("Parent directory is not writable");
        File destination = resolvePath(new File(parent, newName).getPath());
        if (destination.exists()) throw new IOException("An entry with that name already exists");
        if (!source.renameTo(destination)) throw new IOException("Could not rename: " + entry.getName());
        return toEntry(destination, parent.getPath(), destination.getName());
    }

    @Override
    public void delete(@NonNull StorageEntry entry) throws IOException {
        File target = resolve(entry);
        if (target.equals(root)) throw new IOException("The storage root cannot be deleted");
        if (!target.delete()) {
            throw new IOException("Could not delete: " + target.getPath()
                + " (the directory may not be empty or may be protected)");
        }
    }

    @Override
    public boolean exists(@NonNull StorageEntry entry) {
        try {
            return resolve(entry).exists();
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private StorageEntry toEntry(File file, String parentId, String displayName) throws IOException {
        return toEntry(file, parentId, displayName, null);
    }

    private StorageEntry toEntry(File file,
                                 String parentId,
                                 String displayName,
                                 String mimeOverride) throws IOException {
        boolean directory = file.isDirectory();
        int capabilities = 0;
        if (file.canRead()) capabilities |= StorageCapability.READ;
        if (directory && file.canRead()) capabilities |= StorageCapability.LIST;
        if (directory && file.canWrite()) capabilities |= StorageCapability.CREATE_CHILD;

        File parent = file.getParentFile();
        if (!file.equals(root) && parent != null && parent.canWrite()) {
            capabilities |= StorageCapability.RENAME | StorageCapability.DELETE;
        }
        if (!directory && file.canWrite()) capabilities |= StorageCapability.WRITE;

        return new StorageEntry(
            id,
            file.getCanonicalPath(),
            parentId,
            displayName,
            directory ? DocumentsContract.Document.MIME_TYPE_DIR
                : (mimeOverride == null || mimeOverride.trim().isEmpty()
                    ? getMimeType(file) : mimeOverride),
            directory,
            directory ? 0L : file.length(),
            file.lastModified(),
            capabilities
        );
    }

    private File resolve(StorageEntry entry) throws IOException {
        if (!id.equals(entry.getProviderId())) throw new IOException("Entry belongs to another provider");
        File file = resolvePath(entry.getDocumentId());
        if (!file.exists()) throw new IOException("Entry no longer exists: " + entry.getName());
        return file;
    }

    private File resolvePath(String path) throws IOException {
        File absolute = new File(path).getAbsoluteFile();
        File file = absolute.getCanonicalFile();
        // Do not follow symlinks. This keeps listing and destructive operations inside the root.
        if (!file.getPath().equals(absolute.getPath())) {
            throw new IOException("Symbolic links are not supported by this storage provider");
        }
        String resolved = file.getPath();
        if (!resolved.equals(canonicalRoot)
            && !resolved.startsWith(canonicalRoot + File.separator)) {
            throw new IOException("Path escapes this storage location");
        }
        return file;
    }

    private static void requireDirectory(File file) throws IOException {
        if (!file.isDirectory()) throw new IOException("Parent is not a directory");
    }

    private static void validateName(String name) throws IOException {
        if (name == null || name.trim().isEmpty()
            || ".".equals(name) || "..".equals(name)
            || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
            || name.indexOf(0) >= 0) {
            throw new IOException("Invalid file name");
        }
    }

    private static String getMimeType(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String extension = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }
}
