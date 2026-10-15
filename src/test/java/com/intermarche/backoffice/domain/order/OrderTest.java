package com.intermarche.backoffice.domain.order;

import com.intermarche.backoffice.domain.store.Store;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Order} business logic.
 * Pure unit tests — no database, no Quarkus.
 */
class OrderTest {

    private Order createOrder() {
        Order order = new Order();
        order.orderId = "ORD-2026-1024";
        order.orderDate = LocalDateTime.of(2026, 10, 14, 10, 0);
        order.channel = "ECOMMERCE_APP";
        order.status = "VALIDATED";
        return order;
    }

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaultConstructor_linesInitializedEmpty() {
        Order order = new Order();
        assertNotNull(order.lines);
        assertTrue(order.lines.isEmpty());
    }

    // --------------------------------------------------
    // addLine
    // --------------------------------------------------

    @Test
    void addLine_addsToLines() {
        Order order = createOrder();
        OrderLine line = new OrderLine();
        line.lineNumber = 1;

        order.addLine(line);

        assertEquals(1, order.lines.size());
        assertTrue(order.lines.contains(line));
    }

    @Test
    void addLine_sameLineTwice_setSemantics() {
        Order order = createOrder();
        OrderLine line = new OrderLine();

        order.addLine(line);
        order.addLine(line);

        assertEquals(1, order.lines.size());
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_nullStoreAndCustomer_noException() {
        Order order = createOrder();
        order.store = null;
        order.customer = null;
        assertDoesNotThrow(order::getChecksum);
    }

    @Test
    void getChecksum_changesWithOrderId() {
        Order order = createOrder();
        int checksum1 = order.getChecksum();
        order.orderId = "ORD-9999";
        assertNotEquals(checksum1, order.getChecksum());
    }

    @Test
    void getChecksum_changesWithStatus() {
        Order order = createOrder();
        int checksum1 = order.getChecksum();
        order.status = "SHIPPED";
        assertNotEquals(checksum1, order.getChecksum());
    }

    @Test
    void getChecksum_changesWithChannel() {
        Order order = createOrder();
        int checksum1 = order.getChecksum();
        order.channel = "POS_TERMINAL";
        assertNotEquals(checksum1, order.getChecksum());
    }

    @Test
    void getChecksum_changesWithStoreId() {
        Order order = createOrder();
        Store store = new Store();
        store.id = 1L;
        order.store = store;
        int checksum1 = order.getChecksum();

        store.id = 2L;
        assertNotEquals(checksum1, order.getChecksum());
    }

    @Test
    void getChecksum_changesWithCustomerId() {
        Order order = createOrder();
        Customer customer = new Customer();
        customer.id = 5L;
        order.customer = customer;
        int checksum1 = order.getChecksum();

        customer.id = 6L;
        assertNotEquals(checksum1, order.getChecksum());
    }

    @Test
    void getChecksum_changesWithSpecification() {
        Order order = createOrder();
        int checksum1 = order.getChecksum();
        order.specification = "{\"note\":\"fragile\"}";
        assertNotEquals(checksum1, order.getChecksum());
    }
}
