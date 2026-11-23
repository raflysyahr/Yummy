package com.termux.app.theme.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.theme.adapter.AppThemeColorValueParser;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.VSCodeColorTheme;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Applies VS Code workbench colors to Yummy-owned editor chrome (toolbar, tabs, status bar,
 * shortcut row and editor popups). It deliberately excludes the terminal, file/language artwork,
 * TextMate syntax colors and the Sora document surface, which has its own controller.
 */
public final class EditorUiComponentThemeController {
    private static final String TAG_ACTIVE_TAB = "yummy-editor-tab-active";
    private static final String TAG_SELECTED_POPUP = "yummy-editor-popup-selected";

    private final Activity activity;
    private final Map<View, ViewState> baseStates = new IdentityHashMap<>();
    private final Map<View, ViewState> dynamicTabStates = new IdentityHashMap<>();
    private LoadedTheme currentTheme;
    private Palette palette;

    private static final class ViewState {
        final Drawable background;
        final ColorStateList text;
        final ColorStateList tint;

        ViewState(View view) {
            Drawable drawable = view.getBackground();
            background = drawable == null ? null : drawable.getConstantState() == null
                ? drawable : drawable.getConstantState().newDrawable(view.getResources()).mutate();
            text = view instanceof TextView ? ((TextView) view).getTextColors() : null;
            tint = view instanceof ImageView ? ((ImageView) view).getImageTintList() : null;
        }

        void restore(View view) {
            view.setBackground(background);
            if (text != null && view instanceof TextView) {
                ((TextView) view).setTextColor(text);
            }
            if (view instanceof ImageView) {
                ((ImageView) view).setImageTintList(tint);
            }
        }
    }

    static final class Palette {
        Integer toolbarBackground, toolbarForeground;
        Integer tabsBackground, activeTabBackground, activeTabForeground;
        Integer inactiveTabBackground, inactiveTabForeground;
        Integer statusBackground, statusForeground;
        Integer panelBackground, panelForeground;
        Integer divider, popupBackground, popupForeground;
        Integer popupSelectedBackground, popupSelectedForeground;
        Integer buttonForeground;
    }

    public EditorUiComponentThemeController(Activity activity) {
        if (activity == null) throw new IllegalArgumentException("Activity is required");
        this.activity = activity;
        captureBaseStates();
    }

    /** Applies the selected Editor UI workbench palette without changing other theme targets. */
    public void apply(LoadedTheme theme) {
        if (theme == null) throw new IllegalArgumentException("Editor UI theme is required");
        restoreBaseStates();
        restoreDynamicTabStates();
        currentTheme = theme;
        palette = resolve(theme.getColorTheme());
        applyStaticViews();
        applyDynamicTabs();
    }

    /** Reapplies Editor UI after an independent App UI palette update. */
    public void refreshBaseAndReapply() {
        if (currentTheme == null) {
            captureBaseStates();
            return;
        }
        // The selected Editor UI target has precedence on editor chrome. Do not snapshot
        // our own current overrides as a new base when the App UI palette changes.
        palette = resolve(currentTheme.getColorTheme());
        applyStaticViews();
        applyDynamicTabs();
    }

    /** Called after EditorController recreates the tab strip. */
    public void refreshDynamicViews() {
        restoreDynamicTabStates();
        dynamicTabStates.clear();
        captureDynamicTabStates();
        if (currentTheme != null) applyDynamicTabs();
    }

    /** Styles the dynamically-created encoding popup using workbench widget/menu colors. */
    public void applyPopup(View popupRoot) {
        if (popupRoot == null || currentTheme == null || palette == null) return;
        if (palette.popupBackground != null) popupRoot.setBackgroundColor(palette.popupBackground);
        applyPopupChildren(popupRoot);
    }

    public Integer getPopupBackgroundColor() {
        return palette == null ? null : palette.popupBackground;
    }

    /** Styles editor-owned confirmation dialogs without changing the Activity or terminal theme. */
    public void applyDialog(AlertDialog dialog) {
        if (dialog == null || currentTheme == null || palette == null) return;

        Window window = dialog.getWindow();
        if (window != null && palette.popupBackground != null) {
            window.setBackgroundDrawable(new ColorDrawable(palette.popupBackground));
        }

        int titleId = activity.getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = titleId == 0 ? null : dialog.findViewById(titleId);
        setTextColor(title, palette.popupForeground);
        setTextColor(dialog.findViewById(android.R.id.message), palette.popupForeground);

        Integer actionForeground = palette.buttonForeground != null
            ? palette.buttonForeground : palette.popupForeground;
        for (int which : new int[] { AlertDialog.BUTTON_POSITIVE,
                AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL }) {
            Button button = dialog.getButton(which);
            setTextColor(button, actionForeground);
        }
    }

    private static void setTextColor(TextView view, Integer color) {
        if (view != null && color != null) view.setTextColor(color);
    }

    /** Removes only this target's overrides and restores the latest captured base appearance. */
    public void clear() {
        restoreBaseStates();
        restoreDynamicTabStates();
        currentTheme = null;
        palette = null;
    }

    static Palette resolve(VSCodeColorTheme document) {
        Palette p = new Palette();
        if (document == null) return p;
        p.toolbarBackground = color(document, "titleBar.activeBackground");
        p.toolbarForeground = color(document, "titleBar.activeForeground");
        p.tabsBackground = color(document, "editorGroupHeader.tabsBackground");
        p.activeTabBackground = color(document, "tab.activeBackground");
        p.activeTabForeground = color(document, "tab.activeForeground");
        p.inactiveTabBackground = color(document, "tab.inactiveBackground");
        p.inactiveTabForeground = color(document, "tab.inactiveForeground");
        p.statusBackground = color(document, "statusBar.background");
        p.statusForeground = color(document, "statusBar.foreground");
        p.panelBackground = color(document, "panel.background");
        p.panelForeground = color(document, "panel.foreground");
        p.divider = color(document, "editorGroup.border");
        p.popupBackground = firstColor(document, "editorWidget.background", "menu.background");
        p.popupForeground = firstColor(document, "editorWidget.foreground", "menu.foreground");
        p.popupSelectedBackground = firstColor(document,
            "list.activeSelectionBackground", "menu.selectionBackground");
        p.popupSelectedForeground = firstColor(document,
            "list.activeSelectionForeground", "menu.selectionForeground");
        p.buttonForeground = color(document, "button.foreground");
        return p;
    }

    static Integer firstColor(VSCodeColorTheme document, String first, String second) {
        Integer value = color(document, first);
        return value != null ? value : color(document, second);
    }

    static Integer color(VSCodeColorTheme document, String id) {
        String value = document.getColor(id);
        if (value == null || value.trim().isEmpty() || "default".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return AppThemeColorValueParser.parseHexColor(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void captureBaseStates() {
        baseStates.clear();
        dynamicTabStates.clear();
        capture(baseStates, activity.findViewById(R.id.yummy_app_bar));
        capture(baseStates, activity.findViewById(R.id.yummy_tab_bar));
        capture(baseStates, activity.findViewById(R.id.yummy_editor_status_bar));
        capture(baseStates, activity.findViewById(R.id.yummy_keys_row));
        capture(baseStates, activity.findViewById(R.id.yummy_editor_top_divider));
        capture(baseStates, activity.findViewById(R.id.yummy_editor_bottom_divider));
        capture(baseStates, activity.findViewById(R.id.yummy_editor_status_divider));
        capture(baseStates, activity.findViewById(R.id.yummy_btn_new_tab));
        capture(baseStates, activity.findViewById(R.id.yummy_btn_tabs_menu));
        captureForegroundTree(baseStates, activity.findViewById(R.id.yummy_app_bar));
        captureForegroundTree(baseStates, activity.findViewById(R.id.yummy_editor_status_bar));
        captureForegroundTree(baseStates, activity.findViewById(R.id.yummy_keys_row));
        captureDynamicTabStates();
    }

    private void captureDynamicTabStates() {
        View tabs = activity.findViewById(R.id.yummy_tabs_container);
        if (tabs instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) tabs;
            for (int i = 0; i < group.getChildCount(); i++) {
                captureTree(dynamicTabStates, group.getChildAt(i));
            }
        }
    }

    private static void capture(Map<View, ViewState> states, View view) {
        if (view != null && !states.containsKey(view)) states.put(view, new ViewState(view));
    }

    private void captureForegroundTree(Map<View, ViewState> states, View view) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (isProtectedArtwork(child)) continue;
            capture(states, child);
            if (child instanceof ViewGroup) captureForegroundTree(states, child);
        }
    }

    private void captureTree(Map<View, ViewState> states, View view) {
        capture(states, view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) captureTree(states, group.getChildAt(i));
        }
    }

    private static boolean isProtectedArtwork(View view) {
        int id = view.getId();
        return id == R.id.yummy_app_file_icon
            || id == R.id.yummy_status_lang_icon
            || id == R.id.yummy_session_save_icon
            || id == R.id.yummy_session_save_pill;
    }

    private void restoreBaseStates() {
        for (Map.Entry<View, ViewState> entry : baseStates.entrySet()) {
            entry.getValue().restore(entry.getKey());
        }
    }

    private void restoreDynamicTabStates() {
        for (Map.Entry<View, ViewState> entry : dynamicTabStates.entrySet()) {
            entry.getValue().restore(entry.getKey());
        }
    }

    private void applyStaticViews() {
        setBackground(R.id.yummy_app_bar, palette.toolbarBackground);
        setBackground(R.id.yummy_tab_bar, palette.tabsBackground);
        setBackground(R.id.yummy_editor_status_bar, palette.statusBackground);
        setBackground(R.id.yummy_keys_row, palette.panelBackground);
        setBackground(R.id.yummy_editor_top_divider, palette.divider);
        setBackground(R.id.yummy_editor_bottom_divider, palette.divider);
        setBackground(R.id.yummy_editor_status_divider, palette.divider);

        applyForegroundTree(activity.findViewById(R.id.yummy_app_bar), palette.toolbarForeground);
        applyForegroundTree(activity.findViewById(R.id.yummy_editor_status_bar), palette.statusForeground);
        applyForegroundTree(activity.findViewById(R.id.yummy_keys_row), palette.panelForeground);
        Integer tabActionForeground = palette.inactiveTabForeground != null
            ? palette.inactiveTabForeground : palette.toolbarForeground;
        applyImageTint(R.id.yummy_btn_new_tab, tabActionForeground);
        applyImageTint(R.id.yummy_btn_tabs_menu, tabActionForeground);
    }

    /** Applies a resolved background color when both the view and color are available. */
    private void setBackground(int viewId, Integer color) {
        if (color == null) return;
        View view = activity.findViewById(viewId);
        if (view != null) view.setBackgroundColor(color);
    }

    private void applyImageTint(int viewId, Integer color) {
        if (color == null) return;
        View view = activity.findViewById(viewId);
        if (view instanceof ImageView) {
            ((ImageView) view).setImageTintList(ColorStateList.valueOf(color));
        }
    }

    private void applyDynamicTabs() {
        View tabs = activity.findViewById(R.id.yummy_tabs_container);
        if (!(tabs instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) tabs;
        for (int i = 0; i < group.getChildCount(); i++) {
            View tab = group.getChildAt(i);
            boolean active = TAG_ACTIVE_TAB.equals(tab.getTag());
            Integer background = active ? palette.activeTabBackground : palette.inactiveTabBackground;
            Integer foreground = active ? palette.activeTabForeground : palette.inactiveTabForeground;
            if (background != null) tab.setBackground(new ColorDrawable(background));
            applyTabForeground(tab, foreground);
        }
    }

    private void applyTabForeground(View view, Integer foreground) {
        if (foreground == null || isProtectedArtwork(view)) return;
        if (view instanceof TextView) ((TextView) view).setTextColor(foreground);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTabForeground(group.getChildAt(i), foreground);
            }
        }
    }

    private void applyForegroundTree(View view, Integer foreground) {
        if (view == null || foreground == null || isProtectedArtwork(view)) return;
        if (view instanceof TextView) ((TextView) view).setTextColor(foreground);
        if (view instanceof ImageView) ((ImageView) view).setImageTintList(ColorStateList.valueOf(foreground));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyForegroundTree(group.getChildAt(i), foreground);
            }
        }
    }

    private void applyPopupChildren(View view) {
        if (view instanceof ViewGroup && palette.popupBackground != null) {
            view.setBackgroundColor(palette.popupBackground);
        }
        if (view instanceof TextView) {
            boolean selected = TAG_SELECTED_POPUP.equals(view.getTag());
            Integer foreground = selected && palette.popupSelectedForeground != null
                ? palette.popupSelectedForeground : palette.popupForeground;
            if (foreground != null) ((TextView) view).setTextColor(foreground);
            if (selected && palette.popupSelectedBackground != null) {
                view.setBackground(new ColorDrawable(palette.popupSelectedBackground));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyPopupChildren(group.getChildAt(i));
        }
    }
}
