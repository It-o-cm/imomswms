package com.intermarche.backoffice.graphql.handling;

import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
import com.intermarche.backoffice.graphql.LotResourceService;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.graphql.api.Context;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProductCollectingOrderResource}.
 * <p>
 * Covers store-context scoping (employee store vs global roles), date-range queries,
 * single-order lookup and the update mutation (field updates, assignee validation,
 * cross-store security checks). Panache statics are mocked: no Quarkus test required.
 */
class ProductCollectingOrderResourceTest {

    private static final Long STORE_ID = 7L;
    private static final Long OTHER_STORE_ID = 8L;
    private static final Long ORDER_ID = 42L;
    private static final Long EMPLOYEE_ID = 99L;
    private static final String BADGE_ID = "BADGE-001";

    private MockedStatic<PanacheEntityBase> panacheMock;
    private LotResourceService lotResourceService;

    @BeforeEach
    void setUp() {
        panacheMock = mockStatic(PanacheEntityBase.class);
        lotResourceService = mock(LotResourceService.class);
    }

    @AfterEach
    void tearDown() {
        panacheMock.close();
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private ProductCollectingOrderResource newResource(boolean admin, boolean serviceCaller) {
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(BADGE_ID);
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.getPrincipal()).thenReturn(principal);
        when(identity.hasRole("ADMIN")).thenReturn(admin);
        when(identity.hasRole("SERVICE_CALLER")).thenReturn(serviceCaller);

        ProductCollectingOrderResource resource = new ProductCollectingOrderResource();
        resource.identity = identity;
        resource.lotResourceService = lotResourceService;
        return resource;
    }

    private Context emptyContext() {
        DataFetchingFieldSelectionSet selectionSet = mock(DataFetchingFieldSelectionSet.class);
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getSelectionSet()).thenReturn(selectionSet);
        Context context = mock(Context.class);
        when(context.unwrap(DataFetchingEnvironment.class)).thenReturn(env);
        return context;
    }

    private Store store(Long id) {
        Store store = new Store();
        store.id = id;
        return store;
    }

    private Employee employee(Store store) {
        Employee employee = new Employee();
        employee.store = store;
        return employee;
    }

    private ProductCollectingOrder order(Store store) {
        ProductCollectingOrder order = new ProductCollectingOrder();
        order.id = ORDER_ID;
        order.store = store;
        return order;
    }

    /**
     * Stubs Employee.findByBadgeId (through the Panache find static) to return the given employee.
     */
    private void stubBadgeLookup(Employee employee) {
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(employee);
        panacheMock.when(() -> PanacheEntityBase.find(startsWith("SELECT e FROM Employee"), any(Object[].class)))
                .thenReturn(query);
    }

    /**
     * Stubs ProductCollectingOrder.find(...) to return a query yielding the given order (or null).
     */
    private void stubOrderFind(ProductCollectingOrder result) {
        PanacheQuery<ProductCollectingOrder> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        panacheMock.when(() -> PanacheEntityBase.find(
                        argThat(jpql -> !jpql.startsWith("SELECT e FROM Employee")), any(Object[].class)))
                .thenReturn(query);
    }

    /**
     * Records the arguments of the last ProductCollectingOrder.list(...) call.
     */
    private static class ListCall {
        String jpql;
        Object[] params = new Object[0];
    }

    /**
     * Stubs ProductCollectingOrder.list(...) (both the single-arg and varargs overloads)
     * and captures the arguments of the last call into the returned holder.
     */
    private ListCall stubOrderList(List<ProductCollectingOrder> result) {
        ListCall call = new ListCall();
        panacheMock.when(() -> PanacheEntityBase.list(anyString()))
                .thenAnswer(invocation -> {
                    Object[] args = invocation.getArguments();
                    call.jpql = (String) args[0];
                    call.params = new Object[0];
                    return result;
                });
        panacheMock.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    Object[] args = invocation.getArguments();
                    call.jpql = (String) args[0];
                    call.params = args.length > 1 && args[1] instanceof Object[]
                            ? (Object[]) args[1]
                            : Arrays.copyOfRange(args, 1, args.length);
                    return result;
                });
        return call;
    }

    /**
     * Stubs findById: ORDER_ID yields the order, EMPLOYEE_ID yields the assignee, anything else null.
     */
    private void stubFindById(ProductCollectingOrder order, Employee assignee) {
        panacheMock.when(() -> PanacheEntityBase.findById(any()))
                .thenAnswer(invocation -> {
                    Object id = invocation.getArgument(0);
                    if (ORDER_ID.equals(id)) return order;
                    if (EMPLOYEE_ID.equals(id)) return assignee;
                    return null;
                });
    }

    private ProductCollectingOrderResource.ProductCollectingOrderRecord newInput() {
        return new ProductCollectingOrderResource.ProductCollectingOrderRecord();
    }

    // --------------------------------------------------
    // allProductCollectingOrders
    // --------------------------------------------------

    @Test
    void all_withStoreContext_filtersByStoreId() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        ListCall call = stubOrderList(List.of(order(store)));

        List<ProductCollectingOrder> result = newResource(false, false).allProductCollectingOrders(emptyContext());

        assertEquals(1, result.size());
        assertTrue(call.jpql.contains("where o.store.id = ?1"));
        assertArrayEquals(new Object[]{STORE_ID}, call.params);
    }

    @Test
    void all_withoutStoreContext_queriesWithoutFilter() throws Exception {
        stubBadgeLookup(null);
        stubOrderList(List.of());

        List<ProductCollectingOrder> result = newResource(true, false).allProductCollectingOrders(emptyContext());

        assertNotNull(result);
        panacheMock.verify(() -> PanacheEntityBase.list(argThat(jpql -> !jpql.contains("where"))));
    }

    @Test
    void all_employeeWithoutStoreButAdmin_queriesWithoutFilter() throws Exception {
        stubBadgeLookup(employee(null));
        stubOrderList(List.of());

        List<ProductCollectingOrder> result = newResource(true, false).allProductCollectingOrders(emptyContext());

        assertNotNull(result);
        panacheMock.verify(() -> PanacheEntityBase.list(argThat(jpql -> !jpql.contains("where"))));
    }

    @Test
    void all_serviceCallerWithoutStore_queriesWithoutFilter() throws Exception {
        stubBadgeLookup(null);
        stubOrderList(List.of());

        List<ProductCollectingOrder> result = newResource(false, true).allProductCollectingOrders(emptyContext());

        assertNotNull(result);
        panacheMock.verify(() -> PanacheEntityBase.list(argThat(jpql -> !jpql.contains("where"))));
    }

    @Test
    void all_noStoreAndNoGlobalRole_throwsGraphQLException() throws Exception {
        stubBadgeLookup(null);

        ProductCollectingOrderResource resource = newResource(false, false);

        GraphQLException ex = assertThrows(GraphQLException.class,
                () -> resource.allProductCollectingOrders(emptyContext()));
        assertInstanceOf(SecurityException.class, ex.getCause());
    }

    // --------------------------------------------------
    // productCollectingOrdersByDateRange
    // --------------------------------------------------

    @Test
    void byDateRange_withStoreContext_appendsStoreFilter() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        ListCall call = stubOrderList(List.of(order(store)));

        LocalDateTime start = LocalDateTime.of(2026, 10, 14, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 14, 18, 0);

        List<ProductCollectingOrder> result = newResource(false, false)
                .productCollectingOrdersByDateRange(start, end, emptyContext());

        assertEquals(1, result.size());
        assertTrue(call.jpql.contains("o.requestedStart >= ?1 AND o.requestedStart <= ?2"));
        assertTrue(call.jpql.contains("AND o.store.id = ?3"));
        assertArrayEquals(new Object[]{start, end, STORE_ID}, call.params);
    }

    @Test
    void byDateRange_withoutStoreContext_noStoreFilter() throws Exception {
        stubBadgeLookup(null);
        ListCall call = stubOrderList(List.of());

        LocalDateTime start = LocalDateTime.of(2026, 10, 14, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 14, 18, 0);

        List<ProductCollectingOrder> result = newResource(true, false)
                .productCollectingOrdersByDateRange(start, end, emptyContext());

        assertNotNull(result);
        assertTrue(call.jpql.contains("o.requestedStart >= ?1 AND o.requestedStart <= ?2"));
        assertFalse(call.jpql.contains("store.id"));
        assertArrayEquals(new Object[]{start, end}, call.params);
    }

    // --------------------------------------------------
    // productCollectingOrder (by id)
    // --------------------------------------------------

    @Test
    void byId_withStoreContext_found_returnsOrder() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        ProductCollectingOrder found = order(store);
        stubOrderFind(found);

        ProductCollectingOrder result = newResource(false, false).productCollectingOrder(ORDER_ID, emptyContext());

        assertSame(found, result);
        panacheMock.verify(() -> PanacheEntityBase.find(
                argThat(jpql -> jpql.contains("o.id = ?1") && jpql.contains("AND o.store.id = ?2")),
                any(Object[].class)));
    }

    @Test
    void byId_withStoreContext_notFound_throwsNoSuchElement() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        stubOrderFind(null);

        ProductCollectingOrderResource resource = newResource(false, false);

        assertThrows(NoSuchElementException.class, () -> resource.productCollectingOrder(ORDER_ID, emptyContext()));
    }

    @Test
    void byId_withoutStoreContext_found_returnsOrder() throws Exception {
        stubBadgeLookup(null);
        ProductCollectingOrder found = order(store(OTHER_STORE_ID));
        stubOrderFind(found);

        ProductCollectingOrder result = newResource(true, false).productCollectingOrder(ORDER_ID, emptyContext());

        assertSame(found, result);
        panacheMock.verify(() -> PanacheEntityBase.find(
                argThat(jpql -> jpql.contains("o.id = ?1") && !jpql.contains("store.id")),
                any(Object[].class)));
    }

    @Test
    void byId_withoutStoreContext_notFound_throwsNoSuchElement() throws Exception {
        stubBadgeLookup(null);
        stubOrderFind(null);

        ProductCollectingOrderResource resource = newResource(true, false);

        assertThrows(NoSuchElementException.class, () -> resource.productCollectingOrder(ORDER_ID, emptyContext()));
    }

    // --------------------------------------------------
    // updateProductCollectingOrder
    // --------------------------------------------------

    @Test
    void update_orderNotFound_throwsNoSuchElement() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        stubFindById(null, null);

        ProductCollectingOrderResource resource = newResource(false, false);

        assertThrows(NoSuchElementException.class,
                () -> resource.updateProductCollectingOrder(ORDER_ID, newInput()));
    }

    @Test
    void update_orderFromDifferentStore_throwsGraphQLException() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        stubFindById(order(store(OTHER_STORE_ID)), null);

        ProductCollectingOrderResource resource = newResource(false, false);

        GraphQLException ex = assertThrows(GraphQLException.class,
                () -> resource.updateProductCollectingOrder(ORDER_ID, newInput()));
        assertInstanceOf(SecurityException.class, ex.getCause());
    }

    @Test
    void update_allFieldsWithAssigneeFromSameStore_appliesChanges() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        ProductCollectingOrder order = order(store);
        Employee assignee = employee(store);
        stubFindById(order, assignee);

        ProductCollectingOrderResource.ProductCollectingOrderRecord input = newInput();
        input.status = BaseHandlingOrder.HandlingOrderStatus.PROCESSING;
        input.requestedStart = LocalDateTime.of(2026, 10, 15, 9, 0);
        input.requestedEnd = LocalDateTime.of(2026, 10, 15, 17, 0);
        input.assigneeId = EMPLOYEE_ID;
        input.lots = List.of();

        ProductCollectingOrder result = newResource(false, false).updateProductCollectingOrder(ORDER_ID, input);

        assertSame(order, result);
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.PROCESSING, order.status);
        assertEquals(input.requestedStart, order.requestedStart);
        assertEquals(input.requestedEnd, order.requestedEnd);
        assertSame(assignee, order.assignee);
        verify(lotResourceService).synchronizeLots(order, input.lots);
    }

    @Test
    void update_assigneeFromDifferentStore_throwsGraphQLException() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        ProductCollectingOrder order = order(store);
        stubFindById(order, employee(store(OTHER_STORE_ID)));

        ProductCollectingOrderResource.ProductCollectingOrderRecord input = newInput();
        input.assigneeId = EMPLOYEE_ID;

        ProductCollectingOrderResource resource = newResource(false, false);

        GraphQLException ex = assertThrows(GraphQLException.class,
                () -> resource.updateProductCollectingOrder(ORDER_ID, input));
        assertInstanceOf(SecurityException.class, ex.getCause());
    }

    @Test
    void update_assigneeNotFound_throwsNoSuchElement() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        stubFindById(order(store), null);

        ProductCollectingOrderResource.ProductCollectingOrderRecord input = newInput();
        input.assigneeId = EMPLOYEE_ID;

        ProductCollectingOrderResource resource = newResource(false, false);

        assertThrows(NoSuchElementException.class,
                () -> resource.updateProductCollectingOrder(ORDER_ID, input));
    }

    @Test
    void update_nullAssigneeId_clearsAssignee() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        ProductCollectingOrder order = order(store);
        order.assignee = employee(store);
        stubFindById(order, null);

        ProductCollectingOrderResource.ProductCollectingOrderRecord input = newInput();
        input.assigneeId = null;

        ProductCollectingOrder result = newResource(false, false).updateProductCollectingOrder(ORDER_ID, input);

        assertSame(order, result);
        assertNull(order.assignee);
        verify(lotResourceService).synchronizeLots(order, input.lots);
    }

    @Test
    void update_adminWithoutStoreContext_skipsStoreChecks() throws Exception {
        stubBadgeLookup(null);
        ProductCollectingOrder order = order(store(OTHER_STORE_ID));
        Employee assignee = employee(store(OTHER_STORE_ID));
        stubFindById(order, assignee);

        ProductCollectingOrderResource.ProductCollectingOrderRecord input = newInput();
        input.assigneeId = EMPLOYEE_ID;
        input.requestedStart = LocalDateTime.of(2026, 10, 15, 9, 0);

        ProductCollectingOrder result = newResource(true, false).updateProductCollectingOrder(ORDER_ID, input);

        assertSame(order, result);
        assertSame(assignee, order.assignee);
        assertEquals(input.requestedStart, order.requestedStart);
    }
}
