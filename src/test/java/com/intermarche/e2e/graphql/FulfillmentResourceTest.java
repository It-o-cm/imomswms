package com.intermarche.e2e.graphql;

import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.graphql.FulfillmentResource;
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
import java.time.Instant;
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
 * Integration tests for {@link FulfillmentResource}.
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
public class FulfillmentResourceTest {

    @Inject
    FulfillmentResource fulfillmentResource;

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

    private Order persistOrder(String orderId, Store store) {
        Order order = new Order();
        order.orderId = orderId;
        order.orderDate = LocalDateTime.now();
        order.channel = "ECOMMERCE";
        order.status = "VALIDATED";
        order.store = store;
        order.persist();
        return order;
    }

    private Fulfillment persistFulfillment(Order order, LocalDateTime deliveryDate) {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.order = order;

        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.READY;
        chunk.label = "Chunk " + order.orderId;
        chunk.deliveryDate = deliveryDate;
        fulfillment.addChunk(chunk);
        fulfillment.persist(); // cascade persists the chunk
        return fulfillment;
    }

    private FulfillmentChunk singleChunk(Fulfillment fulfillment) {
        return fulfillment.chunks.iterator().next();
    }

    private FulfillmentLine addLineToChunk(FulfillmentChunk chunk, Product product, int lineNumber,
                                           String quantity, BaseLocation location) {
        FulfillmentLine line = new FulfillmentLine();
        line.lineNumber = lineNumber;
        line.product = product;
        line.requestedQuantity = new BigDecimal(quantity);
        line.location = location;
        chunk.addLine(line);
        line.persist(); // the parent collection only maintains the FK at flush time
        return line;
    }

    private StoreShelfLocation persistShelfLocation(Product product, String area, String aisle) {
        StoreShelfLocation location = new StoreShelfLocation();
        location.product = product; // BaseLocation.product is @NotNull
        location.area = area;
        location.aisle = aisle;
        location.persist();
        return location;
    }

    // --------------------------------------------------
    // Query Tests: allFulfillments (Requires MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllFulfillments_Empty() throws GraphQLException {
        List<Fulfillment> fulfillments = fulfillmentResource.allFulfillments(mockContext(Map.of()));
        assertNotNull(fulfillments);
        assertTrue(fulfillments.isEmpty(), "No fulfillment should exist in a fresh test database");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllFulfillments_ReturnsAll() throws GraphQLException {
        Store store = createStore("STORE_F1");
        persistFulfillment(persistOrder("ORD_F1", store), LocalDateTime.now().plusDays(1));
        persistFulfillment(persistOrder("ORD_F2", store), LocalDateTime.now().plusDays(1));

        List<Fulfillment> fulfillments = fulfillmentResource.allFulfillments(mockContext(Map.of()));
        assertEquals(2, fulfillments.size());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllFulfillments_ChunksSelection_FetchJoinsChunks() throws GraphQLException {
        Store store = createStore("STORE_F2");
        Fulfillment fulfillment = persistFulfillment(persistOrder("ORD_F3", store), LocalDateTime.now().plusDays(1));
        Panache.getEntityManager().flush();
        Panache.getEntityManager().clear();

        List<Fulfillment> fulfillments = fulfillmentResource.allFulfillments(mockContext(Map.of("chunks", Set.of())));
        assertEquals(1, fulfillments.size());
        Fulfillment found = fulfillments.get(0);
        assertTrue(Hibernate.isInitialized(found.chunks), "chunks must be fetch-joined when selected");
        assertEquals(1, found.chunks.size());
        assertEquals("Chunk ORD_F3", singleChunk(found).label);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllFulfillments_ChunksWithLinesSelection_FetchJoinsLines() throws GraphQLException {
        Store store = createStore("STORE_F3");
        Fulfillment fulfillment = persistFulfillment(persistOrder("ORD_F4", store), LocalDateTime.now().plusDays(1));
        Product product = DomainUtils.createAndPersistProduct("1234567890140", "Cheese", ProductType.UNIT);
        addLineToChunk(singleChunk(fulfillment), product, 1, "1.000", null);
        Panache.getEntityManager().flush();
        Panache.getEntityManager().clear();

        List<Fulfillment> fulfillments = fulfillmentResource.allFulfillments(
                mockContext(Map.of("chunks", Set.of("lines"))));
        assertEquals(1, fulfillments.size());
        FulfillmentChunk chunk = singleChunk(fulfillments.get(0));
        assertTrue(Hibernate.isInitialized(chunk.lines), "chunk lines must be fetch-joined when selected");
        assertEquals(1, chunk.lines.size());
        FulfillmentLine line = chunk.lines.iterator().next();
        assertTrue(Hibernate.isInitialized(line.product), "line product must be fetch-joined when lines are selected");
        assertEquals("1234567890140", line.product.ean);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllFulfillments_NoSelection_ChunksStayLazy() throws GraphQLException {
        Store store = createStore("STORE_F4");
        persistFulfillment(persistOrder("ORD_F5", store), LocalDateTime.now().plusDays(1));
        Panache.getEntityManager().flush();
        Panache.getEntityManager().clear();

        List<Fulfillment> fulfillments = fulfillmentResource.allFulfillments(mockContext(Map.of()));
        assertEquals(1, fulfillments.size());
        assertFalse(Hibernate.isInitialized(fulfillments.get(0).chunks), "chunks must stay lazy when not selected");
    }

    // --------------------------------------------------
    // Query Tests: fulfillment / fulfillmentByOrderId (MANAGER)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testFulfillment_Success() throws GraphQLException {
        Store store = createStore("STORE_F5");
        Fulfillment existing = persistFulfillment(persistOrder("ORD_F6", store), LocalDateTime.now().plusDays(1));

        Fulfillment found = fulfillmentResource.fulfillment(existing.id, mockContext(Map.of()));
        assertNotNull(found);
        assertEquals("ORD_F6", found.order.orderId);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testFulfillment_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            fulfillmentResource.fulfillment(9999L, mockContext(Map.of()));
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testFulfillmentByOrderId_Success() throws GraphQLException {
        Store store = createStore("STORE_F6");
        persistFulfillment(persistOrder("ORD_F7", store), LocalDateTime.now().plusDays(1));

        Fulfillment found = fulfillmentResource.fulfillmentByOrderId("ORD_F7", mockContext(Map.of()));
        assertNotNull(found);
        assertEquals("ORD_F7", found.order.orderId);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testFulfillmentByOrderId_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            fulfillmentResource.fulfillmentByOrderId("ORD_UNKNOWN", mockContext(Map.of()));
        });
    }

    // --------------------------------------------------
    // Query Tests: fulfillmentsByDeliveryDate (MANAGER/ADMIN/SERVICE_CALLER)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testFulfillmentsByDeliveryDate_Global() throws GraphQLException {
        Store store = createStore("STORE_F7");
        persistFulfillment(persistOrder("ORD_F8", store), LocalDateTime.now().plusDays(1));

        List<Fulfillment> inRange = fulfillmentResource.fulfillmentsByDeliveryDate(
                LocalDateTime.now(), LocalDateTime.now().plusDays(2), mockContext(Map.of()));
        assertEquals(1, inRange.size(), "Chunk delivering in 1 day must match the [now, now+2d] range");

        List<Fulfillment> outOfRange = fulfillmentResource.fulfillmentsByDeliveryDate(
                LocalDateTime.now().plusDays(3), LocalDateTime.now().plusDays(4), mockContext(Map.of()));
        assertTrue(outOfRange.isEmpty(), "Chunk delivering in 1 day must not match the [now+3d, now+4d] range");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testFulfillmentsByDeliveryDate_ScopedToStore() throws GraphQLException {
        Store store = createStore("STORE_F8");
        Store otherStore = createStore("STORE_F8_OTHER");
        createEmployee("testUser", store);
        persistFulfillment(persistOrder("ORD_F9", store), LocalDateTime.now().plusDays(1));
        persistFulfillment(persistOrder("ORD_F10", otherStore), LocalDateTime.now().plusDays(1));

        List<Fulfillment> fulfillments = fulfillmentResource.fulfillmentsByDeliveryDate(
                LocalDateTime.now(), LocalDateTime.now().plusDays(2), mockContext(Map.of()));
        assertEquals(1, fulfillments.size(), "MANAGER must only see fulfillments of his store");
        assertEquals("ORD_F9", fulfillments.get(0).order.orderId);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testFulfillmentsByDeliveryDate_ChunksWithLinesSelection() throws GraphQLException {
        Store store = createStore("STORE_F9");
        Fulfillment fulfillment = persistFulfillment(persistOrder("ORD_F11", store), LocalDateTime.now().plusDays(1));
        Product product = DomainUtils.createAndPersistProduct("1234567890141", "Ham", ProductType.UNIT);
        addLineToChunk(singleChunk(fulfillment), product, 1, "2.000", null);
        Panache.getEntityManager().flush();
        Panache.getEntityManager().clear();

        List<Fulfillment> fulfillments = fulfillmentResource.fulfillmentsByDeliveryDate(
                LocalDateTime.now(), LocalDateTime.now().plusDays(2), mockContext(Map.of("chunks", Set.of("lines"))));
        assertEquals(1, fulfillments.size());
        FulfillmentChunk chunk = singleChunk(fulfillments.get(0));
        assertTrue(Hibernate.isInitialized(chunk.lines), "chunk lines must be fetch-joined when selected");
        assertEquals(1, chunk.lines.size());
    }

    // --------------------------------------------------
    // Mutation Tests: Create (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateFulfillment_Success() throws GraphQLException {
        Store store = createStore("STORE_F10");
        Order order = persistOrder("ORD_F12", store);
        Product product = DomainUtils.createAndPersistProduct("1234567890142", "Butter", ProductType.UNIT);

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        input.orderId = "ORD_F12";
        FulfillmentResource.ReservationRecord reservation = new FulfillmentResource.ReservationRecord();
        reservation.status = ReservationRecord.ReservationStatus.PROCESSING;
        reservation.leasing = Instant.now();
        input.reservationRecord = reservation;
        FulfillmentResource.FulfillmentChunkRecord chunkInput = new FulfillmentResource.FulfillmentChunkRecord();
        chunkInput.type = FulfillmentChunk.READY;
        chunkInput.deliveryDate = LocalDateTime.now().plusDays(1);
        FulfillmentResource.FulfillmentLineRecord lineInput = new FulfillmentResource.FulfillmentLineRecord();
        lineInput.lineNumber = 1;
        lineInput.productId = product.id;
        lineInput.requestedQuantity = new BigDecimal("1.500");
        chunkInput.lines = List.of(lineInput);
        input.chunks = List.of(chunkInput);

        Fulfillment created = fulfillmentResource.createFulfillment(input);

        assertNotNull(created);
        assertNotNull(created.id);
        assertEquals(order.id, created.order.id);
        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, created.reservationRecord.status);
        assertNotNull(created.reservationRecord.leasing);
        assertEquals(1, created.chunks.size());
        FulfillmentChunk chunk = singleChunk(created);
        assertEquals(FulfillmentChunk.READY, chunk.type);
        assertEquals(1, chunk.lines.size());
        FulfillmentLine line = chunk.lines.iterator().next();
        assertEquals(1, line.lineNumber);
        assertEquals(product.id, line.product.id);
        assertEquals(new BigDecimal("1.500"), line.requestedQuantity);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateFulfillment_OrderNotFound() {
        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        input.orderId = "ORD_UNKNOWN";

        assertThrows(NoSuchElementException.class, () -> {
            fulfillmentResource.createFulfillment(input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateFulfillment_AlreadyExists() {
        Store store = createStore("STORE_F11");
        Order order = persistOrder("ORD_F13", store);
        persistFulfillment(order, LocalDateTime.now().plusDays(1));

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        input.orderId = "ORD_F13"; // a fulfillment already exists for this order

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            fulfillmentResource.createFulfillment(input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateFulfillment_ProductNotFoundInLine() {
        Store store = createStore("STORE_F12");
        persistOrder("ORD_F14", store);

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        input.orderId = "ORD_F14";
        FulfillmentResource.FulfillmentChunkRecord chunkInput = new FulfillmentResource.FulfillmentChunkRecord();
        chunkInput.type = FulfillmentChunk.READY;
        FulfillmentResource.FulfillmentLineRecord lineInput = new FulfillmentResource.FulfillmentLineRecord();
        lineInput.lineNumber = 1;
        lineInput.productId = 9999L;
        lineInput.requestedQuantity = BigDecimal.ONE;
        chunkInput.lines = List.of(lineInput);
        input.chunks = List.of(chunkInput);

        assertThrows(NoSuchElementException.class, () -> {
            fulfillmentResource.createFulfillment(input);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Update (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateFulfillment_Success() throws GraphQLException {
        Store store = createStore("STORE_F13");
        Fulfillment existing = persistFulfillment(persistOrder("ORD_F15", store), LocalDateTime.now().plusDays(1));

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        FulfillmentResource.ReservationRecord reservation = new FulfillmentResource.ReservationRecord();
        reservation.status = ReservationRecord.ReservationStatus.DONE;
        reservation.leasing = Instant.now().plusSeconds(3600);
        input.reservationRecord = reservation;

        Fulfillment updated = fulfillmentResource.updateFulfillment(existing.id, input);

        assertEquals(ReservationRecord.ReservationStatus.DONE, updated.reservationRecord.status);
        assertNotNull(updated.reservationRecord.leasing);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateFulfillment_NotFound() {
        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();

        assertThrows(NoSuchElementException.class, () -> {
            fulfillmentResource.updateFulfillment(9999L, input);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: updateFulfillmentChunk (ADMIN or MANAGER)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateFulfillmentChunk_Success() throws GraphQLException {
        Store store = createStore("STORE_F14");
        Fulfillment fulfillment = persistFulfillment(persistOrder("ORD_F16", store), LocalDateTime.now().plusDays(1));
        FulfillmentChunk chunk = singleChunk(fulfillment);
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = "BLD_F1";
        building.persist();

        FulfillmentResource.FulfillmentChunkRecord input = new FulfillmentResource.FulfillmentChunkRecord();
        input.type = FulfillmentChunk.PENDING;
        input.label = "Updated label";
        input.deliveryDate = LocalDateTime.now().plusDays(3);
        input.buildingId = building.id;

        FulfillmentChunk updated = fulfillmentResource.updateFulfillmentChunk(chunk.id, input);

        assertEquals(FulfillmentChunk.PENDING, updated.type);
        assertEquals("Updated label", updated.label);
        assertNotNull(updated.deliveryDate);
        assertNotNull(updated.building);
        assertEquals(building.id, updated.building.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateFulfillmentChunk_NullBuildingId_ClearsBuilding() throws GraphQLException {
        Store store = createStore("STORE_F15");
        Fulfillment fulfillment = persistFulfillment(persistOrder("ORD_F17", store), LocalDateTime.now().plusDays(1));
        FulfillmentChunk chunk = singleChunk(fulfillment);
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = "BLD_F2";
        building.persist();
        chunk.building = building;
        chunk.persist();

        FulfillmentResource.FulfillmentChunkRecord input = new FulfillmentResource.FulfillmentChunkRecord();
        input.buildingId = null; // explicit null: building must be cleared

        FulfillmentChunk updated = fulfillmentResource.updateFulfillmentChunk(chunk.id, input);
        assertNull(updated.building, "A null buildingId must clear the building");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateFulfillmentChunk_AddLine_Success() throws GraphQLException {
        Store store = createStore("STORE_F16");
        Fulfillment fulfillment = persistFulfillment(persistOrder("ORD_F18", store), LocalDateTime.now().plusDays(1));
        FulfillmentChunk chunk = singleChunk(fulfillment);
        Product productA = DomainUtils.createAndPersistProduct("1234567890143", "Milk", ProductType.UNIT);
        Product productB = DomainUtils.createAndPersistProduct("1234567890144", "Eggs", ProductType.UNIT);
        StoreShelfLocation location = persistShelfLocation(productA, "A1", "B2");
        FulfillmentLine existingLine = addLineToChunk(chunk, productA, 1, "1.000", location);

        FulfillmentResource.FulfillmentChunkRecord input = new FulfillmentResource.FulfillmentChunkRecord();
        // the existing line is referenced by id: the replace strategy must keep it
        FulfillmentResource.FulfillmentLineRecord keepLine = new FulfillmentResource.FulfillmentLineRecord();
        keepLine.id = existingLine.id;
        keepLine.lineNumber = 1;
        keepLine.productId = productA.id;
        keepLine.requestedQuantity = new BigDecimal("1.000");
        keepLine.locationId = location.id;
        FulfillmentResource.FulfillmentLineRecord newLine = new FulfillmentResource.FulfillmentLineRecord();
        newLine.id = null; // new line
        newLine.lineNumber = 2;
        newLine.productId = productB.id;
        newLine.requestedQuantity = new BigDecimal("6.000");
        newLine.locationId = location.id;
        input.lines = List.of(keepLine, newLine);

        FulfillmentChunk updated = fulfillmentResource.updateFulfillmentChunk(chunk.id, input);

        assertEquals(2, updated.lines.size(), "A new line must be created and the referenced one kept");
        assertTrue(updated.lines.stream().anyMatch(l -> l.lineNumber == 2 && l.product.id.equals(productB.id)));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateFulfillmentChunk_RemovesLinesMissingFromInput() throws GraphQLException {
        Store store = createStore("STORE_F18");
        Fulfillment fulfillment = persistFulfillment(persistOrder("ORD_F20", store), LocalDateTime.now().plusDays(1));
        FulfillmentChunk chunk = singleChunk(fulfillment);
        Product productA = DomainUtils.createAndPersistProduct("1234567890146", "Cheese", ProductType.UNIT);
        Product productB = DomainUtils.createAndPersistProduct("1234567890147", "Ham", ProductType.UNIT);
        StoreShelfLocation location = persistShelfLocation(productA, "A3", "B4");
        addLineToChunk(chunk, productA, 1, "1.000", location);
        FulfillmentLine lineB = addLineToChunk(chunk, productB, 2, "2.000", location);

        // only line B is referenced: line A must be removed (replace strategy)
        FulfillmentResource.FulfillmentChunkRecord input = new FulfillmentResource.FulfillmentChunkRecord();
        FulfillmentResource.FulfillmentLineRecord keepLine = new FulfillmentResource.FulfillmentLineRecord();
        keepLine.id = lineB.id;
        keepLine.lineNumber = 2;
        keepLine.productId = productB.id;
        keepLine.requestedQuantity = new BigDecimal("2.000");
        keepLine.locationId = location.id;
        input.lines = List.of(keepLine);

        FulfillmentChunk updated = fulfillmentResource.updateFulfillmentChunk(chunk.id, input);

        assertEquals(1, updated.lines.size(), "Lines missing from the input must be removed");
        assertEquals(lineB.id, updated.lines.iterator().next().id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateFulfillmentChunk_UnknownLineId() {
        Store store = createStore("STORE_F17");
        Fulfillment fulfillment = persistFulfillment(persistOrder("ORD_F19", store), LocalDateTime.now().plusDays(1));
        FulfillmentChunk chunk = singleChunk(fulfillment);
        Product product = DomainUtils.createAndPersistProduct("1234567890145", "Bread", ProductType.UNIT);
        StoreShelfLocation location = persistShelfLocation(product, "A2", "B3");
        addLineToChunk(chunk, product, 1, "1.000", location);

        FulfillmentResource.FulfillmentChunkRecord input = new FulfillmentResource.FulfillmentChunkRecord();
        FulfillmentResource.FulfillmentLineRecord ghostLine = new FulfillmentResource.FulfillmentLineRecord();
        ghostLine.id = 9999L; // does not belong to this chunk
        ghostLine.lineNumber = 1;
        ghostLine.productId = product.id;
        ghostLine.requestedQuantity = BigDecimal.ONE;
        ghostLine.locationId = location.id;
        input.lines = List.of(ghostLine);

        assertThrows(NoSuchElementException.class, () -> {
            fulfillmentResource.updateFulfillmentChunk(chunk.id, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateFulfillmentChunk_NotFound() {
        FulfillmentResource.FulfillmentChunkRecord input = new FulfillmentResource.FulfillmentChunkRecord();
        input.type = FulfillmentChunk.READY;

        assertThrows(NoSuchElementException.class, () -> {
            fulfillmentResource.updateFulfillmentChunk(9999L, input);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteFulfillment_Success() throws GraphQLException {
        Store store = createStore("STORE_F18");
        Fulfillment existing = persistFulfillment(persistOrder("ORD_F20", store), LocalDateTime.now().plusDays(1));

        boolean result = fulfillmentResource.deleteFulfillment(existing.id);
        assertTrue(result, "Delete should return true");
        assertNull(Fulfillment.findById(existing.id));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteFulfillment_NotFound() throws GraphQLException {
        boolean result = fulfillmentResource.deleteFulfillment(9999L);
        assertFalse(result, "Delete should return false for non-existent ID");
    }
}
