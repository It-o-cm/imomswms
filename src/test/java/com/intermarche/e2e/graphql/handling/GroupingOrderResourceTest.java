package com.intermarche.e2e.graphql.handling;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import com.intermarche.backoffice.graphql.handling.GroupingOrderResource;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.smallrye.graphql.api.Context;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link GroupingOrderResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 * <p>
 * The GraphQL {@link Context} is mocked: the selection set controls which fetch joins
 * the resource appends to its JPQL queries.
 */
@QuarkusTest
@TestTransaction
public class GroupingOrderResourceTest {

    @Inject
    GroupingOrderResource groupingOrderResource;

    // --------------------------------------------------
    // Fixture Helpers
    // --------------------------------------------------

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
        Store store = new Store();
        store.code = code;
        store.name = "Store " + code;
        store.address = new Adresse();
        store.persist();
        return store;
    }

    private Employee createEmployee(String badgeId, Store store) {
        Employee employee = new Employee();
        employee.firstName = "Emp";
        employee.lastName = badgeId;
        employee.badgeId = badgeId;
        employee.email = badgeId + "@test.com";
        employee.password = BCrypt.hashpw("secret", BCrypt.gensalt());
        employee.role = Employee.EmployeeRole.PICKER;
        employee.store = store;
        employee.persist();
        return employee;
    }

    private GroupingOrder createGroupingOrder(Store store, String code, LocalDateTime requestedStart) {
        GroupingOrder order = new GroupingOrder();
        order.store = store;
        order.code = code;
        order.requestedStart = requestedStart;
        order.requestedEnd = requestedStart != null ? requestedStart.plusHours(1) : null;
        order.persist();
        return order;
    }

    // --------------------------------------------------
    // Query Tests: global scope (ADMIN)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testAllGroupingOrders_Empty() throws GraphQLException {
        List<GroupingOrder> result = groupingOrderResource.allGroupingOrders(mockContext());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testAllGroupingOrders_ReturnsAll() throws GraphQLException {
        Store store = createStore("GRP_S1");
        createGroupingOrder(store, "GRP_01", LocalDateTime.now());
        createGroupingOrder(store, "GRP_02", LocalDateTime.now());

        List<GroupingOrder> result = groupingOrderResource.allGroupingOrders(mockContext());
        assertEquals(2, result.size());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testAllGroupingOrders_WithCustomerAndBuildingSelection() throws GraphQLException {
        Store store = createStore("GRP_S2");
        GroupingOrder order = createGroupingOrder(store, "GRP_03", LocalDateTime.now());

        Customer customer = new Customer();
        customer.customerId = "GRP-CUST";
        customer.firstName = "Jane";
        customer.lastName = "Smith";
        customer.email = "grp@test.com";
        customer.persist();
        order.customer = customer;

        StoreBuilding building = new StoreBuilding();
        building.buildingCode = "GRP-BLD";
        building.persist();
        Buffer buffer = new Buffer();
        buffer.building = building;
        buffer.purpose = Buffer.BufferPurpose.PROCESSING;
        buffer.persist();
        order.buffer = buffer;

        // Selection contains "customer" and "building": both fetch joins must be appended
        List<GroupingOrder> result = groupingOrderResource.allGroupingOrders(mockContext("customer", "building"));
        assertEquals(1, result.size());
        assertNotNull(result.get(0).customer);
        assertEquals("GRP-CUST", result.get(0).customer.customerId);
        assertNotNull(result.get(0).buffer);
        assertNotNull(result.get(0).buffer.building);
        assertEquals("GRP-BLD", result.get(0).buffer.building.buildingCode);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testGroupingOrdersByDateRange_Filters() throws GraphQLException {
        Store store = createStore("GRP_S3");
        LocalDateTime base = LocalDateTime.of(2026, 10, 14, 12, 0);
        createGroupingOrder(store, "GRP_BEFORE", base.minusDays(2));
        createGroupingOrder(store, "GRP_IN", base);
        createGroupingOrder(store, "GRP_AFTER", base.plusDays(2));

        List<GroupingOrder> result = groupingOrderResource.groupingOrdersByDateRange(
                base.minusDays(1), base.plusDays(1), mockContext());
        assertEquals(1, result.size());
        assertEquals("GRP_IN", result.get(0).code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testGroupingOrderById_Success() throws GraphQLException {
        Store store = createStore("GRP_S4");
        GroupingOrder order = createGroupingOrder(store, "GRP_04", LocalDateTime.now());

        GroupingOrder found = groupingOrderResource.groupingOrder(order.id, mockContext());
        assertNotNull(found);
        assertEquals("GRP_04", found.code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testGroupingOrderById_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            groupingOrderResource.groupingOrder(9999L, mockContext());
        });
    }

    // --------------------------------------------------
    // Query Tests: store scope (MANAGER linked to a store)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllGroupingOrders_ScopedToStore() throws GraphQLException {
        Store ownStore = createStore("GRP_OWN");
        Store otherStore = createStore("GRP_OTHER");
        createEmployee("testUser", ownStore);
        createGroupingOrder(ownStore, "GRP_OWN_1", LocalDateTime.now());
        createGroupingOrder(otherStore, "GRP_OTHER_1", LocalDateTime.now());

        List<GroupingOrder> result = groupingOrderResource.allGroupingOrders(mockContext());
        assertEquals(1, result.size());
        assertEquals("GRP_OWN_1", result.get(0).code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testGroupingOrderById_Scoped_OtherStore_NotFound() {
        Store ownStore = createStore("GRP_OWN2");
        Store otherStore = createStore("GRP_OTHER2");
        createEmployee("testUser", ownStore);
        GroupingOrder otherOrder = createGroupingOrder(otherStore, "GRP_OTHER_2", LocalDateTime.now());

        assertThrows(NoSuchElementException.class, () -> {
            groupingOrderResource.groupingOrder(otherOrder.id, mockContext());
        });
    }

    // --------------------------------------------------
    // Mutation Tests
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateGroupingOrder_Success() throws GraphQLException {
        Store store = createStore("GRP_S5");
        GroupingOrder order = createGroupingOrder(store, "GRP_05", LocalDateTime.now());
        Employee assignee = createEmployee("GRP_EMP", store);
        LocalDateTime newStart = LocalDateTime.of(2026, 10, 15, 8, 0);

        GroupingOrderResource.GroupingOrderRecord input = new GroupingOrderResource.GroupingOrderRecord();
        input.status = BaseHandlingOrder.HandlingOrderStatus.PROCESSING;
        input.requestedStart = newStart;
        input.assigneeId = assignee.id;

        GroupingOrder updated = groupingOrderResource.updateGroupingOrder(order.id, input);

        assertEquals(BaseHandlingOrder.HandlingOrderStatus.PROCESSING, updated.status);
        assertEquals(newStart, updated.requestedStart);
        assertEquals(assignee.id, updated.assignee.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateGroupingOrder_NotFound() {
        GroupingOrderResource.GroupingOrderRecord input = new GroupingOrderResource.GroupingOrderRecord();
        input.status = BaseHandlingOrder.HandlingOrderStatus.DONE;

        assertThrows(NoSuchElementException.class, () -> {
            groupingOrderResource.updateGroupingOrder(9999L, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateGroupingOrder_AssigneeNotFound() {
        Store store = createStore("GRP_S6");
        GroupingOrder order = createGroupingOrder(store, "GRP_06", LocalDateTime.now());

        GroupingOrderResource.GroupingOrderRecord input = new GroupingOrderResource.GroupingOrderRecord();
        input.assigneeId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            groupingOrderResource.updateGroupingOrder(order.id, input);
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testUpdateGroupingOrder_Scoped_OtherStoreOrder_Denied() {
        Store ownStore = createStore("GRP_OWN3");
        Store otherStore = createStore("GRP_OTHER3");
        createEmployee("testUser", ownStore);
        GroupingOrder otherOrder = createGroupingOrder(otherStore, "GRP_OTHER_3", LocalDateTime.now());

        GroupingOrderResource.GroupingOrderRecord input = new GroupingOrderResource.GroupingOrderRecord();
        input.status = BaseHandlingOrder.HandlingOrderStatus.DONE;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            groupingOrderResource.updateGroupingOrder(otherOrder.id, input);
        });
        assertInstanceOf(SecurityException.class, ex.getCause());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testUpdateGroupingOrder_Scoped_AssigneeFromOtherStore_Denied() {
        Store ownStore = createStore("GRP_OWN4");
        Store otherStore = createStore("GRP_OTHER4");
        createEmployee("testUser", ownStore);
        GroupingOrder order = createGroupingOrder(ownStore, "GRP_OWN_4", LocalDateTime.now());
        Employee foreignAssignee = createEmployee("GRP_EMP2", otherStore);

        GroupingOrderResource.GroupingOrderRecord input = new GroupingOrderResource.GroupingOrderRecord();
        input.assigneeId = foreignAssignee.id;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            groupingOrderResource.updateGroupingOrder(order.id, input);
        });
        assertInstanceOf(SecurityException.class, ex.getCause());
    }
}
