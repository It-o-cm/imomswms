package com.intermarche.e2e.graphql.order;

import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.order.OrderRequest;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.graphql.order.OrderRequestResource;
import com.intermarche.e2e.domain.util.DomainUtils;
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
 * Integration tests for {@link OrderRequestResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 * <p>
 * The GraphQL {@link Context} is mocked for the queries relying on the selection set.
 * Date range queries use ranges relative to now because createdAt/updatedAt are set
 * by JPA lifecycle callbacks at persist time.
 */
@QuarkusTest
@TestTransaction
public class OrderRequestResourceTest {

    @Inject
    OrderRequestResource orderRequestResource;

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

    private Employee createEmployee(String badgeId, Store store) {
        Employee employee = new Employee();
        employee.firstName = "Emp";
        employee.lastName = badgeId;
        employee.badgeId = badgeId;
        employee.email = badgeId + "@test.com";
        employee.password = BCrypt.hashpw("secret", BCrypt.gensalt()); // BCrypt hashes are 60 chars (@Size(min=60))
        employee.role = Employee.EmployeeRole.MANAGER;
        employee.store = store;
        employee.persist();
        return employee;
    }

    private OrderRequest persistOrderRequest(String orderNumber, String storeCode) {
        OrderRequest request = new OrderRequest(orderNumber, storeCode, "{\"payload\":{}}");
        request.persist();
        return request;
    }

    // --------------------------------------------------
    // Query Tests: allOrderRequests (Requires MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testAllOrderRequests_GlobalAccess_Admin() {
        persistOrderRequest("REQ_O1", "STORE_A");
        persistOrderRequest("REQ_O2", "STORE_B");

        List<OrderRequest> requests = orderRequestResource.allOrderRequests(mockContext());
        assertEquals(2, requests.size(), "ADMIN without store link must see all requests");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllOrderRequests_ScopedToStore_Manager() {
        Store store = createStore("STORE_ORQ1");
        createEmployee("testUser", store);
        persistOrderRequest("REQ_O3", "STORE_ORQ1");
        persistOrderRequest("REQ_O4", "STORE_OTHER");

        List<OrderRequest> requests = orderRequestResource.allOrderRequests(mockContext());
        assertEquals(1, requests.size(), "MANAGER must only see requests of his store");
        assertEquals("REQ_O3", requests.get(0).orderNumber);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllOrderRequests_ManagerWithoutStore_SecurityException() {
        // No employee with badgeId "testUser": MANAGER without store link must be rejected
        assertThrows(SecurityException.class, () -> {
            orderRequestResource.allOrderRequests(mockContext());
        });
    }

    // --------------------------------------------------
    // Query Tests: orderRequest / ordersByNumber (MANAGER)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOrderRequest_Success() {
        OrderRequest existing = persistOrderRequest("REQ_O5", "STORE_A");

        OrderRequest found = orderRequestResource.orderRequest(existing.id);
        assertNotNull(found);
        assertEquals("REQ_O5", found.orderNumber);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOrderRequest_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            orderRequestResource.orderRequest(9999L);
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOrdersByNumber_MultipleHistory() {
        persistOrderRequest("REQ_O6", "STORE_A");
        persistOrderRequest("REQ_O6", "STORE_A"); // history: same number twice

        List<OrderRequest> requests = orderRequestResource.ordersByNumber("REQ_O6");
        assertEquals(2, requests.size(), "Order numbers are not unique: history must be returned");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOrdersByNumber_Empty() {
        List<OrderRequest> requests = orderRequestResource.ordersByNumber("REQ_UNKNOWN");
        assertNotNull(requests);
        assertTrue(requests.isEmpty());
    }

    // --------------------------------------------------
    // Query Tests: ordersByCreatedDateRange (MANAGER)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testOrdersByCreatedDateRange_Global_NoEnd() {
        persistOrderRequest("REQ_O7", "STORE_A");

        List<OrderRequest> inRange = orderRequestResource.ordersByCreatedDateRange(
                mockContext(), LocalDateTime.now().minusHours(1), null);
        assertEquals(1, inRange.size(), "Request created now must match start=now-1h, end=null");

        List<OrderRequest> outOfRange = orderRequestResource.ordersByCreatedDateRange(
                mockContext(), LocalDateTime.now().plusHours(1), null);
        assertTrue(outOfRange.isEmpty(), "Request created now must not match start=now+1h, end=null");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testOrdersByCreatedDateRange_Global_WithEnd() {
        persistOrderRequest("REQ_O8", "STORE_A");

        List<OrderRequest> inRange = orderRequestResource.ordersByCreatedDateRange(
                mockContext(), LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        assertEquals(1, inRange.size(), "Request created now must match the [now-1h, now+1h] range");

        List<OrderRequest> outOfRange = orderRequestResource.ordersByCreatedDateRange(
                mockContext(), LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1));
        assertTrue(outOfRange.isEmpty(), "Request created now must not match a past range");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOrdersByCreatedDateRange_Scoped_NoEnd() {
        Store store = createStore("STORE_ORQ2");
        createEmployee("testUser", store);
        persistOrderRequest("REQ_O9", "STORE_ORQ2");
        persistOrderRequest("REQ_O10", "STORE_OTHER");

        List<OrderRequest> requests = orderRequestResource.ordersByCreatedDateRange(
                mockContext(), LocalDateTime.now().minusHours(1), null);
        assertEquals(1, requests.size(), "Scoped query must filter by store code");
        assertEquals("REQ_O9", requests.get(0).orderNumber);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOrdersByCreatedDateRange_Scoped_WithEnd() {
        Store store = createStore("STORE_ORQ3");
        createEmployee("testUser", store);
        persistOrderRequest("REQ_O11", "STORE_ORQ3");
        persistOrderRequest("REQ_O12", "STORE_OTHER");

        List<OrderRequest> requests = orderRequestResource.ordersByCreatedDateRange(
                mockContext(), LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        assertEquals(1, requests.size(), "Scoped query must filter by store code");
        assertEquals("REQ_O11", requests.get(0).orderNumber);
    }

    // --------------------------------------------------
    // Query Tests: ordersByUpdatedDateRange (MANAGER)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testOrdersByUpdatedDateRange_Global_NoEnd() {
        persistOrderRequest("REQ_O13", "STORE_A");

        List<OrderRequest> inRange = orderRequestResource.ordersByUpdatedDateRange(
                mockContext(), LocalDateTime.now().minusHours(1), null);
        assertEquals(1, inRange.size(), "Request updated now must match start=now-1h, end=null");

        List<OrderRequest> outOfRange = orderRequestResource.ordersByUpdatedDateRange(
                mockContext(), LocalDateTime.now().plusHours(1), null);
        assertTrue(outOfRange.isEmpty(), "Request updated now must not match start=now+1h, end=null");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testOrdersByUpdatedDateRange_Global_WithEnd() {
        persistOrderRequest("REQ_O14", "STORE_A");

        List<OrderRequest> inRange = orderRequestResource.ordersByUpdatedDateRange(
                mockContext(), LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        assertEquals(1, inRange.size(), "Request updated now must match the [now-1h, now+1h] range");

        List<OrderRequest> outOfRange = orderRequestResource.ordersByUpdatedDateRange(
                mockContext(), LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1));
        assertTrue(outOfRange.isEmpty(), "Request updated now must not match a past range");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOrdersByUpdatedDateRange_Scoped_NoEnd() {
        Store store = createStore("STORE_ORQ4");
        createEmployee("testUser", store);
        persistOrderRequest("REQ_O15", "STORE_ORQ4");
        persistOrderRequest("REQ_O16", "STORE_OTHER");

        List<OrderRequest> requests = orderRequestResource.ordersByUpdatedDateRange(
                mockContext(), LocalDateTime.now().minusHours(1), null);
        assertEquals(1, requests.size(), "Scoped query must filter by store code");
        assertEquals("REQ_O15", requests.get(0).orderNumber);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOrdersByUpdatedDateRange_Scoped_WithEnd() {
        Store store = createStore("STORE_ORQ5");
        createEmployee("testUser", store);
        persistOrderRequest("REQ_O17", "STORE_ORQ5");
        persistOrderRequest("REQ_O18", "STORE_OTHER");

        List<OrderRequest> requests = orderRequestResource.ordersByUpdatedDateRange(
                mockContext(), LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        assertEquals(1, requests.size(), "Scoped query must filter by store code");
        assertEquals("REQ_O17", requests.get(0).orderNumber);
    }

    // --------------------------------------------------
    // Mutation Tests: Create / Update (Requires MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testCreateOrderRequest_Success() throws GraphQLException {
        OrderRequestResource.OrderRequestRecord input = new OrderRequestResource.OrderRequestRecord();
        input.orderNumber = "REQ_O19";
        input.storeCode = "STORE_A";
        input.specification = "{\"order\":{}}";

        OrderRequest created = orderRequestResource.createOrderRequest(input);

        assertNotNull(created);
        assertNotNull(created.id);
        assertEquals("REQ_O19", created.orderNumber);
        assertEquals("STORE_A", created.storeCode);
        assertEquals("{\"order\":{}}", created.specification);
        assertNotNull(created.createdAt, "createdAt must be set");
        assertNotNull(created.reservationRecord, "Reservation record must be initialized");
        assertEquals(ReservationRecord.ReservationStatus.PENDING, created.reservationRecord.status,
                "A new request must be PENDING");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testUpdateOrderRequest_Success() throws GraphQLException {
        OrderRequest existing = persistOrderRequest("REQ_O20", "STORE_A");

        OrderRequestResource.OrderRequestRecord input = new OrderRequestResource.OrderRequestRecord();
        input.orderNumber = "REQ_O20B";
        input.storeCode = "STORE_B";
        input.specification = "{\"updated\":true}";

        OrderRequest updated = orderRequestResource.updateOrderRequest(existing.id, input);

        assertEquals("REQ_O20B", updated.orderNumber);
        assertEquals("STORE_B", updated.storeCode);
        assertEquals("{\"updated\":true}", updated.specification);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testUpdateOrderRequest_PartialUpdate_KeepsOtherFields() throws GraphQLException {
        OrderRequest existing = persistOrderRequest("REQ_O21", "STORE_A");

        OrderRequestResource.OrderRequestRecord input = new OrderRequestResource.OrderRequestRecord();
        input.specification = "{\"only\":\"spec\"}";

        OrderRequest updated = orderRequestResource.updateOrderRequest(existing.id, input);

        assertEquals("REQ_O21", updated.orderNumber, "orderNumber must remain unchanged");
        assertEquals("STORE_A", updated.storeCode, "storeCode must remain unchanged");
        assertEquals("{\"only\":\"spec\"}", updated.specification);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testUpdateOrderRequest_NotFound() {
        OrderRequestResource.OrderRequestRecord input = new OrderRequestResource.OrderRequestRecord();
        input.orderNumber = "GHOST";

        assertThrows(NoSuchElementException.class, () -> {
            orderRequestResource.updateOrderRequest(9999L, input);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testDeleteOrderRequest_Success() throws GraphQLException {
        OrderRequest existing = persistOrderRequest("REQ_O22", "STORE_A");

        boolean result = orderRequestResource.deleteOrderRequest(existing.id);
        assertTrue(result, "Delete should return true");
        assertNull(OrderRequest.findById(existing.id));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testDeleteOrderRequest_NotFound() throws GraphQLException {
        boolean result = orderRequestResource.deleteOrderRequest(9999L);
        assertFalse(result, "Delete should return false for non-existent ID");
    }

    // --------------------------------------------------
    // Record Tests
    // --------------------------------------------------

    @Test
    void testOrderRequestRecord_ToString() {
        OrderRequestResource.OrderRequestRecord record = new OrderRequestResource.OrderRequestRecord();
        record.orderNumber = "REQ_REC";
        record.storeCode = "STORE_REC";
        record.specification = "{}";

        assertEquals("OrderRequestRecord [orderNumber=REQ_REC, storeCode=STORE_REC, specification={}]",
                record.toString());
    }
}
