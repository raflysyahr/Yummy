package com.termux.app.theme.adapter;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
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
            "UNDERLINE", "LINE_BLOCK_LABEL");

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
