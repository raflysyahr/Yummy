package com.termux.app.theme.adapter;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the boundary between VS Code workbench colors and Sora-owned syntax colors.
 * Every public Sora color field must be mapped or explicitly classified as syntax-owned
 * or lacking a semantically safe VS Code workbench equivalent.
 */
@RunWith(RobolectricTestRunner.class)
public class SoraEditorColorFieldCoverageTest {

    @Test
    public void everyPublicSoraColorFieldIsMappedOrExplicitlyClassified() {
        Set<String> mapped = mappedFields(new EditorUiThemeAdapter().getIdentifierMappings());
        Set<String> syntaxOwned = names(
            "ATTRIBUTE_VALUE", "ATTRIBUTE_NAME", "HTML_TAG", "ANNOTATION",
            "FUNCTION_NAME", "IDENTIFIER_NAME", "IDENTIFIER_VAR", "LITERAL",
            "OPERATOR", "COMMENT", "KEYWORD", "STATIC_SPAN_BACKGROUND",
            "STATIC_SPAN_FOREGROUND");
        Set<String> noSafeWorkbenchEquivalent = names(
            "FUNCTION_CHAR_BACKGROUND_STROKE", "HIGHLIGHTED_DELIMITERS_UNDERLINE",
            "LINE_NUMBER_PANEL", "LINE_NUMBER_PANEL_TEXT", "SIDE_BLOCK_LINE",
            "SNIPPET_BACKGROUND_INACTIVE", "STRIKETHROUGH", "STRIKE_THROUGH",
            "UNDERLINE", "LINE_BLOCK_LABEL", "MINIMAP_VIEWPORT_BORDER");

        Set<String> allSoraFields = publicStaticIntFields();
        Set<String> classified = new HashSet<>();
        classified.addAll(mapped);
        classified.addAll(syntaxOwned);
        classified.addAll(noSafeWorkbenchEquivalent);

        assertTrue("Mapped fields must not overlap TextMate-owned fields",
            Collections.disjoint(mapped, syntaxOwned));
        assertTrue("Mapped fields must not overlap fields without safe equivalents",
            Collections.disjoint(mapped, noSafeWorkbenchEquivalent));
        assertTrue("Syntax-owned and unsupported classifications must be disjoint",
            Collections.disjoint(syntaxOwned, noSafeWorkbenchEquivalent));
        assertEquals("Every public Sora color field must be classified", allSoraFields, classified);
    }

    @Test
    public void eachMappedSoraFieldHasExactlyOneWorkbenchSource() {
        Map<String, List<String>> mappings = new EditorUiThemeAdapter().getIdentifierMappings();
        Set<String> mapped = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : mappings.entrySet()) {
            assertFalse("Workbench identifier must not be blank", entry.getKey().trim().isEmpty());
            assertFalse("Workbench identifier must map at least one Sora field", entry.getValue().isEmpty());
            for (String fieldName : entry.getValue()) {
                assertTrue("Sora field must exist: " + fieldName,
                    publicStaticIntFields().contains(fieldName));
                assertTrue("Sora field must have one authoritative workbench source: " + fieldName,
                    mapped.add(fieldName));
            }
        }
    }

    @Test
    public void mappedSoraColorIdsAreUniqueAndDoNotOverlapTextMateFields() throws Exception {
        Map<String, List<String>> mappings = new EditorUiThemeAdapter().getIdentifierMappings();
        Set<Integer> mappedIds = new HashSet<>();
        for (List<String> fieldNames : mappings.values()) {
            for (String fieldName : fieldNames) {
                Field field = EditorColorScheme.class.getField(fieldName);
                int colorId = field.getInt(null);
                assertTrue("Mapped Sora color IDs must be unique: " + fieldName,
                    mappedIds.add(colorId));
            }
        }

        for (String syntaxFieldName : names(
            "ATTRIBUTE_VALUE", "ATTRIBUTE_NAME", "HTML_TAG", "ANNOTATION",
            "FUNCTION_NAME", "IDENTIFIER_NAME", "IDENTIFIER_VAR", "LITERAL",
            "OPERATOR", "COMMENT", "KEYWORD", "STATIC_SPAN_BACKGROUND",
            "STATIC_SPAN_FOREGROUND")) {
            Field syntaxField = EditorColorScheme.class.getField(syntaxFieldName);
            int syntaxColorId = syntaxField.getInt(null);
            assertFalse("Editor UI mapping must not alias TextMate color ID: "
                + syntaxFieldName, mappedIds.contains(syntaxColorId));
        }
    }

    @Test
    public void everyPublicSoraColorIdIsUniqueWithinDeclaredRangeExceptLegacyAlias()
        throws Exception {
        int startColorId = readDeclaredColorId("START_COLOR_ID");
        int endColorId = readDeclaredColorId("END_COLOR_ID");
        assertTrue("Sora color ID range must start above zero", startColorId > 0);
        assertTrue("Sora color ID range must be ordered", endColorId >= startColorId);

        Map<Integer, Set<String>> fieldsByColorId = new HashMap<>();
        int publicColorFieldCount = 0;
        for (Field field : EditorColorScheme.class.getFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != int.class
                || !Modifier.isPublic(modifiers)
                || !Modifier.isStatic(modifiers)
                || !Modifier.isFinal(modifiers)) {
                continue;
            }

            publicColorFieldCount++;
            int colorId = field.getInt(null);
            assertTrue("Sora color ID must be >= START_COLOR_ID: " + field.getName(),
                colorId >= startColorId);
            assertTrue("Sora color ID must be <= END_COLOR_ID: " + field.getName(),
                colorId <= endColorId);
            Set<String> owners = fieldsByColorId.get(colorId);
            if (owners == null) {
                owners = new HashSet<>();
                fieldsByColorId.put(colorId, owners);
            }
            owners.add(field.getName());
        }

        assertTrue("The pinned Sora version must expose public color ID fields",
            publicColorFieldCount > 0);

        Set<String> expectedAlias = names("STRIKETHROUGH", "STRIKE_THROUGH");
        int canonicalId = EditorColorScheme.class.getField("STRIKETHROUGH").getInt(null);
        int aliasId = EditorColorScheme.class.getField("STRIKE_THROUGH").getInt(null);
        assertEquals("STRIKE_THROUGH must remain an alias of STRIKETHROUGH",
            canonicalId, aliasId);
        assertEquals("The documented alias pair must be the only shared color ID",
            expectedAlias, fieldsByColorId.get(canonicalId));

        int duplicateIdGroups = 0;
        for (Map.Entry<Integer, Set<String>> entry : fieldsByColorId.entrySet()) {
            if (entry.getValue().size() <= 1) continue;
            duplicateIdGroups++;
            assertEquals("Only the documented STRIKETHROUGH alias may share an ID "
                + entry.getKey(), expectedAlias, entry.getValue());
        }
        assertEquals("Sora must expose exactly one documented alias ID group",
            1, duplicateIdGroups);
    }

    private static int readDeclaredColorId(String fieldName) throws Exception {
        Field field = EditorColorScheme.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static Set<String> mappedFields(Map<String, List<String>> mappings) {
        Set<String> result = new HashSet<>();
        for (List<String> fields : mappings.values()) result.addAll(fields);
        return result;
    }

    private static Set<String> publicStaticIntFields() {
        Set<String> result = new HashSet<>();
        for (Field field : EditorColorScheme.class.getFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() == int.class
                && Modifier.isPublic(modifiers)
                && Modifier.isStatic(modifiers)
                && Modifier.isFinal(modifiers)) {
                result.add(field.getName());
            }
        }
        return result;
    }

    private static Set<String> names(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
