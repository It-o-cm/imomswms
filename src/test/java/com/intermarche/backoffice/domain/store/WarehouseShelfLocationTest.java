package com.intermarche.backoffice.domain.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WarehouseShelfLocation} business logic.
 * Focuses on {@link WarehouseShelfLocation#requiresForklift()} and checksum sensitivity.
 */
public class WarehouseShelfLocationTest {

    // --------------------------------------------------
    // requiresForklift
    // --------------------------------------------------

    @Test
    void requiresForklift_shouldReturnTrue_whenElevationAbove2() {
        WarehouseShelfLocation loc = new WarehouseShelfLocation();
        loc.elevation = "3";
        assertTrue(loc.requiresForklift());

        loc.elevation = "10";
        assertTrue(loc.requiresForklift());
    }

    @Test
    void requiresForklift_shouldReturnFalse_whenElevationIs1Or2() {
        WarehouseShelfLocation loc = new WarehouseShelfLocation();
        loc.elevation = "1";
        assertFalse(loc.requiresForklift());

        loc.elevation = "2";
        assertFalse(loc.requiresForklift());
    }

    @Test
    void requiresForklift_shouldReturnFalse_whenElevationIsNull() {
        WarehouseShelfLocation loc = new WarehouseShelfLocation();
        loc.elevation = null;
        assertFalse(loc.requiresForklift());
    }

    @Test
    void requiresForklift_shouldReturnFalse_whenElevationIsNotNumeric() {
        WarehouseShelfLocation loc = new WarehouseShelfLocation();
        loc.elevation = "abc";
        assertFalse(loc.requiresForklift());
    }

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaults_shouldHaveOnePallet() {
        WarehouseShelfLocation loc = new WarehouseShelfLocation();
        assertEquals(1, loc.maxPallets);
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_shouldChangeWhenMaxWeightChanges() {
        WarehouseShelfLocation loc = new WarehouseShelfLocation();
        loc.maxWeightKg = 500.0;
        int checksum1 = loc.getChecksum();

        loc.maxWeightKg = 1000.0;
        assertNotEquals(checksum1, loc.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenMaxPalletsChange() {
        WarehouseShelfLocation loc = new WarehouseShelfLocation();
        loc.maxPallets = 1;
        int checksum1 = loc.getChecksum();

        loc.maxPallets = 4;
        assertNotEquals(checksum1, loc.getChecksum());
    }

    @Test
    void getChecksum_shouldIncludeInheritedPhysicalComponents() {
        WarehouseShelfLocation loc = new WarehouseShelfLocation();
        loc.area = "COLD";
        loc.aisle = "A12";
        int checksum1 = loc.getChecksum();

        loc.area = "DRY";
        assertNotEquals(checksum1, loc.getChecksum());
    }
}
