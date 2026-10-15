package com.intermarche.backoffice.domain.oms;

import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FulfillmentLine}.
 * Pure unit tests — no database, no Quarkus.
 */
class FulfillmentLineTest {

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaultConstructor_locationIsNull() {
        assertNull(new FulfillmentLine().location);
    }

    @Test
    void defaultConstructor_lineNumberIsZero() {
        assertEquals(0, new FulfillmentLine().lineNumber);
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_nullProductAndLocation_noException() {
        FulfillmentLine line = new FulfillmentLine();
        line.product = null;
        line.location = null;
        assertDoesNotThrow(line::getChecksum);
    }

    @Test
    void getChecksum_changesWithLineNumber() {
        FulfillmentLine line = new FulfillmentLine();
        line.requestedQuantity = BigDecimal.ONE;
        int checksum1 = line.getChecksum();

        line.lineNumber = 5;
        assertNotEquals(checksum1, line.getChecksum());
    }

    @Test
    void getChecksum_changesWithRequestedQuantity() {
        FulfillmentLine line = new FulfillmentLine();
        line.requestedQuantity = BigDecimal.ONE;
        int checksum1 = line.getChecksum();

        line.requestedQuantity = new BigDecimal("2.5");
        assertNotEquals(checksum1, line.getChecksum());
    }

    @Test
    void getChecksum_changesWithProductId() {
        FulfillmentLine line = new FulfillmentLine();
        line.requestedQuantity = BigDecimal.ONE;
        Product product = new Product();
        product.id = 1L;
        line.product = product;
        int checksum1 = line.getChecksum();

        product.id = 2L;
        assertNotEquals(checksum1, line.getChecksum());
    }

    @Test
    void getChecksum_changesWithLocationId() {
        FulfillmentLine line = new FulfillmentLine();
        line.requestedQuantity = BigDecimal.ONE;
        StoreShelfLocation location = new StoreShelfLocation();
        location.id = 10L;
        line.location = location;
        int checksum1 = line.getChecksum();

        location.id = 11L;
        assertNotEquals(checksum1, line.getChecksum());
    }

    @Test
    void getChecksum_sameFields_sameValue() {
        FulfillmentLine line1 = createLine();
        FulfillmentLine line2 = createLine();

        assertEquals(line1.getChecksum(), line2.getChecksum());
    }

    @Test
    void getChecksum_nullRequestedQuantity_noException() {
        FulfillmentLine line = new FulfillmentLine();
        line.requestedQuantity = null;
        assertDoesNotThrow(line::getChecksum);
    }

    private FulfillmentLine createLine() {
        FulfillmentLine line = new FulfillmentLine();
        line.lineNumber = 1;
        line.requestedQuantity = new BigDecimal("3.000");
        return line;
    }
}
