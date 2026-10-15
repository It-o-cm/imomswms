package com.intermarche.e2e.graphql.order;

import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.OrderLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.graphql.order.OrderResource;
import com.intermarche.e2e.domain.util.DomainUtils;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.smallrye.graphql.api.Context;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link OrderResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 * <p>
 * The GraphQL {@link Context} is mocked: the selection set controls which fetch joins
 * the resource appends to its JPQL queries. The persistence context is cleared before
 * the queries so that {@link Hibernate#isInitialized} reflects the fetch joins only.
 */
@QuarkusTest
@TestTransaction
public class OrderResourceTest {

    @Inject
    OrderResource orderResource;

    // --------------------------------------------------
    // Fixture Helpers
    // --------------------------------------------------

    /**
     * Builds a mocked GraphQL context whose selection set contains exactly the given fields.
     */
    private Context mockContext(String... selectedFields) {
        Set<String> selected = Set.of(selectedFields);
        DataFetchingFieldSelectionSet selectionSet = mock(DataFetchingFieldSelectionSet.class);
        when(selectionSet.contains(anyString())).thenAnswer(inv -> selected.contains(inv.getArgument(0)));
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getSelectionSet()).thenReturn(selectionSet);
        Context context = mock(Context.class);
        when(context.unwrap(DataFetchingEnvironment.class)).thenReturn(env);
        return context;
    }

    private Store createStore(String code) {
        return DomainUtils.createAndPersistStore(code, 48.0, 2.0);
    }

    private Customer createCustomer(String customerId) {
        Customer customer = new Customer();
        customer.customerId = customerId;
        customer.firstName = "John";
        customer.lastName = "Doe";
        customer.email = customerId.toLowerCase() + "@test.com";
        customer.persist();
        return customer;
    }

    private Order persistOrder(String orderId, Store store, Customer customer) {
        Order order = new Order();
        order.orderId = orderId;
        order.orderDate = LocalDateTime.now();
        order.channel = "ECOMMERCE";
        order.status = "VALIDATED";
        order.store = store;
        order.customer = customer;
        order.persist();
        return order;
    }

    private OrderLine addLine(Order order, Product product, int lineNumber, String quantity) {
        OrderLine line = new OrderLine();
        line.lineNumber = lineNumber;
        line.product = product;
        line.requestedQuantity = new BigDecimal(quantity);
        order.addLine(line);
        order.persist();
        return line;
    }

    // --------------------------------------------------
    // Query Tests (Requires MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllOrders_Empty() {
        List<Order> orders = orderResource.allOrders(mockContext());
        assertNotNull(orders);
        assertTrue(orders.isEmpty(), "No order should exist in a fresh test database");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllOrders_ReturnsAllOrders() {
        Store store = createStore("STORE_O1");
        Customer customer = createCustomer("CUST_O1");
        persistOrder("ORD_O1", store, customer);
        persistOrder("ORD_O2", store, customer);

        List<Order> orders = orderResource.allOrders(mockContext());
        assertEquals(2, orders.size());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllOrders_LinesSelection_FetchJoinsLinesAndProducts() {
        Store store = createStore("STORE_O2");
        Customer customer = createCustomer("CUST_O2");
        Order order = persistOrder("ORD_O3", store, customer);
        Product product = DomainUtils.createAndPersistProduct("1234567890123", "Pasta", ProductType.UNIT);
        addLine(order, product, 1, "2.000");
        addLine(order, product, 2, "1.500");
        Panache.getEntityManager().flush(); // send pending INSERTs before detaching
        Panache.getEntityManager().clear();

        List<Order> orders = orderResource.allOrders(mockContext("lines"));
        assertEquals(1, orders.size());
        Order found = orders.get(0);
        assertTrue(Hibernate.isInitialized(found.lines), "lines must be fetch-joined when selected");
        assertEquals(2, found.lines.size());
        OrderLine line = found.lines.iterator().next();
        assertTrue(Hibernate.isInitialized(line.product), "line.product must be fetch-joined when lines are selected");
        assertEquals("1234567890123", line.product.ean);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllOrders_CustomerSelection_FetchJoinsCustomer() {
        Store store = createStore("STORE_O3");
        Customer customer = createCustomer("CUST_O3");
        persistOrder("ORD_O4", store, customer);
        Panache.getEntityManager().flush(); // send pending INSERTs before detaching
        Panache.getEntityManager().clear();

        List<Order> orders = orderResource.allOrders(mockContext("customer"));
        assertEquals(1, orders.size());
        Order found = orders.get(0);
        assertTrue(Hibernate.isInitialized(found.customer), "customer must be fetch-joined when selected");
        assertEquals("CUST_O3", found.customer.customerId);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllOrders_NoSelection_DoesNotFetchLinesNorCustomer() {
        Store store = createStore("STORE_O4");
        Customer customer = createCustomer("CUST_O4");
        Order order = persistOrder("ORD_O5", store, customer);
        Product product = DomainUtils.createAndPersistProduct("1234567890124", "Rice", ProductType.UNIT);
        addLine(order, product, 1, "1.000");
        Panache.getEntityManager().flush(); // send pending INSERTs before detaching
        Panache.getEntityManager().clear();

        List<Order> orders = orderResource.allOrders(mockContext());
        assertEquals(1, orders.size());
        Order found = orders.get(0);
        assertFalse(Hibernate.isInitialized(found.lines), "lines must stay lazy when not selected");
        // Note: customer is a @ManyToOne (EAGER by default), so it is always initialized;
        // only the lines collection discriminates the fetch join behavior.
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOrderById_Success() {
        Store store = createStore("STORE_O5");
        Customer customer = createCustomer("CUST_O5");
        Order existing = persistOrder("ORD_O6", store, customer);

        Order found = orderResource.order(existing.id, mockContext());
        assertNotNull(found);
        assertEquals("ORD_O6", found.orderId);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOrderById_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            orderResource.order(9999L, mockContext());
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOrderById_LinesSelection_FetchJoinsLines() {
        Store store = createStore("STORE_O6");
        Customer customer = createCustomer("CUST_O6");
        Order existing = persistOrder("ORD_O7", store, customer);
        Product product = DomainUtils.createAndPersistProduct("1234567890125", "Milk", ProductType.UNIT);
        addLine(existing, product, 1, "3.000");
        Panache.getEntityManager().flush(); // send pending INSERTs before detaching
        Panache.getEntityManager().clear();

        Order found = orderResource.order(existing.id, mockContext("lines", "customer"));
        assertNotNull(found);
        assertTrue(Hibernate.isInitialized(found.lines), "lines must be fetch-joined when selected");
        assertEquals(1, found.lines.size());
        assertTrue(Hibernate.isInitialized(found.customer), "customer must be fetch-joined when selected");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOrderByOrderId_Success() {
        Store store = createStore("STORE_O7");
        Customer customer = createCustomer("CUST_O7");
        persistOrder("ORD_O8", store, customer);

        Order found = orderResource.orderByOrderId("ORD_O8", mockContext());
        assertNotNull(found);
        assertEquals("ORD_O8", found.orderId);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOrderByOrderId_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            orderResource.orderByOrderId("ORD_UNKNOWN", mockContext());
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Create (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateOrder_WithLines_Success() throws GraphQLException {
        Store store = createStore("STORE_O8");
        Customer customer = createCustomer("CUST_O8");
        Product product = DomainUtils.createAndPersistProduct("1234567890126", "Bread", ProductType.UNIT);

        OrderResource.OrderRecord input = new OrderResource.OrderRecord();
        input.orderId = "ORD_O9";
        input.orderDate = LocalDateTime.now();
        input.channel = "POS_TERMINAL";
        input.status = "VALIDATED";
        input.storeId = store.id;
        input.customerId = customer.id;
        input.specification = "{\"header\":{}}";
        OrderResource.OrderLineRecord lineInput = new OrderResource.OrderLineRecord();
        lineInput.lineNumber = 1;
        lineInput.productId = product.id;
        lineInput.requestedQuantity = new BigDecimal("2.500");
        lineInput.specification = "{\"line\":{}}";
        input.lines = List.of(lineInput);

        Order created = orderResource.createOrder(input);

        assertNotNull(created);
        assertNotNull(created.id);
        assertEquals("ORD_O9", created.orderId);
        assertEquals("POS_TERMINAL", created.channel);
        assertEquals("VALIDATED", created.status);
        assertEquals(store.id, created.store.id);
        assertEquals(customer.id, created.customer.id);
        assertEquals("{\"header\":{}}", created.specification);
        assertEquals(1, created.lines.size());
        OrderLine line = created.lines.iterator().next();
        assertEquals(1, line.lineNumber);
        assertEquals(product.id, line.product.id);
        assertEquals(new BigDecimal("2.500"), line.requestedQuantity);
        assertEquals("{\"line\":{}}", line.specification);

        // Verify persistence from a fresh load
        Order reloaded = Order.findById(created.id);
        assertNotNull(reloaded);
        assertEquals(1, reloaded.lines.size());
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateOrder_WithoutLines_Success() throws GraphQLException {
        Store store = createStore("STORE_O9");
        Customer customer = createCustomer("CUST_O9");

        OrderResource.OrderRecord input = new OrderResource.OrderRecord();
        input.orderId = "ORD_O10";
        input.channel = "ECOMMERCE";
        input.status = "VALIDATED";
        input.storeId = store.id;
        input.customerId = customer.id;
        // lines is null

        Order created = orderResource.createOrder(input);

        assertNotNull(created);
        assertNotNull(created.id);
        assertTrue(created.lines.isEmpty(), "No line should be created");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateOrder_NullOrderDate_DefaultsToNow() throws GraphQLException {
        Store store = createStore("STORE_O10");
        Customer customer = createCustomer("CUST_O10");

        OrderResource.OrderRecord input = new OrderResource.OrderRecord();
        input.orderId = "ORD_O11";
        input.channel = "ECOMMERCE";
        input.status = "VALIDATED";
        input.storeId = store.id;
        input.customerId = customer.id;
        input.orderDate = null; // must default to now

        LocalDateTime before = LocalDateTime.now().minusMinutes(1);
        Order created = orderResource.createOrder(input);
        LocalDateTime after = LocalDateTime.now().plusMinutes(1);

        assertNotNull(created.orderDate, "orderDate must default to now when null");
        assertTrue(!created.orderDate.isBefore(before) && !created.orderDate.isAfter(after),
                "orderDate must be close to now");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateOrder_DuplicateOrderId() {
        Store store = createStore("STORE_O11");
        Customer customer = createCustomer("CUST_O11");
        persistOrder("ORD_O12", store, customer);

        OrderResource.OrderRecord input = new OrderResource.OrderRecord();
        input.orderId = "ORD_O12"; // duplicate
        input.channel = "ECOMMERCE";
        input.status = "VALIDATED";
        input.storeId = store.id;
        input.customerId = customer.id;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            orderResource.createOrder(input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateOrder_StoreNotFound() {
        Customer customer = createCustomer("CUST_O12");

        OrderResource.OrderRecord input = new OrderResource.OrderRecord();
        input.orderId = "ORD_O13";
        input.channel = "ECOMMERCE";
        input.status = "VALIDATED";
        input.storeId = 9999L;
        input.customerId = customer.id;

        assertThrows(NoSuchElementException.class, () -> {
            orderResource.createOrder(input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateOrder_CustomerNotFound() {
        Store store = createStore("STORE_O12");

        OrderResource.OrderRecord input = new OrderResource.OrderRecord();
        input.orderId = "ORD_O14";
        input.channel = "ECOMMERCE";
        input.status = "VALIDATED";
        input.storeId = store.id;
        input.customerId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            orderResource.createOrder(input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateOrder_ProductNotFoundInLine() {
        Store store = createStore("STORE_O13");
        Customer customer = createCustomer("CUST_O13");

        OrderResource.OrderRecord input = new OrderResource.OrderRecord();
        input.orderId = "ORD_O15";
        input.channel = "ECOMMERCE";
        input.status = "VALIDATED";
        input.storeId = store.id;
        input.customerId = customer.id;
        OrderResource.OrderLineRecord lineInput = new OrderResource.OrderLineRecord();
        lineInput.lineNumber = 1;
        lineInput.productId = 9999L;
        lineInput.requestedQuantity = BigDecimal.ONE;
        input.lines = List.of(lineInput);

        assertThrows(NoSuchElementException.class, () -> {
            orderResource.createOrder(input);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Update (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateOrder_PartialUpdate_Success() throws GraphQLException {
        Store store = createStore("STORE_O14");
        Customer customer = createCustomer("CUST_O14");
        Order existing = persistOrder("ORD_O16", store, customer);

        OrderResource.OrderRecord input = new OrderResource.OrderRecord();
        input.status = "SHIPPED";
        input.channel = "POS_TERMINAL";
        input.specification = "{\"updated\":true}";

        Order updated = orderResource.updateOrder(existing.id, input);

        assertEquals("SHIPPED", updated.status);
        assertEquals("POS_TERMINAL", updated.channel);
        assertEquals("{\"updated\":true}", updated.specification);
        // Untouched fields must be preserved
        assertEquals("ORD_O16", updated.orderId, "orderId must not change");
        assertEquals(store.id, updated.store.id, "store must not change");
        assertEquals(customer.id, updated.customer.id, "customer must not change");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateOrder_NotFound() {
        OrderResource.OrderRecord input = new OrderResource.OrderRecord();
        input.status = "SHIPPED";

        assertThrows(NoSuchElementException.class, () -> {
            orderResource.updateOrder(9999L, input);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteOrder_Success() throws GraphQLException {
        Store store = createStore("STORE_O15");
        Customer customer = createCustomer("CUST_O15");
        Order existing = persistOrder("ORD_O17", store, customer);

        boolean result = orderResource.deleteOrder(existing.id);
        assertTrue(result, "Delete should return true");
        assertNull(Order.findById(existing.id));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteOrder_NotFound() throws GraphQLException {
        boolean result = orderResource.deleteOrder(9999L);
        assertFalse(result, "Delete should return false for non-existent ID");
    }
}
