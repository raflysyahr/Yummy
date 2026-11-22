package com.termux.app.filemanager.ui;

import com.termux.app.theme.schema.YummyColorSchemeDefaults;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Regression coverage for File Manager Color Scheme state-to-property mappings. */
public class YummyFileManagerColorSchemeTest {

    @Test
    public void selectedAndCurrentRowsUseSelectionBackground() {
        assertEquals("sidebar.selection.background",
            YummyFileManagerColorProperties.treeRowBackgroundProperty(true));
    }

    @Test
    public void ordinaryRowsUseTreeRowBackground() {
        assertEquals("sidebar.tree.row.background",
            YummyFileManagerColorProperties.treeRowBackgroundProperty(false));
    }

    @Test
    public void rowAndActionPropertiesExistInDefaultColorScheme() throws Exception {
        JSONObject defaults = new JSONObject(YummyColorSchemeDefaults.toJson());
        JSONObject sidebar = defaults.getJSONObject("sidebar");
        assertNotNull(sidebar.getString("foreground"));
        assertNotNull(sidebar.getJSONObject("selection").getString("background"));
        assertNotNull(sidebar.getJSONObject("tree").getJSONObject("row").getString("background"));
        assertTrue(sidebar.getJSONObject("dialog").getJSONObject("button")
            .getJSONObject("primary").has("background"));
        assertTrue(sidebar.getJSONObject("dialog").getJSONObject("button")
            .getJSONObject("primary").has("foreground"));
    }
}
