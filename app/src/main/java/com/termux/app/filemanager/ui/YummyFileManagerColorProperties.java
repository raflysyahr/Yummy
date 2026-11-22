package com.termux.app.filemanager.ui;

/** Stable Color Scheme property selection for File Manager row states. */
final class YummyFileManagerColorProperties {
    private YummyFileManagerColorProperties() { }

    static String treeRowBackgroundProperty(boolean selectedOrCurrent) {
        return selectedOrCurrent
            ? "sidebar.selection.background"
            : "sidebar.tree.row.background";
    }
}
