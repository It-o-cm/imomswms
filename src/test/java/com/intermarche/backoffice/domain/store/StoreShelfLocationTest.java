package com.intermarche.backoffice.domain.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StoreShelfLocation} business logic.
 * Focuses on {@link StoreShelfLocation#isEyeLevel()} and checksum sensitivity.
 */
public class StoreShelfLocationTest {

    // --------------------------------------------------
    // isEyeLevel
    // --------------------------------------------------

    @Test
    void isEyeLevel_shouldReturnTrue_forElevations2And3() {
        StoreShelfLocation loc = new StoreShelfLocation();
        loc.elevation = "2";
        assertTrue(loc.isEyeLevel());

        loc.elevation = "3";
        assertTrue(loc.isEyeLevel());
    }

    @Test
    void isEyeLevel_shouldReturnFalse_forOtherElevations() {
        StoreShelfLocation loc = new StoreShelfLocation();
        loc.elevation = "1";
        assertFalse(loc.isEyeLevel());

        loc.elevation = "4";
        assertFalse(loc.isEyeLevel());

        loc.elevation = "0";
        assertFalse(loc.isEyeLevel());
    }

    @Test
    void isEyeLevel_shouldReturnFalse_whenElevationIsNull() {
        StoreShelfLocation loc = new StoreShelfLocation();
        loc.elevation = null;
        assertFalse(loc.isEyeLevel());
    }

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaults_shouldHaveOneFacing() {
        StoreShelfLocation loc = new StoreShelfLocation();
        assertEquals(1, loc.facings);
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_shouldChangeWhenFacingsChange() {
        StoreShelfLocation loc = new StoreShelfLocation();
        loc.facings = 1;
        int checksum1 = loc.getChecksum();

        loc.facings = 3;
        assertNotEquals(checksum1, loc.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenDepartmentChanges() {
        StoreShelfLocation loc = new StoreShelfLocation();
        loc.department = "GROCERY";
        int checksum1 = loc.getChecksum();

        loc.department = "DELI";
        assertNotEquals(checksum1, loc.getChecksum());
    }

    @Test
    void getChecksum_shouldIncludeInheritedPhysicalComponents() {
        StoreShelfLocation loc = new StoreShelfLocation();
        loc.area = "EPICERIE";
        loc.aisle = "A05";
        int checksum1 = loc.getChecksum();

        loc.aisle = "A06";
        assertNotEquals(checksum1, loc.getChecksum());
    }
}
