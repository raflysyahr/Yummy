package com.termux.app.theme.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
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

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
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
    private final Map<View, Map<View, ViewState>> dynamicPopupStates = new IdentityHashMap<>();
    private LoadedTheme currentTheme;
    private Palette palette;

    private static final class ViewState {
        final Drawable background;
        final Integer colorDrawableBackground;
        final ColorStateList backgroundTint;
        final ColorStateList text;
        final ColorStateList hintText;
        final ColorStateList tint;

        ViewState(View view) {
            Drawable drawable = view.getBackground();
            // ColorDrawable can be mutated in-place by View.setBackgroundColor(). Keep its
            // value, not a potentially shared Drawable instance, as the immutable snapshot.
            if (drawable instanceof ColorDrawable) {
                colorDrawableBackground = ((ColorDrawable) drawable).getColor();
                background = null;
            } else {
                colorDrawableBackground = null;
                background = drawable == null ? null : drawable.getConstantState() == null
                    ? drawable : drawable.getConstantState().newDrawable(view.getResources()).mutate();
            }
            backgroundTint = view.getBackgroundTintList();
            text = view instanceof TextView ? ((TextView) view).getTextColors() : null;
            hintText = view instanceof TextView ? ((TextView) view).getHintTextColors() : null;
            tint = view instanceof ImageView ? ((ImageView) view).getImageTintList() : null;
        }

        void restore(View view) {
            view.setBackground(colorDrawableBackground != null
                ? new ColorDrawable(colorDrawableBackground) : background);
            view.setBackgroundTintList(backgroundTint);
            if (text != null && view instanceof TextView) {
                ((TextView) view).setTextColor(text);
            }
            if (hintText != null && view instanceof TextView) {
                ((TextView) view).setHintTextColor(hintText);
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
        Integer divider, toolbarBorder, headerBorder, tabBorder, activeTabBorder, statusBorder;
        Integer popupBackground, popupForeground, popupBorder;
        Integer popupSelectedBackground, popupSelectedForeground;
        Integer tabHoverBackground, tabHoverForeground;
        Integer listHoverBackground, listHoverForeground;
        Integer buttonBackground, buttonHoverBackground, buttonBorder, buttonForeground;
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
        restorePopupStates();
        currentTheme = theme;
        palette = resolve(theme.getColorTheme());
        applyStaticViews();
        applyDynamicTabs();
        reapplyTrackedPopups();
    }

    /** Restores the non-Editor-UI appearance before AppThemeController updates bound Views. */
    public void prepareForAppThemeChange() {
        restoreBaseStates();
        restoreDynamicTabStates();
        restorePopupStates();
    }

    /** Re-snapshots the latest App UI appearance and reapplies the selected Editor UI overlay. */
    public void refreshBaseAndReapply() {
        // AppThemeController calls prepareForAppThemeChange() before changing any bound View.
        // Re-capture here so an Editor UI theme with missing identifiers falls back to the
        // newly selected App UI rather than the colors from Activity creation or an old theme.
        captureBaseStates();
        if (currentTheme == null) {
            restorePopupStates();
            return;
        }
        palette = resolve(currentTheme.getColorTheme());
        applyStaticViews();
        applyDynamicTabs();
        reapplyTrackedPopups();
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
        if (popupRoot == null) return;
        Map<View, ViewState> states = dynamicPopupStates.get(popupRoot);
        if (states == null) {
            states = new IdentityHashMap<>();
            captureTree(states, popupRoot);
            dynamicPopupStates.put(popupRoot, states);
        } else {
            restoreStates(states);
        }
        if (currentTheme == null || palette == null) return;
        if (palette.popupBackground != null || palette.popupBorder != null) {
            popupRoot.setBackground(createSurfaceDrawable(
                popupRoot, palette.popupBackground, palette.popupBorder));
            popupRoot.setBackgroundTintList(null);
        }
        applyPopupChildren(popupRoot, true);
    }

    /** Restores and forgets a popup when its owning PopupWindow is dismissed. */
    public void releasePopup(View popupRoot) {
        if (popupRoot == null) return;
        Map<View, ViewState> states = dynamicPopupStates.remove(popupRoot);
        if (states != null) restoreStates(states);
    }

    public Integer getPopupBackgroundColor() {
        return palette == null ? null : palette.popupBackground;
    }

    /** Styles editor-owned confirmation dialogs without changing the Activity or terminal theme. */
    public void applyDialog(AlertDialog dialog) {
        if (dialog == null || currentTheme == null || palette == null) return;

        Window window = dialog.getWindow();
        if (window != null && (palette.popupBackground != null || palette.popupBorder != null)) {
            window.setBackgroundDrawable(createSurfaceDrawable(
                window.getDecorView(), palette.popupBackground, palette.popupBorder));
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
            applyDialogButtonSurface(button);
        }
    }

    private static void setTextColor(TextView view, Integer color) {
        if (view != null && color != null) view.setTextColor(color);
    }

    /** Removes only this target's overrides and restores the latest captured base appearance. */
    public void clear() {
        restoreBaseStates();
        restoreDynamicTabStates();
        restorePopupStates();
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
        p.toolbarBorder = color(document, "titleBar.border");
        p.headerBorder = firstColor(document, "editorGroupHeader.border", "editorGroup.border");
        p.tabBorder = color(document, "tab.border");
        p.activeTabBorder = firstColor(document, "tab.activeBorder", "tab.unfocusedActiveBorder");
        p.statusBorder = color(document, "statusBar.border");
        p.popupBackground = firstColor(document, "editorWidget.background", "menu.background");
        p.popupForeground = firstColor(document, "editorWidget.foreground", "menu.foreground");
        p.popupBorder = firstColor(document, "editorWidget.border", "menu.border");
        p.popupSelectedBackground = firstColor(document,
            "list.activeSelectionBackground", "menu.selectionBackground");
        p.popupSelectedForeground = firstColor(document,
            "list.activeSelectionForeground", "menu.selectionForeground");
        p.tabHoverBackground = color(document, "tab.hoverBackground");
        p.tabHoverForeground = color(document, "tab.hoverForeground");
        p.listHoverBackground = color(document, "list.hoverBackground");
        p.listHoverForeground = color(document, "list.hoverForeground");
        p.buttonBackground = color(document, "button.background");
        p.buttonHoverBackground = color(document, "button.hoverBackground");
        p.buttonBorder = color(document, "button.border");
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
        capture(baseStates, activity.findViewById(R.id.yummy_app_bar_divider));
        capture(baseStates, activity.findViewById(R.id.yummy_tab_bar));
        capture(baseStates, activity.findViewById(R.id.yummy_editor_status_bar));
        capture(baseStates, activity.findViewById(R.id.yummy_keys_row));
        capture(baseStates, activity.findViewById(R.id.yummy_editor_top_divider));
        capture(baseStates, activity.findViewById(R.id.yummy_editor_bottom_divider));
        capture(baseStates, activity.findViewById(R.id.yummy_editor_status_divider));
        capture(baseStates, activity.findViewById(R.id.yummy_status_ln_divider));
        capture(baseStates, activity.findViewById(R.id.yummy_status_col_divider));
        capture(baseStates, activity.findViewById(R.id.yummy_status_spaces_divider));
        capture(baseStates, activity.findViewById(R.id.yummy_status_language_divider));
        capture(baseStates, activity.findViewById(R.id.yummy_status_encoding_divider));
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
        restoreStates(baseStates);
    }

    private void restoreDynamicTabStates() {
        restoreStates(dynamicTabStates);
    }

    private void restorePopupStates() {
        for (Map<View, ViewState> states : dynamicPopupStates.values()) {
            restoreStates(states);
        }
    }

    private static void restoreStates(Map<View, ViewState> states) {
        for (Map.Entry<View, ViewState> entry : states.entrySet()) {
            entry.getValue().restore(entry.getKey());
        }
    }

    private void reapplyTrackedPopups() {
        List<View> roots = new ArrayList<>(dynamicPopupStates.keySet());
        for (View root : roots) applyPopup(root);
    }

    private void applyStaticViews() {
        setBackground(R.id.yummy_app_bar, palette.toolbarBackground);
        setBackground(R.id.yummy_app_bar_divider, palette.toolbarBorder);
        setBackground(R.id.yummy_tab_bar, palette.tabsBackground);
        setBackground(R.id.yummy_editor_status_bar, palette.statusBackground);
        setBackground(R.id.yummy_keys_row, palette.panelBackground);
        Integer headerBorder = palette.headerBorder != null ? palette.headerBorder : palette.divider;
        Integer statusBorder = palette.statusBorder != null ? palette.statusBorder : palette.divider;
        setBackground(R.id.yummy_editor_top_divider, headerBorder);
        setBackground(R.id.yummy_editor_bottom_divider, palette.divider);
        setBackground(R.id.yummy_editor_status_divider, statusBorder);
        setBackground(R.id.yummy_status_ln_divider, statusBorder);
        setBackground(R.id.yummy_status_col_divider, statusBorder);
        setBackground(R.id.yummy_status_spaces_divider, statusBorder);
        setBackground(R.id.yummy_status_language_divider, statusBorder);
        setBackground(R.id.yummy_status_encoding_divider, statusBorder);

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
        if (view != null) {
            // Editor UI is a higher-priority overlay for explicitly mapped chrome colors.
            view.setBackgroundTintList(null);
            view.setBackgroundColor(color);
        }
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
            Integer border = active && palette.activeTabBorder != null
                ? palette.activeTabBorder : palette.tabBorder;
            ViewState baseState = dynamicTabStates.get(tab);
            Drawable baseBackground = baseState == null ? tab.getBackground() : baseState.background;
            Drawable interactiveBackground = createInteractiveBackground(
                tab, background, palette.tabHoverBackground, border, baseBackground);
            if (interactiveBackground != null) {
                tab.setBackgroundTintList(null);
                tab.setBackground(interactiveBackground);
            }
            applyTabForeground(tab, foreground, palette.tabHoverForeground);
        }
    }

    private void applyTabForeground(View view, Integer foreground, Integer hoverForeground) {
        if (isProtectedArtwork(view)) return;
        if (view instanceof TextView) {
            applyStatefulTextForeground((TextView) view, foreground, hoverForeground);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTabForeground(group.getChildAt(i), foreground, hoverForeground);
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

    private void applyPopupChildren(View view, boolean root) {
        if (!root && view instanceof ViewGroup && palette.popupBackground != null) {
            view.setBackgroundTintList(null);
            view.setBackgroundColor(palette.popupBackground);
        }
        if (view instanceof TextView) {
            boolean selected = TAG_SELECTED_POPUP.equals(view.getTag());
            Integer foreground = selected && palette.popupSelectedForeground != null
                ? palette.popupSelectedForeground : palette.popupForeground;
            Integer hoverForeground = selected && palette.popupSelectedForeground != null
                ? palette.popupSelectedForeground : palette.listHoverForeground;
            applyStatefulTextForeground((TextView) view, foreground, hoverForeground);
            Integer normalBackground = selected ? palette.popupSelectedBackground : null;
            Integer hoverBackground = selected && normalBackground != null
                ? normalBackground : palette.listHoverBackground;
            Drawable itemBackground = createInteractiveBackground(
                view, normalBackground, hoverBackground, null);
            if (itemBackground != null) {
                view.setBackgroundTintList(null);
                view.setBackground(itemBackground);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyPopupChildren(group.getChildAt(i), false);
            }
        }
    }

    private void applyDialogButtonSurface(Button button) {
        if (button == null) return;
        Drawable background = createInteractiveBackground(
            button, palette.buttonBackground, palette.buttonHoverBackground, palette.buttonBorder);
        if (background != null) {
            button.setBackgroundTintList(null);
            button.setBackground(background);
        }
    }

    /** Builds a surface fill and optional one-dp outline while retaining an existing base drawable. */
    private Drawable createSurfaceDrawable(View target, Integer fillColor, Integer borderColor) {
        return createSurfaceDrawable(target, target.getBackground(), fillColor, borderColor);
    }

    private Drawable createSurfaceDrawable(
        View target, Drawable baseBackground, Integer fillColor, Integer borderColor
    ) {
        Drawable base = fillColor == null ? copyDrawable(baseBackground, target)
            : new ColorDrawable(fillColor);
        if (borderColor == null) return base;

        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.TRANSPARENT);
        outline.setStroke(dp(target, 1), borderColor);
        if (base == null) return outline;
        return new LayerDrawable(new Drawable[] { base, outline });
    }

    /** Adds pressed/focused/hovered colors without tinting icons or replacing the text state. */
    private Drawable createInteractiveBackground(
        View target, Integer normalColor, Integer hoverColor, Integer borderColor
    ) {
        return createInteractiveBackground(
            target, normalColor, hoverColor, borderColor, target.getBackground());
    }

    private Drawable createInteractiveBackground(
        View target, Integer normalColor, Integer hoverColor, Integer borderColor,
        Drawable baseBackground
    ) {
        if (normalColor == null && hoverColor == null && borderColor == null) return null;
        Drawable normal = createSurfaceDrawable(
            target, baseBackground, normalColor, borderColor);
        if (normal == null) normal = copyDrawable(baseBackground, target);
        if (normal == null) normal = new ColorDrawable(Color.TRANSPARENT);
        if (hoverColor == null) return normal;

        Drawable hovered = createSurfaceDrawable(
            target, baseBackground, hoverColor, borderColor);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { android.R.attr.state_pressed }, hovered);
        states.addState(new int[] { android.R.attr.state_focused }, copyDrawable(hovered, target));
        states.addState(new int[] { android.R.attr.state_hovered }, copyDrawable(hovered, target));
        states.addState(new int[0], normal);
        return states;
    }

    private Drawable copyDrawable(Drawable drawable, View target) {
        if (drawable == null) return null;
        Drawable.ConstantState state = drawable.getConstantState();
        return state == null ? drawable.mutate()
            : state.newDrawable(target.getResources()).mutate();
    }

    private static int dp(View target, int value) {
        return Math.max(1, Math.round(value * target.getResources().getDisplayMetrics().density));
    }

    private static void applyStatefulTextForeground(
        TextView view, Integer normal, Integer hover
    ) {
        if (view == null || (normal == null && hover == null)) return;
        int base = normal == null ? view.getCurrentTextColor() : normal;
        view.setTextColor(createForegroundColorStateList(base, hover));
    }

    static ColorStateList createForegroundColorStateList(Integer normal, Integer hover) {
        if (normal == null) return null;
        if (hover == null) return ColorStateList.valueOf(normal);
        int[][] states = new int[][] {
            new int[] { android.R.attr.state_pressed },
            new int[] { android.R.attr.state_focused },
            new int[] { android.R.attr.state_hovered },
            new int[0]
        };
        int[] colors = new int[] { hover, hover, hover, normal };
        return new ColorStateList(states, colors);
    }
}
