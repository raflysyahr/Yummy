package com.termux.app.filemanager.storage.provider;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;

import com.termux.app.filemanager.storage.model.StorageCapability;
import com.termux.app.filemanager.storage.model.StorageEntry;
import com.termux.app.filemanager.storage.model.StorageLocation;
import com.termux.app.filemanager.storage.model.StorageProvider;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Storage provider backed by a user-granted Storage Access Framework tree URI. */
public final class SafTreeStorageProvider implements StorageProvider {
    private static final String[] DOCUMENT_PROJECTION = new String[] {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS
    };

    private final Context context;
    private final ContentResolver resolver;
    private final String id;
    private final StorageLocation location;
    private final Uri treeUri;
    private final String rootDocumentId;
    private final int grantedFlags;

    public SafTreeStorageProvider(@NonNull Context context,
                                  @NonNull String id,
                                  @NonNull String name,
                                  @NonNull Uri treeUri,
                                  int grantedFlags) {
        if (!DocumentsContract.isTreeUri(treeUri)) {
            throw new IllegalArgumentException("URI is not a Storage Access Framework tree URI");
        }
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.id = id;
        this.treeUri = treeUri;
        this.rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        this.grantedFlags = grantedFlags
            & (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        this.location = new StorageLocation(
            id,
            name,
            treeUri.getAuthority(),
            StorageLocation.Type.SAF_TREE,
            (this.grantedFlags & android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0
        );
    }

    @NonNull @Override public String getId() { return id; }
    @NonNull @Override public StorageLocation getLocation() { return location; }
    @NonNull public Uri getTreeUri() { return treeUri; }
    public int getGrantedFlags() { return grantedFlags; }

    @Override
    public boolean isAvailable() {
        if ((getPersistedGrantFlags() & android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            return false;
        }

        // A persisted grant can outlive a removable drive. Verify that its root is reachable too.
        Cursor cursor = null;
        try {
            cursor = resolver.query(buildDocumentUri(rootDocumentId),
                new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID },
                null, null, null);
            return cursor != null && cursor.moveToFirst();
        } catch (RuntimeException e) {
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    @NonNull
    @Override
    public StorageEntry getRootEntry() throws IOException {
        StorageEntry root = queryEntry(rootDocumentId, null, location.getName());
        return new StorageEntry(
            root.getProviderId(), root.getDocumentId(), null, location.getName(),
            root.getMimeType(), root.isDirectory(), root.getSize(), root.getLastModified(),
            root.getCapabilities()
        );
    }

    @NonNull
    @Override
    public List<StorageEntry> list(@NonNull StorageEntry directory) throws IOException {
        requireOwned(directory);
        if (!directory.isDirectory()) throw new IOException("Entry is not a directory");
        if (!directory.hasCapability(StorageCapability.LIST)) {
            throw new IOException("This location does not allow listing this directory");
        }

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, directory.getDocumentId()
        );
        List<StorageEntry> result = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = resolver.query(
                childrenUri,
                DOCUMENT_PROJECTION,
                null,
                null,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME + " COLLATE NOCASE ASC"
            );
            if (cursor == null) throw new IOException("Storage provider returned no file listing");
            int activeGrantFlags = getPersistedGrantFlags();
            while (cursor.moveToNext()) {
                result.add(readEntry(cursor, directory.getDocumentId(), null, activeGrantFlags));
            }
            return result;
        } catch (SecurityException e) {
            throw new IOException("Access to this storage location was revoked", e);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    @NonNull
    @Override
    public InputStream openRead(@NonNull StorageEntry file) throws IOException {
        requireOwned(file);
        if (file.isDirectory()) throw new IOException("Cannot open a directory as a file");
        if (!file.hasCapability(StorageCapability.READ)) throw new IOException("File is not readable");
        try {
            InputStream input = resolver.openInputStream(buildDocumentUri(file.getDocumentId()));
            if (input == null) throw new IOException("Storage provider returned an empty input stream");
            return input;
        } catch (SecurityException e) {
            throw new IOException("Read permission is no longer available", e);
        }
    }

    @NonNull
    @Override
    public OutputStream openWrite(@NonNull StorageEntry file, boolean append) throws IOException {
        requireOwned(file);
        if (file.isDirectory()) throw new IOException("Cannot write to a directory");
        if (!file.hasCapability(StorageCapability.WRITE)) throw new IOException("File is not writable");
        try {
            OutputStream output = resolver.openOutputStream(
                buildDocumentUri(file.getDocumentId()), append ? "wa" : "wt"
            );
            if (output == null) throw new IOException("Storage provider returned an empty output stream");
            return output;
        } catch (SecurityException e) {
            throw new IOException("Write permission is no longer available", e);
        }
    }

    @NonNull
    @Override
    public StorageEntry createFile(@NonNull StorageEntry parent,
                                   @NonNull String name,
                                   @NonNull String mimeType) throws IOException {
        validateName(name);
        requireOwned(parent);
        requireCreatableDirectory(parent);
        String safeMime = mimeType == null || mimeType.trim().isEmpty()
            ? "application/octet-stream" : mimeType;
        Uri created = createDocument(parent, safeMime, name);
        return queryEntry(DocumentsContract.getDocumentId(created), parent.getDocumentId(), name);
    }

    @NonNull
    @Override
    public StorageEntry createDirectory(@NonNull StorageEntry parent,
                                        @NonNull String name) throws IOException {
        validateName(name);
        requireOwned(parent);
        requireCreatableDirectory(parent);
        Uri created = createDocument(
            parent, DocumentsContract.Document.MIME_TYPE_DIR, name
        );
        return queryEntry(DocumentsContract.getDocumentId(created), parent.getDocumentId(), name);
    }

    @NonNull
    @Override
    public StorageEntry rename(@NonNull StorageEntry entry,
                               @NonNull String newName) throws IOException {
        validateName(newName);
        requireOwned(entry);
        if (!entry.hasCapability(StorageCapability.RENAME)) {
            throw new IOException("This storage provider does not support renaming this entry");
        }
        try {
            Uri renamed = DocumentsContract.renameDocument(
                resolver, buildDocumentUri(entry.getDocumentId()), newName
            );
            if (renamed == null) throw new IOException("Storage provider did not rename the entry");
            String newDocumentId = DocumentsContract.getDocumentId(renamed);
            return queryEntry(newDocumentId, entry.getParentDocumentId(), newName);
        } catch (SecurityException | UnsupportedOperationException e) {
            throw new IOException("Renaming is not supported by this storage provider", e);
        }
    }

    @Override
    public void delete(@NonNull StorageEntry entry) throws IOException {
        requireOwned(entry);
        if (entry.isRoot()) throw new IOException("The storage root cannot be deleted");
        if (!entry.hasCapability(StorageCapability.DELETE)) {
            throw new IOException("This storage provider does not allow deleting this entry");
        }
        try {
            if (!DocumentsContract.deleteDocument(resolver, buildDocumentUri(entry.getDocumentId()))) {
                throw new IOException("Storage provider refused to delete: " + entry.getName());
            }
        } catch (SecurityException | UnsupportedOperationException e) {
            throw new IOException("Deleting is not supported by this storage provider", e);
        }
    }

    @Override
    public boolean exists(@NonNull StorageEntry entry) {
        if (!id.equals(entry.getProviderId())) return false;
        try {
            queryEntry(entry.getDocumentId(), entry.getParentDocumentId(), entry.getName());
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }


    private int getPersistedGrantFlags() {
        for (android.content.UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (!treeUri.equals(permission.getUri())) continue;
            int flags = 0;
            if (permission.isReadPermission()) {
                flags |= android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
            }
            if (permission.isWritePermission()) {
                flags |= android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            }
            return flags;
        }
        return 0;
    }

    private Uri createDocument(StorageEntry parent, String mimeType, String name) throws IOException {
        try {
            Uri created = DocumentsContract.createDocument(
                resolver, buildDocumentUri(parent.getDocumentId()), mimeType, name
            );
            if (created == null) throw new IOException("Storage provider could not create the entry");
            return created;
        } catch (SecurityException | UnsupportedOperationException e) {
            throw new IOException("Creating entries is not supported by this storage provider", e);
        }
    }

    private void requireCreatableDirectory(StorageEntry parent) throws IOException {
        if (!parent.isDirectory()) throw new IOException("Parent is not a directory");
        if (!parent.hasCapability(StorageCapability.CREATE_CHILD)) {
            throw new IOException("This directory does not allow creating entries");
        }
    }

    private void requireOwned(StorageEntry entry) throws IOException {
        if (!id.equals(entry.getProviderId())) throw new IOException("Entry belongs to another provider");
    }

    private Uri buildDocumentUri(String documentId) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
    }

    private StorageEntry queryEntry(String documentId,
                                    String parentDocumentId,
                                    String fallbackName) throws IOException {
        Uri documentUri = buildDocumentUri(documentId);
        Cursor cursor = null;
        try {
            cursor = resolver.query(documentUri, DOCUMENT_PROJECTION, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                throw new FileNotFoundException("Document no longer exists: " + documentId);
            }
            return readEntry(cursor, parentDocumentId, fallbackName);
        } catch (SecurityException e) {
            throw new IOException("Access to this storage location was revoked", e);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private StorageEntry readEntry(Cursor cursor, String parentDocumentId) throws IOException {
        return readEntry(cursor, parentDocumentId, null, getPersistedGrantFlags());
    }

    private StorageEntry readEntry(Cursor cursor,
                                   String parentDocumentId,
                                   String fallbackName) throws IOException {
        return readEntry(cursor, parentDocumentId, fallbackName, getPersistedGrantFlags());
    }

    private StorageEntry readEntry(Cursor cursor,
                                   String parentDocumentId,
                                   String fallbackName,
                                   int activeGrantFlags) throws IOException {
        String documentId = getString(cursor, DocumentsContract.Document.COLUMN_DOCUMENT_ID);
        String name = getString(cursor, DocumentsContract.Document.COLUMN_DISPLAY_NAME);
        String mimeType = getString(cursor, DocumentsContract.Document.COLUMN_MIME_TYPE);
        if (documentId == null) throw new IOException("Document provider omitted the document ID");
        if (name == null || name.isEmpty()) name = fallbackName == null ? documentId : fallbackName;
        if (mimeType == null || mimeType.isEmpty()) mimeType = "application/octet-stream";

        boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);
        long size = getLong(cursor, DocumentsContract.Document.COLUMN_SIZE);
        long modified = getLong(cursor, DocumentsContract.Document.COLUMN_LAST_MODIFIED);
        int flags = (int) getLong(cursor, DocumentsContract.Document.COLUMN_FLAGS);

        int capabilities = 0;
        if ((activeGrantFlags & android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            capabilities |= StorageCapability.READ;
            if (directory) capabilities |= StorageCapability.LIST;
        }
        if ((activeGrantFlags & android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            if (!directory && (flags & DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0) {
                capabilities |= StorageCapability.WRITE;
            }
            if (directory && (flags & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0) {
                capabilities |= StorageCapability.CREATE_CHILD;
            }
            if ((flags & DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0) {
                capabilities |= StorageCapability.RENAME;
            }
            if ((flags & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0) {
                capabilities |= StorageCapability.DELETE;
            }
        }

        return new StorageEntry(
            id, documentId, parentDocumentId, name, mimeType, directory,
            Math.max(0L, size), Math.max(0L, modified), capabilities
        );
    }

    private static String getString(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index >= 0 && !cursor.isNull(index) ? cursor.getString(index) : null;
    }

    private static long getLong(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index >= 0 && !cursor.isNull(index) ? cursor.getLong(index) : 0L;
    }

    private static void validateName(String name) throws IOException {
        if (name == null || name.trim().isEmpty()
            || ".".equals(name) || "..".equals(name)
            || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
            || name.indexOf(0) >= 0) {
            throw new IOException("Invalid file name");
        }
    }
}
