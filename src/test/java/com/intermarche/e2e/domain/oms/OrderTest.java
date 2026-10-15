package com.intermarche.e2e.domain.oms;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.OrderLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests d'intégration pour l'entité {@link Order}.
 * Couvre la persistance, addLine, getChecksum et les contraintes de validation.
 */
@QuarkusTest
public class OrderTest {

    // --------------------------------------------------
    // Fixtures
    // --------------------------------------------------

    private Store createStore(String prefix) {
        Store store = new Store();
        store.code = prefix + "-S";
        store.name = "Store " + prefix;
        store.address = new Adresse();
        store.address.streetLine1 = "1 rue de Test";
        store.address.city = "Lyon";
        store.address.postalCode = "69001";
        store.address.country = "France";
        store.persist();
        return store;
    }

    private Customer createCustomer(String prefix) {
        Customer customer = new Customer();
        customer.customerId = prefix + "-CUST";
        customer.firstName = "Jane";
        customer.lastName = "Smith";
        customer.email = prefix + "-cust@test.com";
        customer.persist();
        return customer;
    }

    private Product createProduct(String prefix, String ean) {
        Product product = new Product();
        product.ean = ean;
        product.name = "Product " + prefix;
        product.productType = ProductType.UNIT;
        product.temperatureZone = TemperatureZone.AMBIENT;
        product.persist();
        return product;
    }

    private Order createOrder(Store store, Customer customer, String prefix) {
        Order order = new Order();
        order.orderId = prefix + "-ORDER";
        order.orderDate = LocalDateTime.now();
        order.channel = "ECOMMERCE";
        order.status = "VALIDATED";
        order.store = store;
        order.customer = customer;
        order.persist();
        return order;
    }

    // --------------------------------------------------
    // Persistence Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void persist_shouldSucceed_withValidData() {
        Store store = createStore("ORD01");
        Customer customer = createCustomer("ORD01");
        Order order = createOrder(store, customer, "ORD01");

        assertNotNull(order.id);
        assertEquals("ORD01-ORDER", order.orderId);
        assertEquals("VALIDATED", order.status);
    }

    @Test
    @TestTransaction
    void persist_shouldSucceed_withoutCustomer() {
        Store store = createStore("ORD02");
        Order order = new Order();
        order.orderId = "ORD02-ORDER";
        order.orderDate = LocalDateTime.now();
        order.channel = "POS";
        order.status = "VALIDATED";
        order.store = store;
        order.customer = null;
        order.persist();

        assertNotNull(order.id);
        assertNull(order.customer);
    }

    @Test
    @TestTransaction
    void persist_shouldStoreSpecificationJson() {
        Store store = createStore("ORD03");
        Customer customer = createCustomer("ORD03");
        Order order = createOrder(store, customer, "ORD03");
        order.specification = "{\"key\":\"value\"}";

        Order found = Order.findById(order.id);
        assertNotNull(found.specification);
        assertTrue(found.specification.contains("value"));
    }

    // --------------------------------------------------
    // addLine Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void addLine_shouldAddLineToOrder() {
        Store store = createStore("ORD04");
        Customer customer = createCustomer("ORD04");
        Order order = createOrder(store, customer, "ORD04");
        Product product = createProduct("ORD04", "ORD04-EAN1");

        OrderLine line = new OrderLine();
        line.lineNumber = 1;
        line.product = product;
        line.requestedQuantity = new BigDecimal("2.000");
        line.persist();

        order.addLine(line);

        assertTrue(order.lines.contains(line));
        assertEquals(1, order.lines.size());
    }

    @Test
    @TestTransaction
    void addLine_shouldSupportMultipleLines() {
        Store store = createStore("ORD05");
        Customer customer = createCustomer("ORD05");
        Order order = createOrder(store, customer, "ORD05");
        Product p1 = createProduct("ORD05", "ORD05-EAN1");
        Product p2 = createProduct("ORD05", "ORD05-EAN2");

        OrderLine line1 = new OrderLine();
        line1.lineNumber = 1;
        line1.product = p1;
        line1.requestedQuantity = new BigDecimal("1.000");
        line1.persist();

        OrderLine line2 = new OrderLine();
        line2.lineNumber = 2;
        line2.product = p2;
        line2.requestedQuantity = new BigDecimal("3.000");
        line2.persist();

        order.addLine(line1);
        order.addLine(line2);

        assertEquals(2, order.lines.size());
    }

    // --------------------------------------------------
    // Checksum Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenStatusChanges() {
        Store store = createStore("ORD06");
        Customer customer = createCustomer("ORD06");
        Order order = createOrder(store, customer, "ORD06");

        int checksum1 = order.getChecksum();
        order.status = "SHIPPED";
        int checksum2 = order.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenChannelChanges() {
        Store store = createStore("ORD07");
        Customer customer = createCustomer("ORD07");
        Order order = createOrder(store, customer, "ORD07");

        int checksum1 = order.getChecksum();
        order.channel = "POS_TERMINAL";
        int checksum2 = order.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenSpecificationChanges() {
        Store store = createStore("ORD08");
        Customer customer = createCustomer("ORD08");
        Order order = createOrder(store, customer, "ORD08");

        int checksum1 = order.getChecksum();
        order.specification = "{\"updated\":true}";
        int checksum2 = order.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    // --------------------------------------------------
    // Query Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void findById_shouldReturnOrder() {
        Store store = createStore("ORD09");
        Customer customer = createCustomer("ORD09");
        Order order = createOrder(store, customer, "ORD09");

        Order found = Order.findById(order.id);
        assertNotNull(found);
        assertEquals(order.orderId, found.orderId);
    }

    @Test
    @TestTransaction
    void findById_shouldReturnNull_whenNotFound() {
        assertNull(Order.findById(999999L));
    }

    @Test
    @TestTransaction
    void listAll_shouldReturnPersistedOrders() {
        Store store = createStore("ORD10");
        Customer customer = createCustomer("ORD10");
        createOrder(store, customer, "ORD10");

        assertFalse(Order.listAll().isEmpty());
    }
}
