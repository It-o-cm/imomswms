package com.intermarche.backoffice.graphql.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.order.OrderRequest;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.security.Principal;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderRequestResource}.
 * Covers the identity accessor, the partial-update branches of the update mutation and the store checks of the
 * create and update mutations.
 */
class OrderRequestResourceTest {

    private static final String SPEC_S001 = "{\"orderHeader\":{\"store\":{\"code\":\"S001\"}}}";
    private static final String SPEC_S002 = "{\"orderHeader\":{\"store\":{\"code\":\"S002\"}}}";

    private MockedStatic<PanacheEntityBase> panacheMock;

    /**
     * Opens the Panache static mock and declares the stores S001 (id 1) and S002 (id 2).
     */
    @BeforeEach
    void setUp() {
        panacheMock = mockStatic(PanacheEntityBase.class);
        panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class))).thenAnswer(invocation -> {
            Object argument = invocation.getArguments()[1];
            String code = (String) (argument instanceof Object[] params ? params[0] : argument);
            PanacheQuery<Store> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn("S001".equals(code) ? store(1L, "S001") : "S002".equals(code) ? store(2L, "S002") : null);
            return query;
        });
    }

    /**
     * Releases the Panache static mock.
     */
    @AfterEach
    void tearDown() {
        panacheMock.close();
    }

    /**
     * Builds a store.
     *
     * @param id   the id
     * @param code the code
     * @return the store
     */
    private static Store store(Long id, String code) {
        Store store = new Store();
        store.id = id;
        store.code = code;
        return store;
    }

    /**
     * Builds the resource for an administrator.
     *
     * @return the resource
     */
    private OrderRequestResource newResource() {
        OrderRequestResource resource = new OrderRequestResource();
        resource.identity = mock(SecurityIdentity.class);
        when(resource.identity.hasRole("ADMIN")).thenReturn(true);
        resource.objectMapper = new ObjectMapper();
        return resource;
    }

    /**
     * Builds the resource for a manager of store S001.
     *
     * @return the resource
     */
    private OrderRequestResource managerOfS001() {
        OrderRequestResource resource = new OrderRequestResource();
        resource.identity = mock(SecurityIdentity.class);
        Principal principal = () -> "100234";
        when(resource.identity.getPrincipal()).thenReturn(principal);
        resource.objectMapper = new ObjectMapper();
        Employee employee = new Employee();
        employee.store = store(1L, "S001");
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(employee);
        panacheMock.when(() -> PanacheEntityBase.find(startsWith("SELECT e FROM Employee"), any(Object[].class))).thenReturn(query);
        return resource;
    }

    /**
     * Builds a stored order request of store S001.
     *
     * @param id the id
     * @return the order request
     */
    private OrderRequest orderRequest(Long id) {
        OrderRequest order = new OrderRequest();
        order.id = id;
        order.orderNumber = "ORD-1";
        order.storeCode = "S001";
        order.specification = SPEC_S001;
        return order;
    }

    /**
     * Builds an input.
     *
     * @param storeCode     the store code
     * @param specification the specification
     * @return the input
     */
    private static OrderRequestResource.OrderRequestRecord input(String storeCode, String specification) {
        OrderRequestResource.OrderRequestRecord input = new OrderRequestResource.OrderRequestRecord();
        input.orderNumber = "ORD-2";
        input.storeCode = storeCode;
        input.specification = specification;
        return input;
    }

    /**
     * Checks that the injected identity is returned.
     */
    @Test
    void getIdentity_returnsInjectedIdentity() {
        OrderRequestResource resource = new OrderRequestResource();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        resource.identity = identity;

        assertSame(identity, resource.getIdentity());
    }

    /**
     * Checks that every given field is applied.
     *
     * @throws Exception if the mutation fails
     */
    @Test
    void updateOrderRequest_appliesAllFields() throws Exception {
        OrderRequest order = orderRequest(1L);
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(order);

        OrderRequest result = newResource().updateOrderRequest(1L, input("S002", SPEC_S002));

        assertSame(order, result);
        assertEquals("ORD-2", order.orderNumber);
        assertEquals("S002", order.storeCode);
        assertEquals(SPEC_S002, order.specification);
    }

    /**
     * Checks that missing fields keep their values.
     *
     * @throws Exception if the mutation fails
     */
    @Test
    void updateOrderRequest_nullFields_keepsExistingValues() throws Exception {
        OrderRequest order = orderRequest(1L);
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(order);

        OrderRequest result = newResource().updateOrderRequest(1L, new OrderRequestResource.OrderRequestRecord());

        assertSame(order, result);
        assertEquals("ORD-1", order.orderNumber);
        assertEquals("S001", order.storeCode);
        assertEquals(SPEC_S001, order.specification);
    }

    /**
     * Checks that an unknown order request is reported as missing.
     */
    @Test
    void updateOrderRequest_notFound_throwsNoSuchElement() {
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(null);

        assertThrows(NoSuchElementException.class,
                () -> newResource().updateOrderRequest(99L, new OrderRequestResource.OrderRequestRecord()));
    }

    /**
     * Checks that a manager sees the order request of another store as missing.
     */
    @Test
    void updateOrderRequest_otherStoreOfAManager_isReportedAsMissing() {
        OrderRequest order = orderRequest(1L);
        order.storeCode = "S002";
        order.specification = SPEC_S002;
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(order);

        assertThrows(NoSuchElementException.class,
                () -> managerOfS001().updateOrderRequest(1L, new OrderRequestResource.OrderRequestRecord()));
        assertEquals("S002", order.storeCode);
    }

    /**
     * Checks that a manager cannot move an order request of its store to another store.
     */
    @Test
    void updateOrderRequest_managerMovingToAnotherStore_isRefused() {
        OrderRequest order = orderRequest(1L);
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(order);

        GraphQLException refused = assertThrows(GraphQLException.class,
                () -> managerOfS001().updateOrderRequest(1L, input("S002", SPEC_S002)));

        assertInstanceOf(SecurityException.class, refused.getCause());
        assertEquals("S001", order.storeCode);
    }

    /**
     * Checks that a manager may update an order request of its own store.
     *
     * @throws Exception if the mutation fails
     */
    @Test
    void updateOrderRequest_managerOnItsStore_isApplied() throws Exception {
        OrderRequest order = orderRequest(1L);
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(order);

        managerOfS001().updateOrderRequest(1L, input(null, null));

        assertEquals("ORD-2", order.orderNumber);
    }

    /**
     * Checks that a creation is refused for an unknown or missing store, another store than the manager's, a
     * specification that is not JSON or that targets another store.
     */
    @Test
    void createOrderRequest_refusesInvalidOrForeignTargets() {
        OrderRequestResource manager = managerOfS001();
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(GraphQLException.class, () -> manager.createOrderRequest(input("NOPE", null))).getCause());
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(GraphQLException.class, () -> manager.createOrderRequest(input(null, null))).getCause());
        assertInstanceOf(SecurityException.class,
                assertThrows(GraphQLException.class, () -> manager.createOrderRequest(input("S002", SPEC_S002))).getCause());
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(GraphQLException.class, () -> manager.createOrderRequest(input("S001", "{not json"))).getCause());
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(GraphQLException.class, () -> manager.createOrderRequest(input("S001", SPEC_S002))).getCause());
    }

    /**
     * Checks that a creation on the manager's store is accepted, with a matching, blank, missing or store-less
     * specification.
     *
     * @throws Exception if the mutation fails
     */
    @Test
    void createOrderRequest_onTheOwnStore_isPersisted() throws Exception {
        try (var arc = mockStatic(io.quarkus.arc.Arc.class)) {
            var session = com.intermarche.backoffice.testutil.PanacheTestSupport.mockArcSession(arc);
            OrderRequestResource manager = managerOfS001();
            for (String spec : new String[]{SPEC_S001, " ", null, "{\"orderHeader\":{}}"}) {
                OrderRequest created = manager.createOrderRequest(input("S001", spec));
                assertEquals("S001", created.storeCode);
            }
            verify(session, times(4)).persist(any(OrderRequest.class));
        }
    }
}
