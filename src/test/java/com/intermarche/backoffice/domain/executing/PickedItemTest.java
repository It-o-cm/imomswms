package com.intermarche.backoffice.domain.executing;

import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.wms.LotLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PickedItem} business logic.
 * Focuses on the {@link PickedItem.PickStatus} enum, default status
 * and checksum sensitivity.
 */
public class PickedItemTest {

    // --------------------------------------------------
    // PickStatus Enum
    // --------------------------------------------------

    @Test
    void pickStatus_shouldHaveAllExpectedValues() {
        assertEquals(3, PickedItem.PickStatus.values().length);
        assertNotNull(PickedItem.PickStatus.valueOf("PENDING"));
        assertNotNull(PickedItem.PickStatus.valueOf("PICKED"));
        assertNotNull(PickedItem.PickStatus.valueOf("CANCELED"));
    }

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaultStatus_shouldBePending() {
        PickedItem item = new PickedItem();
        assertEquals(PickedItem.PickStatus.PENDING, item.status);
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_shouldChangeWhenQuantityChanges() {
        PickedItem item = new PickedItem();
        item.quantity = new BigDecimal("1.000");
        int checksum1 = item.getChecksum();

        item.quantity = new BigDecimal("2.000");
        assertNotEquals(checksum1, item.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenStatusChanges() {
        PickedItem item = new PickedItem();
        item.status = PickedItem.PickStatus.PENDING;
        int checksum1 = item.getChecksum();

        item.status = PickedItem.PickStatus.PICKED;
        assertNotEquals(checksum1, item.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenLotLineIdChanges() {
        PickedItem item = new PickedItem();
        item.lotLine = createLotLine(1L);
        int checksum1 = item.getChecksum();

        item.lotLine = createLotLine(2L);
        assertNotEquals(checksum1, item.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenProductIdChanges() {
        PickedItem item = new PickedItem();
        item.product = createProduct(1L);
        int checksum1 = item.getChecksum();

        item.product = createProduct(2L);
        assertNotEquals(checksum1, item.getChecksum());
    }

    @Test
    void getChecksum_shouldNotFailWhenRelationsAreNull() {
        PickedItem item = new PickedItem();
        item.lotLine = null;
        item.product = null;
        item.loadedTote = null;
        assertDoesNotThrow(item::getChecksum);
    }

    private LotLine createLotLine(Long id) {
        LotLine lotLine = new LotLine();
        lotLine.id = id;
        return lotLine;
    }

    private Product createProduct(Long id) {
        Product product = new Product();
        product.id = id;
        return product;
    }
}
