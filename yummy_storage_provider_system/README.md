# Yummy Storage Provider System (phase 1)

This package adds a provider-neutral storage layer for the `feature/editor` branch. It is the storage foundation only; it does not add the File Manager screen yet.

## What is included

- `StorageProvider`: one contract for listing, reading, writing, creating, renaming and deleting entries.
- `StorageEntry`: provider-neutral file/directory metadata. Its `documentId` is opaque and must not be interpreted by UI code.
- `StorageLocation`: storage source metadata for the future locations screen.
- `LocalFileStorageProvider`: Yummy's private Termux files directory and Yummy Home.
- `SafTreeStorageProvider`: any user-granted Android Storage Access Framework tree.
- `StorageRegistry`: registers built-in locations and persists SAF tree grants across restarts.
- `StorageTreePicker`: creates the Android folder-picker intent and consumes its result.

No new Gradle dependency, manifest permission, or manifest provider is required for this phase. The branch already has AndroidX annotations, `termux-shared`, and a `TermuxDocumentsProvider` declaration. The implementation intentionally uses the Android SAF APIs directly instead of adding `DocumentFile`.

## Storage sources

The registry adds two built-in private roots:

- `yummy.files`: the parent of `TermuxConstants.TERMUX_HOME_DIR`, i.e. Yummy's private Termux files area. This includes Home and the Termux environment.
- `yummy.home`: Yummy's own `$HOME` directory.

Additional roots are added with `ACTION_OPEN_DOCUMENT_TREE`. This can include Android shared storage, SD cards, USB OTG, cloud document providers, and a separate Termux app's `com.termux.documents` provider when that provider is installed and visible in Android's picker.

The separate Termux app is accessed through the user's SAF grant, not by reading `/data/data/com.termux` directly. Termux's SAF document provider exposes its `$HOME`; it does not expose `$PREFIX` to external apps.

## Minimal integration example

Create one registry from the application context and keep it for the File Manager controller:

```java
StorageRegistry storageRegistry = new StorageRegistry(getApplicationContext());
```

Start the Android folder picker when the user taps **Add Storage**:

```java
private static final int REQUEST_ADD_STORAGE = 4101;

startActivityForResult(
    StorageTreePicker.createOpenTreeIntent(),
    REQUEST_ADD_STORAGE
);
```

Consume the picker result in the Activity:

```java
@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    if (requestCode != REQUEST_ADD_STORAGE) return;

    try {
        StorageProvider provider = StorageTreePicker.acceptResult(
            storageRegistry,
            resultCode,
            data
        );
        if (provider != null) {
            // Refresh the future storage-locations list.
        }
    } catch (IOException e) {
        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
    }
}
```

Browse a location without knowing its underlying storage type:

```java
StorageProvider provider = storageRegistry.getProvider("yummy.home");
StorageEntry root = provider.getRootEntry();
List<StorageEntry> children = provider.list(root);

// A SAF location has an ID starting with "saf.".
for (StorageProvider item : storageRegistry.getProviders()) {
    StorageLocation location = item.getLocation();
    // Display location.getName(), location.getDescription(), and item.isAvailable().
}
```

For an opened file, keep the `StorageProvider` and `StorageEntry` pair. Read/write through `openRead()` and `openWrite()`; do not convert SAF document IDs into filesystem paths. These provider methods do blocking I/O, so call them from an executor/worker thread rather than the Android main thread.

## Important provider limitations

- SAF providers decide which operations are supported. Check `StorageEntry.hasCapability(...)` before showing Write/Create/Rename/Delete actions.
- A provider may grant read-only access. The registry preserves the actual read/write grant returned by Android.
- USB/SD permissions can remain persisted while a volume is unplugged. `isAvailable()` also probes the selected root and reports it unavailable while the root cannot be reached.
- Some providers do not support rename, delete, or writing in place. The UI must handle `IOException` rather than assuming every operation exists.
- Deleting a non-empty directory is not recursive at this layer. A later File Manager operation service should walk its children and delete them explicitly after confirmation.
- Cross-provider copy/move is intentionally a separate transfer service to be added after this provider layer. It should stream bytes between `openRead()` and `createFile()`/`openWrite()` and only delete the source after a successful copy when performing a move.

## Apply

From the root of the Yummy repository, run:

```bash
bash yummy_storage_provider_system/install_storage_provider_system.sh
```

The installer only adds the new Java files. It refuses to overwrite a conflicting file and does not edit the current editor, manifest, or Gradle files.
