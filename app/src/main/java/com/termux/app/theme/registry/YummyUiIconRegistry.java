package com.termux.app.theme.registry;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.theme.model.VSCodeIconTheme;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stable registry of icon slots owned by Yummy's UI.
 *
 * <p>VS Code File Icon Themes describe files and folders, not the positions of buttons in
 * Yummy. This registry is the adapter boundary: Yummy owns stable slot IDs and fallback
 * resources, while a theme may optionally provide a matching semantic definition or an
 * explicit Yummy-specific mapping.</p>
 *
 * <p>Semantic candidate IDs are convenience suggestions only; they are not part of the VS Code
 * Icon Theme specification. An explicit mapping always wins. When no usable definition is
 * available, callers must keep the registered Yummy fallback. File/folder association slots are
 * resolved by {@link com.termux.app.theme.resolver.VSCodeIconThemeResolver} with file context,
 * not by semantic name matching.</p>
 *
 * <p>This class is metadata only. It does not load assets, mutate Views, change layouts, or
 * interact with terminal content.</p>
 */
public final class YummyUiIconRegistry {
    public static final String SIDEBAR_MENU = "sidebar.menu";
    public static final String SIDEBAR_EXPLORER = "sidebar.explorer";
    public static final String SIDEBAR_TERMINAL = "sidebar.terminal";

    public static final String EDITOR_SEARCH = "editor.search";
    public static final String EDITOR_RUN = "editor.run";
    public static final String EDITOR_SAVE = "editor.save";
    public static final String EDITOR_SESSIONS = "editor.sessions";
    public static final String EDITOR_TABS_MENU = "editor.tabs_menu";
    public static final String EDITOR_NEW_TAB = "editor.new_tab";
    public static final String EDITOR_CLOSE = "editor.close";
    public static final String EDITOR_UNDO = "editor.undo";
    public static final String EDITOR_REDO = "editor.redo";
    public static final String EDITOR_PASTE = "editor.paste";
    public static final String EDITOR_STATUS_LANGUAGE = "editor.status.language";
    public static final String EDITOR_SESSION_SYNC = "editor.session_sync";
    public static final String EDITOR_TAB_FILE = "editor.tab_file";

    public static final String FILE_MANAGER_SEARCH = "file_manager.search";
    public static final String FILE_MANAGER_MORE = "file_manager.more";
    public static final String FILE_MANAGER_NEW_FILE = "file_manager.new_file";
    public static final String FILE_MANAGER_NEW_FOLDER = "file_manager.new_folder";
    public static final String FILE_MANAGER_SORT = "file_manager.sort";
    public static final String FILE_MANAGER_SELECT = "file_manager.select";
    public static final String FILE_MANAGER_DONE = "file_manager.done";
    public static final String FILE_MANAGER_SAVE = "file_manager.save";
    public static final String FILE_MANAGER_COPY = "file_manager.copy";
    public static final String FILE_MANAGER_MOVE = "file_manager.move";
    public static final String FILE_MANAGER_DELETE = "file_manager.delete";
    public static final String FILE_MANAGER_CANCEL = "file_manager.cancel";
    public static final String FILE_MANAGER_CLOSE = "file_manager.close";
    public static final String FILE_MANAGER_STORAGE_LOCAL = "file_manager.storage.local";
    public static final String FILE_MANAGER_STORAGE_PRIVATE = "file_manager.storage.private";
    public static final String FILE_MANAGER_STORAGE_EXTERNAL = "file_manager.storage.external";
    public static final String FILE_MANAGER_STORAGE_ALL = "file_manager.storage.all";
    public static final String FILE_MANAGER_STORAGE_ADD = "file_manager.storage.add";
    public static final String FILE_MANAGER_ENTRY_FILE = "file_manager.entry.file";
    public static final String FILE_MANAGER_ENTRY_FOLDER = "file_manager.entry.folder";

    public static final String TERMINAL_SESSIONS = "terminal.sessions";
    public static final String TERMINAL_NEW_SESSION = "terminal.new_session";
    public static final String TERMINAL_FULLSCREEN = "terminal.fullscreen";
    public static final String TERMINAL_CLOSE = "terminal.close";

    private static final YummyUiIconRegistry DEFAULT = new YummyUiIconRegistry();
    private final Map<String, IconSlot> slots;
    private final List<IconSlot> orderedSlots;

    public YummyUiIconRegistry() {
        Map<String, IconSlot> index = new LinkedHashMap<>();
        List<IconSlot> ordered = new ArrayList<>();

        // Navigation / editor toolbar.
        add(index, ordered, ui(SIDEBAR_MENU, "Application menu", Category.NAVIGATION,
            R.drawable.ic_yummy_menu, "menu", "menu-alt"));
        add(index, ordered, ui(SIDEBAR_EXPLORER, "Explorer", Category.NAVIGATION,
            R.drawable.ic_yummy_file, "explorer", "file-explorer", "files"));
        add(index, ordered, ui(SIDEBAR_TERMINAL, "Terminal", Category.NAVIGATION,
            R.drawable.ic_yummy_terminal, "terminal", "console"));
        add(index, ordered, ui(EDITOR_SEARCH, "Search", Category.EDITOR,
            R.drawable.ic_yummy_search, "search", "find"));
        add(index, ordered, ui(EDITOR_RUN, "Run", Category.EDITOR,
            R.drawable.ic_yummy_play, "run", "play", "run-all"));
        add(index, ordered, ui(EDITOR_SAVE, "Save", Category.EDITOR,
            R.drawable.ic_yummy_save, "save", "save-file", "save-all"));
        add(index, ordered, ui(EDITOR_SESSIONS, "Sessions", Category.EDITOR,
            R.drawable.ic_yummy_sessions, "sessions", "session-list"));
        add(index, ordered, ui(EDITOR_TABS_MENU, "Editor tabs menu", Category.EDITOR,
            R.drawable.ic_yummy_sort, "tabs", "tab-list", "list", "layers"));
        add(index, ordered, ui(EDITOR_NEW_TAB, "New editor tab", Category.EDITOR,
            R.drawable.ic_yummy_add, "new-tab", "add-tab", "plus"));
        add(index, ordered, ui(EDITOR_CLOSE, "Close", Category.EDITOR,
            R.drawable.ic_yummy_close, "close", "close-window", "x"));
        add(index, ordered, ui(EDITOR_UNDO, "Undo", Category.EDITOR,
            R.drawable.ic_yummy_undo, "undo"));
        add(index, ordered, ui(EDITOR_REDO, "Redo", Category.EDITOR,
            R.drawable.ic_yummy_redo, "redo"));
        add(index, ordered, ui(EDITOR_PASTE, "Paste", Category.EDITOR,
            R.drawable.ic_yummy_clipboard_paste, "paste", "clipboard"));
        add(index, ordered, ui(EDITOR_STATUS_LANGUAGE, "Language mode", Category.EDITOR,
            R.drawable.ic_yummy_language_plain));
        add(index, ordered, ui(EDITOR_SESSION_SYNC, "Session save status", Category.EDITOR,
            R.drawable.ic_yummy_session_sync, "sync", "refresh"));
        add(index, ordered, association(EDITOR_TAB_FILE, "Editor tab file", Category.EDITOR,
            IconKind.FILE_ASSOCIATION, R.drawable.ic_fm_file_fallback));

        // File Manager actions and storage providers.
        add(index, ordered, ui(FILE_MANAGER_SEARCH, "File Manager search", Category.FILE_MANAGER,
            R.drawable.ic_fm_search, "search", "find"));
        add(index, ordered, ui(FILE_MANAGER_MORE, "File Manager more actions", Category.FILE_MANAGER,
            R.drawable.ic_fm_more, "more", "more-horizontal", "ellipsis"));
        add(index, ordered, ui(FILE_MANAGER_NEW_FILE, "Create file", Category.FILE_MANAGER,
            R.drawable.ic_fm_add_file, "new-file", "file-add", "add-file", "file-plus"));
        add(index, ordered, ui(FILE_MANAGER_NEW_FOLDER, "Create folder", Category.FILE_MANAGER,
            R.drawable.ic_fm_add_folder, "new-folder", "folder-add", "add-folder", "folder-plus"));
        add(index, ordered, ui(FILE_MANAGER_SORT, "Sort", Category.FILE_MANAGER,
            R.drawable.ic_fm_sort, "sort", "sort-ascending", "sort-descending"));
        add(index, ordered, ui(FILE_MANAGER_SELECT, "Select", Category.FILE_MANAGER,
            R.drawable.ic_fm_select, "select", "checkbox", "check-box"));
        add(index, ordered, ui(FILE_MANAGER_DONE, "Confirm", Category.FILE_MANAGER,
            R.drawable.ic_fm_done, "done", "confirm", "check", "checkmark"));
        add(index, ordered, ui(FILE_MANAGER_SAVE, "Save here", Category.FILE_MANAGER,
            R.drawable.ic_fm_save, "save", "save-file"));
        add(index, ordered, ui(FILE_MANAGER_COPY, "Copy", Category.FILE_MANAGER,
            R.drawable.ic_fm_copy, "copy", "duplicate"));
        add(index, ordered, ui(FILE_MANAGER_MOVE, "Move", Category.FILE_MANAGER,
            R.drawable.ic_fm_move, "move", "relocate"));
        add(index, ordered, ui(FILE_MANAGER_DELETE, "Delete", Category.FILE_MANAGER,
            R.drawable.ic_fm_delete, "delete", "trash", "trash-can"));
        add(index, ordered, ui(FILE_MANAGER_CANCEL, "Cancel", Category.FILE_MANAGER,
            R.drawable.ic_fm_cancel, "cancel", "close", "x"));
        add(index, ordered, ui(FILE_MANAGER_CLOSE, "Close dialog", Category.FILE_MANAGER,
            R.drawable.ic_yummy_close, "close", "x"));
        add(index, ordered, ui(FILE_MANAGER_STORAGE_LOCAL, "Local storage", Category.STORAGE,
            R.drawable.ic_fm_storage_local, "home", "home-folder", "hard-drive"));
        add(index, ordered, ui(FILE_MANAGER_STORAGE_PRIVATE, "Private storage", Category.STORAGE,
            R.drawable.ic_fm_storage_private, "lock", "private", "shield"));
        add(index, ordered, ui(FILE_MANAGER_STORAGE_EXTERNAL, "External storage", Category.STORAGE,
            R.drawable.ic_fm_storage_external, "storage", "drive", "hard-drive"));
        add(index, ordered, ui(FILE_MANAGER_STORAGE_ALL, "All storage", Category.STORAGE,
            R.drawable.ic_fm_storage_all, "database", "storage", "server"));
        add(index, ordered, ui(FILE_MANAGER_STORAGE_ADD, "Add storage", Category.STORAGE,
            R.drawable.ic_fm_storage_add, "add-storage", "storage-add", "plus"));
        add(index, ordered, association(FILE_MANAGER_ENTRY_FILE, "File Manager file row",
            Category.FILE_MANAGER, IconKind.FILE_ASSOCIATION, R.drawable.ic_fm_file_fallback));
        add(index, ordered, association(FILE_MANAGER_ENTRY_FOLDER, "File Manager folder row",
            Category.FILE_MANAGER, IconKind.FOLDER_ASSOCIATION, 0));

        // Terminal panel controls are application chrome; terminal content/configuration is not
        // part of this registry and must remain untouched.
        add(index, ordered, ui(TERMINAL_SESSIONS, "Terminal sessions", Category.TERMINAL,
            R.drawable.ic_yummy_sessions, "sessions", "session-list"));
        add(index, ordered, ui(TERMINAL_NEW_SESSION, "New terminal session", Category.TERMINAL,
            R.drawable.ic_yummy_add, "new-session", "add-session", "plus"));
        add(index, ordered, ui(TERMINAL_FULLSCREEN, "Terminal fullscreen", Category.TERMINAL,
            R.drawable.ic_yummy_fullscreen, "fullscreen", "maximize", "expand"));
        add(index, ordered, ui(TERMINAL_CLOSE, "Close terminal panel", Category.TERMINAL,
            R.drawable.ic_yummy_close, "close", "x"));

        this.slots = Collections.unmodifiableMap(index);
        this.orderedSlots = Collections.unmodifiableList(ordered);
    }

    /** Returns the process-wide default slot catalog. */
    @NonNull
    public static YummyUiIconRegistry getDefault() {
        return DEFAULT;
    }

    /** Returns all slots in stable declaration order. */
    @NonNull
    public List<IconSlot> getSlots() {
        return orderedSlots;
    }

    /** Returns a slot by its stable Yummy ID, or null when the ID is unknown. */
    @Nullable
    public IconSlot getSlot(@Nullable String slotId) {
        return slotId == null ? null : slots.get(slotId);
    }

    /** Returns the fallback drawable for a known slot, or zero for an unknown/delegated slot. */
    @DrawableRes
    public int getFallbackDrawable(@Nullable String slotId) {
        IconSlot slot = getSlot(slotId);
        return slot == null ? 0 : slot.getFallbackDrawable();
    }

    /**
     * Suggests Yummy UI mappings only when an Icon Theme contains an exact semantic definition
     * ID from a slot's candidate list. No fuzzy matching or file/folder association is performed.
     */
    @NonNull
    public Map<String, String> suggestMappings(@Nullable VSCodeIconTheme theme) {
        if (theme == null) return Collections.emptyMap();
        Map<String, String> suggestions = new LinkedHashMap<>();
        for (IconSlot slot : orderedSlots) {
            if (slot.getKind() != IconKind.UI_ACTION) continue;
            String definitionId = findCandidateDefinition(theme, slot.getDefinitionCandidates());
            if (definitionId != null) suggestions.put(slot.getId(), definitionId);
        }
        return Collections.unmodifiableMap(suggestions);
    }

    /**
     * Resolves a UI action using explicit Yummy mapping first, then exact semantic suggestions,
     * and finally the registered Yummy fallback. Unknown slot IDs return null.
     */
    @Nullable
    public ResolvedIcon resolve(
        @Nullable String slotId,
        @Nullable VSCodeIconTheme theme,
        @Nullable Map<String, String> explicitMappings
    ) {
        IconSlot slot = getSlot(slotId);
        if (slot == null) return null;

        if (slot.getKind() != IconKind.UI_ACTION) {
            return new ResolvedIcon(slot, null, ResolutionSource.ASSOCIATION_REQUIRED);
        }

        if (explicitMappings != null && explicitMappings.containsKey(slot.getId())) {
            String requestedId = explicitMappings.get(slot.getId());
            VSCodeIconTheme.IconDefinition explicit = findUsableDefinition(theme, requestedId);
            if (explicit != null) {
                return new ResolvedIcon(slot, explicit, ResolutionSource.EXPLICIT_MAPPING);
            }
            // A stale explicit mapping must not unexpectedly select a different semantic icon.
            return new ResolvedIcon(slot, null, ResolutionSource.INVALID_MAPPING_FALLBACK);
        }

        if (theme != null) {
            String suggestedId = findCandidateDefinition(theme, slot.getDefinitionCandidates());
            VSCodeIconTheme.IconDefinition suggested = findUsableDefinition(theme, suggestedId);
            if (suggested != null) {
                return new ResolvedIcon(slot, suggested, ResolutionSource.SEMANTIC_MATCH);
            }
        }
        return new ResolvedIcon(slot, null, ResolutionSource.YUMMY_FALLBACK);
    }

    /**
     * Removes unknown slot IDs and references to missing/non-renderable definitions from a
     * persisted Yummy mapping. The returned map is safe to pass to {@link #resolve}.
     */
    @NonNull
    public Map<String, String> validateMappings(
        @Nullable VSCodeIconTheme theme,
        @Nullable Map<String, String> mappings
    ) {
        if (theme == null || mappings == null || mappings.isEmpty()) return Collections.emptyMap();
        Map<String, String> valid = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            IconSlot slot = getSlot(entry.getKey());
            if (slot == null || slot.getKind() != IconKind.UI_ACTION) continue;
            VSCodeIconTheme.IconDefinition definition = findUsableDefinition(theme, entry.getValue());
            if (definition != null) valid.put(slot.getId(), definition.getId());
        }
        return Collections.unmodifiableMap(valid);
    }

    @Nullable
    private static String findCandidateDefinition(
        VSCodeIconTheme theme,
        List<String> candidates
    ) {
        if (theme == null || candidates == null || candidates.isEmpty()) return null;
        for (String candidate : candidates) {
            VSCodeIconTheme.IconDefinition definition = findUsableDefinition(theme, candidate);
            if (definition != null) return definition.getId();
        }
        return null;
    }

    @Nullable
    private static VSCodeIconTheme.IconDefinition findUsableDefinition(
        @Nullable VSCodeIconTheme theme,
        @Nullable String definitionId
    ) {
        if (theme == null || definitionId == null || definitionId.trim().isEmpty()) return null;
        VSCodeIconTheme.IconDefinition exact = theme.getIconDefinition(definitionId.trim());
        if (isUsable(exact)) return exact;
        // Definition IDs in extension JSON can vary in case; compare exactly after a locale-stable fold.
        String normalized = definitionId.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, VSCodeIconTheme.IconDefinition> entry
            : theme.getIconDefinitions().entrySet()) {
            if (entry.getKey() != null
                && entry.getKey().toLowerCase(Locale.ROOT).equals(normalized)
                && isUsable(entry.getValue())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean isUsable(@Nullable VSCodeIconTheme.IconDefinition definition) {
        return definition != null && (definition.usesImage() || definition.usesFontGlyph());
    }

    private static IconSlot ui(String id, String title, Category category,
        @DrawableRes int fallback, String... candidates) {
        return new IconSlot(id, title, category, IconKind.UI_ACTION, fallback,
            candidates == null ? Collections.emptyList() : Arrays.asList(candidates));
    }

    private static IconSlot association(String id, String title, Category category,
        IconKind kind, @DrawableRes int fallback) {
        return new IconSlot(id, title, category, kind, fallback, Collections.emptyList());
    }

    private static void add(Map<String, IconSlot> index, List<IconSlot> ordered, IconSlot slot) {
        if (index.containsKey(slot.getId())) {
            throw new IllegalStateException("Duplicate Yummy UI icon slot: " + slot.getId());
        }
        index.put(slot.getId(), slot);
        ordered.add(slot);
    }

    public enum Category {
        NAVIGATION,
        EDITOR,
        FILE_MANAGER,
        STORAGE,
        TERMINAL
    }

    public enum IconKind {
        /** Fixed Yummy UI action; may use explicit or exact semantic icon-definition mapping. */
        UI_ACTION,
        /** Resolved from a file path/name/language ID by VSCodeIconThemeResolver. */
        FILE_ASSOCIATION,
        /** Resolved from folder name/open state by VSCodeIconThemeResolver. */
        FOLDER_ASSOCIATION
    }

    public enum ResolutionSource {
        EXPLICIT_MAPPING,
        SEMANTIC_MATCH,
        YUMMY_FALLBACK,
        INVALID_MAPPING_FALLBACK,
        ASSOCIATION_REQUIRED
    }

    /** Immutable declaration of one stable Yummy UI icon slot. */
    public static final class IconSlot {
        private final String id;
        private final String title;
        private final Category category;
        private final IconKind kind;
        @DrawableRes private final int fallbackDrawable;
        private final List<String> definitionCandidates;

        private IconSlot(String id, String title, Category category, IconKind kind,
            @DrawableRes int fallbackDrawable, List<String> definitionCandidates) {
            this.id = id;
            this.title = title;
            this.category = category;
            this.kind = kind;
            this.fallbackDrawable = fallbackDrawable;
            this.definitionCandidates = Collections.unmodifiableList(
                new ArrayList<>(definitionCandidates));
        }

        @NonNull public String getId() { return id; }
        @NonNull public String getTitle() { return title; }
        @NonNull public Category getCategory() { return category; }
        @NonNull public IconKind getKind() { return kind; }
        @DrawableRes public int getFallbackDrawable() { return fallbackDrawable; }
        @NonNull public List<String> getDefinitionCandidates() { return definitionCandidates; }
        public boolean usesFileAssociation() { return kind == IconKind.FILE_ASSOCIATION; }
        public boolean usesFolderAssociation() { return kind == IconKind.FOLDER_ASSOCIATION; }
    }

    /** Immutable icon selection result; a null definition means use the Yummy fallback. */
    public static final class ResolvedIcon {
        private final IconSlot slot;
        private final VSCodeIconTheme.IconDefinition definition;
        private final ResolutionSource source;

        private ResolvedIcon(IconSlot slot, VSCodeIconTheme.IconDefinition definition,
            ResolutionSource source) {
            this.slot = slot;
            this.definition = definition;
            this.source = source;
        }

        @NonNull public IconSlot getSlot() { return slot; }
        @Nullable public VSCodeIconTheme.IconDefinition getDefinition() { return definition; }
        @Nullable public String getDefinitionId() {
            return definition == null ? null : definition.getId();
        }
        @NonNull public ResolutionSource getSource() { return source; }
        @DrawableRes public int getFallbackDrawable() { return slot.getFallbackDrawable(); }
        public boolean usesCustomIcon() { return definition != null; }
        public boolean usesFallback() {
            return source == ResolutionSource.YUMMY_FALLBACK
                || source == ResolutionSource.INVALID_MAPPING_FALLBACK;
        }
        public boolean requiresAssociation() {
            return source == ResolutionSource.ASSOCIATION_REQUIRED;
        }
    }
}
