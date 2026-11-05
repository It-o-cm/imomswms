package com.intermarche.backoffice.imports;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ImporterCsvResource} utility methods.
 * <p>
 * Covers the safe parsing helpers (including default-value fallbacks), the staged
 * fallback size progression and the counter merging logic.
 */
class ImporterCsvResourceTest {

    /**
     * Minimal concrete implementation used to exercise the generic helpers
     * without any persistence.
     */
    private static class TestImporter extends ImporterCsvResource {
        /**
         * Prepares no context.
         *
         * @param parsedLines the lines of the chunk
         * @param targetCodes their codes
         * @param counters    the created and updated counts
         * @param errors      the error messages
         * @return an empty context
         */
        @Override
        public Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
            return Map.of();
        }

        /**
         * Does nothing with a line.
         *
         * @param data      the line
         * @param entityMap the context
         * @param counters  the created and updated counts
         */
        @Override
        public void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        }

        /**
         * Finds no entity.
         *
         * @param data the line
         * @return null
         */
        @Override
        public Object findEntityForLine(LineData data) {
            return null;
        }
    }

    private final TestImporter importer = new TestImporter();

    // --------------------------------------------------
    // safeGetOrNull
    // --------------------------------------------------

    /**
     * Checks safe get or null: out of bounds, returns null.
     */
    @Test
    void safeGetOrNull_outOfBounds_returnsNull() {
        assertNull(importer.safeGetOrNull(new String[]{"a"}, 5));
        assertNull(importer.safeGetOrNull(new String[]{"a"}, -1));
    }

    /**
     * Checks safe get or null: blank, returns null.
     */
    @Test
    void safeGetOrNull_blank_returnsNull() {
        assertNull(importer.safeGetOrNull(new String[]{"a", "  "}, 1));
        assertNull(importer.safeGetOrNull(new String[]{"a", ""}, 1));
    }

    /**
     * Checks safe get or null: value, returns trimmed.
     */
    @Test
    void safeGetOrNull_value_returnsTrimmed() {
        assertEquals("b", importer.safeGetOrNull(new String[]{"a", "  b  "}, 1));
    }

    // --------------------------------------------------
    // safeParseInt with default
    // --------------------------------------------------

    /**
     * Checks safe parse int with default: null, returns default.
     */
    @Test
    void safeParseIntWithDefault_null_returnsDefault() {
        assertEquals(7, importer.safeParseInt(new String[]{"a"}, 3, 7));
    }

    /**
     * Checks safe parse int with default: blank, returns default.
     */
    @Test
    void safeParseIntWithDefault_blank_returnsDefault() {
        assertEquals(7, importer.safeParseInt(new String[]{"a", "   "}, 1, 7));
    }

    /**
     * Checks safe parse int with default: valid, returns parsed.
     */
    @Test
    void safeParseIntWithDefault_valid_returnsParsed() {
        assertEquals(42, importer.safeParseInt(new String[]{"a", "42"}, 1, 7));
    }

    /**
     * Checks safe parse int with default: invalid, returns default.
     */
    @Test
    void safeParseIntWithDefault_invalid_returnsDefault() {
        assertEquals(7, importer.safeParseInt(new String[]{"a", "xyz"}, 1, 7));
    }

    // --------------------------------------------------
    // getNextSize
    // --------------------------------------------------

    /**
     * Checks get next size: follows staged fallback progression.
     */
    @Test
    void getNextSize_followsStagedFallbackProgression() {
        assertEquals(ImporterCsvResource.STAGE_2_SIZE, importer.getNextSize(ImporterCsvResource.STAGE_1_SIZE));
        assertEquals(ImporterCsvResource.STAGE_3_SIZE, importer.getNextSize(ImporterCsvResource.STAGE_2_SIZE));
        assertEquals(1, importer.getNextSize(ImporterCsvResource.STAGE_3_SIZE));
        assertEquals(1, importer.getNextSize(1));
    }

    // --------------------------------------------------
    // updateCounters
    // --------------------------------------------------

    /**
     * Checks update counters: merges local into global.
     */
    @Test
    void updateCounters_mergesLocalIntoGlobal() {
        int[] counters = {1, 2};
        importer.updateCounters(counters, new int[]{3, 4});

        assertEquals(4, counters[0]);
        assertEquals(6, counters[1]);
    }

    // --------------------------------------------------
    // buildAnswer / rowError
    // --------------------------------------------------

    /**
     * Checks build answer: escapes the messages in valid json.
     */
    @Test
    void buildAnswer_escapesTheMessagesInValidJson() throws Exception {
        String json = ImporterCsvResource.buildAnswer(new int[]{2, 3}, List.of("Line 4 (\"A\\B\"): bad\nvalue")).toString();

        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertEquals(2, tree.get("createdCount").asInt());
        assertEquals(3, tree.get("updatedCount").asInt());
        assertEquals("Line 4 (\"A\\B\"): bad\nvalue", tree.get("errors").get(0).asText());
    }

    /**
     * Checks build answer: without errors, has no error field.
     */
    @Test
    void buildAnswer_withoutErrors_hasNoErrorField() throws Exception {
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(ImporterCsvResource.buildAnswer(new int[]{0, 0}, List.of()).toString());

        assertNull(tree.get("errors"));
    }

    /**
     * Checks row error: keeps validation messages.
     */
    @Test
    void rowError_keepsValidationMessages() {
        assertEquals("Unknown Role 'X'", ImporterCsvResource.rowError(new IllegalArgumentException("Unknown Role 'X'")));
        assertEquals("closed", ImporterCsvResource.rowError(new IllegalStateException("closed")));
        assertEquals("Unknown Role 'X'", ImporterCsvResource.rowError(new RuntimeException("wrapper", new IllegalArgumentException("Unknown Role 'X'"))));
    }

    /**
     * Checks row error: hides technical failures.
     */
    /**
     * Checks row error: bean validation failures name the field, even when wrapped.
     */
    @Test
    void rowError_namesTheViolatedFields() {
        @SuppressWarnings("unchecked")
        jakarta.validation.ConstraintViolation<Object> violation = mock(jakarta.validation.ConstraintViolation.class);
        jakarta.validation.Path path = mock(jakarta.validation.Path.class);
        when(path.toString()).thenReturn("name");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must not be blank");
        var violations = new jakarta.validation.ConstraintViolationException(Set.of(violation));

        assertEquals("name : must not be blank", ImporterCsvResource.rowError(new RuntimeException("commit", violations)));
        assertEquals("ligne refusée (doublon ou donnée invalide)",
                ImporterCsvResource.rowError(new jakarta.validation.ConstraintViolationException(Set.of())));
    }

    @Test
    void rowError_hidesTechnicalFailures() {
        String generic = "ligne refusée (doublon ou donnée invalide)";
        assertEquals(generic, ImporterCsvResource.rowError(new RuntimeException("ORA-00001 unique constraint (SCHEMA.PK_EMPLOYEE)")));
        assertEquals(generic, ImporterCsvResource.rowError(new RuntimeException("tx", new java.sql.SQLException("Unique index PUBLIC.EMPLOYEES"))));
        assertEquals(generic, ImporterCsvResource.rowError(new IllegalArgumentException()));
    }
}
