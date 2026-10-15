package com.intermarche.backoffice.domain.wms.handling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ZoneCollectingOrder} business logic.
 * Focuses on {@link ZoneCollectingOrder#getPrefix()} and checksum sensitivity.
 */
public class ZoneCollectingOrderTest {

    @Test
    void getPrefix_shouldReturnZON() {
        ZoneCollectingOrder order = new ZoneCollectingOrder();
        assertEquals("ZON", order.getPrefix());
    }

    @Test
    void getChecksum_shouldChangeWhenAreaChanges() {
        ZoneCollectingOrder order = new ZoneCollectingOrder();
        order.area = "FRESH";
        int checksum1 = order.getChecksum();

        order.area = "DRY";
        assertNotEquals(checksum1, order.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenAisleChanges() {
        ZoneCollectingOrder order = new ZoneCollectingOrder();
        order.aisle = "A01";
        int checksum1 = order.getChecksum();

        order.aisle = "A02";
        assertNotEquals(checksum1, order.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenLotsChange() {
        ZoneCollectingOrder order = new ZoneCollectingOrder();
        int checksum1 = order.getChecksum();

        order.lots.add(new com.intermarche.backoffice.domain.wms.Lot());
        assertNotEquals(checksum1, order.getChecksum());
    }
}
