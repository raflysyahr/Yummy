package com.termux.app.theme.storage;

import android.content.Context;
import android.content.res.AssetManager;

import com.termux.app.theme.io.ThemeResourcePaths;
import com.termux.app.theme.loader.ThemePackageLoader;
import com.termux.app.theme.loader.ThemePackageSourceLoader;
import com.termux.app.theme.loader.ThemeLoadException;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeMetadata;
import com.termux.app.theme.registry.YummyThemeRegistry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Manages imported theme packages in Yummy's private app storage.
 *
 * <p>Packages are installed from an already-unpacked directory containing metadata.json,
 * the referenced VS Code Color Theme JSON, and any optional resources such as LICENSE or
 * TextMate files. Archive/document-picker import is deliberately handled by a later stage.</p>
 *
 * <p>Install and replace operations are staged and validated before the destination package
 * is changed. A replacement keeps a temporary backup until the new directory is promoted.</p>
 */
public final class ThemePackageStorage {
    public static final String THEMES_DIRECTORY_NAME = "themes";
    public static final String IMPORTED_DIRECTORY_NAME = "imported";
    public static final String STAGING_DIRECTORY_NAME = ".staging";

    private static final Pattern VALID_THEME_ID =
        Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final int MAX_PACKAGE_FILES = 128;
    private static final int MAX_DIRECTORY_DEPTH = 8;
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_PACKAGE_BYTES = 8L * 1024L * 1024L;

    private final File filesDirectory;
    private final AssetManager assets;
    private final ThemePackageSourceLoader packageLoader;
    private long operationSequence;

    /** Creates storage under context.getFilesDir()/themes/imported. */
    public ThemePackageStorage(Context context) {
        this(requireFilesDirectory(context), requireAssets(context), new ThemePackageLoader());
    }

    /** Constructor exposed for tests and for callers that need to inject a loader. */
    public ThemePackageStorage(
        File filesDirectory,
        AssetManager assets,
        ThemePackageSourceLoader packageLoader
    ) {
        if (filesDirectory == null) {
            throw new IllegalArgumentException("App files directory is required");
        }
        if (assets == null) {
            throw new IllegalArgumentException("AssetManager is required");
        }
        if (packageLoader == null) {
            throw new IllegalArgumentException("Theme package loader is required");
        }
        this.filesDirectory = filesDirectory;
        this.assets = assets;
        this.packageLoader = packageLoader;
    }

    /** Returns the private directory scanned by {@link YummyThemeRegistry}. */
    public File getImportedThemesDirectory() {
        return new File(new File(filesDirectory, THEMES_DIRECTORY_NAME), IMPORTED_DIRECTORY_NAME);
    }

    /**
     * Installs a new imported package. Existing packages and built-in IDs are never overwritten.
     * The source directory must already contain a valid Yummy package with source="imported".
     */
    public synchronized LoadedTheme importPackage(File sourcePackageDirectory)
        throws ThemeStorageException {
        return installPackage(sourcePackageDirectory, false);
    }

    /**
     * Replaces an existing imported package with the same ID. Built-in packages cannot be
     * replaced. The old package is restored if promoting the staged replacement fails.
     */
    public synchronized LoadedTheme replacePackage(File sourcePackageDirectory)
        throws ThemeStorageException {
        return installPackage(sourcePackageDirectory, true);
    }

    /** Removes only an imported package whose directory is a direct child of the import root. */
    public synchronized void removeImportedTheme(String themeId) throws ThemeStorageException {
        String id = requireThemeId(themeId);
        StorageDirectories directories = ensureStorageDirectories();
        File target = new File(directories.importedRoot, id);

        if (!target.exists()) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.PACKAGE_NOT_FOUND,
                "Imported theme does not exist: " + id
            );
        }
        requireDirectChild(directories.importedRoot, target, "Imported theme directory");
        deleteTree(target, directories.importedRoot);
    }

    /** Returns whether a direct imported package directory exists for the supplied ID. */
    public synchronized boolean hasImportedTheme(String themeId) throws ThemeStorageException {
        String id = requireThemeId(themeId);
        StorageDirectories directories = ensureStorageDirectories();
        File target = new File(directories.importedRoot, id);
        if (!target.exists()) return false;
        requireDirectChild(directories.importedRoot, target, "Imported theme directory");
        return target.isDirectory();
    }

    private LoadedTheme installPackage(File sourcePackageDirectory, boolean replace)
        throws ThemeStorageException {
        if (sourcePackageDirectory == null || !sourcePackageDirectory.isDirectory()) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_SOURCE,
                "Theme source must be an existing package directory"
            );
        }

        final File sourceRoot;
        final LoadedTheme sourceTheme;
        try {
            sourceRoot = sourcePackageDirectory.getCanonicalFile();
            if (!sourceRoot.isDirectory()) {
                throw new ThemeStorageException(
                    ThemeStorageException.Code.INVALID_SOURCE,
                    "Theme source directory is not accessible"
                );
            }
            sourceTheme = packageLoader.loadImported(sourceRoot);
        } catch (IOException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_SOURCE,
                "Could not resolve the theme source directory",
                error
            );
        } catch (ThemeLoadException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Theme source package is invalid: " + error.getMessage(),
                error
            );
        }

        String id = requireThemeId(sourceTheme.getMetadata().getId());
        StorageDirectories directories = ensureStorageDirectories();
        ensureIdIsNotBuiltIn(id);

        File destination = new File(directories.importedRoot, id);
        boolean destinationExists = destination.exists();
        if (replace && !destinationExists) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.PACKAGE_NOT_FOUND,
                "Cannot replace a package that is not installed: " + id
            );
        }
        if (!replace && destinationExists) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.PACKAGE_ALREADY_EXISTS,
                "An imported theme with ID '" + id + "' already exists"
            );
        }
        if (destinationExists) {
            requireDirectChild(directories.importedRoot, destination, "Replacement target");
            if (!destination.isDirectory()) {
                throw new ThemeStorageException(
                    ThemeStorageException.Code.UNSAFE_PATH,
                    "Replacement target is not a package directory: " + id
                );
            }
        }

        File stagedPackage = new File(
            directories.stagingRoot,
            id + ".stage-" + nextOperationSuffix()
        );
        File backupPackage = null;
        boolean oldPackageMoved = false;
        try {
            if (!stagedPackage.mkdir()) {
                throw new IOException("Could not create staging directory");
            }

            CopyStats stats = new CopyStats();
            copyDirectoryContents(sourceRoot, sourceRoot, stagedPackage, "", stats, 0);

            LoadedTheme stagedTheme;
            try {
                stagedTheme = packageLoader.loadImported(stagedPackage);
                validateOptionalLicenseFile(stagedPackage, stagedTheme.getMetadata());
            } catch (ThemeLoadException error) {
                throw new ThemeStorageException(
                    ThemeStorageException.Code.INVALID_PACKAGE,
                    "Copied theme package failed validation: " + error.getMessage(),
                    error
                );
            }

            if (!id.equals(stagedTheme.getMetadata().getId())) {
                throw new ThemeStorageException(
                    ThemeStorageException.Code.INVALID_PACKAGE,
                    "Theme ID changed while copying the package"
                );
            }

            if (replace) {
                backupPackage = new File(
                    directories.stagingRoot,
                    id + ".backup-" + nextOperationSuffix()
                );
                if (!destination.renameTo(backupPackage)) {
                    throw new IOException("Could not move the current package to a backup");
                }
                oldPackageMoved = true;
            }

            if (!stagedPackage.renameTo(destination)) {
                if (oldPackageMoved && !backupPackage.renameTo(destination)) {
                    throw new IOException(
                        "Could not install the replacement or restore the previous package; "
                            + "the previous package remains in " + backupPackage.getName()
                    );
                }
                oldPackageMoved = false;
                throw new IOException("Could not promote the staged theme package");
            }

            if (oldPackageMoved) {
                // A backup cleanup failure must not report the replacement as failed after
                // the new package has already become the active directory.
                cleanupStagingDirectory(backupPackage, directories.stagingRoot);
                oldPackageMoved = false;
            }
            return stagedTheme;
        } catch (ThemeStorageException error) {
            cleanupStagingDirectory(stagedPackage, directories.stagingRoot);
            if (oldPackageMoved && backupPackage != null && backupPackage.exists()) {
                backupPackage.renameTo(destination);
            }
            throw error;
        } catch (IOException | SecurityException error) {
            cleanupStagingDirectory(stagedPackage, directories.stagingRoot);
            if (oldPackageMoved && backupPackage != null && backupPackage.exists()) {
                backupPackage.renameTo(destination);
            }
            throw new ThemeStorageException(
                ThemeStorageException.Code.IO_FAILURE,
                "Could not store imported theme '" + id + "': " + error.getMessage(),
                error
            );
        }
    }

    private StorageDirectories ensureStorageDirectories() throws ThemeStorageException {
        try {
            File canonicalFiles = filesDirectory.getCanonicalFile();
            if (!canonicalFiles.isDirectory() && !canonicalFiles.mkdirs()) {
                throw new IOException("Could not create the app files directory");
            }
            canonicalFiles = filesDirectory.getCanonicalFile();

            File themesRoot = new File(canonicalFiles, THEMES_DIRECTORY_NAME);
            if (!themesRoot.exists() && !themesRoot.mkdir()) {
                throw new IOException("Could not create the themes directory");
            }
            File canonicalThemesRoot = themesRoot.getCanonicalFile();
            if (!canonicalThemesRoot.isDirectory()
                || !canonicalFiles.equals(canonicalThemesRoot.getParentFile())) {
                throw new IOException("Themes directory resolves outside app-private storage");
            }

            File importedRoot = new File(canonicalThemesRoot, IMPORTED_DIRECTORY_NAME);
            if (!importedRoot.exists() && !importedRoot.mkdir()) {
                throw new IOException("Could not create the imported themes directory");
            }
            File canonicalImportedRoot = importedRoot.getCanonicalFile();
            if (!canonicalImportedRoot.isDirectory()
                || !canonicalThemesRoot.equals(canonicalImportedRoot.getParentFile())) {
                throw new IOException("Imported theme directory resolves outside its parent");
            }

            File stagingRoot = new File(canonicalImportedRoot, STAGING_DIRECTORY_NAME);
            if (!stagingRoot.exists() && !stagingRoot.mkdir()) {
                throw new IOException("Could not create the theme staging directory");
            }
            File canonicalStagingRoot = stagingRoot.getCanonicalFile();
            if (!canonicalStagingRoot.isDirectory()
                || !canonicalImportedRoot.equals(canonicalStagingRoot.getParentFile())) {
                throw new IOException("Theme staging directory resolves outside its parent");
            }
            return new StorageDirectories(canonicalImportedRoot, canonicalStagingRoot);
        } catch (IOException | SecurityException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.STORAGE_UNAVAILABLE,
                "Theme storage is not available: " + error.getMessage(),
                error
            );
        }
    }

    private void ensureIdIsNotBuiltIn(String id) throws ThemeStorageException {
        final String[] builtInIds;
        try {
            builtInIds = assets.list(YummyThemeRegistry.BUILTIN_ASSET_ROOT);
        } catch (IOException | RuntimeException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.STORAGE_UNAVAILABLE,
                "Could not verify whether the theme ID is reserved by a built-in theme",
                error
            );
        }
        if (builtInIds == null) return;
        for (String builtInId : builtInIds) {
            if (id.equals(builtInId)) {
                throw new ThemeStorageException(
                    ThemeStorageException.Code.BUILTIN_ID_RESERVED,
                    "Theme ID '" + id + "' is reserved by a built-in theme"
                );
            }
        }
    }

    private static void copyDirectoryContents(
        File sourceRoot,
        File sourceDirectory,
        File destinationDirectory,
        String relativeDirectory,
        CopyStats stats,
        int depth
    ) throws IOException, ThemeStorageException {
        if (depth > MAX_DIRECTORY_DEPTH) {
            throw limitExceeded("Theme package directory nesting is too deep");
        }
        File[] children = sourceDirectory.listFiles();
        if (children == null) throw new IOException("Could not list source package directory");
        Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareTo(right.getName());
            }
        });

        for (File child : children) {
            File absolute = child.getAbsoluteFile();
            File canonical = child.getCanonicalFile();
            if (!canonical.equals(absolute)) {
                throw new ThemeStorageException(
                    ThemeStorageException.Code.UNSAFE_PATH,
                    "Symbolic links are not allowed in imported theme packages: " + child.getName()
                );
            }
            if (!isWithinDirectory(sourceRoot, canonical)) {
                throw new ThemeStorageException(
                    ThemeStorageException.Code.UNSAFE_PATH,
                    "Theme package entry escapes its source directory: " + child.getName()
                );
            }

            String relativePath = relativeDirectory.isEmpty()
                ? child.getName() : relativeDirectory + "/" + child.getName();
            try {
                ThemeResourcePaths.requireSafeRelativePath(relativePath);
            } catch (IllegalArgumentException error) {
                throw new ThemeStorageException(
                    ThemeStorageException.Code.UNSAFE_PATH,
                    "Unsupported theme package path: " + relativePath,
                    error
                );
            }

            stats.entryCount++;
            if (stats.entryCount > MAX_PACKAGE_FILES) {
                throw limitExceeded("Theme package contains more than " + MAX_PACKAGE_FILES + " entries");
            }

            File destination = new File(destinationDirectory, child.getName());
            if (child.isDirectory()) {
                if (!destination.mkdir()) {
                    throw new IOException("Could not create package directory: " + relativePath);
                }
                copyDirectoryContents(
                    sourceRoot, canonical, destination, relativePath, stats, depth + 1
                );
            } else if (child.isFile()) {
                copyFile(child, destination, relativePath, stats);
            } else {
                throw new ThemeStorageException(
                    ThemeStorageException.Code.UNSAFE_PATH,
                    "Unsupported filesystem entry in theme package: " + relativePath
                );
            }
        }
    }

    private static void copyFile(File source, File destination, String relativePath, CopyStats stats)
        throws IOException, ThemeStorageException {
        if (source.length() > MAX_FILE_BYTES) {
            throw limitExceeded("Theme package file exceeds 2 MiB: " + relativePath);
        }
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            long copied = 0;
            while ((read = input.read(buffer)) != -1) {
                copied += read;
                stats.totalBytes += read;
                if (copied > MAX_FILE_BYTES) {
                    throw limitExceeded("Theme package file exceeds 2 MiB: " + relativePath);
                }
                if (stats.totalBytes > MAX_PACKAGE_BYTES) {
                    throw limitExceeded("Theme package exceeds 8 MiB in total");
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private static void validateOptionalLicenseFile(File packageRoot, ThemeMetadata metadata)
        throws ThemeStorageException {
        String licensePath = metadata.getLicenseFile();
        if (licensePath == null) return;
        try {
            String safePath = ThemeResourcePaths.requireSafeRelativePath(licensePath);
            File canonicalRoot = packageRoot.getCanonicalFile();
            File licenseFile = new File(canonicalRoot, safePath).getCanonicalFile();
            if (!isWithinDirectory(canonicalRoot, licenseFile) || !licenseFile.isFile()) {
                throw new ThemeStorageException(
                    ThemeStorageException.Code.INVALID_PACKAGE,
                    "Theme licenseFile does not exist inside the package: " + safePath
                );
            }
        } catch (IOException | IllegalArgumentException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Could not validate theme license file: " + error.getMessage(),
                error
            );
        }
    }

    private static void requireDirectChild(File parent, File child, String description)
        throws ThemeStorageException {
        try {
            File canonicalParent = parent.getCanonicalFile();
            File canonicalChild = child.getCanonicalFile();
            if (!canonicalParent.equals(canonicalChild.getParentFile())
                || !canonicalChild.equals(child.getAbsoluteFile())) {
                throw new ThemeStorageException(
                    ThemeStorageException.Code.UNSAFE_PATH,
                    description + " resolves outside the imported themes directory"
                );
            }
        } catch (IOException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.UNSAFE_PATH,
                "Could not safely resolve " + description,
                error
            );
        }
    }

    private static boolean isWithinDirectory(File root, File candidate) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File canonicalCandidate = candidate.getCanonicalFile();
        String prefix = canonicalRoot.getPath() + File.separator;
        return canonicalCandidate.equals(canonicalRoot)
            || canonicalCandidate.getPath().startsWith(prefix);
    }

    private static void deleteTree(File target, File allowedParent) throws ThemeStorageException {
        try {
            File canonicalParent = allowedParent.getCanonicalFile();
            File canonicalTarget = target.getCanonicalFile();
            if (!canonicalParent.equals(canonicalTarget.getParentFile())
                || !canonicalTarget.equals(target.getAbsoluteFile())) {
                throw new ThemeStorageException(
                    ThemeStorageException.Code.UNSAFE_PATH,
                    "Refusing to delete a directory outside the expected parent"
                );
            }
            deleteTreeContents(canonicalTarget);
        } catch (IOException | SecurityException error) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.IO_FAILURE,
                "Could not safely remove theme package: " + error.getMessage(),
                error
            );
        }
    }

    private static void deleteTreeContents(File target) throws IOException, ThemeStorageException {
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children == null) throw new IOException("Could not list package for deletion");
            for (File child : children) {
                File canonical = child.getCanonicalFile();
                if (!canonical.equals(child.getAbsoluteFile())) {
                    throw new ThemeStorageException(
                        ThemeStorageException.Code.UNSAFE_PATH,
                        "Refusing to follow a symbolic link while deleting theme package"
                    );
                }
                if (child.isDirectory()) {
                    deleteTreeContents(child);
                } else if (!child.isFile()) {
                    throw new ThemeStorageException(
                        ThemeStorageException.Code.UNSAFE_PATH,
                        "Refusing to delete an unsupported filesystem entry"
                    );
                }
                if (!child.delete()) throw new IOException("Could not delete package entry: " + child.getName());
            }
        }
        if (!target.delete()) throw new IOException("Could not delete package directory: " + target.getName());
    }

    private static void cleanupStagingDirectory(File target, File stagingRoot) {
        if (target == null || !target.exists()) return;
        try {
            deleteTree(target, stagingRoot);
        } catch (ThemeStorageException ignored) {
            // Keep an unsafe or partially recovered directory for manual inspection.
        }
    }

    private String nextOperationSuffix() {
        operationSequence++;
        return Long.toHexString(System.nanoTime()) + "-" + operationSequence;
    }

    private static String requireThemeId(String value) throws ThemeStorageException {
        if (value == null || !VALID_THEME_ID.matcher(value).matches()) {
            throw new ThemeStorageException(
                ThemeStorageException.Code.INVALID_PACKAGE,
                "Invalid theme ID: " + value
            );
        }
        return value;
    }

    private static ThemeStorageException limitExceeded(String message) {
        return new ThemeStorageException(ThemeStorageException.Code.PACKAGE_LIMIT_EXCEEDED, message);
    }

    private static File requireFilesDirectory(Context context) {
        if (context == null || context.getFilesDir() == null) {
            throw new IllegalArgumentException("Application context with private files directory is required");
        }
        return context.getFilesDir();
    }

    private static AssetManager requireAssets(Context context) {
        if (context == null || context.getAssets() == null) {
            throw new IllegalArgumentException("Application context with AssetManager is required");
        }
        return context.getAssets();
    }

    private static final class CopyStats {
        int entryCount;
        long totalBytes;
    }

    private static final class StorageDirectories {
        final File importedRoot;
        final File stagingRoot;

        StorageDirectories(File importedRoot, File stagingRoot) {
            this.importedRoot = importedRoot;
            this.stagingRoot = stagingRoot;
        }
    }
}
