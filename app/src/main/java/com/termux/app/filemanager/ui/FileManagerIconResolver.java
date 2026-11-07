package com.termux.app.filemanager.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.filemanager.storage.model.StorageEntry;
import com.termux.app.filemanager.storage.model.StorageProvider;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves file and folder imagery from the official Material Icon Theme 5.40.0
 * mapping catalog. PNGs are pre-rendered from the extension's original SVGs so
 * Android can display them without an extra SVG rendering dependency.
 */
public final class FileManagerIconResolver {
    private static final String ASSET_ROOT = "material-icon-theme/";
    private static volatile ThemeMapping theme;

    private static final LruCache<String, Bitmap> bitmapCache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
            return value.getAllocationByteCount();
        }
    };

    private FileManagerIconResolver() { }

    @DrawableRes
    public static int storage(@Nullable StorageProvider provider) {
        if (provider == null) return R.drawable.ic_fm_storage_external;
        String id = provider.getId();
        if ("yummy.home".equals(id)) return R.drawable.ic_fm_storage_local;
        if ("yummy.files".equals(id)) return R.drawable.ic_fm_storage_private;
        return R.drawable.ic_fm_storage_external;
    }

    @NonNull
    public static Drawable file(@NonNull Context context, @Nullable StorageEntry entry, boolean expanded) {
        String iconId = resolveIconId(context, entry, expanded);
        Bitmap bitmap = loadBitmap(context, iconId);
        if (bitmap == null && !"file".equals(iconId)) bitmap = loadBitmap(context, "file");
        if (bitmap != null) return new BitmapDrawable(context.getResources(), bitmap);
        return context.getDrawable(R.drawable.ic_fm_file_fallback);
    }

    @NonNull
    public static String resolveIconId(@NonNull Context context, @Nullable StorageEntry entry, boolean expanded) {
        ThemeMapping mapping = getTheme(context);
        if (entry == null) return mapping.file;

        String name = entry.getName() == null ? "" : entry.getName().toLowerCase(Locale.ROOT);
        if (entry.isDirectory()) {
            if (entry.isRoot()) {
                Map<String, String> rootNames = expanded
                    ? mapping.rootFolderNamesExpanded : mapping.rootFolderNames;
                String rootIcon = rootNames.get(name);
                if (rootIcon == null || rootIcon.isEmpty()) {
                    rootIcon = expanded ? mapping.rootFolderExpanded : mapping.rootFolder;
                }
                if (rootIcon == null || rootIcon.isEmpty()) {
                    rootIcon = expanded ? mapping.folderExpanded : mapping.folder;
                }
                return rootIcon;
            }
            Map<String, String> names = expanded ? mapping.folderNamesExpanded : mapping.folderNames;
            String folderIcon = names.get(name);
            if (folderIcon == null && expanded) folderIcon = mapping.folderNames.get(name);
            if (folderIcon == null || folderIcon.isEmpty()) {
                folderIcon = expanded ? mapping.folderExpanded : mapping.folder;
            }
            return folderIcon;
        }

        return resolveFileNameIcon(mapping, name);
    }


    /** Resolves a file icon from its display name, using the same mapping as File Manager rows. */
    @NonNull
    public static Drawable file(@NonNull Context context, @Nullable String filename) {
        String iconId = resolveIconId(context, filename);
        Bitmap bitmap = loadBitmap(context, iconId);
        if (bitmap == null && !"file".equals(iconId)) bitmap = loadBitmap(context, "file");
        if (bitmap != null) return new BitmapDrawable(context.getResources(), bitmap);
        return context.getDrawable(R.drawable.ic_fm_file_fallback);
    }

    /** Filename-only variant for editor tabs and other UI that has no StorageEntry instance. */
    @NonNull
    public static String resolveIconId(@NonNull Context context, @Nullable String filename) {
        ThemeMapping mapping = getTheme(context);
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        return resolveFileNameIcon(mapping, name);
    }

    @NonNull
    private static String resolveFileNameIcon(@NonNull ThemeMapping mapping, @NonNull String name) {
        String namedIcon = mapping.fileNames.get(name);
        if (namedIcon != null && !namedIcon.isEmpty()) return namedIcon;

        // Support compound extensions (e.g. .d.ts, .spec.js, .tar.gz) by testing
        // each suffix after a dot; the longest matching suffix wins.
        String bestIcon = null;
        int bestLength = -1;
        int dot = name.indexOf('.');
        while (dot >= 0 && dot < name.length() - 1) {
            String suffix = name.substring(dot + 1);
            String candidate = mapping.fileExtensions.get(suffix);
            if (candidate != null && suffix.length() > bestLength) {
                bestIcon = candidate;
                bestLength = suffix.length();
            }
            dot = name.indexOf('.', dot + 1);
        }
        return bestIcon == null || bestIcon.isEmpty() ? mapping.file : bestIcon;
    }

    @Nullable
    private static Bitmap loadBitmap(@NonNull Context context, @NonNull String iconId) {
        synchronized (bitmapCache) {
            Bitmap cached = bitmapCache.get(iconId);
            if (cached != null && !cached.isRecycled()) return cached;
        }
        try (InputStream input = context.getAssets().open(ASSET_ROOT + "icons/" + iconId + ".png")) {
            Bitmap decoded = BitmapFactory.decodeStream(input);
            if (decoded == null) return null;
            synchronized (bitmapCache) {
                bitmapCache.put(iconId, decoded);
            }
            return decoded;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    @NonNull
    private static ThemeMapping getTheme(@NonNull Context context) {
        ThemeMapping cached = theme;
        if (cached != null) return cached;
        synchronized (FileManagerIconResolver.class) {
            if (theme != null) return theme;
            theme = ThemeMapping.load(context);
            return theme;
        }
    }

    private static final class ThemeMapping {
        final Map<String, String> fileExtensions = new HashMap<>();
        final Map<String, String> fileNames = new HashMap<>();
        final Map<String, String> folderNames = new HashMap<>();
        final Map<String, String> folderNamesExpanded = new HashMap<>();
        final Map<String, String> rootFolderNames = new HashMap<>();
        final Map<String, String> rootFolderNamesExpanded = new HashMap<>();
        final String file;
        final String folder;
        final String folderExpanded;
        final String rootFolder;
        final String rootFolderExpanded;

        private ThemeMapping(JSONObject json) throws JSONException {
            copyObject(json.optJSONObject("fileExtensions"), fileExtensions);
            copyObject(json.optJSONObject("fileNames"), fileNames);
            copyObject(json.optJSONObject("folderNames"), folderNames);
            copyObject(json.optJSONObject("folderNamesExpanded"), folderNamesExpanded);
            copyObject(json.optJSONObject("rootFolderNames"), rootFolderNames);
            copyObject(json.optJSONObject("rootFolderNamesExpanded"), rootFolderNamesExpanded);
            file = fallback(json.optString("file", "file"), "file");
            folder = fallback(json.optString("folder", "folder"), "folder");
            folderExpanded = fallback(json.optString("folderExpanded", "folder-open"), "folder-open");
            rootFolder = fallback(json.optString("rootFolder", "folder-root"), folder);
            rootFolderExpanded = fallback(json.optString("rootFolderExpanded", "folder-root-open"), folderExpanded);
        }

        static ThemeMapping load(Context context) {
            try (InputStream input = context.getAssets().open(ASSET_ROOT + "mapping.json")) {
                byte[] bytes = new byte[input.available()];
                int offset = 0;
                while (offset < bytes.length) {
                    int count = input.read(bytes, offset, bytes.length - offset);
                    if (count < 0) break;
                    offset += count;
                }
                JSONObject json = new JSONObject(new String(bytes, 0, offset, StandardCharsets.UTF_8));
                return new ThemeMapping(json);
            } catch (IOException | JSONException | RuntimeException ignored) {
                return new ThemeMapping();
            }
        }

        private ThemeMapping() {
            file = "file";
            folder = "folder";
            folderExpanded = "folder-open";
            rootFolder = "folder-root";
            rootFolderExpanded = "folder-root-open";
        }

        private static void copyObject(JSONObject source, Map<String, String> target) {
            if (source == null) return;
            Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = source.optString(key, null);
                if (value != null && !value.isEmpty()) target.put(key.toLowerCase(Locale.ROOT), value);
            }
        }

        private static String fallback(String value, String defaultValue) {
            return value == null || value.isEmpty() ? defaultValue : value;
        }
    }
}
