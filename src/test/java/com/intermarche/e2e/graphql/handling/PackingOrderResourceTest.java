package com.intermarche.e2e.graphql.handling;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.handling.PackingOrder;
import com.intermarche.backoffice.graphql.LotResourceService;
import com.intermarche.backoffice.graphql.handling.PackingOrderResource;
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
 * Integration tests for {@link PackingOrderResource}.
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
public class PackingOrderResourceTest {

    @Inject
    PackingOrderResource packingOrderResource;

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
        employee.password = BCrypt.hashpw("secret", BCrypt.gensalt()); // BCrypt hashes are 60 chars (@Size(min=60))
        employee.role = Employee.EmployeeRole.PICKER;
        employee.store = store;
        employee.persist();
        return employee;
    }

    private PackingOrder createPackingOrder(Store store, String code, LocalDateTime requestedStart) {
        PackingOrder order = new PackingOrder();
        order.store = store;
        order.code = code;
        order.requestedStart = requestedStart;
        order.requestedEnd = requestedStart != null ? requestedStart.plusHours(1) : null;
        order.persist();
        return order;
    }

    private Lot createLotWithChunk(Store store, String prefix, PackingOrder order) {
        Customer customer = new Customer();
        customer.customerId = prefix + "-CUST";
        customer.firstName = "Jane";
        customer.lastName = "Smith";
        customer.email = prefix + "@test.com";
        customer.persist();

        Order omsOrder = new Order();
        omsOrder.orderId = prefix + "-ORDER";
        omsOrder.orderDate = LocalDateTime.now();
        omsOrder.channel = "ECOMMERCE";
        omsOrder.status = "VALIDATED";
        omsOrder.store = store;
        omsOrder.customer = customer;
        omsOrder.persist();

        Fulfillment fulfillment = new Fulfillment();
        fulfillment.order = omsOrder;
        fulfillment.persist();

        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.READY;
        chunk.label = "Chunk " + prefix;
        chunk.deliveryDate = LocalDateTime.now().plusDays(1);
        chunk.fulfillment = fulfillment;
        chunk.persist();

        Lot lot = new Lot();
        lot.store = store;
        lot.customer = customer;
        lot.fulfillment = fulfillment;
        lot.chunk = chunk;
        lot.persist();

        order.addLot(lot);
        return lot;
    }

    // --------------------------------------------------
    // Query Tests: global scope (ADMIN)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testAllPackingOrders_Empty() throws GraphQLException {
        List<PackingOrder> result = packingOrderResource.allPackingOrders(mockContext());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testAllPackingOrders_ReturnsAll() throws GraphQLException {
        Store store = createStore("PAK_S1");
        createPackingOrder(store, "PAK_01", LocalDateTime.now());
        createPackingOrder(store, "PAK_02", LocalDateTime.now());

        List<PackingOrder> result = packingOrderResource.allPackingOrders(mockContext());
        assertEquals(2, result.size());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testAllPackingOrders_WithCustomerSelection() throws GraphQLException {
        Store store = createStore("PAK_S2");
        PackingOrder order = createPackingOrder(store, "PAK_03", LocalDateTime.now());
        Customer customer = new Customer();
        customer.customerId = "PAK-CUST";
        customer.firstName = "Jane";
        customer.lastName = "Smith";
        customer.email = "pak@test.com";
        customer.persist();
        order.customer = customer;

        // Selection contains "customer": the resource must add the customer fetch join
        List<PackingOrder> result = packingOrderResource.allPackingOrders(mockContext("customer"));
        assertEquals(1, result.size());
        assertNotNull(result.get(0).customer);
        assertEquals("PAK-CUST", result.get(0).customer.customerId);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testAllPackingOrders_WithFullSelection() throws GraphQLException {
        Store store = createStore("PAK_S3");
        PackingOrder order = createPackingOrder(store, "PAK_04", LocalDateTime.now());
        order.assignee = createEmployee("PAK_EMP", store);
        createLotWithChunk(store, "PAK", order);

        // Selection contains everything: exercises the whole fetch-join chain
        List<PackingOrder> result = packingOrderResource.allPackingOrders(
                mockContext("store", "assignee", "lots", "chunk", "fulfillment", "order"));
        assertEquals(1, result.size());
        assertNotNull(result.get(0).store);
        assertNotNull(result.get(0).assignee);
        assertEquals(1, result.get(0).lots.size());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testPackingOrdersByDateRange_Filters() throws GraphQLException {
        Store store = createStore("PAK_S4");
        LocalDateTime base = LocalDateTime.of(2026, 10, 14, 12, 0);
        createPackingOrder(store, "PAK_BEFORE", base.minusDays(2));
        createPackingOrder(store, "PAK_IN", base);
        createPackingOrder(store, "PAK_AFTER", base.plusDays(2));

        List<PackingOrder> result = packingOrderResource.packingOrdersByDateRange(
                base.minusDays(1), base.plusDays(1), mockContext());
        assertEquals(1, result.size());
        assertEquals("PAK_IN", result.get(0).code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testPackingOrderById_Success() throws GraphQLException {
        Store store = createStore("PAK_S5");
        PackingOrder order = createPackingOrder(store, "PAK_05", LocalDateTime.now());

        PackingOrder found = packingOrderResource.packingOrder(order.id, mockContext());
        assertNotNull(found);
        assertEquals("PAK_05", found.code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testPackingOrderById_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            packingOrderResource.packingOrder(9999L, mockContext());
        });
    }

    // --------------------------------------------------
    // Query Tests: store scope (MANAGER linked to a store)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllPackingOrders_ScopedToStore() throws GraphQLException {
        Store ownStore = createStore("PAK_OWN");
        Store otherStore = createStore("PAK_OTHER");
        createEmployee("testUser", ownStore); // links the testUser principal to ownStore
        createPackingOrder(ownStore, "PAK_OWN_1", LocalDateTime.now());
        createPackingOrder(otherStore, "PAK_OTHER_1", LocalDateTime.now());

        List<PackingOrder> result = packingOrderResource.allPackingOrders(mockContext());
        assertEquals(1, result.size());
        assertEquals("PAK_OWN_1", result.get(0).code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPackingOrderById_Scoped_Success() throws GraphQLException {
        Store ownStore = createStore("PAK_OWN2");
        createEmployee("testUser", ownStore);
        PackingOrder order = createPackingOrder(ownStore, "PAK_OWN_2", LocalDateTime.now());

        PackingOrder found = packingOrderResource.packingOrder(order.id, mockContext());
        assertNotNull(found);
        assertEquals("PAK_OWN_2", found.code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPackingOrderById_Scoped_OtherStore_NotFound() {
        Store ownStore = createStore("PAK_OWN3");
        Store otherStore = createStore("PAK_OTHER3");
        createEmployee("testUser", ownStore);
        PackingOrder otherOrder = createPackingOrder(otherStore, "PAK_OTHER_3", LocalDateTime.now());

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () -> {
            packingOrderResource.packingOrder(otherOrder.id, mockContext());
        });
        assertTrue(ex.getMessage().contains("not found in your store"));
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPackingOrdersByDateRange_ScopedToStore() throws GraphQLException {
        Store ownStore = createStore("PAK_OWN4");
        Store otherStore = createStore("PAK_OTHER4");
        createEmployee("testUser", ownStore);
        LocalDateTime base = LocalDateTime.of(2026, 10, 14, 12, 0);
        createPackingOrder(ownStore, "PAK_OWN_4", base);
        createPackingOrder(otherStore, "PAK_OTHER_4", base);

        List<PackingOrder> result = packingOrderResource.packingOrdersByDateRange(
                base.minusDays(1), base.plusDays(1), mockContext());
        assertEquals(1, result.size());
        assertEquals("PAK_OWN_4", result.get(0).code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllPackingOrders_ManagerWithoutStore_SecurityException() {
        // MANAGER with no employee linked to a store: getStoreContext must reject
        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            packingOrderResource.allPackingOrders(mockContext());
        });
        assertInstanceOf(SecurityException.class, ex.getCause());
    }

    // --------------------------------------------------
    // Mutation Tests: global scope (ADMIN)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePackingOrder_Success() throws GraphQLException {
        Store store = createStore("PAK_S6");
        PackingOrder order = createPackingOrder(store, "PAK_06", LocalDateTime.now());
        Employee assignee = createEmployee("PAK_EMP2", store);
        LocalDateTime newStart = LocalDateTime.of(2026, 10, 15, 8, 0);
        LocalDateTime newEnd = LocalDateTime.of(2026, 10, 15, 10, 0);

        PackingOrderResource.PackingOrderRecord input = new PackingOrderResource.PackingOrderRecord();
        input.status = BaseHandlingOrder.HandlingOrderStatus.PROCESSING;
        input.requestedStart = newStart;
        input.requestedEnd = newEnd;
        input.assigneeId = assignee.id;

        PackingOrder updated = packingOrderResource.updatePackingOrder(order.id, input);

        assertEquals(BaseHandlingOrder.HandlingOrderStatus.PROCESSING, updated.status);
        assertEquals(newStart, updated.requestedStart);
        assertEquals(newEnd, updated.requestedEnd);
        assertNotNull(updated.assignee);
        assertEquals(assignee.id, updated.assignee.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePackingOrder_NotFound() {
        PackingOrderResource.PackingOrderRecord input = new PackingOrderResource.PackingOrderRecord();
        input.status = BaseHandlingOrder.HandlingOrderStatus.DONE;

        assertThrows(NoSuchElementException.class, () -> {
            packingOrderResource.updatePackingOrder(9999L, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePackingOrder_AssigneeNotFound() {
        Store store = createStore("PAK_S7");
        PackingOrder order = createPackingOrder(store, "PAK_07", LocalDateTime.now());

        PackingOrderResource.PackingOrderRecord input = new PackingOrderResource.PackingOrderRecord();
        input.assigneeId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            packingOrderResource.updatePackingOrder(order.id, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePackingOrder_Unassign() throws GraphQLException {
        Store store = createStore("PAK_S8");
        PackingOrder order = createPackingOrder(store, "PAK_08", LocalDateTime.now());
        order.assignee = createEmployee("PAK_EMP3", store);

        PackingOrderResource.PackingOrderRecord input = new PackingOrderResource.PackingOrderRecord();
        input.assigneeId = null; // explicit unassign

        PackingOrder updated = packingOrderResource.updatePackingOrder(order.id, input);
        assertNull(updated.assignee, "Assignee should be cleared when assigneeId is null");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePackingOrder_WithLotSync_Success() throws GraphQLException {
        Store store = createStore("PAK_S9");
        PackingOrder order = createPackingOrder(store, "PAK_09", LocalDateTime.now());
        Lot lot = createLotWithChunk(store, "PAK_LOT", order);

        LotResourceService.LotUpdateRecord lotInput = new LotResourceService.LotUpdateRecord();
        lotInput.id = lot.id;
        lotInput.status = Lot.LotStatus.COLLECTED;

        PackingOrderResource.PackingOrderRecord input = new PackingOrderResource.PackingOrderRecord();
        input.lots = List.of(lotInput);

        PackingOrder updated = packingOrderResource.updatePackingOrder(order.id, input);
        assertNotNull(updated);
        Lot updatedLot = Lot.findById(lot.id);
        assertEquals(Lot.LotStatus.COLLECTED, updatedLot.status);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePackingOrder_LotNotInOrder() {
        Store store = createStore("PAK_S10");
        PackingOrder order = createPackingOrder(store, "PAK_10", LocalDateTime.now());

        LotResourceService.LotUpdateRecord lotInput = new LotResourceService.LotUpdateRecord();
        lotInput.id = 9999L; // unknown lot

        PackingOrderResource.PackingOrderRecord input = new PackingOrderResource.PackingOrderRecord();
        input.lots = List.of(lotInput);

        assertThrows(NoSuchElementException.class, () -> {
            packingOrderResource.updatePackingOrder(order.id, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePackingOrder_LotIdNull() {
        Store store = createStore("PAK_S11");
        PackingOrder order = createPackingOrder(store, "PAK_11", LocalDateTime.now());

        LotResourceService.LotUpdateRecord lotInput = new LotResourceService.LotUpdateRecord();
        lotInput.id = null; // mandatory

        PackingOrderResource.PackingOrderRecord input = new PackingOrderResource.PackingOrderRecord();
        input.lots = List.of(lotInput);

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            packingOrderResource.updatePackingOrder(order.id, input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    // --------------------------------------------------
    // Mutation Tests: store scope (MANAGER linked to a store)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testUpdatePackingOrder_Scoped_Success() throws GraphQLException {
        Store ownStore = createStore("PAK_OWN5");
        createEmployee("testUser", ownStore);
        PackingOrder order = createPackingOrder(ownStore, "PAK_OWN_5", LocalDateTime.now());
        Employee assignee = createEmployee("PAK_EMP4", ownStore);

        PackingOrderResource.PackingOrderRecord input = new PackingOrderResource.PackingOrderRecord();
        input.status = BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
        input.assigneeId = assignee.id;

        PackingOrder updated = packingOrderResource.updatePackingOrder(order.id, input);
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, updated.status);
        assertEquals(assignee.id, updated.assignee.id);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testUpdatePackingOrder_Scoped_OtherStoreOrder_Denied() {
        Store ownStore = createStore("PAK_OWN6");
        Store otherStore = createStore("PAK_OTHER6");
        createEmployee("testUser", ownStore);
        PackingOrder otherOrder = createPackingOrder(otherStore, "PAK_OTHER_6", LocalDateTime.now());

        PackingOrderResource.PackingOrderRecord input = new PackingOrderResource.PackingOrderRecord();
        input.status = BaseHandlingOrder.HandlingOrderStatus.DONE;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            packingOrderResource.updatePackingOrder(otherOrder.id, input);
        });
        assertInstanceOf(SecurityException.class, ex.getCause());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testUpdatePackingOrder_Scoped_AssigneeFromOtherStore_Denied() {
        Store ownStore = createStore("PAK_OWN7");
        Store otherStore = createStore("PAK_OTHER7");
        createEmployee("testUser", ownStore);
        PackingOrder order = createPackingOrder(ownStore, "PAK_OWN_7", LocalDateTime.now());
        Employee foreignAssignee = createEmployee("PAK_EMP5", otherStore);

        PackingOrderResource.PackingOrderRecord input = new PackingOrderResource.PackingOrderRecord();
        input.assigneeId = foreignAssignee.id;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            packingOrderResource.updatePackingOrder(order.id, input);
        });
        assertInstanceOf(SecurityException.class, ex.getCause());
    }
}
