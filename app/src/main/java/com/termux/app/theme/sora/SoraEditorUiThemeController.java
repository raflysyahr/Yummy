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
 * <p>The controller keeps two snapshots: the syntax-owned base and the effective base after
 * App UI colors have been applied. Editor UI is always an overlay on the effective base. Before
 * an App UI or Editor Syntax change, callers must restore the syntax-owned snapshot; after the
 * external layer has changed, they capture the new syntax/effective base and reapply Editor UI.
 * This prevents colors from an older Editor UI or App UI theme becoming the next theme's base.</p>
 *
 * <p>TextMate token colors remain owned by Sora's TextMate registry. Only color fields exposed
 * by the installed Sora version are touched.</p>
 */
public final class SoraEditorUiThemeController {
    private static final String TAG = "YummyEditorUiTheme";

    private final CodeEditor editor;
    private final EditorUiThemeAdapter adapter = new EditorUiThemeAdapter();
    private final Map<Integer, Integer> syntaxBaseColors = new LinkedHashMap<>();
    private final Map<Integer, Integer> baseColors = new LinkedHashMap<>();
    private LoadedTheme currentTheme;
    private boolean syntaxBaseCaptured;
    private boolean baseCaptured;

    public SoraEditorUiThemeController(CodeEditor editor) {
        if (editor == null) throw new IllegalArgumentException("Sora CodeEditor is required");
        this.editor = editor;
        syntaxBaseCaptured = captureColors(syntaxBaseColors);
    }

    /** Selects a theme and applies its supported editor-surface colors. */
    public void apply(LoadedTheme theme) {
        if (theme == null) throw new IllegalArgumentException("Editor UI theme is required");
        ResolvedEditorUiTheme resolved = adapter.resolve(theme);
        if (!baseCaptured) baseCaptured = captureColors(baseColors);
        restoreBaseColors();
        currentTheme = theme;
        applyResolved(resolved);
    }

    /**
     * Removes App UI and Editor UI overlays before AppThemeController changes its palette.
     * The subsequent refreshBaseAndReapply() records the newly applied App UI colors as the
     * effective base while preserving the syntax-owned snapshot for future App UI switches.
     */
    public void prepareForAppThemeChange() {
        restoreSyntaxBaseColors();
    }

    /** Removes App UI and Editor UI overlays before Sora activates a different TextMate theme. */
    public void prepareForSyntaxThemeChange() {
        restoreSyntaxBaseColors();
    }

    /** Captures the newly activated TextMate surface before the App UI overlay is applied. */
    public void captureSyntaxBaseColors() {
        syntaxBaseCaptured = captureColors(syntaxBaseColors);
    }

    /** Restores the current TextMate-owned surface after a failed syntax-theme activation. */
    public void restoreSyntaxBaseColors() {
        if (syntaxBaseCaptured) restoreColors(syntaxBaseColors);
    }

    /**
     * Captures the current surface after an App UI or TextMate update, then reapplies the
     * independently selected Editor UI overlay. Call only after the matching prepare method.
     */
    public void refreshBaseAndReapply() {
        baseCaptured = captureColors(baseColors);
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

    /** Clears the Editor UI overlay and restores the latest App UI / TextMate base surface. */
    public void clear() {
        if (baseCaptured) restoreBaseColors();
        currentTheme = null;
    }

    private boolean captureColors(Map<Integer, Integer> destination) {
        EditorColorScheme scheme = editor.getColorScheme();
        if (scheme == null) return false;
        destination.clear();
        for (String fieldName : adapter.getIdentifierMappings().values()) {
            Integer colorId = findColorId(fieldName);
            if (colorId == null || destination.containsKey(colorId)) continue;
            try {
                destination.put(colorId, scheme.getColor(colorId));
            } catch (RuntimeException error) {
                Log.w(TAG, "Cannot snapshot Sora color field " + fieldName, error);
            }
        }
        return !destination.isEmpty();
    }

    private void restoreBaseColors() {
        restoreColors(baseColors);
    }

    private void restoreColors(Map<Integer, Integer> colors) {
        EditorColorScheme scheme = editor.getColorScheme();
        if (scheme == null) return;
        for (Map.Entry<Integer, Integer> entry : colors.entrySet()) {
            scheme.setColor(entry.getKey(), entry.getValue());
        }
        editor.invalidate();
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
