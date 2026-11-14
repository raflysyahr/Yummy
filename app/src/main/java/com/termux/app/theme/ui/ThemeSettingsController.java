package com.termux.app.theme.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.app.theme.adapter.AppThemeAdapter;
import com.termux.app.theme.adapter.AppThemeColorValueParser;
import com.termux.app.theme.model.AppThemeColorRole;
import com.termux.app.theme.model.AppThemeFallbacks;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ResolvedAppTheme;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeSelection;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;
import com.termux.app.theme.model.ThemeVersion;
import com.termux.app.theme.registry.ThemeRegistrySnapshot;
import com.termux.app.theme.registry.YummyThemeRegistry;
import com.termux.app.theme.settings.ThemeSelectionStore;
import com.termux.app.theme.settings.ThemePackageArchiveImporter;
import com.termux.app.theme.loader.ThemePackageLoader;
import com.termux.app.theme.storage.ThemePackageStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Yummy theme settings and preview UI.
 *
 * <p>The App UI Color Scheme and Editor Syntax Theme selectors are separate. Previewing a
 * theme is read-only: it does not register a Sora theme, apply an App UI palette, or alter the
 * currently active editor/terminal. A selection is persisted only after its target applier
 * reports success.</p>
 *
 * <p>The host must pass its existing Nunito typeface. This controller does not change fonts,
 * grammar configuration, document content, tabs, undo history, or terminal settings.</p>
 */
public final class ThemeSettingsController {
    public interface EditorSyntaxThemeApplier {
        void apply(LoadedTheme theme) throws Exception;
    }

    private static final int COLOR_BACKGROUND = Color.rgb(12, 13, 15);
    private static final int COLOR_SURFACE = Color.rgb(22, 24, 28);
    private static final int COLOR_BORDER = Color.rgb(39, 42, 48);
    private static final int COLOR_TEXT = Color.rgb(239, 240, 242);
    private static final int COLOR_MUTED = Color.rgb(145, 150, 158);
    private static final int COLOR_ACCENT = Color.rgb(34, 197, 94);
    private static final int REQUEST_IMPORT_PACKAGE = 0x594E;
    private static final int REQUEST_EXPORT_THEME = 0x594F;

    private final Activity activity;
    private final YummyThemeRegistry registry;
    private final AppThemeController appThemeController;
    private final EditorSyntaxThemeApplier editorSyntaxThemeApplier;
    private final ThemeSelectionStore selectionStore;
    private final ThemePackageStorage packageStorage;
    private final Typeface uiTypeface;

    private ThemeRegistrySnapshot snapshot = ThemeRegistrySnapshot.empty();
    private ThemeSelection selection = ThemeSelection.defaults();
    private Dialog activeDialog;
    private ThemeImportController themeImportController;
    private String pendingExportThemeId;
    private final ExecutorService packageIoExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean released;
    private ThemePackageArchiveImporter.Result pendingPackageRestore;

    public ThemeSettingsController(
        Activity activity,
        YummyThemeRegistry registry,
        AppThemeController appThemeController,
        EditorSyntaxThemeApplier editorSyntaxThemeApplier,
        Typeface uiTypeface
    ) {
        if (activity == null) throw new IllegalArgumentException("Activity is required");
        if (registry == null) throw new IllegalArgumentException("YummyThemeRegistry is required");
        this.activity = activity;
        this.registry = registry;
        this.appThemeController = appThemeController;
        this.editorSyntaxThemeApplier = editorSyntaxThemeApplier;
        this.selectionStore = new ThemeSelectionStore(activity);
        this.packageStorage = new ThemePackageStorage(activity);
        this.uiTypeface = uiTypeface;
    }

    /** Reloads available packages and displays the two independent theme selectors. */
    public void show() {
        requireMainThread();
        snapshot = registry.reload();
        selection = selectionStore.read(snapshot);
        showSettingsDialog();
    }

    /**
     * Applies the two persisted selections after their respective UI/editor owners are ready.
     * Each target is attempted independently: an Editor Syntax failure does not undo a working
     * App UI theme, and vice versa. Call after AppThemeController bindings and Sora TextMate
     * initialization have completed.
     */
    public void applySavedThemes() {
        requireMainThread();
        snapshot = registry.reload();
        selection = selectionStore.read(snapshot);
        List<String> failures = new java.util.ArrayList<>();
        List<String> recoveries = new java.util.ArrayList<>();

        LoadedTheme appTheme = snapshot.findById(selection.getAppColorSchemeId());
        if (appTheme != null && appTheme.getMetadata().supports(ThemeTarget.APP_UI)) {
            try {
                if (appThemeController == null) {
                    throw new IllegalStateException("AppThemeController is not connected");
                }
                appThemeController.applyTheme(appTheme);
            } catch (Exception error) {
                try {
                    LoadedTheme fallback = findRecoveryTheme(
                        ThemeTarget.APP_UI, selection.getAppColorSchemeId());
                    if (fallback == null || appThemeController == null) throw error;
                    appThemeController.applyTheme(fallback);
                    selectionStore.selectAppUiTheme(fallback.getMetadata().getId());
                    selection = selection.withAppColorScheme(fallback.getMetadata().getId());
                    recoveries.add("App UI restored with '" + fallback.getMetadata().getLabel() + "'");
                } catch (Exception recoveryError) {
                    failures.add("App UI: " + safeMessage(recoveryError));
                }
            }
        }

        LoadedTheme syntaxTheme = snapshot.findById(selection.getEditorSyntaxThemeId());
        if (syntaxTheme != null && syntaxTheme.getMetadata().supports(ThemeTarget.EDITOR_SYNTAX)) {
            try {
                if (editorSyntaxThemeApplier == null) {
                    throw new IllegalStateException("Editor Syntax applier is not connected");
                }
                editorSyntaxThemeApplier.apply(syntaxTheme);
            } catch (Exception error) {
                try {
                    LoadedTheme fallback = findRecoveryTheme(
                        ThemeTarget.EDITOR_SYNTAX, selection.getEditorSyntaxThemeId());
                    if (fallback == null || editorSyntaxThemeApplier == null) throw error;
                    editorSyntaxThemeApplier.apply(fallback);
                    selectionStore.selectEditorSyntaxTheme(fallback.getMetadata().getId());
                    selection = selection.withEditorSyntaxTheme(fallback.getMetadata().getId());
                    recoveries.add("Editor Syntax restored with '" + fallback.getMetadata().getLabel() + "'");
                } catch (Exception recoveryError) {
                    failures.add("Editor Syntax: " + safeMessage(recoveryError));
                }
            }
        }

        if (!recoveries.isEmpty() || !failures.isEmpty()) {
            StringBuilder message = new StringBuilder();
            if (!recoveries.isEmpty()) {
                message.append("Theme recovery: ").append(
                    android.text.TextUtils.join("; ", recoveries));
            }
            if (!failures.isEmpty()) {
                if (message.length() > 0) message.append(". ");
                message.append("Some saved themes could not be applied: ").append(
                    android.text.TextUtils.join("; ", failures));
            }
            Toast.makeText(activity, message.toString(), Toast.LENGTH_LONG).show();
        }
    }

    private LoadedTheme findRecoveryTheme(ThemeTarget target, String failedThemeId) {
        LoadedTheme declaredDefault = snapshot.getDefaultThemeFor(target);
        if (declaredDefault != null
            && !failedThemeId.equals(declaredDefault.getMetadata().getId())
            && declaredDefault.getMetadata().supports(target)) {
            return declaredDefault;
        }
        List<LoadedTheme> available = target == ThemeTarget.APP_UI
            ? snapshot.getAppUiThemes() : snapshot.getEditorSyntaxThemes();
        for (LoadedTheme candidate : available) {
            if (candidate != null && candidate.getMetadata().supports(target)
                && !failedThemeId.equals(candidate.getMetadata().getId())) {
                return candidate;
            }
        }
        return null;
    }

    /** Dismisses the current settings/preview dialog, for example from Activity.onDestroy(). */
    public void dismiss() {
        requireMainThread();
        released = true;
        packageIoExecutor.shutdownNow();
        if (pendingPackageRestore != null) {
            ThemePackageArchiveImporter.deleteTree(pendingPackageRestore.getPackageDirectory());
            pendingPackageRestore = null;
        }
        if (themeImportController != null) {
            themeImportController.release();
            themeImportController = null;
        }
        if (activeDialog != null) {
            Dialog dialog = activeDialog;
            activeDialog = null;
            dialog.dismiss();
        }
    }

    public ThemeSelection getSelection() {
        return selection;
    }

    /**
     * Forward Activity.onActivityResult to this method. Returns true for theme JSON/ZIP imports
     * and package export document creation.
     */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        requireMainThread();
        if (requestCode == REQUEST_IMPORT_PACKAGE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                inspectPackageArchive(data.getData());
            }
            return true;
        }
        if (requestCode == REQUEST_EXPORT_THEME) {
            String themeId = pendingExportThemeId;
            pendingExportThemeId = null;
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null
                && themeId != null) {
                exportThemeToUri(themeId, data.getData());
            }
            return true;
        }
        if (requestCode == ThemeImportController.REQUEST_CODE) {
            // The Activity may be recreated while an external document picker is open.
            // Recreate the lazy controller before forwarding the result so it is not dropped.
            ensureThemeImportController();
            return themeImportController.onActivityResult(requestCode, resultCode, data);
        }
        return false;
    }

    private ThemeImportController ensureThemeImportController() {
        if (themeImportController == null) {
            themeImportController = new ThemeImportController(
                activity,
                registry,
                packageStorage,
                appThemeController,
                uiTypeface,
                importedId -> {
                    snapshot = registry.reload();
                    selection = selectionStore.read(snapshot);
                    Toast.makeText(activity,
                        "Imported theme successfully. Select it under Application UI or Editor Syntax.",
                        Toast.LENGTH_LONG).show();
                    showSettingsDialog();
                }
            );
        }
        return themeImportController;
    }

    private void startThemeImport() {
        requireMainThread();
        ensureThemeImportController().launchPicker();
    }

    private void showSettingsDialog() {
        Dialog dialog = createDialog();
        LinearLayout root = createRoot();
        root.addView(text("Themes", 22, textColor(), true));
        root.addView(text(
            "Application UI and editor syntax themes are selected independently.",
            13, mutedColor(), false
        ), marginParams(0, dp(5), 0, dp(18)));

        addSelectionSection(
            root,
            "APPLICATION UI COLOR SCHEME",
            ThemeTarget.APP_UI,
            selection.getAppColorSchemeId()
        );
        addSelectionSection(
            root,
            "EDITOR SYNTAX THEME",
            ThemeTarget.EDITOR_SYNTAX,
            selection.getEditorSyntaxThemeId()
        );

        TextView importTheme = action("Import VS Code Theme JSON", true, this::startThemeImport);
        root.addView(importTheme, marginParams(0, dp(18), 0, 0));
        root.addView(text(
            "Import a standalone .json Color Theme. Yummy validates it and creates package metadata.",
            11, mutedColor(), false), marginParams(0, dp(5), 0, 0));

        TextView debugReport = action(
            "View Last Theme Import Debug Report", false,
            () -> ensureThemeImportController().showLastImportDebugReport());
        root.addView(debugReport, marginParams(0, dp(9), 0, 0));
        root.addView(text(
            "Open and copy the latest JSON import and preview diagnostic report.",
            11, mutedColor(), false), marginParams(0, dp(4), 0, 0));

        TextView importPackage = action(
            "Import Yummy Theme Package (.zip)", false, this::launchPackageImport);
        root.addView(importPackage, marginParams(0, dp(9), 0, 0));
        root.addView(text(
            "Restore a complete exported package, including metadata and bundled resources.",
            11, mutedColor(), false), marginParams(0, dp(4), 0, 0));

        TextView manageThemes = action(
            "Manage imported themes", false, this::showImportedThemes);
        root.addView(manageThemes, marginParams(0, dp(12), 0, 0));

        TextView close = action("Close", false, dialog::dismiss);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.topMargin = dp(16);
        root.addView(close, closeParams);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(backgroundColor());
        scroll.addView(root);
        dialog.setContentView(scroll);
        showDialog(dialog, 560);
    }

    /** Shows only user-imported packages; bundled themes are never offered for removal. */
    private void showImportedThemes() {
        requireMainThread();
        snapshot = registry.reload();

        Dialog dialog = createDialog();
        LinearLayout root = createRoot();
        root.addView(text("Manage imported themes", 20, textColor(), true));
        root.addView(text(
            "Remove theme packages stored by Yummy. Built-in themes cannot be removed.",
            12, mutedColor(), false), marginParams(0, dp(5), 0, dp(14)));

        List<LoadedTheme> imported = snapshot.getThemesFrom(ThemeSource.IMPORTED);
        if (imported.isEmpty()) {
            root.addView(text("No imported themes installed.", 13, mutedColor(), false));
        } else {
            for (LoadedTheme theme : imported) {
                addImportedThemeRow(root, dialog, theme);
            }
        }

        TextView back = action("Back", false, () -> {
            dialog.dismiss();
            showSettingsDialog();
        });
        root.addView(back, marginParams(0, dp(14), 0, 0));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(backgroundColor());
        scroll.addView(root);
        dialog.setContentView(scroll);
        showDialog(dialog, 560);
    }

    private void addImportedThemeRow(
        LinearLayout root,
        Dialog listDialog,
        LoadedTheme theme
    ) {
        String id = theme.getMetadata().getId();
        StringBuilder details = new StringBuilder();
        details.append(appearanceLabel(theme)).append(" · ").append(id);
        details.append("\nVersion: ").append(theme.getMetadata().getVersion());
        if (theme.getMetadata().supports(ThemeTarget.APP_UI)) {
            details.append("\nApplication UI");
        }
        if (theme.getMetadata().supports(ThemeTarget.EDITOR_SYNTAX)) {
            if (details.charAt(details.length() - 1) != '\n') details.append(" · ");
            details.append("Editor Syntax");
        }
        String author = theme.getMetadata().getAuthor();
        String license = theme.getMetadata().getLicense();
        if (author != null) details.append("\nAuthor: ").append(author);
        if (license != null) details.append("\nLicense: ").append(license);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(rect(surfaceColor(), borderColor()));
        card.addView(text(theme.getMetadata().getLabel(), 15, textColor(), true));
        card.addView(text(details.toString(), 11, mutedColor(), false),
            marginParams(0, dp(4), 0, dp(8)));

        try {
            List<ThemePackageStorage.BackupInfo> backups = packageStorage.listBackups(id);
            if (!backups.isEmpty()) {
                card.addView(text("VERSION BACKUPS", 10, mutedColor(), true),
                    marginParams(0, 0, 0, dp(3)));
                for (ThemePackageStorage.BackupInfo backup : backups) {
                    TextView restore = action("Restore backup · v" + backup.getVersion(), false,
                        () -> confirmRestoreBackup(theme, backup));
                    card.addView(restore, marginParams(0, 0, 0, dp(3)));
                }
            }
        } catch (Exception error) {
            card.addView(text("Backups unavailable: " + safeMessage(error), 10, mutedColor(), false),
                marginParams(0, 0, 0, dp(4)));
        }

        TextView export = action("Export package (.zip)", false,
            () -> launchThemeExport(theme));
        card.addView(export, marginParams(0, 0, 0, dp(4)));

        TextView remove = action("Remove", false, () -> {
            listDialog.dismiss();
            confirmRemoveTheme(theme);
        });
        card.addView(remove);
        root.addView(card, marginParams(0, 0, 0, dp(8)));
    }

    private void launchPackageImport() {
        requireMainThread();
        if (released) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
            "application/zip", "application/x-zip-compressed", "application/octet-stream"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQUEST_IMPORT_PACKAGE);
        } catch (Exception error) {
            Toast.makeText(activity, "Could not open package picker: " + safeMessage(error),
                Toast.LENGTH_LONG).show();
        }
    }

    private void inspectPackageArchive(Uri uri) {
        if (released) return;
        Toast.makeText(activity, "Reading Yummy theme package…", Toast.LENGTH_SHORT).show();
        packageIoExecutor.execute(() -> {
            ThemePackageArchiveImporter.Result result;
            try {
                InputStream input = activity.getContentResolver().openInputStream(uri);
                if (input == null) throw new IOException("Could not open selected ZIP document");
                try (InputStream archive = input) {
                    result = new ThemePackageArchiveImporter().extract(
                        archive, new File(activity.getCacheDir(), "theme-package-restore"));
                }
            } catch (Exception error) {
                postPackageResult(() -> Toast.makeText(activity,
                    "Could not read theme package: " + safeMessage(error), Toast.LENGTH_LONG).show());
                return;
            }
            ThemePackageArchiveImporter.Result extracted = result;
            activity.runOnUiThread(() -> {
                if (!released && !activity.isFinishing()) {
                    prepareExtractedPackage(extracted);
                } else {
                    ThemePackageArchiveImporter.deleteTree(extracted.getPackageDirectory());
                }
            });
        });
    }

    private void prepareExtractedPackage(ThemePackageArchiveImporter.Result result) {
        if (released || activity.isFinishing()) {
            ThemePackageArchiveImporter.deleteTree(result.getPackageDirectory());
            return;
        }
        try {
            snapshot = registry.reload();
            String id = result.getTheme().getMetadata().getId();
            LoadedTheme collision = snapshot.findById(id);
            boolean packageExists = packageStorage.hasImportedTheme(id);

            if (collision != null && collision.getMetadata().getSource() == ThemeSource.BUILT_IN) {
                id = createAvailableImportedId(id);
                rewritePackageId(result.getPackageDirectory(), id);
                LoadedTheme renamed = new ThemePackageLoader().loadImported(result.getPackageDirectory());
                result = new ThemePackageArchiveImporter.Result(result.getPackageDirectory(), renamed);
                collision = null;
                packageExists = false;
            }

            pendingPackageRestore = result;
            if ((collision != null && collision.getMetadata().getSource() == ThemeSource.IMPORTED)
                || packageExists) {
                if (collision == null || collision.getMetadata().getSource() != ThemeSource.IMPORTED) {
                    throw new IllegalStateException(
                        "An imported package with this ID exists but its installed version cannot be validated. "
                            + "Remove or repair that package before updating it.");
                }
                ThemeVersion incoming = ThemeVersion.parse(result.getTheme().getMetadata().getVersion());
                ThemeVersion installed = ThemeVersion.parse(collision.getMetadata().getVersion());
                int comparison = incoming.compareTo(installed);
                if (comparison <= 0) {
                    throw new IllegalStateException(comparison == 0
                        ? "This theme version is already installed (v" + installed + ")."
                        : "Downgrade blocked. Installed v" + installed + " is newer than incoming v" + incoming + ".");
                }
                confirmPackageUpdate(result, collision);
            } else {
                installExtractedPackage(result, false);
            }
        } catch (Exception error) {
            if (pendingPackageRestore == result) pendingPackageRestore = null;
            ThemePackageArchiveImporter.deleteTree(result.getPackageDirectory());
            Toast.makeText(activity, "Could not prepare theme package: " + safeMessage(error),
                Toast.LENGTH_LONG).show();
        }
    }

    private String createAvailableImportedId(String originalId) throws Exception {
        String base = originalId.length() > 54 ? originalId.substring(0, 54) : originalId;
        String candidate = base + "-imported";
        int suffix = 2;
        while (snapshot.findById(candidate) != null || packageStorage.hasImportedTheme(candidate)) {
            String ending = "-" + suffix++;
            int maximumBase = 64 - ending.length();
            String prefix = base.length() > maximumBase ? base.substring(0, maximumBase) : base;
            candidate = prefix + ending;
        }
        return candidate;
    }

    private static void rewritePackageId(File packageDirectory, String newId) throws Exception {
        File metadataFile = new File(packageDirectory, "metadata.json");
        byte[] bytes;
        try (InputStream input = new FileInputStream(metadataFile)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            bytes = output.toByteArray();
        }
        String json = new String(bytes, StandardCharsets.UTF_8);
        if (json.startsWith("\uFEFF")) json = json.substring(1);
        JSONObject metadata = new JSONObject(json);
        metadata.put("id", newId);
        try (FileOutputStream output = new FileOutputStream(metadataFile, false)) {
            output.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void confirmPackageUpdate(
        ThemePackageArchiveImporter.Result result, LoadedTheme installedTheme
    ) {
        String incomingVersion = result.getTheme().getMetadata().getVersion();
        String installedVersion = installedTheme.getMetadata().getVersion();
        Dialog dialog = createDialog();
        LinearLayout root = createRoot();
        root.addView(text("Update theme package?", 19, textColor(), true));
        root.addView(text("'" + result.getTheme().getMetadata().getLabel() + "' will be updated from v"
            + installedVersion + " to v" + incomingVersion
            + ". Yummy will save the installed version as a backup before replacing it.",
            13, mutedColor(), false), marginParams(0, dp(7), 0, dp(16)));
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = action("Cancel", false, () -> {
            dialog.dismiss();
            if (pendingPackageRestore == result) pendingPackageRestore = null;
            ThemePackageArchiveImporter.deleteTree(result.getPackageDirectory());
        });
        TextView update = action("Back up & update", true, () -> {
            dialog.dismiss();
            installExtractedPackage(result, true);
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        updateParams.leftMargin = dp(8);
        actions.addView(update, updateParams);
        root.addView(actions);
        dialog.setContentView(root);
        showDialog(dialog, 460);
    }

    private void installExtractedPackage(
        ThemePackageArchiveImporter.Result result, boolean replaceExisting
    ) {
        if (released) {
            if (pendingPackageRestore == result) pendingPackageRestore = null;
            ThemePackageArchiveImporter.deleteTree(result.getPackageDirectory());
            return;
        }
        if (pendingPackageRestore == result) pendingPackageRestore = null;
        packageIoExecutor.execute(() -> {
            String id = result.getTheme().getMetadata().getId();
            String version = result.getTheme().getMetadata().getVersion();
            Exception failure = null;
            ThemePackageStorage.BackupInfo backup = null;
            try {
                if (replaceExisting) {
                    backup = packageStorage.backupImportedTheme(id);
                    packageStorage.replacePackage(result.getPackageDirectory());
                } else {
                    packageStorage.importPackage(result.getPackageDirectory());
                }
            } catch (Exception error) {
                failure = error;
            } finally {
                ThemePackageArchiveImporter.deleteTree(result.getPackageDirectory());
            }
            Exception installFailure = failure;
            ThemePackageStorage.BackupInfo createdBackup = backup;
            postPackageResult(() -> {
                if (installFailure != null) {
                    Toast.makeText(activity, "Could not install theme package: "
                        + safeMessage(installFailure), Toast.LENGTH_LONG).show();
                } else {
                    snapshot = registry.reload();
                    selection = selectionStore.read(snapshot);
                    if (replaceExisting) applySavedThemes();
                    String message = replaceExisting
                        ? "Theme updated to v" + version + ". Backup v"
                            + (createdBackup == null ? "?" : createdBackup.getVersion()) + " saved."
                        : "Theme package restored: " + id + ". Select it in the appropriate theme section.";
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                }
                showSettingsDialog();
            });
        });
    }

    private void postPackageResult(Runnable action) {
        activity.runOnUiThread(() -> {
            if (!released && !activity.isFinishing()) action.run();
        });
    }

    private void confirmRestoreBackup(
        LoadedTheme theme, ThemePackageStorage.BackupInfo backup
    ) {
        String id = theme.getMetadata().getId();
        Dialog dialog = createDialog();
        LinearLayout root = createRoot();
        root.addView(text("Restore theme backup?", 19, textColor(), true));
        root.addView(text("Restore '" + theme.getMetadata().getLabel() + "' to version v"
            + backup.getVersion() + "? The currently installed version will first be saved as another backup.",
            13, mutedColor(), false), marginParams(0, dp(7), 0, dp(16)));
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = action("Cancel", false, dialog::dismiss);
        TextView restore = action("Restore", true, () -> {
            dialog.dismiss();
            packageIoExecutor.execute(() -> {
                Exception failure = null;
                try {
                    packageStorage.restoreBackup(id, backup.getBackupId());
                } catch (Exception error) {
                    failure = error;
                }
                Exception restoreFailure = failure;
                postPackageResult(() -> {
                    if (restoreFailure != null) {
                        Toast.makeText(activity, "Could not restore theme backup: "
                            + safeMessage(restoreFailure), Toast.LENGTH_LONG).show();
                    } else {
                        snapshot = registry.reload();
                        selection = selectionStore.read(snapshot);
                        applySavedThemes();
                        Toast.makeText(activity, "Theme restored to v" + backup.getVersion(),
                            Toast.LENGTH_LONG).show();
                    }
                    showImportedThemes();
                });
            });
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams restoreParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        restoreParams.leftMargin = dp(8);
        actions.addView(restore, restoreParams);
        root.addView(actions);
        dialog.setContentView(root);
        showDialog(dialog, 460);
    }

    private void launchThemeExport(LoadedTheme theme) {
        requireMainThread();
        String id = theme.getMetadata().getId();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, id + ".yummy-theme.zip");
        pendingExportThemeId = id;
        try {
            activity.startActivityForResult(intent, REQUEST_EXPORT_THEME);
        } catch (Exception error) {
            pendingExportThemeId = null;
            Toast.makeText(activity, "Could not open destination picker: "
                + safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private void exportThemeToUri(String themeId, Uri destination) {
        try {
            File root = packageStorage.getImportedThemesDirectory().getCanonicalFile();
            File packageDirectory = new File(root, themeId).getCanonicalFile();
            if (!packageDirectory.isDirectory()
                || !root.equals(packageDirectory.getParentFile())) {
                throw new IOException("Imported theme package is missing or unsafe");
            }

            OutputStream output = activity.getContentResolver().openOutputStream(destination, "w");
            if (output == null) throw new IOException("Could not open destination document");
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                addDirectoryToZip(packageDirectory, packageDirectory, zip);
            }
            Toast.makeText(activity, "Theme exported: " + themeId,
                Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(activity, "Could not export theme: " + safeMessage(error),
                Toast.LENGTH_LONG).show();
        }
    }

    private static void addDirectoryToZip(
        File current,
        File packageDirectory,
        ZipOutputStream zip
    ) throws IOException {
        File canonical = current.getCanonicalFile();
        String packagePath = packageDirectory.getCanonicalPath();
        if (!canonical.getPath().equals(packagePath)
            && !canonical.getPath().startsWith(packagePath + File.separator)) {
            throw new IOException("Theme package contains an unsafe path");
        }
        File[] children = canonical.listFiles();
        if (children == null) throw new IOException("Could not read theme package");
        java.util.Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
        for (File child : children) {
            File safeChild = child.getCanonicalFile();
            if (!safeChild.getPath().equals(packagePath)
                && !safeChild.getPath().startsWith(packagePath + File.separator)) {
                throw new IOException("Theme package contains a symbolic-link escape");
            }
            if (safeChild.isDirectory()) {
                addDirectoryToZip(safeChild, packageDirectory, zip);
            } else if (safeChild.isFile()) {
                String relative = packageDirectory.toURI().relativize(safeChild.toURI()).getPath();
                if (relative.length() == 0 || relative.startsWith("../")) {
                    throw new IOException("Invalid theme package entry");
                }
                zip.putNextEntry(new ZipEntry(relative));
                try (BufferedInputStream input = new BufferedInputStream(
                    new FileInputStream(safeChild))) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
                }
                zip.closeEntry();
            } else {
                throw new IOException("Theme package contains an unsupported file type");
            }
        }
    }

    private void confirmRemoveTheme(LoadedTheme theme) {
        String id = theme.getMetadata().getId();
        Dialog dialog = createDialog();
        LinearLayout root = createRoot();
        root.addView(text("Remove theme?", 19, textColor(), true));
        root.addView(text(
            "'" + theme.getMetadata().getLabel() + "' will be removed from Yummy's imported theme storage. "
                + "This cannot be undone.",
            13, mutedColor(), false), marginParams(0, dp(7), 0, dp(16)));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = action("Cancel", false, dialog::dismiss);
        TextView remove = action("Remove theme", true, () -> {
            dialog.dismiss();
            removeImportedTheme(id);
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        removeParams.leftMargin = dp(8);
        actions.addView(remove, removeParams);
        root.addView(actions);

        dialog.setContentView(root);
        showDialog(dialog, 440);
    }

    private void removeImportedTheme(String themeId) {
        ThemeSelection before = selectionStore.read(snapshot);
        try {
            packageStorage.removeImportedTheme(themeId);
        } catch (Exception error) {
            Toast.makeText(activity,
                "Could not remove theme: " + safeMessage(error), Toast.LENGTH_LONG).show();
            showImportedThemes();
            return;
        }

        snapshot = registry.reload();
        selection = selectionStore.read(snapshot);
        List<String> failures = new java.util.ArrayList<>();

        if (!before.getAppColorSchemeId().equals(selection.getAppColorSchemeId())) {
            try {
                LoadedTheme fallback = snapshot.findById(selection.getAppColorSchemeId());
                if (fallback == null || !fallback.getMetadata().supports(ThemeTarget.APP_UI)) {
                    throw new IllegalStateException("No replacement App UI theme is available");
                }
                if (appThemeController == null) {
                    throw new IllegalStateException("App UI theme controller is unavailable");
                }
                appThemeController.applyTheme(fallback);
            } catch (Exception error) {
                failures.add("Application UI: " + safeMessage(error));
            }
        }

        if (!before.getEditorSyntaxThemeId().equals(selection.getEditorSyntaxThemeId())) {
            try {
                LoadedTheme fallback = snapshot.findById(selection.getEditorSyntaxThemeId());
                if (fallback == null || !fallback.getMetadata().supports(ThemeTarget.EDITOR_SYNTAX)) {
                    throw new IllegalStateException("No replacement Editor Syntax theme is available");
                }
                if (editorSyntaxThemeApplier == null) {
                    throw new IllegalStateException("Editor Syntax applier is unavailable");
                }
                editorSyntaxThemeApplier.apply(fallback);
            } catch (Exception error) {
                failures.add("Editor Syntax: " + safeMessage(error));
            }
        }

        String message = "Theme removed";
        if (!failures.isEmpty()) {
            message += ". Some active colors could not be refreshed: "
                + android.text.TextUtils.join("; ", failures);
        }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        showImportedThemes();
    }

    private void addSelectionSection(
        LinearLayout root,
        String heading,
        ThemeTarget target,
        String selectedId
    ) {
        TextView title = text(heading, 11, accentColor(), true);
        title.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams titleParams = marginParams(0, dp(18), 0, dp(7));
        root.addView(title, titleParams);

        LoadedTheme selected = snapshot.findById(selectedId);
        if (selected != null && !selected.getMetadata().supports(target)) selected = null;
        String value = selected == null
            ? "Unavailable · " + selectedId
            : selected.getMetadata().getLabel();
        TextView selectedLabel = text(value, 15, textColor(), true);
        root.addView(selectedLabel);

        String helper = target == ThemeTarget.APP_UI
            ? "Controls Yummy surfaces only. Terminal colors are not affected."
            : "Controls syntax highlighting in the Sora code editor only.";
        root.addView(text(helper, 12, mutedColor(), false),
            marginParams(0, dp(3), 0, dp(9)));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView choose = action("Choose theme", true, () -> showThemeChooser(target));
        TextView preview = action("Preview", false, () -> {
            LoadedTheme current = findSelected(target);
            if (current != null) showPreview(target, current);
            else showUnavailableMessage(selectedId);
        });
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        actions.addView(choose, actionParams);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        previewParams.leftMargin = dp(8);
        actions.addView(preview, previewParams);
        root.addView(actions);

        View divider = new View(activity);
        divider.setBackgroundColor(borderColor());
        root.addView(divider, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void showThemeChooser(ThemeTarget target) {
        List<LoadedTheme> themes = target == ThemeTarget.APP_UI
            ? snapshot.getAppUiThemes() : snapshot.getEditorSyntaxThemes();
        if (themes.isEmpty()) {
            showUnavailableMessage("No themes are available for " + targetLabel(target));
            return;
        }

        Dialog dialog = createDialog();
        LinearLayout root = createRoot();
        root.addView(text("Choose " + targetLabel(target), 20, textColor(), true));
        root.addView(text("Preview before applying a theme.", 13, mutedColor(), false),
            marginParams(0, dp(4), 0, dp(12)));

        for (LoadedTheme theme : themes) {
            addThemeChoice(root, dialog, target, theme);
        }

        TextView close = action("Back", false, dialog::dismiss);
        root.addView(close, marginParams(0, dp(12), 0, 0));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(backgroundColor());
        scroll.addView(root);
        dialog.setContentView(scroll);
        showDialog(dialog, 560);
    }

    private void addThemeChoice(
        LinearLayout root,
        Dialog chooserDialog,
        ThemeTarget target,
        LoadedTheme theme
    ) {
        String id = theme.getMetadata().getId();
        boolean selected = id.equals(selectedId(target));

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(rect(surfaceColor(), borderColor()));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(theme.getMetadata().getLabel(), 15, textColor(), true));
        String details = sourceLabel(theme) + " · " + appearanceLabel(theme);
        labels.addView(text(details, 11, mutedColor(), false),
            marginParams(0, dp(3), 0, 0));
        header.addView(labels, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (selected) header.addView(text("SELECTED", 10, accentColor(), true));
        card.addView(header);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView preview = action("Preview", false, () -> showPreview(target, theme));
        TextView use = action(selected ? "Selected" : "Use theme", true, () -> {
            chooserDialog.dismiss();
            applySelection(target, theme);
        });
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        buttonParams.topMargin = dp(9);
        buttons.addView(preview, buttonParams);
        LinearLayout.LayoutParams useParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        useParams.leftMargin = dp(8);
        useParams.topMargin = dp(9);
        buttons.addView(use, useParams);
        card.addView(buttons);
        LinearLayout.LayoutParams cardParams = marginParams(0, dp(5), 0, dp(5));
        root.addView(card, cardParams);
    }

    private void showPreview(ThemeTarget target, LoadedTheme theme) {
        Dialog dialog = createDialog();
        LinearLayout root = createRoot();
        root.addView(text(theme.getMetadata().getLabel(), 20, textColor(), true));
        root.addView(text(
            sourceLabel(theme) + " · " + appearanceLabel(theme) + " · "
                + theme.getMetadata().getId(),
            11, mutedColor(), false
        ), marginParams(0, dp(4), 0, dp(10)));

        String author = theme.getMetadata().getAuthor();
        String license = theme.getMetadata().getLicense();
        if (author != null || license != null) {
            StringBuilder attribution = new StringBuilder();
            if (author != null) attribution.append("Author: ").append(author);
            if (license != null) {
                if (attribution.length() > 0) attribution.append("\n");
                attribution.append("License: ").append(license);
            }
            root.addView(text(attribution.toString(), 12, mutedColor(), false),
                marginParams(0, 0, 0, dp(12)));
        }

        if (target == ThemeTarget.APP_UI) {
            addAppUiPreview(root, theme);
        } else {
            addEditorSyntaxPreview(root, theme);
        }

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView close = action("Close", false, dialog::dismiss);
        TextView apply = action("Use this theme", true, () -> {
            dialog.dismiss();
            applySelection(target, theme);
        });
        actions.addView(close, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        applyParams.leftMargin = dp(8);
        actions.addView(apply, applyParams);
        LinearLayout.LayoutParams actionsParams = marginParams(0, dp(16), 0, 0);
        root.addView(actions, actionsParams);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(backgroundColor());
        scroll.addView(root);
        dialog.setContentView(scroll);
        showDialog(dialog, 560);
    }

    private void addAppUiPreview(LinearLayout root, LoadedTheme theme) {
        AppThemeFallbacks fallbacks = AppThemeFallbacks.fromCurrentYummyResources(
            activity.getResources(), activity.getPackageName());
        ResolvedAppTheme palette = new AppThemeAdapter().resolve(theme, fallbacks);
        root.addView(text("APPLICATION UI PALETTE", 11, accentColor(), true),
            marginParams(0, 0, 0, dp(7)));

        AppThemeColorRole[] roles = {
            AppThemeColorRole.APP_BACKGROUND,
            AppThemeColorRole.TOOLBAR_BACKGROUND,
            AppThemeColorRole.SIDEBAR_BACKGROUND,
            AppThemeColorRole.EDITOR_BACKGROUND,
            AppThemeColorRole.TAB_ACTIVE_BACKGROUND,
            AppThemeColorRole.STATUS_BAR_BACKGROUND,
            AppThemeColorRole.PRIMARY_TEXT,
            AppThemeColorRole.SECONDARY_TEXT,
            AppThemeColorRole.ACCENT,
            AppThemeColorRole.DIVIDER
        };
        for (AppThemeColorRole role : roles) {
            Integer color = palette.getColor(role);
            addColorRow(root, roleLabel(role), color,
                palette.getSourceColorIdentifier(role));
        }
        root.addView(text(
            "This is a palette preview. Only Views explicitly bound through AppThemeController "
                + "receive these colors; terminal Views remain excluded.",
            11, mutedColor(), false
        ), marginParams(0, dp(10), 0, 0));
    }

    private void addEditorSyntaxPreview(LinearLayout root, LoadedTheme theme) {
        boolean light = isLight(theme.getMetadata().getAppearance());
        int previewBackground = light ? Color.rgb(248, 248, 248) : Color.rgb(30, 30, 30);
        int previewForeground = light ? Color.rgb(40, 40, 40) : Color.rgb(212, 212, 212);

        root.addView(text("EDITOR SYNTAX SAMPLE", 11, accentColor(), true),
            marginParams(0, 0, 0, dp(7)));

        TextView code = new TextView(activity);
        code.setTextSize(13);
        code.setTextColor(previewForeground);
        code.setTypeface(Typeface.MONOSPACE);
        code.setPadding(dp(13), dp(13), dp(13), dp(13));
        code.setGravity(Gravity.START);
        code.setBackground(rect(previewBackground, borderColor()));
        code.setText(buildSyntaxSample(theme));
        root.addView(code, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String note = theme.getColorTheme().getTokenColorsReference() == null
            ? "Preview uses a lightweight scope-to-token approximation. Sora applies the complete "
                + "TextMate selectors and grammar when the theme is selected."
            : "External TextMate reference: " + theme.getColorTheme().getTokenColorsReference()
                + ". The preview is illustrative; Sora loads the referenced package resource.";
        root.addView(text(note, 11, mutedColor(), false),
            marginParams(0, dp(9), 0, 0));
        if (Boolean.TRUE.equals(theme.getColorTheme().getSemanticHighlighting())) {
            root.addView(text(
                "This theme declares semantic highlighting. Semantic token rendering depends "
                    + "on editor support and is not inferred by this preview.",
                11, mutedColor(), false
            ), marginParams(0, dp(6), 0, 0));
        }
    }

    private SpannableStringBuilder buildSyntaxSample(LoadedTheme theme) {
        String source = "def greet(name):\n"
            + "    message = \"Hello, \" + name\n"
            + "    count = 42\n"
            + "    # Welcome to Yummy\n"
            + "    return message\n";
        SpannableStringBuilder styled = new SpannableStringBuilder(source);
        SyntaxStyle keyword = findSyntaxStyle(theme, "keyword", Color.rgb(197, 134, 192));
        SyntaxStyle function = findSyntaxStyle(theme, "entity.name.function", Color.rgb(220, 220, 170));
        SyntaxStyle string = findSyntaxStyle(theme, "string", Color.rgb(206, 145, 120));
        SyntaxStyle number = findSyntaxStyle(theme, "constant.numeric", Color.rgb(181, 206, 168));
        SyntaxStyle comment = findSyntaxStyle(theme, "comment", Color.rgb(106, 153, 85));
        SyntaxStyle variable = findSyntaxStyle(theme, "variable", Color.rgb(156, 220, 254));

        applyTokenStyle(styled, "def", keyword);
        applyTokenStyle(styled, "return", keyword);
        applyTokenStyle(styled, "greet", function);
        applyTokenStyle(styled, "name", variable);
        applyTokenStyle(styled, "message", variable);
        applyTokenStyle(styled, "\"Hello, \"", string);
        applyTokenStyle(styled, "42", number);
        applyTokenStyle(styled, "# Welcome to Yummy", comment);
        return styled;
    }

    private static void applyTokenStyle(
        SpannableStringBuilder text,
        String token,
        SyntaxStyle style
    ) {
        int start = text.toString().indexOf(token);
        if (start < 0 || token.isEmpty()) return;
        int end = start + token.length();
        text.setSpan(new ForegroundColorSpan(style.color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int typefaceStyle = (style.bold ? Typeface.BOLD : Typeface.NORMAL)
            | (style.italic ? Typeface.ITALIC : Typeface.NORMAL);
        if (typefaceStyle != Typeface.NORMAL) {
            text.setSpan(new StyleSpan(typefaceStyle), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (style.underline) {
            text.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (style.strikethrough) {
            text.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static SyntaxStyle findSyntaxStyle(LoadedTheme theme, String category, int fallback) {
        JSONArray rules = theme.getColorTheme().getTokenColors();
        if (rules == null) return new SyntaxStyle(fallback, false, false);
        SyntaxStyle result = new SyntaxStyle(fallback, false, false);
        String wanted = category.toLowerCase(Locale.ROOT);
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null || !scopeMatches(rule.opt("scope"), wanted)) continue;
            JSONObject settings = rule.optJSONObject("settings");
            if (settings == null) continue;
            String foreground = settings.optString("foreground", null);
            if (foreground != null) {
                try {
                    result.color = AppThemeColorValueParser.parseHexColor(foreground);
                } catch (IllegalArgumentException ignored) {
                    // Keep the previous matching rule or the preview fallback.
                }
            }
            if (settings.has("fontStyle")) {
                applyPreviewFontStyle(result, settings.optString("fontStyle", ""));
            }
        }
        return result;
    }

    /**
     * Mirrors TextMate fontStyle flags for the lightweight preview.
     * An explicit empty/normal/regular value clears styles from earlier matching rules.
     * Unknown words are ignored; the original tokenColors JSON is retained for Sora.
     */
    private static void applyPreviewFontStyle(SyntaxStyle result, String fontStyle) {
        result.bold = false;
        result.italic = false;
        result.underline = false;
        result.strikethrough = false;
        if (fontStyle == null || fontStyle.trim().isEmpty()) return;

        String normalized = fontStyle.trim().toLowerCase(Locale.ROOT);
        String[] styles = normalized.split("\\s+");
        for (String style : styles) {
            switch (style) {
                case "bold":
                    result.bold = true;
                    break;
                case "italic":
                case "oblique":
                    result.italic = true;
                    break;
                case "underline":
                    result.underline = true;
                    break;
                case "strikethrough":
                case "strike":
                case "line-through":
                    result.strikethrough = true;
                    break;
                case "normal":
                case "regular":
                case "none":
                case "plain":
                    // No decoration; flags were reset before parsing this rule.
                    break;
                default:
                    // Unknown style words do not prevent the rest of the preview.
                    break;
            }
        }
    }

    private static boolean scopeMatches(Object rawScope, String wanted) {
        if (rawScope instanceof String) {
            return scopeMatchesText((String) rawScope, wanted);
        }
        if (rawScope instanceof JSONArray) {
            JSONArray scopes = (JSONArray) rawScope;
            for (int i = 0; i < scopes.length(); i++) {
                if (scopeMatchesText(scopes.optString(i), wanted)) return true;
            }
        }
        return false;
    }

    private static boolean scopeMatchesText(String scope, String wanted) {
        if (scope == null) return false;
        String[] selectors = scope.toLowerCase(Locale.ROOT).split(",");
        for (String selector : selectors) {
            String normalized = selector.trim();
            if (normalized.contains(wanted)) return true;
            if ("entity.name.function".equals(wanted)
                && normalized.contains("support.function")) return true;
            if ("constant.numeric".equals(wanted)
                && normalized.contains("constant.numeric")) return true;
        }
        return false;
    }

    private void addColorRow(
        LinearLayout root,
        String label,
        Integer color,
        String sourceIdentifier
    ) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View swatch = new View(activity);
        swatch.setBackground(rect(color == null ? borderColor() : color, borderColor()));
        row.addView(swatch, new LinearLayout.LayoutParams(dp(32), dp(20)));
        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, 0, 0);
        labels.addView(text(label, 13, textColor(), false));
        String colorValue = color == null ? "Not mapped" : String.format(Locale.ROOT, "#%08X", color);
        if (sourceIdentifier != null) colorValue += " · " + sourceIdentifier;
        labels.addView(text(colorValue, 10, mutedColor(), false));
        row.addView(labels, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row, marginParams(0, dp(4), 0, dp(4)));
    }

    private void applySelection(ThemeTarget target, LoadedTheme theme) {
        try {
            if (target == ThemeTarget.APP_UI) {
                if (appThemeController == null) {
                    throw new IllegalStateException("AppThemeController is not connected");
                }
                appThemeController.applyTheme(theme);
                selectionStore.selectAppUiTheme(theme.getMetadata().getId());
            } else {
                if (editorSyntaxThemeApplier == null) {
                    throw new IllegalStateException("Editor Syntax applier is not connected");
                }
                editorSyntaxThemeApplier.apply(theme);
                selectionStore.selectEditorSyntaxTheme(theme.getMetadata().getId());
            }
            selection = selectionStore.read(snapshot);
            Toast.makeText(activity, targetLabel(target) + " updated", Toast.LENGTH_SHORT).show();
            showSettingsDialog();
        } catch (Exception error) {
            Toast.makeText(activity,
                "Could not apply theme: " + safeMessage(error), Toast.LENGTH_LONG).show();
            showSettingsDialog();
        }
    }

    private LoadedTheme findSelected(ThemeTarget target) {
        String id = selectedId(target);
        LoadedTheme theme = snapshot.findById(id);
        return theme != null && theme.getMetadata().supports(target) ? theme : null;
    }

    private String selectedId(ThemeTarget target) {
        return target == ThemeTarget.APP_UI
            ? selection.getAppColorSchemeId() : selection.getEditorSyntaxThemeId();
    }

    private void showUnavailableMessage(String detail) {
        Toast.makeText(activity, detail, Toast.LENGTH_SHORT).show();
    }

    private Dialog createDialog() {
        Dialog dialog = new Dialog(activity);
        dialog.setOnDismissListener(ignored -> {
            if (activeDialog == dialog) activeDialog = null;
        });
        return dialog;
    }

    private void showDialog(Dialog dialog, int maxWidthDp) {
        if (activeDialog != null && activeDialog != dialog) activeDialog.dismiss();
        activeDialog = dialog;
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int desiredWidth = Math.min(screenWidth - dp(32), dp(maxWidthDp));
            window.setLayout(Math.max(dp(260), desiredWidth),
                WindowManager.LayoutParams.WRAP_CONTENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    private LinearLayout createRoot() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackground(rect(backgroundColor(), borderColor()));
        return root;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value == null ? "" : value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (uiTypeface != null) {
            view.setTypeface(uiTypeface, bold ? Typeface.BOLD : Typeface.NORMAL);
        } else if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private TextView action(String label, boolean primary, Runnable callback) {
        TextView view = text(label, 13, primary ? accentForegroundColor() : textColor(), true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(rect(primary ? accentColor() : surfaceColor(), borderColor()));
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(ignored -> callback.run());
        return view;
    }

    private GradientDrawable rect(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(0f);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams marginParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static String targetLabel(ThemeTarget target) {
        return target == ThemeTarget.APP_UI ? "Application UI Color Scheme" : "Editor Syntax Theme";
    }

    private static String sourceLabel(LoadedTheme theme) {
        return theme.getMetadata().getSource() == ThemeSource.BUILT_IN ? "Built-in" : "Imported";
    }

    private static String appearanceLabel(LoadedTheme theme) {
        ThemeAppearance appearance = theme.getMetadata().getAppearance();
        switch (appearance) {
            case DARK: return "Dark";
            case LIGHT: return "Light";
            case HIGH_CONTRAST_DARK: return "High Contrast Dark";
            case HIGH_CONTRAST_LIGHT: return "High Contrast Light";
            default: return "Unknown appearance";
        }
    }

    private static boolean isLight(ThemeAppearance appearance) {
        return appearance == ThemeAppearance.LIGHT
            || appearance == ThemeAppearance.HIGH_CONTRAST_LIGHT;
    }

    private static String roleLabel(AppThemeColorRole role) {
        return role.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private int backgroundColor() {
        return appColor(AppThemeColorRole.APP_BACKGROUND, COLOR_BACKGROUND);
    }

    private int surfaceColor() {
        return appColor(AppThemeColorRole.SURFACE_BACKGROUND, COLOR_SURFACE);
    }

    private int borderColor() {
        return appColor(AppThemeColorRole.DIVIDER, COLOR_BORDER);
    }

    private int textColor() {
        return appColor(AppThemeColorRole.PRIMARY_TEXT, COLOR_TEXT);
    }

    private int mutedColor() {
        return appColor(AppThemeColorRole.SECONDARY_TEXT, COLOR_MUTED);
    }

    private int accentColor() {
        return appColor(AppThemeColorRole.ACCENT, COLOR_ACCENT);
    }

    private int accentForegroundColor() {
        return appColor(AppThemeColorRole.ACCENT_FOREGROUND, Color.BLACK);
    }

    private int appColor(AppThemeColorRole role, int fallback) {
        if (appThemeController == null || appThemeController.getCurrentTheme() == null) {
            return fallback;
        }
        return appThemeController.getCurrentTheme().getColorOr(role, fallback);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error == null ? "Unknown error" : error.getClass().getSimpleName()
            : message;
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Theme settings must be shown on the Android main thread");
        }
    }

    private static final class SyntaxStyle {
        int color;
        boolean bold;
        boolean italic;
        boolean underline;
        boolean strikethrough;

        SyntaxStyle(int color, boolean bold, boolean italic) {
            this.color = color;
            this.bold = bold;
            this.italic = italic;
        }
    }
}
