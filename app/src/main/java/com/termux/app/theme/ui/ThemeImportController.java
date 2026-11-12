package com.termux.app.theme.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;

import com.termux.app.theme.model.AppThemeColorRole;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.registry.YummyThemeRegistry;
import com.termux.app.theme.settings.VSCodeThemeJsonImporter;
import com.termux.app.theme.storage.ThemePackageStorage;
import com.termux.app.theme.storage.ThemeStorageException;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SAF-based import flow for standalone VS Code Color Theme JSON documents.
 *
 * <p>The controller previews and validates a theme before installation. A duplicate imported
 * theme requires explicit replacement confirmation; a built-in ID is never overwritten and
 * receives a collision-free imported ID instead.</p>
 */
public final class ThemeImportController {
    public static final int REQUEST_CODE = 0x594D;
    private static final String DEBUG_PREFS = "yummy_theme_import_debug";
    private static final String DEBUG_REPORT_KEY = "report";
    private static final String DEBUG_SEQUENCE_KEY = "sequence";

    public interface Listener {
        void onThemeImported(String themeId);
    }

    private static final int FALLBACK_BACKGROUND = Color.rgb(12, 13, 15);
    private static final int FALLBACK_SURFACE = Color.rgb(22, 24, 28);
    private static final int FALLBACK_BORDER = Color.rgb(39, 42, 48);
    private static final int FALLBACK_TEXT = Color.rgb(239, 240, 242);
    private static final int FALLBACK_MUTED = Color.rgb(145, 150, 158);
    private static final int FALLBACK_ACCENT = Color.rgb(34, 197, 94);
    private final Activity activity;
    private final YummyThemeRegistry registry;
    private final ThemePackageStorage storage;
    private final AppThemeController appThemeController;
    private final Typeface uiTypeface;
    private final Listener listener;
    private final VSCodeThemeJsonImporter importer = new VSCodeThemeJsonImporter();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private Dialog activeDialog;
    private volatile boolean released;
    private final StringBuilder debugReport = new StringBuilder();
    private int debugSequence;

    private synchronized void debug(String stage, String detail) {
        debugReport.append(String.format(java.util.Locale.ROOT, "%02d [%s] %s\n",
            ++debugSequence, stage, detail == null ? "" : detail));
        SharedPreferences preferences =
            activity.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE);
        preferences.edit()
            .putString(DEBUG_REPORT_KEY, debugReport.toString())
            .putInt(DEBUG_SEQUENCE_KEY, debugSequence)
            .apply();
    }

    private void restoreDebugReport() {
        SharedPreferences preferences =
            activity.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE);
        String savedReport = preferences.getString(DEBUG_REPORT_KEY, "");
        debugReport.setLength(0);
        if (savedReport != null) debugReport.append(savedReport);
        debugSequence = preferences.getInt(DEBUG_SEQUENCE_KEY, 0);
    }

    private void clearDebugReport() {
        synchronized (this) {
            debugReport.setLength(0);
            debugSequence = 0;
            activity.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
                .edit().remove(DEBUG_REPORT_KEY).remove(DEBUG_SEQUENCE_KEY).apply();
        }
    }

    private void showDebugDialog(String title, String detail) {
        showDebugDialog(title, detail, "Close", null);
    }

    private void showDebugDialog(
        String title, String detail, String closeLabel, Runnable closeAction
    ) {
        if (released || activity.isFinishing()) {
            debug("DIALOG_SKIPPED", "released=" + released
                + ", activityFinishing=" + activity.isFinishing()
                + ", title=" + title);
            return;
        }
        debug("DIALOG", title + ": " + detail);
        Dialog dialog = createDialog();
        LinearLayout root = createRoot();
        root.addView(text(title, 18, textColor(), true));
        ScrollView scroll = new ScrollView(activity);
        TextView report = text(debugReport.toString() + "\n" + detail, 12, textColor(), false);
        report.setTextIsSelectable(true);
        scroll.addView(report);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(260));
        scrollParams.topMargin = dp(10);
        root.addView(scroll, scrollParams);
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(action("Copy Debug Report", true, () -> {
            ClipboardManager clipboard = (ClipboardManager)
                activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Yummy Theme Import Debug",
                    debugReport.toString() + "\n" + detail));
                Toast.makeText(activity, "Debug report copied", Toast.LENGTH_SHORT).show();
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = action(closeLabel, false, () -> {
            dialog.dismiss();
            if (closeAction != null) closeAction.run();
        });
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        closeParams.leftMargin = dp(6);
        buttons.addView(close, closeParams);
        LinearLayout.LayoutParams buttonParams = marginParams(0, dp(10), 0, 0);
        root.addView(buttons, buttonParams);
        dialog.setContentView(root);
        showDialog(dialog, 520);
    }

    public ThemeImportController(
        Activity activity,
        YummyThemeRegistry registry,
        ThemePackageStorage storage,
        AppThemeController appThemeController,
        Typeface uiTypeface,
        Listener listener
    ) {
        if (activity == null) throw new IllegalArgumentException("Activity is required");
        if (registry == null) throw new IllegalArgumentException("YummyThemeRegistry is required");
        if (storage == null) throw new IllegalArgumentException("ThemePackageStorage is required");
        this.activity = activity;
        this.registry = registry;
        this.storage = storage;
        this.appThemeController = appThemeController;
        this.uiTypeface = uiTypeface;
        this.listener = listener;
        restoreDebugReport();
    }

    /** Opens Android's document picker. The returned result is forwarded to onActivityResult. */
    public void launchPicker() {
        requireMainThread();
        if (released) return;
        clearDebugReport();
        debug("PICKER", "Launching ACTION_OPEN_DOCUMENT");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Do not provide EXTRA_MIME_TYPES here. Several Android document providers
        // incorrectly classify .json files and either disable them or return no URI.
        // The importer validates the selected document content after it is opened.
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQUEST_CODE);
        } catch (ActivityNotFoundException error) {
            debug("PICKER_FAILED", error.toString());
            showDebugDialog("Cannot open document picker", error.toString());
        }
    }

    /** Returns true when the request code belongs to this import flow. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE) return false;
        requireMainThread();
        debug("ACTIVITY_RESULT", "resultCode=" + resultCode
            + ", data=" + (data != null)
            + ", uri=" + (data == null ? "null" : String.valueOf(data.getData())));
        if (released) {
            debug("CONTROLLER_RELEASED", "Result received after release");
            showDebugDialog("Import controller was released", debugReport.toString());
            return true;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            debug("NO_DOCUMENT", "Picker returned no usable document URI");
            showDebugDialog("No JSON file was returned",
                "Explorer returned resultCode=" + resultCode + ". Please retry and select a file.");
            return true;
        }
        inspectUri(data.getData());
        return true;
    }

    /** Releases dialogs and the file-reading worker when the owning Activity is destroyed. */
    public void release() {
        requireMainThread();
        debug("CONTROLLER_RELEASE", "Import controller released with Activity lifecycle");
        released = true;
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
        ioExecutor.shutdownNow();
    }

    private void inspectUri(Uri uri) {
        debug("URI_RECEIVED", uri.toString());
        debug("MIME_TYPE", String.valueOf(activity.getContentResolver().getType(uri)));
        showProgress("Reading theme JSON…");
        ioExecutor.execute(() -> {
            VSCodeThemeJsonImporter.Preview preview;
            try {
                String displayName = queryDisplayName(uri);
                debug("DISPLAY_NAME", displayName);
                InputStream input = activity.getContentResolver().openInputStream(uri);
                if (input == null) throw new IllegalStateException("Could not open selected document");
                debug("READ", "InputStream opened");
                preview = importer.inspect(input, displayName);
                debug("JSON_PARSE", "SUCCESS; name=" + preview.getName()
                    + "; id=" + preview.getSuggestedId()
                    + "; appUi=" + preview.supportsAppUi()
                    + "; editorSyntax=" + preview.supportsEditorSyntax());
            } catch (Exception error) {
                debug("READ_OR_PARSE_FAILED", error.toString());
                for (StackTraceElement frame : error.getStackTrace()) debug("STACK", frame.toString());
                postToMain(() -> showDebugDialog("Could not read or parse theme", safeMessage(error)));
                return;
            }
            postToMain(() -> showDebugDialog(
                "JSON read and parsed successfully",
                "Theme: " + preview.getName()
                    + "\nThe preview is ready. Continue to inspect and import this theme.",
                "Continue to Preview",
                () -> beginPreview(preview)
            ));
        });
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(
                uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (RuntimeException ignored) {
            // Some DocumentProviders do not expose DISPLAY_NAME. The URI fallback is sufficient.
        } finally {
            if (cursor != null) cursor.close();
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null ? "Imported Theme.json" : fallback;
    }

    private void beginPreview(VSCodeThemeJsonImporter.Preview preview) {
        debug("PREVIEW", "Showing preview for " + preview.getName());
        if (released || activity.isFinishing()) {
            debug("PREVIEW_SKIPPED", "released=" + released
                + ", activityFinishing=" + activity.isFinishing());
            return;
        }
        dismissActiveDialog();
        if (preview.getAppearance() == ThemeAppearance.UNKNOWN) {
            showAppearanceChooser(preview);
        } else {
            showPreview(preview, preview.getAppearance());
        }
    }

    private void showAppearanceChooser(VSCodeThemeJsonImporter.Preview preview) {
        Dialog dialog = createDialog();
        LinearLayout root = createRoot();
        root.addView(text("Choose theme appearance", 19, textColor(), true));
        root.addView(text(
            "The JSON does not declare a supported VS Code 'type'. Choose the appearance "
                + "so Yummy can create the required package metadata.",
            13, mutedColor(), false), marginParams(0, dp(6), 0, dp(12)));
        addAppearanceChoice(root, dialog, preview, ThemeAppearance.DARK, "Dark");
        addAppearanceChoice(root, dialog, preview, ThemeAppearance.LIGHT, "Light");
        addAppearanceChoice(root, dialog, preview, ThemeAppearance.HIGH_CONTRAST_DARK,
            "High Contrast Dark");
        addAppearanceChoice(root, dialog, preview, ThemeAppearance.HIGH_CONTRAST_LIGHT,
            "High Contrast Light");
        root.addView(action("Cancel", false, dialog::dismiss), marginParams(0, dp(12), 0, 0));
        showDialog(dialog, 420);
    }

    private void addAppearanceChoice(
        LinearLayout root,
        Dialog dialog,
        VSCodeThemeJsonImporter.Preview preview,
        ThemeAppearance appearance,
        String label
    ) {
        TextView option = action(label, false, () -> {
            dialog.dismiss();
            showPreview(preview, appearance);
        });
        root.addView(option, marginParams(0, dp(4), 0, dp(4)));
    }

    private void showPreview(VSCodeThemeJsonImporter.Preview preview, ThemeAppearance appearance) {
        if (released || activity.isFinishing()) return;
        Dialog dialog = createDialog();
        LinearLayout root = createRoot();
        root.addView(text("Import VS Code Color Theme", 19, textColor(), true));
        root.addView(text(preview.getName(), 16, accentColor(), true),
            marginParams(0, dp(9), 0, dp(4)));
        root.addView(text("ID: " + preview.getSuggestedId(), 12, mutedColor(), false));
        root.addView(text("Appearance: " + appearanceLabel(appearance), 12, mutedColor(), false),
            marginParams(0, dp(3), 0, dp(10)));

        List<String> capabilities = new ArrayList<>();
        if (preview.supportsAppUi()) capabilities.add("Application UI Color Scheme");
        if (preview.supportsEditorSyntax()) capabilities.add("Editor Syntax Theme (TextMate)");
        root.addView(text("Supported targets", 11, accentColor(), true));
        root.addView(text(join(capabilities, "\n"), 13, textColor(), false),
            marginParams(0, dp(4), 0, dp(10)));

        if (!preview.getWarnings().isEmpty()) {
            root.addView(text("Import notes", 11, accentColor(), true));
            for (String warning : preview.getWarnings()) {
                root.addView(text("• " + warning, 12, mutedColor(), false),
                    marginParams(0, dp(4), 0, dp(3)));
            }
        }
        root.addView(text(
            "The source JSON is not modified. Yummy creates its own metadata.json and stores "
                + "the imported package in app-private storage. The theme will not be selected "
                + "automatically.",
            12, mutedColor(), false), marginParams(0, dp(12), 0, dp(12)));

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = action("Cancel", false, dialog::dismiss);
        TextView confirm = action("Import theme", true, () -> {
            dialog.dismiss();
            prepareInstall(preview, appearance);
        });
        buttons.addView(cancel, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        confirmParams.leftMargin = dp(8);
        buttons.addView(confirm, confirmParams);
        root.addView(buttons);
        showDialog(dialog, 500);
    }

    private void prepareInstall(VSCodeThemeJsonImporter.Preview preview, ThemeAppearance appearance) {
        debug("INSTALL_PREPARE", "id=" + preview.getSuggestedId());
        try {
            ThemeRegistrySnapshotHolder current = refreshRegistry();
            String suggestedId = preview.getSuggestedId();
            LoadedTheme collision = current.find(suggestedId);
            boolean storedImport = storage.hasImportedTheme(suggestedId);

            if (collision != null && collision.getMetadata().getSource() == ThemeSource.BUILT_IN) {
                suggestedId = findUniqueId(suggestedId, current);
                install(preview, appearance, suggestedId, false);
                return;
            }
            if ((collision != null && collision.getMetadata().getSource() == ThemeSource.IMPORTED)
                || storedImport) {
                final String replaceId = suggestedId;
                showReplaceConfirmation(preview, appearance, replaceId);
                return;
            }
            install(preview, appearance, suggestedId, false);
        } catch (Exception error) {
            debug("INSTALL_PREPARE_FAILED", error.toString());
            showDebugDialog("Could not inspect installed themes", safeMessage(error));
        }
    }

    private ThemeRegistrySnapshotHolder refreshRegistry() {
        return new ThemeRegistrySnapshotHolder(registry.reload());
    }

    private String findUniqueId(String base, ThemeRegistrySnapshotHolder snapshot)
        throws ThemeStorageException {
        String suffix = "-imported";
        String candidate = fitId(base, suffix);
        int index = 2;
        while (snapshot.contains(candidate) || storage.hasImportedTheme(candidate)) {
            suffix = "-" + index++;
            candidate = fitId(base, suffix);
        }
        return candidate;
    }

    private static String fitId(String base, String suffix) {
        int maximumBase = Math.max(1, 64 - suffix.length());
        String prefix = base.length() > maximumBase ? base.substring(0, maximumBase) : base;
        return prefix.replaceAll("[-._]+$", "") + suffix;
    }

    private void showReplaceConfirmation(
        VSCodeThemeJsonImporter.Preview preview,
        ThemeAppearance appearance,
        String themeId
    ) {
        Dialog dialog = createDialog();
        LinearLayout root = createRoot();
        root.addView(text("Replace imported theme?", 19, textColor(), true));
        root.addView(text(
            "An imported theme with ID '" + themeId + "' already exists. Its package will be "
                + "replaced after the new JSON has passed validation. Built-in themes cannot be replaced.",
            13, mutedColor(), false), marginParams(0, dp(8), 0, dp(14)));
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(action("Cancel", false, dialog::dismiss),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView replace = action("Replace", true, () -> {
            dialog.dismiss();
            install(preview, appearance, themeId, true);
        });
        LinearLayout.LayoutParams replaceParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        replaceParams.leftMargin = dp(8);
        buttons.addView(replace, replaceParams);
        root.addView(buttons);
        showDialog(dialog, 460);
    }

    private void install(
        VSCodeThemeJsonImporter.Preview preview,
        ThemeAppearance appearance,
        String themeId,
        boolean replace
    ) {
        debug("INSTALL_START", "id=" + themeId + ", replace=" + replace);
        showProgress(replace ? "Replacing theme…" : "Importing theme…");
        ioExecutor.execute(() -> {
            try {
                String installedId = importer.install(
                    activity, storage, preview, appearance, themeId, replace);
                registry.reload();
                debug("INSTALL_SUCCESS", "installedId=" + installedId);
                postToMain(() -> {
                    dismissActiveDialog();
                    if (released) return;
                    showDebugDialog("Theme import completed",
                        "Imported " + preview.getName() + " as " + installedId
                            + ". Check Manage Imported Themes and choose the target theme.");
                    if (listener != null) listener.onThemeImported(installedId);
                });
            } catch (Exception error) {
                debug("INSTALL_FAILED", error.toString());
                for (StackTraceElement frame : error.getStackTrace()) debug("STACK", frame.toString());
                postToMain(() -> showDebugDialog("Theme import failed", safeMessage(error)));
            }
        });
    }

    private void showProgress(String message) {
        Dialog dialog = createDialog();
        LinearLayout root = createRoot();
        root.addView(text(message, 15, textColor(), true));
        dialog.setContentView(root);
        showDialog(dialog, 320);
    }

    private void showError(String title, String detail) {
        if (released || activity.isFinishing()) return;
        dismissActiveDialog();
        Dialog dialog = createDialog();
        LinearLayout root = createRoot();
        root.addView(text(title, 18, textColor(), true));
        root.addView(text(detail, 13, mutedColor(), false), marginParams(0, dp(8), 0, dp(14)));
        root.addView(action("Close", true, dialog::dismiss));
        showDialog(dialog, 440);
    }

    private void postToMain(Runnable action) {
        if (released) {
            debug("MAIN_CALLBACK_SKIPPED", "Controller was released before posting to main thread");
            return;
        }
        activity.runOnUiThread(() -> {
            if (released) {
                debug("MAIN_CALLBACK_SKIPPED", "Controller was released before main-thread callback");
                return;
            }
            if (activity.isFinishing()) {
                debug("MAIN_CALLBACK_SKIPPED", "Activity was finishing before main-thread callback");
                return;
            }
            action.run();
        });
    }

    private void dismissActiveDialog() {
        if (activeDialog != null) {
            Dialog current = activeDialog;
            activeDialog = null;
            current.dismiss();
        }
    }

    private Dialog createDialog() {
        Dialog dialog = new Dialog(activity);
        dialog.setOnDismissListener(ignored -> {
            if (activeDialog == dialog) activeDialog = null;
        });
        return dialog;
    }

    private void showDialog(Dialog dialog, int maxWidthDp) {
        dismissActiveDialog();
        activeDialog = dialog;
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int desiredWidth = Math.min(screenWidth - dp(32), dp(maxWidthDp));
            window.setLayout(Math.max(dp(260), desiredWidth), WindowManager.LayoutParams.WRAP_CONTENT);
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

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (uiTypeface != null) view.setTypeface(uiTypeface, bold ? Typeface.BOLD : Typeface.NORMAL);
        else if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
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

    private int backgroundColor() { return appColor(AppThemeColorRole.APP_BACKGROUND, FALLBACK_BACKGROUND); }
    private int surfaceColor() { return appColor(AppThemeColorRole.SURFACE_BACKGROUND, FALLBACK_SURFACE); }
    private int borderColor() { return appColor(AppThemeColorRole.DIVIDER, FALLBACK_BORDER); }
    private int textColor() { return appColor(AppThemeColorRole.PRIMARY_TEXT, FALLBACK_TEXT); }
    private int mutedColor() { return appColor(AppThemeColorRole.SECONDARY_TEXT, FALLBACK_MUTED); }
    private int accentColor() { return appColor(AppThemeColorRole.ACCENT, FALLBACK_ACCENT); }
    private int accentForegroundColor() {
        return appColor(AppThemeColorRole.ACCENT_FOREGROUND, Color.BLACK);
    }

    private int appColor(AppThemeColorRole role, int fallback) {
        if (appThemeController == null || appThemeController.getCurrentTheme() == null) return fallback;
        return appThemeController.getCurrentTheme().getColorOr(role, fallback);
    }

    private static String appearanceLabel(ThemeAppearance appearance) {
        switch (appearance) {
            case DARK: return "Dark";
            case LIGHT: return "Light";
            case HIGH_CONTRAST_DARK: return "High Contrast Dark";
            case HIGH_CONTRAST_LIGHT: return "High Contrast Light";
            default: return "Unknown";
        }
    }

    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error == null ? "Unknown error" : error.getClass().getSimpleName()
            : message;
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Theme import UI must be accessed on the Android main thread");
        }
    }

    /** Small adapter that avoids exposing or mutating registry internals in this controller. */
    private static final class ThemeRegistrySnapshotHolder {
        private final com.termux.app.theme.registry.ThemeRegistrySnapshot snapshot;

        ThemeRegistrySnapshotHolder(com.termux.app.theme.registry.ThemeRegistrySnapshot snapshot) {
            this.snapshot = snapshot;
        }

        LoadedTheme find(String id) { return snapshot.findById(id); }
        boolean contains(String id) { return snapshot.contains(id); }
    }
}
