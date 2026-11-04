package com.termux.app.filemanager.storage;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.filemanager.storage.model.StorageLocation;
import com.termux.app.filemanager.storage.model.StorageProvider;
import com.termux.app.filemanager.storage.provider.LocalFileStorageProvider;
import com.termux.app.filemanager.storage.provider.SafTreeStorageProvider;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the list of mounted storage providers and persists user-granted SAF tree locations.
 * Create one registry from application context and reuse it from the File Manager layer.
 */
public final class StorageRegistry {
    private static final String PREFS_NAME = "yummy_storage_registry";
    private static final String KEY_SAF_LOCATIONS = "saf_locations_v1";

    private final Context context;
    private final SharedPreferences preferences;
    private final Map<String, StorageProvider> providers = new LinkedHashMap<>();

    public StorageRegistry(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        registerBuiltInLocations();
        restoreSafLocations();
    }

    /** Returns a snapshot; UI code should not modify the registry's internal collection. */
    @NonNull
    public synchronized List<StorageProvider> getProviders() {
        return Collections.unmodifiableList(new ArrayList<>(providers.values()));
    }

    @Nullable
    public synchronized StorageProvider getProvider(@NonNull String providerId) {
        return providers.get(providerId);
    }

    /**
     * Persist and mount a tree returned by ACTION_OPEN_DOCUMENT_TREE.
     * intentFlags must be the flags from the result Intent, not flags invented by the caller.
     */
    @NonNull
    public synchronized StorageProvider addSafTree(@NonNull Uri treeUri,
                                                   int intentFlags,
                                                   @Nullable String displayName) throws IOException {
        if (!DocumentsContract.isTreeUri(treeUri)) {
            throw new IOException("Android did not return a directory tree URI");
        }

        String ownAuthority = context.getPackageName() + ".documents";
        if (ownAuthority.equals(treeUri.getAuthority())) {
            // Yummy already exposes its home through its own DocumentsProvider.
            StorageProvider ownHome = providers.get("yummy.home");
            if (ownHome != null) return ownHome;
            throw new IOException("Yummy's own storage location is unavailable");
        }

        int grantFlags = intentFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((grantFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            throw new IOException("The selected location did not grant read access");
        }
        if ((intentFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) {
            throw new IOException("The selected location did not offer persistent access. Choose it again from the system picker.");
        }

        try {
            context.getContentResolver().takePersistableUriPermission(treeUri, grantFlags);
        } catch (SecurityException e) {
            throw new IOException("Android could not keep permission for this location", e);
        }

        String providerId = createSafProviderId(treeUri);
        String name = displayName == null || displayName.trim().isEmpty()
            ? inferDisplayName(treeUri) : displayName.trim();
        SafTreeStorageProvider provider = new SafTreeStorageProvider(
            context, providerId, name, treeUri, grantFlags
        );

        // Validate that the selected provider responds before making it visible in the registry.
        try {
            provider.getRootEntry();
        } catch (IOException | RuntimeException e) {
            try {
                context.getContentResolver().releasePersistableUriPermission(treeUri, grantFlags);
            } catch (RuntimeException ignored) {
                // Keep the original validation failure as the useful error.
            }
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("The selected storage location could not be opened", e);
        }

        providers.put(providerId, provider);
        persistSafLocations();
        return provider;
    }

    /** Removes a SAF mount and releases its persisted URI grant. Built-in locations cannot be removed. */
    public synchronized boolean removeSafLocation(@NonNull String providerId) {
        StorageProvider provider = providers.get(providerId);
        if (!(provider instanceof SafTreeStorageProvider)) return false;

        SafTreeStorageProvider safProvider = (SafTreeStorageProvider) provider;
        providers.remove(providerId);
        try {
            context.getContentResolver().releasePersistableUriPermission(
                safProvider.getTreeUri(), safProvider.getGrantedFlags()
            );
        } catch (SecurityException ignored) {
            // The permission may already have been revoked by Android or the user.
        }
        persistSafLocations();
        return true;
    }

    private void registerBuiltInLocations() {
        try {
            // Keep Yummy's own workspace separate from Termux's $PREFIX. Exposing the
            // parent of $HOME would also expose /usr, where deleting files can break Termux.
            File yummyWorkspace = context.getDir("yummy-storage", Context.MODE_PRIVATE);
            LocalFileStorageProvider filesProvider = new LocalFileStorageProvider(
                "yummy.files",
                "Yummy Internal",
                "Private workspace managed by Yummy",
                yummyWorkspace
            );
            providers.put(filesProvider.getId(), filesProvider);

            File home = TermuxConstants.TERMUX_HOME_DIR;
            if (home != null) {
                LocalFileStorageProvider homeProvider = new LocalFileStorageProvider(
                    "yummy.home",
                    "Yummy Home",
                    "Yummy's Termux home directory",
                    home
                );
                providers.put(homeProvider.getId(), homeProvider);
            }
        } catch (IOException | RuntimeException e) {
            android.util.Log.e("YummyStorage", "Could not register Yummy private storage", e);
        }
    }

    private void restoreSafLocations() {
        String saved = preferences.getString(KEY_SAF_LOCATIONS, "[]");
        JSONArray array;
        try {
            array = new JSONArray(saved);
        } catch (JSONException e) {
            android.util.Log.w("YummyStorage", "Ignoring invalid saved storage locations", e);
            return;
        }

        for (int i = 0; i < array.length(); i++) {
            try {
                JSONObject item = array.getJSONObject(i);
                String id = item.getString("id");
                String name = item.getString("name");
                Uri uri = Uri.parse(item.getString("uri"));
                int flags = item.getInt("flags");
                SafTreeStorageProvider provider = new SafTreeStorageProvider(
                    context, id, name, uri, flags
                );
                providers.put(id, provider);
            } catch (Exception e) {
                android.util.Log.w("YummyStorage", "Skipping an invalid saved SAF location", e);
            }
        }
    }

    private void persistSafLocations() {
        JSONArray array = new JSONArray();
        for (StorageProvider provider : providers.values()) {
            if (!(provider instanceof SafTreeStorageProvider)) continue;
            SafTreeStorageProvider saf = (SafTreeStorageProvider) provider;
            JSONObject item = new JSONObject();
            try {
                item.put("id", saf.getId());
                item.put("name", saf.getLocation().getName());
                item.put("uri", saf.getTreeUri().toString());
                item.put("flags", saf.getGrantedFlags());
                array.put(item);
            } catch (JSONException e) {
                android.util.Log.e("YummyStorage", "Could not save a storage location", e);
            }
        }
        preferences.edit().putString(KEY_SAF_LOCATIONS, array.toString()).apply();
    }

    private static String createSafProviderId(Uri uri) {
        String key = uri.toString();
        return "saf." + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String inferDisplayName(Uri uri) {
        String authority = uri.getAuthority();
        if ("com.termux.documents".equals(authority)) return "Termux Home";

        if ("com.android.externalstorage.documents".equals(authority)) {
            String documentId = DocumentsContract.getTreeDocumentId(uri);
            int separator = documentId.indexOf(':');
            String volumeId = separator >= 0 ? documentId.substring(0, separator) : documentId;
            String path = separator >= 0 ? documentId.substring(separator + 1) : "";
            path = path.replaceAll("/+$", "");
            if (!path.isEmpty()) {
                int slash = path.lastIndexOf('/');
                String folderName = slash >= 0 ? path.substring(slash + 1) : path;
                return ("primary".equalsIgnoreCase(volumeId) ? "Internal: " : "Removable: ") + folderName;
            }
            return "primary".equalsIgnoreCase(volumeId)
                ? "Internal Storage" : "Removable Storage (" + volumeId + ")";
        }

        if (authority == null || authority.isEmpty()) return "External Storage";
        return "External: " + authority;
    }
}
