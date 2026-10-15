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
import com.intermarche.backoffice.graphql.handling.PreparingOrderResource;
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
 * Integration tests for {@link PreparingOrderResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class PreparingOrderResourceTest {

    @Inject
    PreparingOrderResource preparingOrderResource;

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

    private PackingOrder createPackingOrder(Store store, String code) {
        PackingOrder order = new PackingOrder();
        order.store = store;
        order.code = code;
        order.persist();
        return order;
    }

    /**
     * Creates a full Lot fixture (customer, order, fulfillment, chunk) and attaches it
     * to the given order.
     *
     * @return the created chunk.
     */
    private FulfillmentChunk createLotWithChunk(Store store, String prefix, PackingOrder order) {
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
        return chunk;
    }

    // --------------------------------------------------
    // Query Tests: handlingOrder
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testHandlingOrder_Success() throws GraphQLException {
        Store store = createStore("PRE_S1");
        PackingOrder order = createPackingOrder(store, "PRE_01");

        BaseHandlingOrder found = preparingOrderResource.handlingOrder(order.id);
        assertNotNull(found);
        assertEquals("PRE_01", found.code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testHandlingOrder_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            preparingOrderResource.handlingOrder(9999L);
        });
    }

    // --------------------------------------------------
    // Query Tests: preparingOrdersByChunkId (global scope)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testPreparingOrdersByChunkId_NoMatch_ReturnsEmpty() throws GraphQLException {
        List<BaseHandlingOrder> result = preparingOrderResource.preparingOrdersByChunkId(9999L, mockContext());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testPreparingOrdersByChunkId_Found() throws GraphQLException {
        Store store = createStore("PRE_S2");
        PackingOrder order = createPackingOrder(store, "PRE_02");
        FulfillmentChunk chunk = createLotWithChunk(store, "PRE", order);

        List<BaseHandlingOrder> result = preparingOrderResource.preparingOrdersByChunkId(chunk.id, mockContext());
        assertEquals(1, result.size());
        assertEquals("PRE_02", result.get(0).code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testPreparingOrdersByChunkId_WithStoreAndAssigneeSelection() throws GraphQLException {
        Store store = createStore("PRE_S3");
        PackingOrder order = createPackingOrder(store, "PRE_03");
        order.assignee = createEmployee("PRE_EMP", store);
        FulfillmentChunk chunk = createLotWithChunk(store, "PRE2", order);

        // Selection contains store/assignee: exercises the fetch joins
        List<BaseHandlingOrder> result = preparingOrderResource.preparingOrdersByChunkId(
                chunk.id, mockContext("store", "assignee"));
        assertEquals(1, result.size());
        assertNotNull(result.get(0).store);
        assertNotNull(result.get(0).assignee);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testPreparingOrdersByChunkId_OtherChunk_ReturnsEmpty() throws GraphQLException {
        Store store = createStore("PRE_S4");
        PackingOrder order = createPackingOrder(store, "PRE_04");
        FulfillmentChunk chunk = createLotWithChunk(store, "PRE3", order);

        // A different chunk id must not match
        List<BaseHandlingOrder> result = preparingOrderResource.preparingOrdersByChunkId(chunk.id + 1000, mockContext());
        assertTrue(result.isEmpty());
    }

    // --------------------------------------------------
    // Query Tests: preparingOrdersByChunkId (store scope)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPreparingOrdersByChunkId_ScopedToStore() throws GraphQLException {
        Store ownStore = createStore("PRE_OWN");
        Store otherStore = createStore("PRE_OTHER");
        createEmployee("testUser", ownStore);

        PackingOrder ownOrder = createPackingOrder(ownStore, "PRE_OWN_1");
        FulfillmentChunk ownChunk = createLotWithChunk(ownStore, "PREOWN", ownOrder);

        PackingOrder otherOrder = createPackingOrder(otherStore, "PRE_OTHER_1");
        FulfillmentChunk otherChunk = createLotWithChunk(otherStore, "PREOTHER", otherOrder);

        // Own chunk: visible
        List<BaseHandlingOrder> ownResult = preparingOrderResource.preparingOrdersByChunkId(ownChunk.id, mockContext());
        assertEquals(1, ownResult.size());
        assertEquals("PRE_OWN_1", ownResult.get(0).code);

        // Other store's chunk: filtered out by the store scope
        List<BaseHandlingOrder> otherResult = preparingOrderResource.preparingOrdersByChunkId(otherChunk.id, mockContext());
        assertTrue(otherResult.isEmpty());
    }
}
