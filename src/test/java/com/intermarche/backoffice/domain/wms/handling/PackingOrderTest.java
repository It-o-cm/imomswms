package com.intermarche.backoffice.domain.wms.handling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PackingOrder} business logic.
 */
public class PackingOrderTest {

    @Test
    void getPrefix_shouldReturnPAK() {
        PackingOrder order = new PackingOrder();
        assertEquals("PAK", order.getPrefix());
    }
}
