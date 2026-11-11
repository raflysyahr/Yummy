package com.termux.app.theme.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
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
import com.termux.app.theme.registry.ThemeRegistrySnapshot;
import com.termux.app.theme.registry.YummyThemeRegistry;
import com.termux.app.theme.settings.ThemeSelectionStore;
import com.termux.app.theme.storage.ThemePackageStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

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

        LoadedTheme appTheme = snapshot.findById(selection.getAppColorSchemeId());
        if (appTheme != null && appTheme.getMetadata().supports(ThemeTarget.APP_UI)) {
            try {
                if (appThemeController == null) {
                    throw new IllegalStateException("AppThemeController is not connected");
                }
                appThemeController.applyTheme(appTheme);
            } catch (Exception error) {
                failures.add("App UI: " + safeMessage(error));
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
                failures.add("Editor Syntax: " + safeMessage(error));
            }
        }

        if (!failures.isEmpty()) {
            Toast.makeText(activity, "Some saved themes could not be applied: "
                + android.text.TextUtils.join("; ", failures), Toast.LENGTH_LONG).show();
        }
    }

    /** Dismisses the current settings/preview dialog, for example from Activity.onDestroy(). */
    public void dismiss() {
        requireMainThread();
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
     * Forward Activity.onActivityResult to this method. Returns true when the result belongs
     * to the standalone VS Code JSON importer.
     */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        requireMainThread();
        return themeImportController != null
            && themeImportController.onActivityResult(requestCode, resultCode, data);
    }

    private void startThemeImport() {
        requireMainThread();
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
                        "Theme imported. Choose it from the appropriate theme selector.",
                        Toast.LENGTH_LONG).show();
                    showSettingsDialog();
                }
            );
        }
        themeImportController.launchPicker();
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

        TextView remove = action("Remove", false, () -> {
            listDialog.dismiss();
            confirmRemoveTheme(theme);
        });
        card.addView(remove);
        root.addView(card, marginParams(0, 0, 0, dp(8)));
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
        if (style.bold) {
            text.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (style.italic) {
            text.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
            String fontStyle = settings.optString("fontStyle", "").toLowerCase(Locale.ROOT);
            if (fontStyle.contains("bold")) result.bold = true;
            if (fontStyle.contains("italic")) result.italic = true;
        }
        return result;
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

        SyntaxStyle(int color, boolean bold, boolean italic) {
            this.color = color;
            this.bold = bold;
            this.italic = italic;
        }
    }
}
