package com.intermarche.backoffice.domain.wms.handling;

import com.intermarche.backoffice.domain.wms.Lot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PreparingOrder} business logic.
 * Uses the concrete subclass {@link ProductCollectingOrder}.
 * Focuses on {@link PreparingOrder#addLot(Lot)}.
 */
public class PreparingOrderTest {

    @Test
    void addLot_shouldAddLotToCollection() {
        ProductCollectingOrder order = new ProductCollectingOrder();
        Lot lot = new Lot();

        order.addLot(lot);

        assertEquals(1, order.lots.size());
        assertTrue(order.lots.contains(lot));
    }

    @Test
    void addLot_shouldThrowIllegalArgument_whenLotIsNull() {
        ProductCollectingOrder order = new ProductCollectingOrder();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> order.addLot(null));
        assertTrue(ex.getMessage().contains("Lot cannot be null"));
    }

    @Test
    void addLot_shouldSetLotStatusToPlannedToBePacked() {
        ProductCollectingOrder order = new ProductCollectingOrder();
        Lot lot = new Lot();
        lot.status = Lot.LotStatus.PENDING;

        order.addLot(lot);

        assertEquals(Lot.LotStatus.PLANNED_TO_BE_PACKED, lot.status);
    }

    @Test
    void lots_shouldBeInitializedEmpty() {
        ProductCollectingOrder order = new ProductCollectingOrder();
        assertNotNull(order.lots);
        assertTrue(order.lots.isEmpty());
    }
}
