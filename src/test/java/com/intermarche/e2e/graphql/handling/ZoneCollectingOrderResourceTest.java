package com.intermarche.e2e.graphql.handling;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.ZoneCollectingOrder;
import com.intermarche.backoffice.graphql.handling.ZoneCollectingOrderResource;
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
 * Integration tests for {@link ZoneCollectingOrderResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class ZoneCollectingOrderResourceTest {

    @Inject
    ZoneCollectingOrderResource zoneCollectingOrderResource;

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

    private ZoneCollectingOrder createZoneCollectingOrder(Store store, String code, LocalDateTime requestedStart) {
        ZoneCollectingOrder order = new ZoneCollectingOrder();
        order.store = store;
        order.code = code;
        order.area = "AREA_" + code;
        order.aisle = "A1";
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
    void testAllZoneCollectingOrders_Empty() throws GraphQLException {
        List<ZoneCollectingOrder> result = zoneCollectingOrderResource.allZoneCollectingOrders(mockContext());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testAllZoneCollectingOrders_ReturnsAll() throws GraphQLException {
        Store store = createStore("ZCO_S1");
        createZoneCollectingOrder(store, "ZCO_01", LocalDateTime.now());
        createZoneCollectingOrder(store, "ZCO_02", LocalDateTime.now());

        List<ZoneCollectingOrder> result = zoneCollectingOrderResource.allZoneCollectingOrders(mockContext());
        assertEquals(2, result.size());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testAllZoneCollectingOrders_WithFullSelection() throws GraphQLException {
        Store store = createStore("ZCO_S2");
        ZoneCollectingOrder order = createZoneCollectingOrder(store, "ZCO_03", LocalDateTime.now());
        order.assignee = createEmployee("ZCO_EMP", store);

        // Selection contains store/assignee: exercises the handling-order fetch joins
        List<ZoneCollectingOrder> result = zoneCollectingOrderResource.allZoneCollectingOrders(
                mockContext("store", "assignee"));
        assertEquals(1, result.size());
        assertNotNull(result.get(0).store);
        assertNotNull(result.get(0).assignee);
        assertEquals("AREA_ZCO_03", result.get(0).area);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testZoneCollectingOrdersByDateRange_Filters() throws GraphQLException {
        Store store = createStore("ZCO_S3");
        LocalDateTime base = LocalDateTime.of(2026, 10, 14, 12, 0);
        createZoneCollectingOrder(store, "ZCO_BEFORE", base.minusDays(2));
        createZoneCollectingOrder(store, "ZCO_IN", base);
        createZoneCollectingOrder(store, "ZCO_AFTER", base.plusDays(2));

        List<ZoneCollectingOrder> result = zoneCollectingOrderResource.zoneCollectingOrdersByDateRange(
                base.minusDays(1), base.plusDays(1), mockContext());
        assertEquals(1, result.size());
        assertEquals("ZCO_IN", result.get(0).code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testZoneCollectingOrderById_Success() throws GraphQLException {
        Store store = createStore("ZCO_S4");
        ZoneCollectingOrder order = createZoneCollectingOrder(store, "ZCO_04", LocalDateTime.now());

        ZoneCollectingOrder found = zoneCollectingOrderResource.zoneCollectingOrder(order.id, mockContext());
        assertNotNull(found);
        assertEquals("ZCO_04", found.code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testZoneCollectingOrderById_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            zoneCollectingOrderResource.zoneCollectingOrder(9999L, mockContext());
        });
    }

    // --------------------------------------------------
    // Query Tests: store scope (MANAGER linked to a store)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllZoneCollectingOrders_ScopedToStore() throws GraphQLException {
        Store ownStore = createStore("ZCO_OWN");
        Store otherStore = createStore("ZCO_OTHER");
        createEmployee("testUser", ownStore);
        createZoneCollectingOrder(ownStore, "ZCO_OWN_1", LocalDateTime.now());
        createZoneCollectingOrder(otherStore, "ZCO_OTHER_1", LocalDateTime.now());

        List<ZoneCollectingOrder> result = zoneCollectingOrderResource.allZoneCollectingOrders(mockContext());
        assertEquals(1, result.size());
        assertEquals("ZCO_OWN_1", result.get(0).code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testZoneCollectingOrderById_Scoped_OtherStore_NotFound() {
        Store ownStore = createStore("ZCO_OWN2");
        Store otherStore = createStore("ZCO_OTHER2");
        createEmployee("testUser", ownStore);
        ZoneCollectingOrder otherOrder = createZoneCollectingOrder(otherStore, "ZCO_OTHER_2", LocalDateTime.now());

        assertThrows(NoSuchElementException.class, () -> {
            zoneCollectingOrderResource.zoneCollectingOrder(otherOrder.id, mockContext());
        });
    }

    // --------------------------------------------------
    // Mutation Tests
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateZoneCollectingOrder_Success() throws GraphQLException {
        Store store = createStore("ZCO_S5");
        ZoneCollectingOrder order = createZoneCollectingOrder(store, "ZCO_05", LocalDateTime.now());
        Employee assignee = createEmployee("ZCO_EMP2", store);
        LocalDateTime newEnd = LocalDateTime.of(2026, 10, 15, 10, 0);

        ZoneCollectingOrderResource.ZoneCollectingOrderRecord input = new ZoneCollectingOrderResource.ZoneCollectingOrderRecord();
        input.status = BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
        input.requestedEnd = newEnd;
        input.assigneeId = assignee.id;

        ZoneCollectingOrder updated = zoneCollectingOrderResource.updateZoneCollectingOrder(order.id, input);

        assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, updated.status);
        assertEquals(newEnd, updated.requestedEnd);
        assertEquals(assignee.id, updated.assignee.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateZoneCollectingOrder_NotFound() {
        ZoneCollectingOrderResource.ZoneCollectingOrderRecord input = new ZoneCollectingOrderResource.ZoneCollectingOrderRecord();
        input.status = BaseHandlingOrder.HandlingOrderStatus.DONE;

        assertThrows(NoSuchElementException.class, () -> {
            zoneCollectingOrderResource.updateZoneCollectingOrder(9999L, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateZoneCollectingOrder_AssigneeNotFound() {
        Store store = createStore("ZCO_S6");
        ZoneCollectingOrder order = createZoneCollectingOrder(store, "ZCO_06", LocalDateTime.now());

        ZoneCollectingOrderResource.ZoneCollectingOrderRecord input = new ZoneCollectingOrderResource.ZoneCollectingOrderRecord();
        input.assigneeId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            zoneCollectingOrderResource.updateZoneCollectingOrder(order.id, input);
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testUpdateZoneCollectingOrder_Scoped_OtherStoreOrder_Denied() {
        Store ownStore = createStore("ZCO_OWN3");
        Store otherStore = createStore("ZCO_OTHER3");
        createEmployee("testUser", ownStore);
        ZoneCollectingOrder otherOrder = createZoneCollectingOrder(otherStore, "ZCO_OTHER_3", LocalDateTime.now());

        ZoneCollectingOrderResource.ZoneCollectingOrderRecord input = new ZoneCollectingOrderResource.ZoneCollectingOrderRecord();
        input.status = BaseHandlingOrder.HandlingOrderStatus.DONE;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            zoneCollectingOrderResource.updateZoneCollectingOrder(otherOrder.id, input);
        });
        assertInstanceOf(SecurityException.class, ex.getCause());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testUpdateZoneCollectingOrder_Scoped_AssigneeFromOtherStore_Denied() {
        Store ownStore = createStore("ZCO_OWN4");
        Store otherStore = createStore("ZCO_OTHER4");
        createEmployee("testUser", ownStore);
        ZoneCollectingOrder order = createZoneCollectingOrder(ownStore, "ZCO_OWN_4", LocalDateTime.now());
        Employee foreignAssignee = createEmployee("ZCO_EMP3", otherStore);

        ZoneCollectingOrderResource.ZoneCollectingOrderRecord input = new ZoneCollectingOrderResource.ZoneCollectingOrderRecord();
        input.assigneeId = foreignAssignee.id;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            zoneCollectingOrderResource.updateZoneCollectingOrder(order.id, input);
        });
        assertInstanceOf(SecurityException.class, ex.getCause());
    }
}
