package com.termux.app.filemanager.storage;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.termux.app.filemanager.storage.model.StorageProvider;

import java.io.IOException;

/** Helpers for asking Android to let the user mount a folder from any SAF document provider. */
public final class StorageTreePicker {
    private StorageTreePicker() {
    }

    /** Build this intent and launch it with startActivityForResult or an Activity Result launcher. */
    public static Intent createOpenTreeIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return intent;
    }

    /**
     * Call from onActivityResult after the picker returns. Returns null when the user cancelled.
     * The result Intent's actual grant flags are passed to StorageRegistry for persistence.
     */
    @Nullable
    public static StorageProvider acceptResult(StorageRegistry registry,
                                               int resultCode,
                                               @Nullable Intent resultData) throws IOException {
        if (resultCode != Activity.RESULT_OK || resultData == null) return null;
        Uri treeUri = resultData.getData();
        if (treeUri == null) throw new IOException("The system picker returned no location");
        return registry.addSafTree(treeUri, resultData.getFlags(), null);
    }
}
