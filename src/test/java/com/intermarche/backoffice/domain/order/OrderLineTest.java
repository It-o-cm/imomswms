package com.intermarche.backoffice.domain.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OrderLine} business logic.
 * Focuses on {@link OrderLine#getChecksum()} sensitivity to business fields.
 */
public class OrderLineTest {

    @Test
    void getChecksum_shouldChangeWhenLineNumberChanges() {
        OrderLine line = createLine();
        line.lineNumber = 1;
        int checksum1 = line.getChecksum();

        line.lineNumber = 2;
        assertNotEquals(checksum1, line.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenRequestedQuantityChanges() {
        OrderLine line = createLine();
        line.requestedQuantity = new BigDecimal("1.000");
        int checksum1 = line.getChecksum();

        line.requestedQuantity = new BigDecimal("2.000");
        assertNotEquals(checksum1, line.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenSpecificationChanges() {
        OrderLine line = createLine();
        line.specification = "{\"offer\":\"A\"}";
        int checksum1 = line.getChecksum();

        line.specification = "{\"offer\":\"B\"}";
        assertNotEquals(checksum1, line.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenProductIdChanges() {
        OrderLine line = createLine();
        line.product = createProduct(1L);
        int checksum1 = line.getChecksum();

        line.product = createProduct(2L);
        assertNotEquals(checksum1, line.getChecksum());
    }

    @Test
    void getChecksum_shouldNotFailWhenProductIsNull() {
        OrderLine line = createLine();
        line.product = null;
        assertDoesNotThrow(line::getChecksum);
    }

    private OrderLine createLine() {
        OrderLine line = new OrderLine();
        line.lineNumber = 1;
        line.requestedQuantity = new BigDecimal("1.000");
        return line;
    }

    private Product createProduct(Long id) {
        Product product = new Product();
        product.id = id;
        product.ean = "EAN-" + id;
        return product;
    }
}
