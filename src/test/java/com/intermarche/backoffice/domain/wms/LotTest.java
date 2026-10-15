package com.intermarche.backoffice.domain.wms;

import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link Lot} business logic.
 * Focuses on {@link Lot#getArea()} and {@link Lot#getAisle()} which compute
 * common area/aisle across all lines in the lot.
 */
public class LotTest {

    // --------------------------------------------------
    // getArea Tests
    // --------------------------------------------------

    @Test
    void getArea_shouldReturnNull_whenNoLines() {
        Lot lot = new Lot();
        lot.lines = new HashSet<>();

        assertNull(lot.getArea());
    }

    @Test
    void getArea_shouldReturnNull_whenLinesIsNull() {
        Lot lot = new Lot();
        lot.lines = null;

        assertNull(lot.getArea());
    }

    @Test
    void getArea_shouldReturnArea_whenAllLinesShareSameArea() {
        Lot lot = new Lot();
        lot.lines = new HashSet<>();
        lot.lines.add(createLotLineWithLocation("FRESH", "A01"));
        lot.lines.add(createLotLineWithLocation("FRESH", "A02"));

        assertEquals("FRESH", lot.getArea());
    }

    @Test
    void getArea_shouldReturnNull_whenLinesHaveDifferentAreas() {
        Lot lot = new Lot();
        lot.lines = new HashSet<>();
        lot.lines.add(createLotLineWithLocation("FRESH", "A01"));
        lot.lines.add(createLotLineWithLocation("DRY", "B01"));

        assertNull(lot.getArea());
    }

    @Test
    void getArea_shouldReturnNull_whenFirstLineHasNoLocation() {
        Lot lot = new Lot();
        lot.lines = new HashSet<>();
        lot.lines.add(createLotLineWithLocation(null, null));
        lot.lines.add(createLotLineWithLocation("FRESH", "A01"));

        assertNull(lot.getArea());
    }

    @Test
    void getArea_shouldReturnNull_whenLocationIsNotShelfLocation() {
        Lot lot = new Lot();
        lot.lines = new HashSet<>();
        LotLine line = new LotLine();
        FulfillmentLine fline = new FulfillmentLine();
        // location is null (not a ShelfLocation)
        fline.location = null;
        line.fulfillmentLine = fline;
        lot.lines.add(line);

        assertNull(lot.getArea());
    }

    // --------------------------------------------------
    // getAisle Tests
    // --------------------------------------------------

    @Test
    void getAisle_shouldReturnNull_whenNoLines() {
        Lot lot = new Lot();
        lot.lines = new HashSet<>();

        assertNull(lot.getAisle());
    }

    @Test
    void getAisle_shouldReturnAisle_whenAllLinesShareSameAisle() {
        Lot lot = new Lot();
        lot.lines = new HashSet<>();
        lot.lines.add(createLotLineWithLocation("FRESH", "A01"));
        lot.lines.add(createLotLineWithLocation("FRESH", "A01"));

        assertEquals("A01", lot.getAisle());
    }

    @Test
    void getAisle_shouldReturnNull_whenLinesHaveDifferentAisles() {
        Lot lot = new Lot();
        lot.lines = new HashSet<>();
        lot.lines.add(createLotLineWithLocation("FRESH", "A01"));
        lot.lines.add(createLotLineWithLocation("FRESH", "A02"));

        assertNull(lot.getAisle());
    }

    @Test
    void getAisle_shouldReturnAisle_whenSameAisleButDifferentAreas() {
        Lot lot = new Lot();
        lot.lines = new HashSet<>();
        lot.lines.add(createLotLineWithLocation("FRESH", "A01"));
        lot.lines.add(createLotLineWithLocation("DRY", "A01"));

        // getAisle only checks aisle, not area
        assertEquals("A01", lot.getAisle());
        // But getArea should return null since areas differ
        assertNull(lot.getArea());
    }

    @Test
    void getArea_shouldReturnNull_whenLineIsNull() {
        Lot lot = new Lot();
        lot.lines = new HashSet<>();
        lot.lines.add(null);

        assertNull(lot.getArea());
    }

    @Test
    void getArea_shouldReturnNull_whenFulfillmentLineIsNull() {
        Lot lot = new Lot();
        lot.lines = new HashSet<>();
        lot.lines.add(new LotLine()); // fulfillmentLine is null

        assertNull(lot.getArea());
    }

    @Test
    void getArea_shouldReturnNull_whenLocationIsNotShelfLocationInstance() {
        Lot lot = new Lot();
        lot.lines = new HashSet<>();
        LotLine line = new LotLine();
        FulfillmentLine fline = new FulfillmentLine();
        fline.location = mock(BaseLocation.class);
        line.fulfillmentLine = fline;
        lot.lines.add(line);

        assertNull(lot.getArea());
    }

    @Test
    void getAisle_shouldReturnNull_whenLineIsNull() {
        Lot lot = new Lot();
        lot.lines = new HashSet<>();
        lot.lines.add(null);

        assertNull(lot.getAisle());
    }

    @Test
    void getAisle_shouldReturnNull_whenFulfillmentLineIsNull() {
        Lot lot = new Lot();
        lot.lines = new HashSet<>();
        lot.lines.add(new LotLine()); // fulfillmentLine is null

        assertNull(lot.getAisle());
    }

    @Test
    void getAisle_shouldReturnNull_whenLocationIsNotShelfLocationInstance() {
        Lot lot = new Lot();
        lot.lines = new HashSet<>();
        LotLine line = new LotLine();
        FulfillmentLine fline = new FulfillmentLine();
        fline.location = mock(BaseLocation.class);
        line.fulfillmentLine = fline;
        lot.lines.add(line);

        assertNull(lot.getAisle());
    }

    // --------------------------------------------------
    // findCommonSpecification Tests
    // --------------------------------------------------

    @Test
    void findCommonSpecification_shouldDelegateToBaseLocation() {
        StoreShelfLocation location = new StoreShelfLocation();
        LotLine line = new LotLine();
        line.location = location;
        Lot lot = new Lot();
        lot.lines = new HashSet<>(Set.of(line));

        com.intermarche.backoffice.domain.store.BaseLocation.LocationSpecification expected =
                new com.intermarche.backoffice.domain.store.BaseLocation.LocationSpecification(null, null, "FRESH", "A01");

        try (org.mockito.MockedStatic<com.intermarche.backoffice.domain.store.BaseLocation> locationMock =
                     org.mockito.Mockito.mockStatic(com.intermarche.backoffice.domain.store.BaseLocation.class)) {
            locationMock.when(() -> com.intermarche.backoffice.domain.store.BaseLocation.findCommonSpecification(
                    org.mockito.ArgumentMatchers.anyList())).thenReturn(expected);

            assertSame(expected, Lot.findCommonSpecification(lot));

            locationMock.verify(() -> com.intermarche.backoffice.domain.store.BaseLocation.findCommonSpecification(
                    org.mockito.ArgumentMatchers.argThat(locs -> locs.size() == 1 && locs.get(0) == location)));
        }
    }

    @Test
    void findCommonSpecification_shouldThrowNPE_whenLotIsNull() {
        // lot.lines est déréférencé sans garde
        assertThrows(NullPointerException.class, () -> Lot.findCommonSpecification(null));
    }

    // --------------------------------------------------
    // addLine Tests
    // --------------------------------------------------

    @Test
    void addLine_shouldAddLineToSet() {
        Lot lot = new Lot();
        lot.lines = new HashSet<>();
        LotLine line = new LotLine();

        lot.addLine(line);

        assertEquals(1, lot.lines.size());
        assertTrue(lot.lines.contains(line));
    }

    // --------------------------------------------------
    // getChecksum Tests
    // --------------------------------------------------

    @Test
    void getChecksum_shouldChangeWhenStatusChanges() {
        Lot lot = new Lot();
        lot.status = Lot.LotStatus.PENDING;
        int checksum1 = lot.getChecksum();

        lot.status = Lot.LotStatus.COLLECTED;
        int checksum2 = lot.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    // --------------------------------------------------
    // LotStatus Enum Tests
    // --------------------------------------------------

    @Test
    void lotStatus_shouldHaveAllExpectedValues() {
        assertEquals(7, Lot.LotStatus.values().length);
        assertNotNull(Lot.LotStatus.valueOf("PENDING"));
        assertNotNull(Lot.LotStatus.valueOf("PLANNED_TO_BE_COLLECTED"));
        assertNotNull(Lot.LotStatus.valueOf("PLANNED_TO_BE_PACKED"));
        assertNotNull(Lot.LotStatus.valueOf("COLLECTED"));
        assertNotNull(Lot.LotStatus.valueOf("PACKED"));
        assertNotNull(Lot.LotStatus.valueOf("DELIVERED"));
        assertNotNull(Lot.LotStatus.valueOf("CANCELED"));
    }

    @Test
    void defaultStatus_shouldBePending() {
        Lot lot = new Lot();
        assertEquals(Lot.LotStatus.PENDING, lot.status);
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Creates a LotLine with a FulfillmentLine that has a ShelfLocation
     * with the given area and aisle.
     */
    private LotLine createLotLineWithLocation(String area, String aisle) {
        StoreShelfLocation location = new StoreShelfLocation();
        location.area = area;
        location.aisle = aisle;

        FulfillmentLine fline = new FulfillmentLine();
        fline.location = location;

        LotLine line = new LotLine();
        line.fulfillmentLine = fline;
        return line;
    }
}
