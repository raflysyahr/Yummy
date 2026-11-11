package com.termux.app.theme.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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
    }

    /** Opens Android's document picker. The returned result is forwarded to onActivityResult. */
    public void launchPicker() {
        requireMainThread();
        if (released) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
            new String[] {"application/json", "text/json", "text/plain"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQUEST_CODE);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(activity, "No document picker is available", Toast.LENGTH_LONG).show();
        }
    }

    /** Returns true when the request code belongs to this import flow. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE) return false;
        requireMainThread();
        if (released || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }
        inspectUri(data.getData());
        return true;
    }

    /** Releases dialogs and the file-reading worker when the owning Activity is destroyed. */
    public void release() {
        requireMainThread();
        released = true;
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
        ioExecutor.shutdownNow();
    }

    private void inspectUri(Uri uri) {
        showProgress("Reading theme JSON…");
        ioExecutor.execute(() -> {
            VSCodeThemeJsonImporter.Preview preview;
            try {
                String displayName = queryDisplayName(uri);
                InputStream input = activity.getContentResolver().openInputStream(uri);
                if (input == null) throw new IllegalStateException("Could not open selected document");
                preview = importer.inspect(input, displayName);
            } catch (Exception error) {
                postToMain(() -> showError("Could not read theme", safeMessage(error)));
                return;
            }
            postToMain(() -> beginPreview(preview));
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
        if (released || activity.isFinishing()) return;
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
        } catch (ThemeStorageException error) {
            showError("Could not inspect installed themes", safeMessage(error));
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
        showProgress(replace ? "Replacing theme…" : "Importing theme…");
        ioExecutor.execute(() -> {
            try {
                String installedId = importer.install(
                    activity, storage, preview, appearance, themeId, replace);
                registry.reload();
                postToMain(() -> {
                    dismissActiveDialog();
                    if (released) return;
                    Toast.makeText(activity, "Imported theme: " + preview.getName(),
                        Toast.LENGTH_LONG).show();
                    if (listener != null) listener.onThemeImported(installedId);
                });
            } catch (Exception error) {
                postToMain(() -> showError("Theme import failed", safeMessage(error)));
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
        if (released) return;
        activity.runOnUiThread(() -> {
            if (!released && !activity.isFinishing()) action.run();
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
