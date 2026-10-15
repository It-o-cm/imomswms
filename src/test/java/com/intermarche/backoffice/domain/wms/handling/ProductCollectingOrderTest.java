package com.intermarche.backoffice.domain.wms.handling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProductCollectingOrder} business logic.
 * Focuses on {@link ProductCollectingOrder#getPrefix()},
 * {@link ProductCollectingOrder#addEan(String)} and checksum sensitivity.
 */
public class ProductCollectingOrderTest {

    @Test
    void getPrefix_shouldReturnPRO() {
        ProductCollectingOrder order = new ProductCollectingOrder();
        assertEquals("PRO", order.getPrefix());
    }

    @Test
    void eans_shouldBeInitializedEmpty() {
        ProductCollectingOrder order = new ProductCollectingOrder();
        assertNotNull(order.eans);
        assertTrue(order.eans.isEmpty());
    }

    @Test
    void addEan_shouldAppendEan() {
        ProductCollectingOrder order = new ProductCollectingOrder();

        order.addEan("1234567890123");
        order.addEan("1234567890124");

        assertEquals(2, order.eans.size());
        assertEquals("1234567890123", order.eans.get(0));
        assertEquals("1234567890124", order.eans.get(1));
    }

    @Test
    void getChecksum_shouldChangeWhenEansChange() {
        ProductCollectingOrder order = new ProductCollectingOrder();
        order.addEan("1234567890123");
        int checksum1 = order.getChecksum();

        order.addEan("1234567890124");
        assertNotEquals(checksum1, order.getChecksum());
    }

    @Test
    void getChecksum_shouldIncludeInheritedFields() {
        ProductCollectingOrder order = new ProductCollectingOrder();
        order.status = com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus.OPEN;
        int checksum1 = order.getChecksum();

        order.status = com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus.PROCESSING;
        assertNotEquals(checksum1, order.getChecksum());
    }
}
