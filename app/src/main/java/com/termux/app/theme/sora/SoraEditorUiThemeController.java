package com.termux.app.theme.sora;

import android.util.Log;

import com.termux.app.theme.adapter.EditorUiThemeAdapter;
import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ResolvedEditorUiTheme;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/**
 * Applies only the Editor UI workbench palette to Sora's editor color scheme.
 *
 * <p>TextMate syntax colors remain owned by Sora's TextMate registry. The controller snapshots
 * the current base scheme after App UI or syntax changes, restores that base before switching
 * Editor UI themes, and writes only color fields supported by the installed Sora version.</p>
 */
public final class SoraEditorUiThemeController {
    private static final String TAG = "YummyEditorUiTheme";

    private final CodeEditor editor;
    private final EditorUiThemeAdapter adapter = new EditorUiThemeAdapter();
    private final Map<Integer, Integer> baseColors = new LinkedHashMap<>();
    private LoadedTheme currentTheme;
    private boolean baseCaptured;

    public SoraEditorUiThemeController(CodeEditor editor) {
        if (editor == null) throw new IllegalArgumentException("Sora CodeEditor is required");
        this.editor = editor;
    }

    /** Selects a theme and applies its supported editor-surface colors. */
    public void apply(LoadedTheme theme) {
        if (theme == null) throw new IllegalArgumentException("Editor UI theme is required");
        ResolvedEditorUiTheme resolved = adapter.resolve(theme);
        if (!baseCaptured) captureBaseColors();
        restoreBaseColors();
        currentTheme = theme;
        applyResolved(resolved);
    }

    /**
     * Call after another independent layer (App UI canvas or TextMate syntax) changes Sora's
     * base scheme. The new base is captured before the current Editor UI palette is reapplied.
     */
    public void refreshBaseAndReapply() {
        captureBaseColors();
        if (currentTheme == null) return;
        try {
            applyResolved(adapter.resolve(currentTheme));
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not reapply Editor UI theme " + currentTheme.getMetadata().getId(), error);
        }
    }

    public LoadedTheme getCurrentTheme() {
        return currentTheme;
    }

    /** Clears the Editor UI override and restores the latest independently themed base scheme. */
    public void clear() {
        if (baseCaptured) restoreBaseColors();
        currentTheme = null;
    }

    private void captureBaseColors() {
        EditorColorScheme scheme = editor.getColorScheme();
        if (scheme == null) return;
        baseColors.clear();
        for (String fieldName : adapter.getIdentifierMappings().values()) {
            Integer colorId = findColorId(fieldName);
            if (colorId == null || baseColors.containsKey(colorId)) continue;
            try {
                baseColors.put(colorId, scheme.getColor(colorId));
            } catch (RuntimeException error) {
                Log.w(TAG, "Cannot snapshot Sora color field " + fieldName, error);
            }
        }
        baseCaptured = true;
    }

    private void restoreBaseColors() {
        EditorColorScheme scheme = editor.getColorScheme();
        if (scheme == null) return;
        for (Map.Entry<Integer, Integer> entry : baseColors.entrySet()) {
            scheme.setColor(entry.getKey(), entry.getValue());
        }
    }

    private void applyResolved(ResolvedEditorUiTheme resolved) {
        EditorColorScheme scheme = editor.getColorScheme();
        if (scheme == null) return;
        for (Map.Entry<String, Integer> entry : resolved.getColorsBySoraField().entrySet()) {
            Integer colorId = findColorId(entry.getKey());
            if (colorId == null) {
                Log.w(TAG, "Sora version does not expose color field " + entry.getKey());
                continue;
            }
            try {
                scheme.setColor(colorId, entry.getValue());
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not apply Sora color field " + entry.getKey(), error);
            }
        }
        editor.invalidate();
    }

    private static Integer findColorId(String fieldName) {
        try {
            Field field = EditorColorScheme.class.getField(fieldName);
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) {
                return null;
            }
            return field.getInt(null);
        } catch (ReflectiveOperationException | SecurityException error) {
            return null;
        }
    }
}
