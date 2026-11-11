package com.termux.app.theme.registry;

import com.termux.app.theme.model.LoadedTheme;
import com.termux.app.theme.model.ThemeSource;
import com.termux.app.theme.model.ThemeTarget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable view of the themes discovered during one registry refresh. */
public final class ThemeRegistrySnapshot {
    private static final Comparator<LoadedTheme> DISPLAY_ORDER = new Comparator<LoadedTheme>() {
        @Override
        public int compare(LoadedTheme left, LoadedTheme right) {
            int label = String.CASE_INSENSITIVE_ORDER.compare(
                left.getMetadata().getLabel(), right.getMetadata().getLabel());
            if (label != 0) return label;
            return left.getMetadata().getId().compareTo(right.getMetadata().getId());
        }
    };

    private final List<LoadedTheme> themes;
    private final Map<String, LoadedTheme> byId;
    private final List<ThemeRegistryIssue> issues;

    ThemeRegistrySnapshot(List<LoadedTheme> themes, List<ThemeRegistryIssue> issues) {
        ArrayList<LoadedTheme> themeCopy = new ArrayList<>(themes);
        Collections.sort(themeCopy, DISPLAY_ORDER);
        this.themes = Collections.unmodifiableList(themeCopy);

        LinkedHashMap<String, LoadedTheme> index = new LinkedHashMap<>();
        for (LoadedTheme theme : themeCopy) {
            index.put(theme.getMetadata().getId(), theme);
        }
        this.byId = Collections.unmodifiableMap(index);
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
    }

    public static ThemeRegistrySnapshot empty() {
        return new ThemeRegistrySnapshot(
            Collections.<LoadedTheme>emptyList(),
            Collections.<ThemeRegistryIssue>emptyList()
        );
    }

    /** All successfully loaded themes, sorted by display label and then stable ID. */
    public List<LoadedTheme> getThemes() {
        return themes;
    }

    /** All successfully loaded themes that support the requested target. */
    public List<LoadedTheme> getThemesFor(ThemeTarget target) {
        if (target == null) return Collections.emptyList();
        ArrayList<LoadedTheme> result = new ArrayList<>();
        for (LoadedTheme theme : themes) {
            if (theme.getMetadata().supports(target)) result.add(theme);
        }
        return Collections.unmodifiableList(result);
    }

    /** Themes that can be used as an App UI color scheme. */
    public List<LoadedTheme> getAppUiThemes() {
        return getThemesFor(ThemeTarget.APP_UI);
    }

    /** Themes that can be used for Editor syntax highlighting. */
    public List<LoadedTheme> getEditorSyntaxThemes() {
        return getThemesFor(ThemeTarget.EDITOR_SYNTAX);
    }

    public List<LoadedTheme> getThemesFrom(ThemeSource source) {
        if (source == null) return Collections.emptyList();
        ArrayList<LoadedTheme> result = new ArrayList<>();
        for (LoadedTheme theme : themes) {
            if (theme.getMetadata().getSource() == source) result.add(theme);
        }
        return Collections.unmodifiableList(result);
    }

    public LoadedTheme findById(String id) {
        return id == null ? null : byId.get(id);
    }

    public boolean contains(String id) {
        return findById(id) != null;
    }

    /**
     * Returns the declared default for a target. Built-in defaults take precedence over
     * imported defaults; ties are resolved by stable theme ID for deterministic results.
     * Returns null when no loaded theme declares itself as the default for that target.
     */
    public LoadedTheme getDefaultThemeFor(final ThemeTarget target) {
        if (target == null) return null;
        ArrayList<LoadedTheme> candidates = new ArrayList<>();
        for (LoadedTheme theme : themes) {
            if (theme.getMetadata().isDefaultFor(target)) candidates.add(theme);
        }
        if (candidates.isEmpty()) return null;

        Collections.sort(candidates, new Comparator<LoadedTheme>() {
            @Override
            public int compare(LoadedTheme left, LoadedTheme right) {
                int leftPriority = sourcePriority(left.getMetadata().getSource());
                int rightPriority = sourcePriority(right.getMetadata().getSource());
                if (leftPriority != rightPriority) return leftPriority - rightPriority;
                return left.getMetadata().getId().compareTo(right.getMetadata().getId());
            }
        });
        return candidates.get(0);
    }

    /** Non-fatal discovery, parsing, duplicate-ID, and metadata consistency problems. */
    public List<ThemeRegistryIssue> getIssues() {
        return issues;
    }

    public int size() {
        return themes.size();
    }

    private static int sourcePriority(ThemeSource source) {
        return source == ThemeSource.BUILT_IN ? 0 : 1;
    }
}
