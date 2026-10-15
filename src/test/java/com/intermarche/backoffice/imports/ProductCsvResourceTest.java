package com.intermarche.backoffice.imports;

import com.intermarche.backoffice.domain.store.TemperatureZone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProductCsvResource}.
 * Focuses on the defensive branches of {@code safeParseTemperatureZone}.
 */
class ProductCsvResourceTest {

    private final ProductCsvResource resource = new ProductCsvResource();

    @Test
    void safeParseTemperatureZone_validValue_returnsZone() {
        assertEquals(TemperatureZone.AMBIENT,
                resource.safeParseTemperatureZone(new String[]{"EAN", "ambient"}, 1));
        assertEquals(TemperatureZone.CHILLED,
                resource.safeParseTemperatureZone(new String[]{"EAN", "Chilled"}, 1));
        assertEquals(TemperatureZone.FROZEN,
                resource.safeParseTemperatureZone(new String[]{"EAN", "FROZEN"}, 1));
    }

    @Test
    void safeParseTemperatureZone_indexOutOfBounds_returnsNull() {
        assertNull(resource.safeParseTemperatureZone(new String[]{"EAN"}, 5));
        assertNull(resource.safeParseTemperatureZone(new String[]{}, 0));
    }

    @Test
    void safeParseTemperatureZone_emptyValue_returnsNull() {
        assertNull(resource.safeParseTemperatureZone(new String[]{"EAN", ""}, 1));
        assertNull(resource.safeParseTemperatureZone(new String[]{"EAN", "   "}, 1));
    }

    @Test
    void safeParseTemperatureZone_unknownValue_returnsNull() {
        assertNull(resource.safeParseTemperatureZone(new String[]{"EAN", "LAVA"}, 1));
    }

    @Test
    void safeParseProductType_validValue_returnsType() {
        assertNotNull(resource.safeParseProductType(new String[]{"EAN", "UNIT"}, 1));
    }

    @Test
    void safeParseProductType_indexOutOfBounds_returnsNull() {
        assertNull(resource.safeParseProductType(new String[]{"EAN"}, 5));
    }

    @Test
    void safeParseProductType_emptyValue_returnsNull() {
        assertNull(resource.safeParseProductType(new String[]{"EAN", "  "}, 1));
    }

    @Test
    void safeParseProductType_unknownValue_returnsNull() {
        assertNull(resource.safeParseProductType(new String[]{"EAN", "WEIRD"}, 1));
    }
}
