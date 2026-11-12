package com.termux.app.theme.registry;

import android.content.res.AssetManager;

import com.termux.app.theme.loader.ThemeLoadException;
import com.termux.app.theme.loader.ThemePackageLoader;
import com.termux.app.theme.loader.ThemePackageSourceLoader;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Discovers built-in and imported theme packages and exposes an immutable registry snapshot.
 * This class does not apply colors, persist selections, or mutate theme package contents.
 *
 * Built-in package metadata is discovered under assets/themes/builtin/<id>/metadata.json.
 * Imported packages are discovered one directory level below the supplied import directory.
 */
public final class YummyThemeRegistry {
    public static final String BUILTIN_ASSET_ROOT = "themes/builtin";
    public static final String IMPORT_METADATA_FILE = "metadata.json";

    private static final Pattern VALID_DIRECTORY_ID =
        Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private final AssetManager assets;
    private final File importedThemesDirectory;
    private final ThemePackageSourceLoader packageLoader;

    private volatile ThemeRegistrySnapshot snapshot = ThemeRegistrySnapshot.empty();

    public YummyThemeRegistry(AssetManager assets, File importedThemesDirectory) {
        this(assets, importedThemesDirectory, new ThemePackageLoader());
    }

    public YummyThemeRegistry(
        AssetManager assets,
        File importedThemesDirectory,
        ThemePackageSourceLoader packageLoader
    ) {
        if (assets == null) throw new IllegalArgumentException("AssetManager is required");
        if (packageLoader == null) throw new IllegalArgumentException("ThemePackageLoader is required");
        this.assets = assets;
        this.importedThemesDirectory = importedThemesDirectory;
        this.packageLoader = packageLoader;
    }

    /** Returns the most recently published immutable snapshot. */
    public ThemeRegistrySnapshot getSnapshot() {
        return snapshot;
    }

    /**
     * Re-scans both theme sources and atomically publishes a new snapshot.
     * A malformed package is reported as an issue and does not prevent other themes loading.
     * Built-in packages are scanned before imported packages, so an imported package cannot
     * shadow a bundled theme with the same ID.
     */
    public synchronized ThemeRegistrySnapshot reload() {
        Map<String, LoadedTheme> accepted = new HashMap<>();
        List<ThemeRegistryIssue> issues = new ArrayList<>();

        scanBuiltInThemes(accepted, issues);
        scanImportedThemes(accepted, issues);
        reportMultipleDefaults(accepted, issues);

        List<LoadedTheme> loadedThemes = new ArrayList<>(accepted.values());
        ThemeRegistrySnapshot next = new ThemeRegistrySnapshot(loadedThemes, issues);
        snapshot = next;
        return next;
    }

    private void scanBuiltInThemes(
        Map<String, LoadedTheme> accepted,
        List<ThemeRegistryIssue> issues
    ) {
        final String[] packageNames;
        try {
            packageNames = assets.list(BUILTIN_ASSET_ROOT);
        } catch (IOException | RuntimeException error) {
            issues.add(issue(
                ThemeRegistryIssue.Code.BUILTIN_SCAN_FAILED,
                BUILTIN_ASSET_ROOT,
                error
            ));
            return;
        }
        if (packageNames == null || packageNames.length == 0) return;

        List<String> sortedNames = new ArrayList<>(Arrays.asList(packageNames));
        Collections.sort(sortedNames, stableStringOrder());
        for (String packageName : sortedNames) {
            if (!isValidDirectoryId(packageName)) continue;
            String metadataPath = BUILTIN_ASSET_ROOT + "/" + packageName + "/metadata.json";
            try {
                LoadedTheme theme = packageLoader.loadBuiltin(assets, metadataPath);
                if (!packageName.equals(theme.getMetadata().getId())) {
                    issues.add(new ThemeRegistryIssue(
                        ThemeRegistryIssue.Code.PACKAGE_ID_MISMATCH,
                        packageName,
                        "Built-in package directory does not match metadata ID '"
                            + theme.getMetadata().getId() + "'"
                    ));
                    continue;
                }
                register(theme, accepted, issues, packageName);
            } catch (ThemeLoadException | RuntimeException error) {
                issues.add(issue(ThemeRegistryIssue.Code.PACKAGE_LOAD_FAILED, packageName, error));
            }
        }
    }

    private void scanImportedThemes(
        Map<String, LoadedTheme> accepted,
        List<ThemeRegistryIssue> issues
    ) {
        if (importedThemesDirectory == null || !importedThemesDirectory.exists()) return;
        if (!importedThemesDirectory.isDirectory()) {
            issues.add(new ThemeRegistryIssue(
                ThemeRegistryIssue.Code.IMPORTED_SCAN_FAILED,
                importedThemesDirectory.getName(),
                "Imported theme path is not a directory"
            ));
            return;
        }

        final File canonicalRoot;
        try {
            canonicalRoot = importedThemesDirectory.getCanonicalFile();
        } catch (IOException | SecurityException error) {
            issues.add(issue(
                ThemeRegistryIssue.Code.IMPORTED_SCAN_FAILED,
                importedThemesDirectory.getName(),
                error
            ));
            return;
        }

        File[] children = canonicalRoot.listFiles();
        if (children == null) {
            issues.add(new ThemeRegistryIssue(
                ThemeRegistryIssue.Code.IMPORTED_SCAN_FAILED,
                canonicalRoot.getName(),
                "Cannot list imported theme directory"
            ));
            return;
        }
        Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return stableStringOrder().compare(left.getName(), right.getName());
            }
        });

        for (File child : children) {
            if (!child.isDirectory()) continue;
            String packageName = child.getName();
            if (!isValidDirectoryId(packageName)) continue;

            try {
                File canonicalPackage = child.getCanonicalFile();
                if (!canonicalPackage.equals(child.getAbsoluteFile())) {
                    issues.add(new ThemeRegistryIssue(
                        ThemeRegistryIssue.Code.UNSAFE_PACKAGE_DIRECTORY,
                        packageName,
                        "Imported package directory is a symbolic link"
                    ));
                    continue;
                }
                File parent = canonicalPackage.getParentFile();
                if (parent == null || !canonicalRoot.equals(parent)) {
                    issues.add(new ThemeRegistryIssue(
                        ThemeRegistryIssue.Code.UNSAFE_PACKAGE_DIRECTORY,
                        packageName,
                        "Imported package directory resolves outside the import root"
                    ));
                    continue;
                }

                LoadedTheme theme = packageLoader.loadImported(canonicalPackage);
                if (!packageName.equals(theme.getMetadata().getId())) {
                    issues.add(new ThemeRegistryIssue(
                        ThemeRegistryIssue.Code.PACKAGE_ID_MISMATCH,
                        packageName,
                        "Imported package directory does not match metadata ID '"
                            + theme.getMetadata().getId() + "'"
                    ));
                    continue;
                }
                register(theme, accepted, issues, packageName);
            } catch (ThemeLoadException | IOException | RuntimeException error) {
                issues.add(issue(ThemeRegistryIssue.Code.PACKAGE_LOAD_FAILED, packageName, error));
            }
        }
    }

    private static void register(
        LoadedTheme theme,
        Map<String, LoadedTheme> accepted,
        List<ThemeRegistryIssue> issues,
        String packageName
    ) {
        String id = theme.getMetadata().getId();
        if (accepted.containsKey(id)) {
            LoadedTheme existing = accepted.get(id);
            issues.add(new ThemeRegistryIssue(
                ThemeRegistryIssue.Code.DUPLICATE_THEME_ID,
                packageName,
                "Theme ID '" + id + "' is already provided by "
                    + existing.getMetadata().getSource().getMetadataValue()
                    + "; duplicate package was ignored"
            ));
            return;
        }
        accepted.put(id, theme);
    }

    private static void reportMultipleDefaults(
        Map<String, LoadedTheme> accepted,
        List<ThemeRegistryIssue> issues
    ) {
        for (ThemeTarget target : ThemeTarget.values()) {
            List<String> defaultIds = new ArrayList<>();
            for (LoadedTheme theme : accepted.values()) {
                if (theme.getMetadata().isDefaultFor(target)) {
                    defaultIds.add(theme.getMetadata().getId());
                }
            }
            if (defaultIds.size() > 1) {
                Collections.sort(defaultIds);
                issues.add(new ThemeRegistryIssue(
                    ThemeRegistryIssue.Code.MULTIPLE_DEFAULTS,
                    target.getMetadataValue(),
                    "Multiple themes declare the '" + target.getMetadataValue()
                        + "' default: " + join(defaultIds)
                        + ". Built-in defaults take precedence over imported defaults."
                ));
            }
        }
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) result.append(", ");
            result.append(values.get(i));
        }
        return result.toString();
    }

    private static boolean isValidDirectoryId(String value) {
        return value != null && VALID_DIRECTORY_ID.matcher(value).matches();
    }

    private static Comparator<String> stableStringOrder() {
        return new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                int insensitive = String.CASE_INSENSITIVE_ORDER.compare(left, right);
                return insensitive != 0 ? insensitive : left.compareTo(right);
            }
        };
    }

    private static ThemeRegistryIssue issue(
        ThemeRegistryIssue.Code code,
        String packageName,
        Throwable error
    ) {
        String message = error == null ? "Unknown error" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error == null ? "Unknown error" : error.getClass().getSimpleName();
        }
        return new ThemeRegistryIssue(code, packageName, message);
    }
}
