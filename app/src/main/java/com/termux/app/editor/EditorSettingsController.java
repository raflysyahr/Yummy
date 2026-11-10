package com.termux.app.editor;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Persisted appearance settings for the Sora code editor.
 * This controller deliberately does not touch the terminal panel.
 */
public final class EditorSettingsController {

    private static final String PREFS_NAME = "yummy_editor_settings";
    private static final String KEY_WORD_WRAP = "word_wrap";
    private static final String KEY_LINE_NUMBERS = "line_numbers";
    private static final String KEY_CURRENT_LINE = "current_line_highlight";
    private static final String KEY_FONT_SIZE = "font_size_sp";
    private static final String KEY_FONT_FAMILY = "font_family";
    private static final String KEY_TAB_WIDTH = "tab_width";

    private static final int BACKGROUND = 0xFF0F1113;
    private static final int SURFACE = 0xFF161A18;
    private static final int SURFACE_ALT = 0xFF202522;
    private static final int BORDER = 0xFF2B3C32;
    private static final int PRIMARY = 0xFFE8EAED;
    private static final int SECONDARY = 0xFF9BA59F;
    private static final int ACCENT = 0xFF00D65A;
    private static final int RIPPLE = 0x3300D65A;

    private static final int DEFAULT_FONT_SIZE = 15;
    private static final int MIN_FONT_SIZE = 10;
    private static final int MAX_FONT_SIZE = 32;
    private static final int DEFAULT_TAB_WIDTH = 4;

    private static final String FONT_JETBRAINS = "jetbrains_mono";
    private static final String FONT_MONOSPACE = "monospace";
    private static final String FONT_SANS = "sans_serif";
    private static final String FONT_SERIF = "serif";

    private final Activity activity;
    @Nullable private final CodeEditor editor;
    private final SharedPreferences preferences;

    private Dialog pageDialog;
    private int fontSize;
    private int tabWidth;
    private String fontFamily;
    private TextView fontSizeValue;
    private TextView fontFamilyValue;
    private TextView preview;

    private EditorSettingsController(@NonNull Activity activity, @Nullable CodeEditor editor) {
        this.activity = activity;
        this.editor = editor;
        this.preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.fontSize = preferences.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE);
        this.tabWidth = normalizeTabWidth(preferences.getInt(KEY_TAB_WIDTH, DEFAULT_TAB_WIDTH));
        this.fontFamily = normalizeFontFamily(preferences.getString(KEY_FONT_FAMILY, FONT_JETBRAINS));
    }

    /** Apply persisted settings after the editor's TextMate color scheme is initialized. */
    public static void applySavedSettings(@NonNull Activity activity, @NonNull CodeEditor editor) {
        SharedPreferences preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        editor.setWordwrap(preferences.getBoolean(KEY_WORD_WRAP, false));
        editor.setLineNumberEnabled(preferences.getBoolean(KEY_LINE_NUMBERS, true));
        editor.setHighlightCurrentLine(preferences.getBoolean(KEY_CURRENT_LINE, true));
        editor.setTextSize(clampFontSize(preferences.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)));
        editor.setTabWidth(normalizeTabWidth(preferences.getInt(KEY_TAB_WIDTH, DEFAULT_TAB_WIDTH)));
        editor.setTypefaceText(resolveTypeface(activity,
            normalizeFontFamily(preferences.getString(KEY_FONT_FAMILY, FONT_JETBRAINS))));
    }

    /** Open the full-screen editor settings page from the File Manager overflow menu. */
    public static void show(@NonNull Activity activity) {
        CodeEditor editor = activity.findViewById(R.id.yummy_code_editor);
        new EditorSettingsController(activity, editor).showPage();
    }

    private void showPage() {
        pageDialog = new Dialog(activity);
        pageDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        pageDialog.setCancelable(true);
        pageDialog.setCanceledOnTouchOutside(false);
        pageDialog.setContentView(buildPage());
        pageDialog.show();

        Window window = pageDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(BACKGROUND));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(BACKGROUND);
            window.setNavigationBarColor(BACKGROUND);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.getDecorView().setSystemUiVisibility(0);
        }
    }

    private View buildPage() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);

        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), 0, dp(16), 0);
        toolbar.setBackgroundColor(BACKGROUND);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(58)));

        TextView back = label("‹", 34, PRIMARY, false);
        back.setGravity(Gravity.CENTER);
        back.setBackground(rippleBackground(Color.TRANSPARENT, 0));
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(42), -1));
        back.setOnClickListener(view -> dismissPage());
        back.setContentDescription("Back to editor");

        TextView title = label("Editor settings", 18, PRIMARY, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -1, 1f);
        titleParams.leftMargin = dp(8);
        toolbar.addView(title, titleParams);

        TextView reset = label("RESET", 12, ACCENT, true);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(dp(10), 0, dp(4), 0);
        reset.setBackground(rippleBackground(Color.TRANSPARENT, 0));
        toolbar.addView(reset, new LinearLayout.LayoutParams(-2, dp(44)));
        reset.setOnClickListener(view -> resetDefaults());

        View toolbarDivider = new View(activity);
        toolbarDivider.setBackgroundColor(BORDER);
        root.addView(toolbarDivider, new LinearLayout.LayoutParams(-1, dp(1)));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        TextView eyebrow = label("APPEARANCE", 11, ACCENT, true);
        content.addView(eyebrow, new LinearLayout.LayoutParams(-1, -2));
        TextView description = label("Customize how code is displayed in the editor.", 13, SECONDARY, false);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(5);
        content.addView(description, descriptionParams);

        LinearLayout displayCard = card();
        LinearLayout.LayoutParams displayCardParams = new LinearLayout.LayoutParams(-1, -2);
        displayCardParams.topMargin = dp(18);
        content.addView(displayCard, displayCardParams);
        addSwitchRow(displayCard, "Word wrap", "Wrap long lines to fit the editor width.",
            KEY_WORD_WRAP, false);
        addDivider(displayCard);
        addSwitchRow(displayCard, "Line numbers", "Show line numbers beside the code.",
            KEY_LINE_NUMBERS, true);
        addDivider(displayCard);
        addSwitchRow(displayCard, "Highlight current line", "Highlight the line containing the cursor.",
            KEY_CURRENT_LINE, true);

        TextView typeSection = label("TYPOGRAPHY", 11, ACCENT, true);
        LinearLayout.LayoutParams typeSectionParams = new LinearLayout.LayoutParams(-1, -2);
        typeSectionParams.topMargin = dp(26);
        content.addView(typeSection, typeSectionParams);

        LinearLayout typeCard = card();
        LinearLayout.LayoutParams typeCardParams = new LinearLayout.LayoutParams(-1, -2);
        typeCardParams.topMargin = dp(12);
        content.addView(typeCard, typeCardParams);
        addFontSizeRow(typeCard);
        addDivider(typeCard);
        addFontFamilyRow(typeCard);

        TextView spacingSection = label("CODE LAYOUT", 11, ACCENT, true);
        LinearLayout.LayoutParams spacingSectionParams = new LinearLayout.LayoutParams(-1, -2);
        spacingSectionParams.topMargin = dp(26);
        content.addView(spacingSection, spacingSectionParams);

        LinearLayout spacingCard = card();
        LinearLayout.LayoutParams spacingCardParams = new LinearLayout.LayoutParams(-1, -2);
        spacingCardParams.topMargin = dp(12);
        content.addView(spacingCard, spacingCardParams);
        addTabWidthRow(spacingCard);

        TextView previewLabel = label("PREVIEW", 11, ACCENT, true);
        LinearLayout.LayoutParams previewLabelParams = new LinearLayout.LayoutParams(-1, -2);
        previewLabelParams.topMargin = dp(26);
        content.addView(previewLabel, previewLabelParams);
        preview = label("const greeting = \"Yummy\";\nprint(greeting);", fontSize, PRIMARY, false);
        preview.setTypeface(resolveTypeface(activity, fontFamily));
        preview.setPadding(dp(16), dp(14), dp(16), dp(14));
        preview.setBackground(flatRectangle(SURFACE, BORDER, dp(1)));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, -2);
        previewParams.topMargin = dp(12);
        content.addView(preview, previewParams);

        return root;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(flatRectangle(SURFACE, BORDER, dp(1)));
        return card;
    }

    /** Adds a subtle separator between settings rows. */
    private void addDivider(@NonNull LinearLayout parent) {
        View divider = new View(activity);
        divider.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.leftMargin = dp(16);
        params.rightMargin = dp(16);
        parent.addView(divider, params);
    }

    private void addSwitchRow(LinearLayout parent, String title, String subtitle,
                              String key, boolean defaultValue) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(14), dp(12));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout copy = new LinearLayout(activity);
        copy.setOrientation(LinearLayout.VERTICAL);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        copy.addView(label(title, 14, PRIMARY, true));
        TextView hint = label(subtitle, 11, SECONDARY, false);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(3);
        copy.addView(hint, hintParams);

        Switch toggle = new Switch(activity);
        boolean checked = preferences.getBoolean(key, defaultValue);
        toggle.setChecked(checked);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int[][] states = new int[][] {
                new int[] { android.R.attr.state_checked },
                new int[] { -android.R.attr.state_checked }
            };
            toggle.setThumbTintList(new ColorStateList(states,
                new int[] { ACCENT, 0xFF9AA19D }));
            toggle.setTrackTintList(new ColorStateList(states,
                new int[] { 0x6600D65A, 0xFF414844 }));
        }
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-2, -2);
        toggleParams.leftMargin = dp(10);
        row.addView(toggle, toggleParams);
        toggle.setOnCheckedChangeListener((button, value) -> {
            preferences.edit().putBoolean(key, value).apply();
            applySettings();
        });
        row.setBackground(rippleBackground(Color.TRANSPARENT, 0));
        row.setOnClickListener(view -> toggle.setChecked(!toggle.isChecked()));
    }

    private void addFontSizeRow(LinearLayout parent) {
        LinearLayout row = optionRow(parent, "Font size", "Zoom the editor text in or out.");
        LinearLayout controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(controls, new LinearLayout.LayoutParams(-2, dp(36)));

        TextView minus = smallButton("−");
        controls.addView(minus, new LinearLayout.LayoutParams(dp(36), dp(34)));
        fontSizeValue = label(fontSize + " sp", 13, PRIMARY, true);
        fontSizeValue.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(dp(58), -1);
        controls.addView(fontSizeValue, valueParams);
        TextView plus = smallButton("+");
        controls.addView(plus, new LinearLayout.LayoutParams(dp(36), dp(34)));

        minus.setOnClickListener(view -> changeFontSize(-1));
        plus.setOnClickListener(view -> changeFontSize(1));
    }

    private void addFontFamilyRow(LinearLayout parent) {
        LinearLayout row = optionRow(parent, "Font family", "Choose the typeface used for code.");
        fontFamilyValue = label(fontFamilyName(fontFamily) + "  ›", 12, ACCENT, true);
        fontFamilyValue.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        fontFamilyValue.setMaxWidth(dp(150));
        fontFamilyValue.setPadding(dp(6), 0, dp(2), 0);
        row.addView(fontFamilyValue, new LinearLayout.LayoutParams(-2, dp(42)));
        row.setBackground(rippleBackground(Color.TRANSPARENT, 0));
        row.setOnClickListener(view -> showFontPicker());
    }

    private void addTabWidthRow(LinearLayout parent) {
        LinearLayout row = optionRow(parent, "Tab width", "Number of spaces used for indentation.");
        LinearLayout choices = new LinearLayout(activity);
        choices.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(choices, new LinearLayout.LayoutParams(-2, dp(36)));
        for (int width : new int[] { 2, 4, 8 }) {
            TextView choice = smallButton(String.valueOf(width));
            choice.setTextColor(tabWidth == width ? ACCENT : PRIMARY);
            LinearLayout.LayoutParams choiceParams = new LinearLayout.LayoutParams(dp(34), dp(32));
            if (width != 2) choiceParams.leftMargin = dp(5);
            choices.addView(choice, choiceParams);
            choice.setOnClickListener(view -> {
                tabWidth = width;
                preferences.edit().putInt(KEY_TAB_WIDTH, tabWidth).apply();
                applySettings();
                updateTabWidthChoices(choices);
            });
        }
    }

    private void updateTabWidthChoices(LinearLayout choices) {
        for (int i = 0; i < choices.getChildCount(); i++) {
            View child = choices.getChildAt(i);
            if (child instanceof TextView) {
                int value = Integer.parseInt(((TextView) child).getText().toString());
                ((TextView) child).setTextColor(value == tabWidth ? ACCENT : PRIMARY);
            }
        }
    }

    private LinearLayout optionRow(LinearLayout parent, String title, String subtitle) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(14), dp(10));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout copy = new LinearLayout(activity);
        copy.setOrientation(LinearLayout.VERTICAL);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        copy.addView(label(title, 14, PRIMARY, true));
        TextView hint = label(subtitle, 11, SECONDARY, false);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(3);
        copy.addView(hint, hintParams);
        return row;
    }

    private void showFontPicker() {
        final String[] ids = { FONT_JETBRAINS, FONT_MONOSPACE, FONT_SANS, FONT_SERIF };
        final String[] names = { "JetBrains Mono", "Monospace", "Sans Serif", "Serif" };
        Dialog picker = new Dialog(activity);
        picker.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(12));
        panel.setBackground(flatRectangle(SURFACE, BORDER, dp(1)));
        panel.addView(label("Font family", 17, PRIMARY, true),
            new LinearLayout.LayoutParams(-1, dp(42)));
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            TextView item = label(names[i], 14,
                id.equals(fontFamily) ? ACCENT : PRIMARY, id.equals(fontFamily));
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(10), 0, dp(10), 0);
            item.setBackground(rippleBackground(Color.TRANSPARENT, 0));
            panel.addView(item, new LinearLayout.LayoutParams(-1, dp(44)));
            item.setOnClickListener(view -> {
                fontFamily = id;
                preferences.edit().putString(KEY_FONT_FAMILY, fontFamily).apply();
                applySettings();
                if (fontFamilyValue != null) fontFamilyValue.setText(fontFamilyName(fontFamily) + "  ›");
                picker.dismiss();
            });
        }
        TextView cancel = label("CANCEL", 12, ACCENT, true);
        cancel.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        panel.addView(cancel, new LinearLayout.LayoutParams(-1, dp(42)));
        cancel.setOnClickListener(view -> picker.dismiss());
        picker.setContentView(panel);
        picker.show();
        Window window = picker.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min(dp(360), screenWidth - dp(40)),
                WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
    }

    private void changeFontSize(int delta) {
        fontSize = clampFontSize(fontSize + delta);
        preferences.edit().putInt(KEY_FONT_SIZE, fontSize).apply();
        applySettings();
        if (fontSizeValue != null) fontSizeValue.setText(fontSize + " sp");
    }

    private void applySettings() {
        if (editor != null) {
            editor.setWordwrap(preferences.getBoolean(KEY_WORD_WRAP, false));
            editor.setLineNumberEnabled(preferences.getBoolean(KEY_LINE_NUMBERS, true));
            editor.setHighlightCurrentLine(preferences.getBoolean(KEY_CURRENT_LINE, true));
            editor.setTextSize(fontSize);
            editor.setTypefaceText(resolveTypeface(activity, fontFamily));
            editor.setTabWidth(tabWidth);
        }
        if (preview != null) {
            preview.setTextSize(fontSize);
            preview.setTypeface(resolveTypeface(activity, fontFamily));
        }
    }

    private void resetDefaults() {
        preferences.edit().clear().apply();
        fontSize = DEFAULT_FONT_SIZE;
        tabWidth = DEFAULT_TAB_WIDTH;
        fontFamily = FONT_JETBRAINS;
        if (pageDialog != null) pageDialog.dismiss();
        showPage();
        applySettings();
    }

    private void dismissPage() {
        if (pageDialog != null) pageDialog.dismiss();
    }

    private TextView smallButton(String value) {
        TextView button = label(value, 17, PRIMARY, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rippleBackground(SURFACE_ALT, 0));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(uiTypeface(bold));
        view.setIncludeFontPadding(false);
        return view;
    }

    private Typeface uiTypeface(boolean bold) {
        try {
            return Typeface.createFromAsset(activity.getAssets(),
                bold ? "fonts/Nunito-Bold.ttf" : "fonts/Nunito-Regular.ttf");
        } catch (RuntimeException ignored) {
            return Typeface.DEFAULT;
        }
    }

    private static Typeface resolveTypeface(Context context, String family) {
        if (FONT_MONOSPACE.equals(family)) return Typeface.MONOSPACE;
        if (FONT_SANS.equals(family)) return Typeface.create("sans-serif", Typeface.NORMAL);
        if (FONT_SERIF.equals(family)) return Typeface.create("serif", Typeface.NORMAL);
        try {
            return Typeface.createFromAsset(context.getAssets(), "fonts/JetBrainsMono-Regular.ttf");
        } catch (RuntimeException ignored) {
            return Typeface.MONOSPACE;
        }
    }

    private String fontFamilyName(String family) {
        if (FONT_MONOSPACE.equals(family)) return "Monospace";
        if (FONT_SANS.equals(family)) return "Sans Serif";
        if (FONT_SERIF.equals(family)) return "Serif";
        return "JetBrains Mono";
    }

    private static String normalizeFontFamily(@Nullable String value) {
        if (FONT_MONOSPACE.equals(value) || FONT_SANS.equals(value) || FONT_SERIF.equals(value)) return value;
        return FONT_JETBRAINS;
    }

    private static int clampFontSize(int value) {
        return Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, value));
    }

    private static int normalizeTabWidth(int value) {
        return value == 2 || value == 8 ? value : DEFAULT_TAB_WIDTH;
    }

    private GradientDrawable flatRectangle(int color, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(0f);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private RippleDrawable rippleBackground(int color, int strokeWidth) {
        GradientDrawable content = flatRectangle(color, strokeWidth > 0 ? BORDER : color,
            strokeWidth);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), content, null);
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
