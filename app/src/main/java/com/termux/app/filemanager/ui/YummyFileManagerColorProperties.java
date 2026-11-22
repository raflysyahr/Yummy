package com.termux.app.filemanager.ui;

/** Stable Color Scheme property selection for File Manager row states. */
final class YummyFileManagerColorProperties {
    private YummyFileManagerColorProperties() { }

    static String treeRowBackgroundProperty(boolean selectedOrCurrent) {
        return selectedOrCurrent
            ? "sidebar.selection.background"
            : "sidebar.tree.row.background";
    }

    static String storageItemForegroundProperty(boolean selected) {
        return selected
            ? "sidebar.storage.item.selectedForeground"
            : "sidebar.storage.foreground";
    }

    static String storageItemIconForegroundProperty(boolean selected) {
        return selected
            ? "sidebar.storage.item.selectedIconForeground"
            : "sidebar.storage.icon.foreground";
    }
}
