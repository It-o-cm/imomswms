package com.intermarche.backoffice.graphql.order;

import com.intermarche.backoffice.domain.order.OrderRequest;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderRequestResource}.
 * Covers the identity accessor and the partial-update branches of the update mutation.
 */
class OrderRequestResourceTest {

    private MockedStatic<PanacheEntityBase> panacheMock;

    @BeforeEach
    void setUp() {
        panacheMock = mockStatic(PanacheEntityBase.class);
    }

    @AfterEach
    void tearDown() {
        panacheMock.close();
    }

    private OrderRequestResource newResource() {
        OrderRequestResource resource = new OrderRequestResource();
        resource.identity = mock(SecurityIdentity.class);
        return resource;
    }

    private OrderRequest orderRequest(Long id) {
        OrderRequest order = new OrderRequest();
        order.id = id;
        order.orderNumber = "ORD-1";
        order.storeCode = "S001";
        order.specification = "old-spec";
        return order;
    }

    @Test
    void getIdentity_returnsInjectedIdentity() {
        OrderRequestResource resource = new OrderRequestResource();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        resource.identity = identity;

        assertSame(identity, resource.getIdentity());
    }

    @Test
    void updateOrderRequest_appliesAllFields() throws Exception {
        OrderRequest order = orderRequest(1L);
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(order);

        OrderRequestResource.OrderRequestRecord input = new OrderRequestResource.OrderRequestRecord();
        input.orderNumber = "ORD-2";
        input.storeCode = "S002";
        input.specification = "new-spec";

        OrderRequest result = newResource().updateOrderRequest(1L, input);

        assertSame(order, result);
        assertEquals("ORD-2", order.orderNumber);
        assertEquals("S002", order.storeCode);
        assertEquals("new-spec", order.specification);
    }

    @Test
    void updateOrderRequest_nullFields_keepsExistingValues() throws Exception {
        OrderRequest order = orderRequest(1L);
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(order);

        OrderRequestResource.OrderRequestRecord input = new OrderRequestResource.OrderRequestRecord();

        OrderRequest result = newResource().updateOrderRequest(1L, input);

        assertSame(order, result);
        assertEquals("ORD-1", order.orderNumber);
        assertEquals("S001", order.storeCode);
        assertEquals("old-spec", order.specification);
    }

    @Test
    void updateOrderRequest_notFound_throwsNoSuchElement() {
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(null);

        OrderRequestResource.OrderRequestRecord input = new OrderRequestResource.OrderRequestRecord();

        assertThrows(NoSuchElementException.class,
                () -> newResource().updateOrderRequest(99L, input));
    }
}
