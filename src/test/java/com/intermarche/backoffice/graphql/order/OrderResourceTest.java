package com.intermarche.backoffice.graphql.order;

import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderResource}.
 * Covers the identity accessor, order creation with lines and the update mutation branches.
 */
class OrderResourceTest {

    private static final Long STORE_ID = 1L;
    private static final Long CUSTOMER_ID = 2L;
    private static final Long PRODUCT_ID = 3L;

    private MockedStatic<PanacheEntityBase> panacheMock;
    private MockedStatic<Arc> arcMock;

    @BeforeEach
    void setUp() {
        panacheMock = mockStatic(PanacheEntityBase.class);
        arcMock = mockStatic(Arc.class);
        PanacheTestSupport.mockArcSession(arcMock);
    }

    @AfterEach
    void tearDown() {
        panacheMock.close();
        arcMock.close();
    }

    private OrderResource newResource() {
        OrderResource resource = new OrderResource();
        resource.identity = mock(SecurityIdentity.class);
        return resource;
    }

    private Store store() {
        Store store = new Store();
        store.id = STORE_ID;
        store.code = "STORE-1";
        return store;
    }

    private Customer customer() {
        Customer customer = new Customer();
        customer.id = CUSTOMER_ID;
        return customer;
    }

    private Product product() {
        Product product = new Product();
        product.id = PRODUCT_ID;
        product.ean = "EAN-1";
        return product;
    }

    @Test
    void getIdentity_returnsInjectedIdentity() {
        OrderResource resource = new OrderResource();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        resource.identity = identity;

        assertSame(identity, resource.getIdentity());
    }

    @Test
    void createOrder_withLines_addsLinesToOrder() throws Exception {
        Store store = store();
        Customer customer = customer();
        Product product = product();
        PanacheQuery<Order> orderQuery = mock(PanacheQuery.class);
        when(orderQuery.firstResult()).thenReturn(null);
        panacheMock.when(() -> PanacheEntityBase.find(eq("orderId"), any(Object[].class)))
                .thenReturn(orderQuery);
        panacheMock.when(() -> PanacheEntityBase.findById(any()))
                .thenAnswer(invocation -> {
                    Object id = invocation.getArgument(0);
                    if (STORE_ID.equals(id)) return store;
                    if (CUSTOMER_ID.equals(id)) return customer;
                    if (PRODUCT_ID.equals(id)) return product;
                    return null;
                });

        OrderResource.OrderRecord input = new OrderResource.OrderRecord();
        input.orderId = "ORD-1";
        input.channel = "WEB";
        input.status = "NEW";
        input.storeId = STORE_ID;
        input.customerId = CUSTOMER_ID;
        input.specification = "spec";
        OrderResource.OrderLineRecord lineInput = new OrderResource.OrderLineRecord();
        lineInput.lineNumber = 1;
        lineInput.productId = PRODUCT_ID;
        lineInput.requestedQuantity = new BigDecimal("2.5");
        lineInput.specification = "line-spec";
        input.lines = List.of(lineInput);

        Order result = newResource().createOrder(input);

        assertEquals("ORD-1", result.orderId);
        assertEquals("WEB", result.channel);
        assertEquals("NEW", result.status);
        assertSame(store, result.store);
        assertSame(customer, result.customer);
        assertEquals("spec", result.specification);
        assertEquals(1, result.lines.size());
        var line = result.lines.iterator().next();
        assertEquals(1, line.lineNumber);
        assertEquals(new BigDecimal("2.5"), line.requestedQuantity);
        assertEquals("line-spec", line.specification);
        assertSame(product, line.product);
    }

    @Test
    void createOrder_withoutLines_skipsLineProcessing() throws Exception {
        PanacheQuery<Order> orderQuery = mock(PanacheQuery.class);
        when(orderQuery.firstResult()).thenReturn(null);
        panacheMock.when(() -> PanacheEntityBase.find(eq("orderId"), any(Object[].class)))
                .thenReturn(orderQuery);
        panacheMock.when(() -> PanacheEntityBase.findById(any()))
                .thenAnswer(invocation -> {
                    Object id = invocation.getArgument(0);
                    if (STORE_ID.equals(id)) return store();
                    if (CUSTOMER_ID.equals(id)) return customer();
                    return null;
                });

        OrderResource.OrderRecord input = new OrderResource.OrderRecord();
        input.orderId = "ORD-2";
        input.storeId = STORE_ID;
        input.customerId = CUSTOMER_ID;
        input.lines = null;

        Order result = newResource().createOrder(input);

        assertEquals("ORD-2", result.orderId);
        assertTrue(result.lines.isEmpty());
        assertNotNull(result.orderDate);
    }

    @Test
    void createOrder_emptyLines_skipsLineProcessing() throws Exception {
        PanacheQuery<Order> orderQuery = mock(PanacheQuery.class);
        when(orderQuery.firstResult()).thenReturn(null);
        panacheMock.when(() -> PanacheEntityBase.find(eq("orderId"), any(Object[].class)))
                .thenReturn(orderQuery);
        panacheMock.when(() -> PanacheEntityBase.findById(any()))
                .thenAnswer(invocation -> {
                    Object id = invocation.getArgument(0);
                    if (STORE_ID.equals(id)) return store();
                    if (CUSTOMER_ID.equals(id)) return customer();
                    return null;
                });

        OrderResource.OrderRecord input = new OrderResource.OrderRecord();
        input.orderId = "ORD-3";
        input.storeId = STORE_ID;
        input.customerId = CUSTOMER_ID;
        input.lines = List.of();

        Order result = newResource().createOrder(input);

        assertEquals("ORD-3", result.orderId);
        assertTrue(result.lines.isEmpty());
    }

    @Test
    void updateOrder_appliesStatusChannelAndSpecification() throws Exception {
        Order order = new Order();
        order.id = 9L;
        order.orderId = "ORD-9";
        order.status = "NEW";
        order.channel = "WEB";
        order.specification = "old-spec";
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(order);

        OrderResource.OrderRecord input = new OrderResource.OrderRecord();
        input.status = "PROCESSING";
        input.channel = "DRIVE";
        input.specification = "new-spec";

        Order result = newResource().updateOrder(9L, input);

        assertSame(order, result);
        assertEquals("PROCESSING", order.status);
        assertEquals("DRIVE", order.channel);
        assertEquals("new-spec", order.specification);
    }

    @Test
    void updateOrder_nullFields_keepsExistingValues() throws Exception {
        Order order = new Order();
        order.id = 9L;
        order.orderId = "ORD-9";
        order.status = "NEW";
        order.channel = "WEB";
        order.specification = "old-spec";
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(order);

        OrderResource.OrderRecord input = new OrderResource.OrderRecord();

        Order result = newResource().updateOrder(9L, input);

        assertSame(order, result);
        assertEquals("NEW", order.status);
        assertEquals("WEB", order.channel);
        assertEquals("old-spec", order.specification);
    }
}
