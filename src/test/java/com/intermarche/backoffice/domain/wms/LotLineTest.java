package com.intermarche.backoffice.domain.wms;

import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LotLine} business logic.
 * Focuses on {@link LotLine#setFulfillmentLine(FulfillmentLine)}, default quantity
 * and checksum sensitivity.
 */
public class LotLineTest {

    @Test
    void defaultQuantity_shouldBeZero() {
        LotLine line = new LotLine();
        assertEquals(BigDecimal.ZERO, line.quantity);
    }

    @Test
    void setFulfillmentLine_shouldSetLineAndCopyLocation() {
        LotLine line = new LotLine();
        FulfillmentLine fline = new FulfillmentLine();
        StoreShelfLocation location = new StoreShelfLocation();
        location.area = "FRESH";
        fline.location = location;

        line.setFulfillmentLine(fline);

        assertSame(fline, line.fulfillmentLine);
        assertSame(location, line.location);
    }

    @Test
    void setFulfillmentLine_shouldCopyNullLocation() {
        LotLine line = new LotLine();
        FulfillmentLine fline = new FulfillmentLine();
        fline.location = null;

        line.setFulfillmentLine(fline);

        assertSame(fline, line.fulfillmentLine);
        assertNull(line.location);
    }

    @Test
    void getChecksum_shouldChangeWhenQuantityChanges() {
        LotLine line = new LotLine();
        line.quantity = new BigDecimal("1.000");
        int checksum1 = line.getChecksum();

        line.quantity = new BigDecimal("2.000");
        assertNotEquals(checksum1, line.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenFulfillmentLineIdChanges() {
        LotLine line = new LotLine();
        line.fulfillmentLine = createFulfillmentLine(1L);
        int checksum1 = line.getChecksum();

        line.fulfillmentLine = createFulfillmentLine(2L);
        assertNotEquals(checksum1, line.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenProductIdChanges() {
        LotLine line = new LotLine();
        line.product = createProduct(1L);
        int checksum1 = line.getChecksum();

        line.product = createProduct(2L);
        assertNotEquals(checksum1, line.getChecksum());
    }

    @Test
    void getChecksum_shouldNotFailWhenRelationsAreNull() {
        LotLine line = new LotLine();
        line.fulfillmentLine = null;
        line.product = null;
        assertDoesNotThrow(line::getChecksum);
    }

    private FulfillmentLine createFulfillmentLine(Long id) {
        FulfillmentLine fline = new FulfillmentLine();
        fline.id = id;
        return fline;
    }

    private Product createProduct(Long id) {
        Product product = new Product();
        product.id = id;
        return product;
    }
}
