package com.termux.app.theme.schema;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Mutable working copy of the {@code yummy.components} property map in a UI Schema document.
 *
 * <p>Only the property explicitly added or removed by a caller is changed. Unknown property IDs
 * and extension fields in existing definitions are retained for forward compatibility. This
 * class performs basic structural checks; cross-property references and runtime value validation
 * are handled by the later schema validator/resolver.</p>
 */
public final class YummyComponentSchema {
    private static final Pattern PROPERTY_ID_PATTERN = Pattern.compile(
        "^[a-z][A-Za-z0-9]*(?:\\.[A-Za-z][A-Za-z0-9]*)+$"
    );

    private JSONObject properties;

    public YummyComponentSchema(JSONObject componentProperties) throws JSONException {
        if (componentProperties == null) {
            throw new IllegalArgumentException("yummy.components must be an object");
        }
        this.properties = copyObject(componentProperties);
    }

    /** Creates an empty property map for a new Yummy namespace. */
    public static YummyComponentSchema empty() {
        try {
            return new YummyComponentSchema(new JSONObject());
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Reads the components map from a complete UI Schema document. */
    public static YummyComponentSchema fromDocument(JSONObject document) throws JSONException {
        if (document == null) throw new IllegalArgumentException("UI Schema document is required");
        JSONObject yummy = document.optJSONObject("yummy");
        JSONObject components = yummy == null ? null : yummy.optJSONObject("components");
        if (components == null) {
            throw new IllegalArgumentException("UI Schema yummy.components must be an object");
        }
        return new YummyComponentSchema(components);
    }

    /** Returns a defensive copy of all component-property definitions. */
    public JSONObject toJson() {
        return copyObjectUnchecked(properties);
    }

    /** Returns the IDs currently stored, including unknown/future IDs. */
    public List<String> getPropertyIds() {
        List<String> ids = new ArrayList<>();
        Iterator<String> keys = properties.keys();
        while (keys.hasNext()) ids.add(keys.next());
        Collections.sort(ids);
        return Collections.unmodifiableList(ids);
    }

    public boolean hasProperty(String propertyId) {
        if (propertyId == null) return false;
        return properties.has(propertyId);
    }

    /** Returns a defensive copy, or null when the requested property is absent. */
    public JSONObject getProperty(String propertyId) {
        if (propertyId == null) return null;
        JSONObject definition = properties.optJSONObject(propertyId);
        return definition == null ? null : copyObjectUnchecked(definition);
    }

    /** Adds or replaces exactly one property; other known and unknown properties are untouched. */
    public void putProperty(String propertyId, JSONObject definition) throws JSONException {
        String id = requirePropertyId(propertyId);
        JSONObject checked = YummyComponentProperty.copyAndValidateDefinition(definition);
        properties.put(id, checked);
    }

    /** Removes exactly one property and leaves all other entries untouched. */
    public boolean removeProperty(String propertyId) {
        if (propertyId == null || !properties.has(propertyId)) return false;
        properties.remove(propertyId);
        return true;
    }

    /** Returns a copied UI Schema document with this property map installed in yummy.components. */
    public JSONObject applyToDocument(JSONObject document) throws JSONException {
        if (document == null) throw new IllegalArgumentException("UI Schema document is required");
        JSONObject result = copyObject(document);
        JSONObject yummy = result.optJSONObject("yummy");
        if (yummy == null) yummy = new JSONObject();
        yummy.put("components", toJson());
        result.put("yummy", yummy);
        return result;
    }

    /** Validates the stable component-property ID format and returns its trimmed form. */
    public static String requirePropertyId(String propertyId) {
        if (propertyId == null) {
            throw new IllegalArgumentException("Yummy component-property ID is required");
        }
        String normalized = propertyId.trim();
        if (!PROPERTY_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                "Invalid Yummy component-property ID '" + normalized
                    + "'; use lower camel-case segments separated by dots"
            );
        }
        return normalized;
    }

    private static JSONObject copyObject(JSONObject value) throws JSONException {
        return new JSONObject(value.toString());
    }

    private static JSONObject copyObjectUnchecked(JSONObject value) {
        try {
            return copyObject(value);
        } catch (JSONException error) {
            return new JSONObject();
        }
    }

}
