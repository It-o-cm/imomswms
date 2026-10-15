package com.intermarche.e2e.graphql.handling;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
import com.intermarche.backoffice.graphql.handling.ProductCollectingOrderResource;
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
 * Integration tests for {@link ProductCollectingOrderResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class ProductCollectingOrderResourceTest {

    @Inject
    ProductCollectingOrderResource productCollectingOrderResource;

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

    private ProductCollectingOrder createProductCollectingOrder(Store store, String code, LocalDateTime requestedStart) {
        ProductCollectingOrder order = new ProductCollectingOrder();
        order.store = store;
        order.code = code;
        order.addEan("1234567890123");
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
    void testAllProductCollectingOrders_Empty() throws GraphQLException {
        List<ProductCollectingOrder> result = productCollectingOrderResource.allProductCollectingOrders(mockContext());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testAllProductCollectingOrders_ReturnsAll() throws GraphQLException {
        Store store = createStore("PCO_S1");
        createProductCollectingOrder(store, "PCO_01", LocalDateTime.now());
        createProductCollectingOrder(store, "PCO_02", LocalDateTime.now());

        List<ProductCollectingOrder> result = productCollectingOrderResource.allProductCollectingOrders(mockContext());
        assertEquals(2, result.size());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testAllProductCollectingOrders_WithFullSelection() throws GraphQLException {
        Store store = createStore("PCO_S2");
        ProductCollectingOrder order = createProductCollectingOrder(store, "PCO_03", LocalDateTime.now());
        order.assignee = createEmployee("PCO_EMP", store);

        // Selection contains store/assignee: exercises the handling-order fetch joins
        List<ProductCollectingOrder> result = productCollectingOrderResource.allProductCollectingOrders(
                mockContext("store", "assignee"));
        assertEquals(1, result.size());
        assertNotNull(result.get(0).store);
        assertNotNull(result.get(0).assignee);
        assertEquals(List.of("1234567890123"), result.get(0).eans);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testProductCollectingOrdersByDateRange_Filters() throws GraphQLException {
        Store store = createStore("PCO_S3");
        LocalDateTime base = LocalDateTime.of(2026, 10, 14, 12, 0);
        createProductCollectingOrder(store, "PCO_BEFORE", base.minusDays(2));
        createProductCollectingOrder(store, "PCO_IN", base);
        createProductCollectingOrder(store, "PCO_AFTER", base.plusDays(2));

        List<ProductCollectingOrder> result = productCollectingOrderResource.productCollectingOrdersByDateRange(
                base.minusDays(1), base.plusDays(1), mockContext());
        assertEquals(1, result.size());
        assertEquals("PCO_IN", result.get(0).code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testProductCollectingOrderById_Success() throws GraphQLException {
        Store store = createStore("PCO_S4");
        ProductCollectingOrder order = createProductCollectingOrder(store, "PCO_04", LocalDateTime.now());

        ProductCollectingOrder found = productCollectingOrderResource.productCollectingOrder(order.id, mockContext());
        assertNotNull(found);
        assertEquals("PCO_04", found.code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"ADMIN"})
    void testProductCollectingOrderById_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            productCollectingOrderResource.productCollectingOrder(9999L, mockContext());
        });
    }

    // --------------------------------------------------
    // Query Tests: store scope (MANAGER linked to a store)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllProductCollectingOrders_ScopedToStore() throws GraphQLException {
        Store ownStore = createStore("PCO_OWN");
        Store otherStore = createStore("PCO_OTHER");
        createEmployee("testUser", ownStore);
        createProductCollectingOrder(ownStore, "PCO_OWN_1", LocalDateTime.now());
        createProductCollectingOrder(otherStore, "PCO_OTHER_1", LocalDateTime.now());

        List<ProductCollectingOrder> result = productCollectingOrderResource.allProductCollectingOrders(mockContext());
        assertEquals(1, result.size());
        assertEquals("PCO_OWN_1", result.get(0).code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testProductCollectingOrderById_Scoped_OtherStore_NotFound() {
        Store ownStore = createStore("PCO_OWN2");
        Store otherStore = createStore("PCO_OTHER2");
        createEmployee("testUser", ownStore);
        ProductCollectingOrder otherOrder = createProductCollectingOrder(otherStore, "PCO_OTHER_2", LocalDateTime.now());

        assertThrows(NoSuchElementException.class, () -> {
            productCollectingOrderResource.productCollectingOrder(otherOrder.id, mockContext());
        });
    }

    // --------------------------------------------------
    // Mutation Tests
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductCollectingOrder_Success() throws GraphQLException {
        Store store = createStore("PCO_S5");
        ProductCollectingOrder order = createProductCollectingOrder(store, "PCO_05", LocalDateTime.now());
        Employee assignee = createEmployee("PCO_EMP2", store);
        LocalDateTime newStart = LocalDateTime.of(2026, 10, 15, 8, 0);

        ProductCollectingOrderResource.ProductCollectingOrderRecord input = new ProductCollectingOrderResource.ProductCollectingOrderRecord();
        input.status = BaseHandlingOrder.HandlingOrderStatus.PROCESSING;
        input.requestedStart = newStart;
        input.assigneeId = assignee.id;

        ProductCollectingOrder updated = productCollectingOrderResource.updateProductCollectingOrder(order.id, input);

        assertEquals(BaseHandlingOrder.HandlingOrderStatus.PROCESSING, updated.status);
        assertEquals(newStart, updated.requestedStart);
        assertEquals(assignee.id, updated.assignee.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductCollectingOrder_NotFound() {
        ProductCollectingOrderResource.ProductCollectingOrderRecord input = new ProductCollectingOrderResource.ProductCollectingOrderRecord();
        input.status = BaseHandlingOrder.HandlingOrderStatus.DONE;

        assertThrows(NoSuchElementException.class, () -> {
            productCollectingOrderResource.updateProductCollectingOrder(9999L, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductCollectingOrder_AssigneeNotFound() {
        Store store = createStore("PCO_S6");
        ProductCollectingOrder order = createProductCollectingOrder(store, "PCO_06", LocalDateTime.now());

        ProductCollectingOrderResource.ProductCollectingOrderRecord input = new ProductCollectingOrderResource.ProductCollectingOrderRecord();
        input.assigneeId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            productCollectingOrderResource.updateProductCollectingOrder(order.id, input);
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testUpdateProductCollectingOrder_Scoped_OtherStoreOrder_Denied() {
        Store ownStore = createStore("PCO_OWN3");
        Store otherStore = createStore("PCO_OTHER3");
        createEmployee("testUser", ownStore);
        ProductCollectingOrder otherOrder = createProductCollectingOrder(otherStore, "PCO_OTHER_3", LocalDateTime.now());

        ProductCollectingOrderResource.ProductCollectingOrderRecord input = new ProductCollectingOrderResource.ProductCollectingOrderRecord();
        input.status = BaseHandlingOrder.HandlingOrderStatus.DONE;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            productCollectingOrderResource.updateProductCollectingOrder(otherOrder.id, input);
        });
        assertInstanceOf(SecurityException.class, ex.getCause());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testUpdateProductCollectingOrder_Scoped_AssigneeFromOtherStore_Denied() {
        Store ownStore = createStore("PCO_OWN4");
        Store otherStore = createStore("PCO_OTHER4");
        createEmployee("testUser", ownStore);
        ProductCollectingOrder order = createProductCollectingOrder(ownStore, "PCO_OWN_4", LocalDateTime.now());
        Employee foreignAssignee = createEmployee("PCO_EMP3", otherStore);

        ProductCollectingOrderResource.ProductCollectingOrderRecord input = new ProductCollectingOrderResource.ProductCollectingOrderRecord();
        input.assigneeId = foreignAssignee.id;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            productCollectingOrderResource.updateProductCollectingOrder(order.id, input);
        });
        assertInstanceOf(SecurityException.class, ex.getCause());
    }
}
