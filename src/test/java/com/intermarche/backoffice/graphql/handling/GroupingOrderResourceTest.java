package com.intermarche.backoffice.graphql.handling;

import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
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
 * Unit tests for {@link GroupingOrderResource}.
 * <p>
 * Covers store-context scoping (employee store vs global roles), date-range queries,
 * single-order lookup, the customer/building fetch-join optimization and the update
 * mutation (field updates, assignee validation, cross-store security checks).
 * Panache statics are mocked: no Quarkus test required.
 */
class GroupingOrderResourceTest {

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

    private GroupingOrderResource newResource(boolean admin, boolean serviceCaller) {
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(BADGE_ID);
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.getPrincipal()).thenReturn(principal);
        when(identity.hasRole("ADMIN")).thenReturn(admin);
        when(identity.hasRole("SERVICE_CALLER")).thenReturn(serviceCaller);

        GroupingOrderResource resource = new GroupingOrderResource();
        resource.identity = identity;
        resource.lotResourceService = lotResourceService;
        return resource;
    }

    private Context emptyContext() {
        return contextWithSelection(false, false);
    }

    private Context contextWithSelection(boolean customer, boolean building) {
        DataFetchingFieldSelectionSet selectionSet = mock(DataFetchingFieldSelectionSet.class);
        when(selectionSet.contains("customer")).thenReturn(customer);
        when(selectionSet.contains("building")).thenReturn(building);
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

    private GroupingOrder order(Store store) {
        GroupingOrder order = new GroupingOrder();
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
     * Stubs GroupingOrder.find(...) to return a query yielding the given order (or null).
     */
    private void stubOrderFind(GroupingOrder result) {
        PanacheQuery<GroupingOrder> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        panacheMock.when(() -> PanacheEntityBase.find(
                        argThat(jpql -> !jpql.startsWith("SELECT e FROM Employee")), any(Object[].class)))
                .thenReturn(query);
    }

    /**
     * Records the arguments of the last GroupingOrder.list(...) call.
     */
    private static class ListCall {
        String jpql;
        Object[] params = new Object[0];
    }

    /**
     * Stubs GroupingOrder.list(...) (both the single-arg and varargs overloads)
     * and captures the arguments of the last call into the returned holder.
     */
    private ListCall stubOrderList(List<GroupingOrder> result) {
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
    private void stubFindById(GroupingOrder order, Employee assignee) {
        panacheMock.when(() -> PanacheEntityBase.findById(any()))
                .thenAnswer(invocation -> {
                    Object id = invocation.getArgument(0);
                    if (ORDER_ID.equals(id)) return order;
                    if (EMPLOYEE_ID.equals(id)) return assignee;
                    return null;
                });
    }

    private GroupingOrderResource.GroupingOrderRecord newInput() {
        return new GroupingOrderResource.GroupingOrderRecord();
    }

    // --------------------------------------------------
    // allGroupingOrders
    // --------------------------------------------------

    @Test
    void all_withStoreContext_filtersByStoreId() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        ListCall call = stubOrderList(List.of(order(store)));

        List<GroupingOrder> result = newResource(false, false).allGroupingOrders(emptyContext());

        assertEquals(1, result.size());
        assertTrue(call.jpql.contains("where o.store.id = ?1"));
        assertArrayEquals(new Object[]{STORE_ID}, call.params);
    }

    @Test
    void all_withoutStoreContext_queriesWithoutFilter() throws Exception {
        stubBadgeLookup(null);
        stubOrderList(List.of());

        List<GroupingOrder> result = newResource(true, false).allGroupingOrders(emptyContext());

        assertNotNull(result);
        panacheMock.verify(() -> PanacheEntityBase.list(argThat(jpql -> !jpql.contains("where"))));
    }

    @Test
    void all_employeeWithoutStoreButAdmin_queriesWithoutFilter() throws Exception {
        stubBadgeLookup(employee(null));
        stubOrderList(List.of());

        List<GroupingOrder> result = newResource(true, false).allGroupingOrders(emptyContext());

        assertNotNull(result);
        panacheMock.verify(() -> PanacheEntityBase.list(argThat(jpql -> !jpql.contains("where"))));
    }

    @Test
    void all_serviceCallerWithoutStore_queriesWithoutFilter() throws Exception {
        stubBadgeLookup(null);
        stubOrderList(List.of());

        List<GroupingOrder> result = newResource(false, true).allGroupingOrders(emptyContext());

        assertNotNull(result);
        panacheMock.verify(() -> PanacheEntityBase.list(argThat(jpql -> !jpql.contains("where"))));
    }

    @Test
    void all_noStoreAndNoGlobalRole_throwsGraphQLException() throws Exception {
        stubBadgeLookup(null);

        GroupingOrderResource resource = newResource(false, false);

        GraphQLException ex = assertThrows(GraphQLException.class,
                () -> resource.allGroupingOrders(emptyContext()));
        assertInstanceOf(SecurityException.class, ex.getCause());
    }

    @Test
    void all_withCustomerAndBuildingSelection_appendsFetchJoins() throws Exception {
        stubBadgeLookup(null);
        ListCall call = stubOrderList(List.of());

        List<GroupingOrder> result = newResource(true, false).allGroupingOrders(contextWithSelection(true, true));

        assertNotNull(result);
        assertTrue(call.jpql.contains("left join fetch o.customer c "));
        assertTrue(call.jpql.contains("left join fetch o.buffer b left outer join fetch b.building bl "));
    }

    @Test
    void all_withCustomerOnlySelection_appendsCustomerJoinOnly() throws Exception {
        stubBadgeLookup(null);
        ListCall call = stubOrderList(List.of());

        newResource(true, false).allGroupingOrders(contextWithSelection(true, false));

        assertTrue(call.jpql.contains("left join fetch o.customer c "));
        assertFalse(call.jpql.contains("o.buffer"));
    }

    @Test
    void all_withBuildingOnlySelection_appendsBufferJoinOnly() throws Exception {
        stubBadgeLookup(null);
        ListCall call = stubOrderList(List.of());

        newResource(true, false).allGroupingOrders(contextWithSelection(false, true));

        assertFalse(call.jpql.contains("o.customer"));
        assertTrue(call.jpql.contains("left join fetch o.buffer b left outer join fetch b.building bl "));
    }

    // --------------------------------------------------
    // groupingOrdersByDateRange
    // --------------------------------------------------

    @Test
    void byDateRange_withStoreContext_appendsStoreFilter() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        ListCall call = stubOrderList(List.of(order(store)));

        LocalDateTime start = LocalDateTime.of(2026, 10, 14, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 14, 18, 0);

        List<GroupingOrder> result = newResource(false, false)
                .groupingOrdersByDateRange(start, end, emptyContext());

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

        List<GroupingOrder> result = newResource(true, false)
                .groupingOrdersByDateRange(start, end, emptyContext());

        assertNotNull(result);
        assertTrue(call.jpql.contains("o.requestedStart >= ?1 AND o.requestedStart <= ?2"));
        assertFalse(call.jpql.contains("store.id"));
        assertArrayEquals(new Object[]{start, end}, call.params);
    }

    // --------------------------------------------------
    // groupingOrder (by id)
    // --------------------------------------------------

    @Test
    void byId_withStoreContext_found_returnsOrder() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        GroupingOrder found = order(store);
        stubOrderFind(found);

        GroupingOrder result = newResource(false, false).groupingOrder(ORDER_ID, emptyContext());

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

        GroupingOrderResource resource = newResource(false, false);

        assertThrows(NoSuchElementException.class, () -> resource.groupingOrder(ORDER_ID, emptyContext()));
    }

    @Test
    void byId_withoutStoreContext_found_returnsOrder() throws Exception {
        stubBadgeLookup(null);
        GroupingOrder found = order(store(OTHER_STORE_ID));
        stubOrderFind(found);

        GroupingOrder result = newResource(true, false).groupingOrder(ORDER_ID, emptyContext());

        assertSame(found, result);
        panacheMock.verify(() -> PanacheEntityBase.find(
                argThat(jpql -> jpql.contains("o.id = ?1") && !jpql.contains("store.id")),
                any(Object[].class)));
    }

    @Test
    void byId_withoutStoreContext_notFound_throwsNoSuchElement() throws Exception {
        stubBadgeLookup(null);
        stubOrderFind(null);

        GroupingOrderResource resource = newResource(true, false);

        assertThrows(NoSuchElementException.class, () -> resource.groupingOrder(ORDER_ID, emptyContext()));
    }

    // --------------------------------------------------
    // updateGroupingOrder
    // --------------------------------------------------

    @Test
    void update_orderNotFound_throwsNoSuchElement() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        stubFindById(null, null);

        GroupingOrderResource resource = newResource(false, false);

        assertThrows(NoSuchElementException.class,
                () -> resource.updateGroupingOrder(ORDER_ID, newInput()));
    }

    @Test
    void update_orderFromDifferentStore_throwsGraphQLException() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        stubFindById(order(store(OTHER_STORE_ID)), null);

        GroupingOrderResource resource = newResource(false, false);

        GraphQLException ex = assertThrows(GraphQLException.class,
                () -> resource.updateGroupingOrder(ORDER_ID, newInput()));
        assertInstanceOf(SecurityException.class, ex.getCause());
    }

    @Test
    void update_allFieldsWithAssigneeFromSameStore_appliesChanges() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        GroupingOrder order = order(store);
        Employee assignee = employee(store);
        stubFindById(order, assignee);

        GroupingOrderResource.GroupingOrderRecord input = newInput();
        input.status = BaseHandlingOrder.HandlingOrderStatus.PROCESSING;
        input.requestedStart = LocalDateTime.of(2026, 10, 15, 9, 0);
        input.requestedEnd = LocalDateTime.of(2026, 10, 15, 17, 0);
        input.assigneeId = EMPLOYEE_ID;
        input.lots = List.of();

        GroupingOrder result = newResource(false, false).updateGroupingOrder(ORDER_ID, input);

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
        GroupingOrder order = order(store);
        stubFindById(order, employee(store(OTHER_STORE_ID)));

        GroupingOrderResource.GroupingOrderRecord input = newInput();
        input.assigneeId = EMPLOYEE_ID;

        GroupingOrderResource resource = newResource(false, false);

        GraphQLException ex = assertThrows(GraphQLException.class,
                () -> resource.updateGroupingOrder(ORDER_ID, input));
        assertInstanceOf(SecurityException.class, ex.getCause());
    }

    @Test
    void update_assigneeNotFound_throwsNoSuchElement() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        stubFindById(order(store), null);

        GroupingOrderResource.GroupingOrderRecord input = newInput();
        input.assigneeId = EMPLOYEE_ID;

        GroupingOrderResource resource = newResource(false, false);

        assertThrows(NoSuchElementException.class,
                () -> resource.updateGroupingOrder(ORDER_ID, input));
    }

    @Test
    void update_nullAssigneeId_clearsAssignee() throws Exception {
        Store store = store(STORE_ID);
        stubBadgeLookup(employee(store));
        GroupingOrder order = order(store);
        order.assignee = employee(store);
        stubFindById(order, null);

        GroupingOrderResource.GroupingOrderRecord input = newInput();
        input.assigneeId = null;

        GroupingOrder result = newResource(false, false).updateGroupingOrder(ORDER_ID, input);

        assertSame(order, result);
        assertNull(order.assignee);
        verify(lotResourceService).synchronizeLots(order, input.lots);
    }

    @Test
    void update_adminWithoutStoreContext_skipsStoreChecks() throws Exception {
        stubBadgeLookup(null);
        GroupingOrder order = order(store(OTHER_STORE_ID));
        Employee assignee = employee(store(OTHER_STORE_ID));
        stubFindById(order, assignee);

        GroupingOrderResource.GroupingOrderRecord input = newInput();
        input.assigneeId = EMPLOYEE_ID;
        input.requestedStart = LocalDateTime.of(2026, 10, 15, 9, 0);

        GroupingOrder result = newResource(true, false).updateGroupingOrder(ORDER_ID, input);

        assertSame(order, result);
        assertSame(assignee, order.assignee);
        assertEquals(input.requestedStart, order.requestedStart);
    }
}
