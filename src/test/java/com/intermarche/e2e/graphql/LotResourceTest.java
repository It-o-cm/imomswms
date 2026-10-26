package com.intermarche.e2e.graphql;

import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.graphql.LotResource;
import com.intermarche.e2e.domain.util.DomainUtils;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.smallrye.graphql.api.Context;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link LotResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 * <p>
 * The GraphQL {@link Context} is mocked: the selection set (including nested fields)
 * controls which fetch joins the resource appends to its JPQL queries.
 */
@QuarkusTest
@TestTransaction
public class LotResourceTest {

    @Inject
    LotResource lotResource;

    // --------------------------------------------------
    // Fixture Helpers
    // --------------------------------------------------

    /**
     * Builds a mocked GraphQL context.
     *
     * @param fields map of top-level field name to its nested selection set (empty set for a leaf field).
     */
    private Context mockContext(Map<String, Set<String>> fields) {
        DataFetchingFieldSelectionSet selectionSet = mock(DataFetchingFieldSelectionSet.class);
        when(selectionSet.contains(anyString())).thenAnswer(inv -> fields.containsKey(inv.getArgument(0)));
        List<SelectedField> fieldMocks = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : fields.entrySet()) {
            Set<String> subFields = entry.getValue();
            DataFetchingFieldSelectionSet subSelection = mock(DataFetchingFieldSelectionSet.class);
            when(subSelection.contains(anyString())).thenAnswer(inv -> subFields.contains(inv.getArgument(0)));
            SelectedField field = mock(SelectedField.class);
            when(field.getName()).thenReturn(entry.getKey());
            when(field.getSelectionSet()).thenReturn(subSelection);
            fieldMocks.add(field);
        }
        when(selectionSet.getFields()).thenReturn(fieldMocks);
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

    /**
     * Creates the full chain Store -> Order -> Fulfillment -> Chunk -> Lot and returns the Lot.
     */
    private Lot persistLot(Store store, String orderId) {
        Customer customer = new Customer();
        customer.customerId = "CUST_" + orderId;
        customer.firstName = "Jane";
        customer.lastName = "Smith";
        customer.email = customer.customerId.toLowerCase() + "@test.com";
        customer.persist();

        Order order = new Order();
        order.orderId = orderId;
        order.orderDate = LocalDateTime.now();
        order.channel = "ECOMMERCE";
        order.status = "VALIDATED";
        order.store = store;
        order.customer = customer;
        order.persist();

        Fulfillment fulfillment = new Fulfillment();
        fulfillment.order = order;

        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.READY;
        chunk.label = "Chunk " + orderId;
        chunk.deliveryDate = LocalDateTime.now().plusDays(1);
        // addChunk before the first persist: cascade-persist only fires at persist() time
        fulfillment.addChunk(chunk);
        fulfillment.persist();

        Lot lot = new Lot();
        lot.store = store;
        lot.customer = customer;
        lot.fulfillment = fulfillment;
        lot.chunk = chunk;
        lot.persist();
        return lot;
    }

    private LotLine addLineToLot(Lot lot, Product product, String quantity) {
        // LotLine.location is mandatory (nullable = false): create a valid shelf location
        StoreShelfLocation location = new StoreShelfLocation();
        location.product = product; // BaseLocation.product is @NotNull
        location.area = "A1";
        location.aisle = "B2";
        location.persist();

        LotLine line = new LotLine();
        line.product = product;
        line.quantity = new BigDecimal(quantity);
        line.location = location;
        lot.addLine(line);
        // lot is already persisted: persist() would be a no-op, persist the line explicitly
        line.persist();
        return line;
    }

    // --------------------------------------------------
    // Query Tests (Requires MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testAllLots_Empty() {
        List<Lot> lots = lotResource.allLots(mockContext(Map.of()));
        assertNotNull(lots);
        assertTrue(lots.isEmpty(), "No lot should exist in a fresh test database");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testAllLots_ReturnsAll() {
        Store store = createStore("STORE_L1");
        persistLot(store, "ORD_L1");
        persistLot(store, "ORD_L2");

        List<Lot> lots = lotResource.allLots(mockContext(Map.of()));
        assertEquals(2, lots.size());
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testAllLots_LinesSelection_FetchJoinsLines() {
        Store store = createStore("STORE_L2");
        Lot lot = persistLot(store, "ORD_L3");
        Product product = DomainUtils.createAndPersistProduct("1234567890150", "Juice", ProductType.UNIT);
        addLineToLot(lot, product, "2.000");
        Panache.getEntityManager().flush();
        Panache.getEntityManager().clear();

        List<Lot> lots = lotResource.allLots(mockContext(Map.of("lines", Set.of())));
        assertEquals(1, lots.size());
        Lot found = lots.get(0);
        assertTrue(Hibernate.isInitialized(found.lines), "lines must be fetch-joined when selected");
        assertEquals(1, found.lines.size());
        assertEquals("1234567890150", found.lines.iterator().next().product.ean);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testAllLots_LinesWithProductSelection_FetchJoinsProduct() {
        Store store = createStore("STORE_L3");
        Lot lot = persistLot(store, "ORD_L4");
        Product product = DomainUtils.createAndPersistProduct("1234567890151", "Water", ProductType.UNIT);
        addLineToLot(lot, product, "1.000");
        Panache.getEntityManager().flush();
        Panache.getEntityManager().clear();

        List<Lot> lots = lotResource.allLots(mockContext(Map.of("lines", Set.of("product"))));
        assertEquals(1, lots.size());
        LotLine line = lots.get(0).lines.iterator().next();
        assertTrue(Hibernate.isInitialized(line.product), "line product must be fetch-joined when selected");
        assertEquals(product.id, line.product.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testAllLots_NoSelection_LinesStayLazy() {
        Store store = createStore("STORE_L4");
        Lot lot = persistLot(store, "ORD_L5");
        Product product = DomainUtils.createAndPersistProduct("1234567890152", "Cola", ProductType.UNIT);
        addLineToLot(lot, product, "1.000");
        Panache.getEntityManager().flush();
        Panache.getEntityManager().clear();

        List<Lot> lots = lotResource.allLots(mockContext(Map.of()));
        assertEquals(1, lots.size());
        assertFalse(Hibernate.isInitialized(lots.get(0).lines), "lines must stay lazy when not selected");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testLot_Success() {
        Store store = createStore("STORE_L5");
        Lot existing = persistLot(store, "ORD_L6");

        Lot found = lotResource.lot(existing.id, mockContext(Map.of()));
        assertNotNull(found);
        assertEquals(existing.id, found.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testLot_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            lotResource.lot(9999L, mockContext(Map.of()));
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testLotsByChunkId() {
        Store store = createStore("STORE_L6");
        Lot lotA = persistLot(store, "ORD_L7");
        persistLot(store, "ORD_L8"); // different chunk
        Lot lotB = persistLot(store, "ORD_L9");
        // lotA and lotB share nothing; create a second lot on lotA's chunk
        Lot lotC = new Lot();
        lotC.store = store;
        lotC.customer = lotA.customer;
        lotC.fulfillment = lotA.fulfillment;
        lotC.chunk = lotA.chunk;
        lotC.persist();

        List<Lot> lots = lotResource.lotsByChunkId(lotA.chunk.id, mockContext(Map.of()));
        assertEquals(2, lots.size(), "Two lots are linked to the same chunk");
        assertTrue(lots.stream().allMatch(l -> l.chunk.id.equals(lotA.chunk.id)));
        assertFalse(lots.stream().anyMatch(l -> l.id.equals(lotB.id)));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testLotsByChunkId_UnknownChunk_ReturnsEmpty() {
        List<Lot> lots = lotResource.lotsByChunkId(9999L, mockContext(Map.of()));
        assertNotNull(lots);
        assertTrue(lots.isEmpty());
    }

    // --------------------------------------------------
    // Mutation Tests: updateLot (Requires ADMIN or MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateLot_Status_Success() throws GraphQLException {
        Store store = createStore("STORE_L7");
        Lot existing = persistLot(store, "ORD_L10");

        LotResource.LotRecord input = new LotResource.LotRecord();
        input.status = Lot.LotStatus.COLLECTED;

        Lot updated = lotResource.updateLot(existing.id, input);
        assertEquals(Lot.LotStatus.COLLECTED, updated.status);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateLot_NotFound() {
        LotResource.LotRecord input = new LotResource.LotRecord();
        input.status = Lot.LotStatus.COLLECTED;

        assertThrows(NoSuchElementException.class, () -> {
            lotResource.updateLot(9999L, input);
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testUpdateLot_ScopedManager_SameStore_Success() throws GraphQLException {
        Store store = createStore("STORE_L8");
        createEmployee("testUser", store);
        Lot existing = persistLot(store, "ORD_L11");

        LotResource.LotRecord input = new LotResource.LotRecord();
        input.status = Lot.LotStatus.COLLECTED;

        Lot updated = lotResource.updateLot(existing.id, input);
        assertEquals(Lot.LotStatus.COLLECTED, updated.status);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testUpdateLot_ScopedManager_OtherStore_SecurityException() {
        Store ownStore = createStore("STORE_L9");
        Store otherStore = createStore("STORE_L9_OTHER");
        createEmployee("testUser", ownStore);
        Lot lotOfOtherStore = persistLot(otherStore, "ORD_L12");

        LotResource.LotRecord input = new LotResource.LotRecord();
        input.status = Lot.LotStatus.COLLECTED;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            lotResource.updateLot(lotOfOtherStore.id, input);
        });
        assertInstanceOf(SecurityException.class, ex.getCause());
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateLot_NullLines_KeepsExistingLines() throws GraphQLException {
        Store store = createStore("STORE_L10");
        Lot existing = persistLot(store, "ORD_L13");
        Product product = DomainUtils.createAndPersistProduct("1234567890153", "Pasta", ProductType.UNIT);
        addLineToLot(existing, product, "1.000");

        LotResource.LotRecord input = new LotResource.LotRecord();
        input.status = Lot.LotStatus.COLLECTED;
        input.lines = null; // lines must be left untouched

        Lot updated = lotResource.updateLot(existing.id, input);
        assertEquals(1, updated.lines.size(), "Lines must be preserved when input.lines is null");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateLot_EmptyLines_RemovesExistingLines() throws GraphQLException {
        Store store = createStore("STORE_L11");
        Lot existing = persistLot(store, "ORD_L14");
        Product product = DomainUtils.createAndPersistProduct("1234567890154", "Rice", ProductType.UNIT);
        addLineToLot(existing, product, "1.000");

        LotResource.LotRecord input = new LotResource.LotRecord();
        input.lines = List.of(); // replacement strategy: all existing lines are removed

        Lot updated = lotResource.updateLot(existing.id, input);
        assertTrue(updated.lines.isEmpty(), "An empty input list must remove all existing lines");

        Lot reloaded = Lot.findById(existing.id);
        assertNotNull(reloaded);
        assertTrue(reloaded.lines.isEmpty(), "Line removal must be persisted");
    }

    // --------------------------------------------------
    // Store scope of a manager
    // --------------------------------------------------

    /**
     * A manager only reads the lots of their store, including among the lots of a chunk.
     */
    @Test
    @TestSecurity(user = "scopedManager", roles = {"MANAGER"})
    void managerOnlyReadsTheLotsOfTheirStore() {
        Store own = createStore("STORE_LS_OWN");
        Store other = createStore("STORE_LS_OTHER");
        createEmployee("scopedManager", own);
        Lot mine = persistLot(own, "ORD_LS_OWN");
        Lot foreign = persistLot(other, "ORD_LS_OTHER");
        // A lot of the other store attached to the chunk of the own store stays hidden.
        Lot intruder = new Lot();
        intruder.store = other;
        intruder.customer = mine.customer;
        intruder.fulfillment = mine.fulfillment;
        intruder.chunk = mine.chunk;
        intruder.persist();

        assertEquals(List.of(mine.id), lotResource.allLots(mockContext(Map.of())).stream().map(l -> l.id).toList());
        assertEquals(mine.id, lotResource.lot(mine.id, mockContext(Map.of())).id);
        assertThrows(NoSuchElementException.class, () -> lotResource.lot(foreign.id, mockContext(Map.of())));
        assertEquals(List.of(mine.id), lotResource.lotsByChunkId(mine.chunk.id, mockContext(Map.of())).stream().map(l -> l.id).toList());
        assertTrue(lotResource.lotsByChunkId(foreign.chunk.id, mockContext(Map.of())).isEmpty());
    }

    /**
     * A manager linked to no store reads no lot.
     */
    @Test
    @TestSecurity(user = "unlinkedManager", roles = {"MANAGER"})
    void managerWithoutStoreReadsNoLot() {
        assertThrows(SecurityException.class, () -> lotResource.allLots(mockContext(Map.of())));
        assertThrows(SecurityException.class, () -> lotResource.lot(1L, mockContext(Map.of())));
        assertThrows(SecurityException.class, () -> lotResource.lotsByChunkId(1L, mockContext(Map.of())));
    }
}
