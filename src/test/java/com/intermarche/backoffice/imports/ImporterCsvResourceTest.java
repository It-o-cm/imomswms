package com.intermarche.backoffice.imports;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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
        @Override
        public Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
            return Map.of();
        }

        @Override
        public void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        }

        @Override
        public Object findEntityForLine(LineData data) {
            return null;
        }
    }

    private final TestImporter importer = new TestImporter();

    // --------------------------------------------------
    // safeGetOrNull
    // --------------------------------------------------

    @Test
    void safeGetOrNull_outOfBounds_returnsNull() {
        assertNull(importer.safeGetOrNull(new String[]{"a"}, 5));
        assertNull(importer.safeGetOrNull(new String[]{"a"}, -1));
    }

    @Test
    void safeGetOrNull_blank_returnsNull() {
        assertNull(importer.safeGetOrNull(new String[]{"a", "  "}, 1));
        assertNull(importer.safeGetOrNull(new String[]{"a", ""}, 1));
    }

    @Test
    void safeGetOrNull_value_returnsTrimmed() {
        assertEquals("b", importer.safeGetOrNull(new String[]{"a", "  b  "}, 1));
    }

    // --------------------------------------------------
    // safeParseInt with default
    // --------------------------------------------------

    @Test
    void safeParseIntWithDefault_null_returnsDefault() {
        assertEquals(7, importer.safeParseInt(new String[]{"a"}, 3, 7));
    }

    @Test
    void safeParseIntWithDefault_blank_returnsDefault() {
        assertEquals(7, importer.safeParseInt(new String[]{"a", "   "}, 1, 7));
    }

    @Test
    void safeParseIntWithDefault_valid_returnsParsed() {
        assertEquals(42, importer.safeParseInt(new String[]{"a", "42"}, 1, 7));
    }

    @Test
    void safeParseIntWithDefault_invalid_returnsDefault() {
        assertEquals(7, importer.safeParseInt(new String[]{"a", "xyz"}, 1, 7));
    }

    // --------------------------------------------------
    // getNextSize
    // --------------------------------------------------

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

    @Test
    void updateCounters_mergesLocalIntoGlobal() {
        int[] counters = {1, 2};
        importer.updateCounters(counters, new int[]{3, 4});

        assertEquals(4, counters[0]);
        assertEquals(6, counters[1]);
    }
}
