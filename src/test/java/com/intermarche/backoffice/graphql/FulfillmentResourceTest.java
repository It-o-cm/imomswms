package com.intermarche.backoffice.graphql;

import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.graphql.api.Context;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FulfillmentResource}.
 * <p>
 * Covers the identity accessor, the delivery-date query (store scoping, fetch-join
 * variants driven by the GraphQL selection set), the optimized-query builders,
 * the create/update mutations (reservation record, chunks, lines, closest-location
 * resolution) and the delete mutation. Panache statics are mocked: no Quarkus test required.
 */
class FulfillmentResourceTest {

    private static final Long STORE_ID = 7L;
    private static final String BADGE_ID = "BADGE-001";
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 14, 8, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 14, 18, 0);

    private MockedStatic<PanacheEntityBase> panacheMock;
    private MockedStatic<Arc> arcMock;
    private Session session;

    @BeforeEach
    void setUp() {
        panacheMock = mockStatic(PanacheEntityBase.class);
        arcMock = mockStatic(Arc.class);
        session = PanacheTestSupport.mockArcSession(arcMock);
    }

    @AfterEach
    void tearDown() {
        panacheMock.close();
        arcMock.close();
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private FulfillmentResource newResource(boolean admin, boolean serviceCaller) {
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(BADGE_ID);
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.getPrincipal()).thenReturn(principal);
        when(identity.hasRole("ADMIN")).thenReturn(admin);
        when(identity.hasRole("SERVICE_CALLER")).thenReturn(serviceCaller);

        FulfillmentResource resource = new FulfillmentResource();
        resource.identity = identity;
        return resource;
    }

    /**
     * Builds a GraphQL context whose selection set reports the given fields.
     * When {@code wantsChunks} is true, the "chunks" field definition is exposed and
     * reports whether "lines" is selected inside it.
     */
    private Context context(boolean wantsChunks, boolean wantsOrder, boolean chunksWantsLines) {
        DataFetchingFieldSelectionSet selectionSet = mock(DataFetchingFieldSelectionSet.class);
        when(selectionSet.contains("chunks")).thenReturn(wantsChunks);
        when(selectionSet.contains("order")).thenReturn(wantsOrder);
        if (wantsChunks) {
            SelectedField chunksField = mock(SelectedField.class);
            when(chunksField.getName()).thenReturn("chunks");
            DataFetchingFieldSelectionSet chunksSelection = mock(DataFetchingFieldSelectionSet.class);
            when(chunksField.getSelectionSet()).thenReturn(chunksSelection);
            when(chunksSelection.contains("lines")).thenReturn(chunksWantsLines);
            // An unrelated field ensures the anyMatch lambda's name-mismatch branch is exercised
            SelectedField idField = mock(SelectedField.class);
            when(idField.getName()).thenReturn("id");
            when(idField.getSelectionSet()).thenReturn(mock(DataFetchingFieldSelectionSet.class));
            when(selectionSet.getFields()).thenReturn(List.of(idField, chunksField));
        } else {
            when(selectionSet.getFields()).thenReturn(List.of());
        }
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getSelectionSet()).thenReturn(selectionSet);
        Context context = mock(Context.class);
        when(context.unwrap(DataFetchingEnvironment.class)).thenReturn(env);
        return context;
    }

    private Context emptyContext() {
        return context(false, false, false);
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
     * Records the arguments of a find(...) call.
     */
    private static class FindCall {
        String jpql;
        Object[] params = new Object[0];
    }

    private static Object[] extractParams(Object[] args) {
        return args.length > 1 && args[1] instanceof Object[]
                ? (Object[]) args[1]
                : Arrays.copyOfRange(args, 1, args.length);
    }

    /**
     * Stubs Fulfillment.find(...) for the delivery-date query and captures the arguments.
     */
    private FindCall stubDeliveryDateFind(List<Fulfillment> result) {
        FindCall call = new FindCall();
        panacheMock.when(() -> PanacheEntityBase.find(
                        argThat(jpql -> jpql.startsWith("SELECT DISTINCT f FROM Fulfillment")), any(Object[].class)))
                .thenAnswer(invocation -> {
                    Object[] args = invocation.getArguments();
                    call.jpql = (String) args[0];
                    call.params = extractParams(args);
                    PanacheQuery<Fulfillment> query = mock(PanacheQuery.class);
                    when(query.list()).thenReturn(result);
                    return query;
                });
        return call;
    }

    /**
     * Stubs Fulfillment.list(String) (single-arg overload) and captures the JPQL.
     */
    private FindCall stubFulfillmentList(List<Fulfillment> result) {
        FindCall call = new FindCall();
        panacheMock.when(() -> PanacheEntityBase.list(anyString()))
                .thenAnswer(invocation -> {
                    call.jpql = invocation.getArgument(0);
                    return result;
                });
        return call;
    }

    /**
     * Stubs Fulfillment.find(...) for the optimized single-fulfillment queries.
     */
    private void stubOptimizedFind(String jpqlFragment, Fulfillment result) {
        PanacheQuery<Fulfillment> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        panacheMock.when(() -> PanacheEntityBase.find(
                        argThat(jpql -> jpql.contains(jpqlFragment)), any(Object[].class)))
                .thenReturn(query);
    }

    private Order order(String orderId) {
        Order order = new Order();
        order.id = 42L;
        order.orderId = orderId;
        return order;
    }

    private Product product(Long id) {
        Product product = new Product();
        product.id = id;
        product.ean = "EAN-" + id;
        return product;
    }

    private Fulfillment fulfillment(Long id) {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = id;
        return fulfillment;
    }

    private FulfillmentLine line(Long id) {
        FulfillmentLine line = new FulfillmentLine();
        line.id = id;
        return line;
    }

    // --------------------------------------------------
    // getIdentity
    // --------------------------------------------------

    @Test
    void getIdentity_returnsInjectedIdentity() {
        FulfillmentResource resource = new FulfillmentResource();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        resource.identity = identity;

        assertSame(identity, resource.getIdentity());
    }

    // --------------------------------------------------
    // fulfillmentsByDeliveryDate
    // --------------------------------------------------

    @Test
    void fulfillmentsByDeliveryDate_withStoreContext_filtersByStore() throws Exception {
        stubBadgeLookup(employee(store(STORE_ID)));
        FindCall call = stubDeliveryDateFind(List.of(fulfillment(1L)));

        List<Fulfillment> result = newResource(false, false)
                .fulfillmentsByDeliveryDate(START, END, emptyContext());

        assertEquals(1, result.size());
        assertEquals("SELECT DISTINCT f FROM Fulfillment f JOIN f.chunks c JOIN f.order o " +
                "WHERE c.deliveryDate >= ?1 AND c.deliveryDate <= ?2 AND o.store.id = ?3", call.jpql);
        assertArrayEquals(new Object[]{START, END, STORE_ID}, call.params);
    }

    @Test
    void fulfillmentsByDeliveryDate_globalAdmin_noStoreFilter() throws Exception {
        stubBadgeLookup(null);
        FindCall call = stubDeliveryDateFind(List.of());

        newResource(true, false).fulfillmentsByDeliveryDate(START, END, emptyContext());

        assertEquals("SELECT DISTINCT f FROM Fulfillment f JOIN f.chunks c " +
                "WHERE c.deliveryDate >= ?1 AND c.deliveryDate <= ?2 ", call.jpql);
        assertArrayEquals(new Object[]{START, END}, call.params);
    }

    @Test
    void fulfillmentsByDeliveryDate_wantsChunksOrderAndLines_usesFetchJoins() throws Exception {
        stubBadgeLookup(null);
        FindCall call = stubDeliveryDateFind(List.of());

        newResource(true, false).fulfillmentsByDeliveryDate(START, END, context(true, true, true));

        assertEquals("SELECT DISTINCT f FROM Fulfillment f JOIN FETCH f.chunks c JOIN FETCH f.order o " +
                "LEFT JOIN FETCH c.lines l LEFT JOIN FETCH l.product " +
                "WHERE c.deliveryDate >= ?1 AND c.deliveryDate <= ?2 ", call.jpql);
        assertArrayEquals(new Object[]{START, END}, call.params);
    }

    @Test
    void fulfillmentsByDeliveryDate_storeContextWantsChunksWithoutLines_fetchesChunksOnly() throws Exception {
        stubBadgeLookup(employee(store(STORE_ID)));
        FindCall call = stubDeliveryDateFind(List.of());

        newResource(false, false).fulfillmentsByDeliveryDate(START, END, context(true, false, false));

        assertEquals("SELECT DISTINCT f FROM Fulfillment f JOIN FETCH f.chunks c JOIN f.order o " +
                "WHERE c.deliveryDate >= ?1 AND c.deliveryDate <= ?2 AND o.store.id = ?3", call.jpql);
        assertArrayEquals(new Object[]{START, END, STORE_ID}, call.params);
    }

    @Test
    void fulfillmentsByDeliveryDate_wantsOrderWithoutChunks_fetchesOrderOnly() throws Exception {
        stubBadgeLookup(employee(store(STORE_ID)));
        FindCall call = stubDeliveryDateFind(List.of());

        newResource(false, false).fulfillmentsByDeliveryDate(START, END, context(false, true, false));

        assertEquals("SELECT DISTINCT f FROM Fulfillment f JOIN f.chunks c JOIN FETCH f.order o " +
                "WHERE c.deliveryDate >= ?1 AND c.deliveryDate <= ?2 AND o.store.id = ?3", call.jpql);
    }

    @Test
    void fulfillmentsByDeliveryDate_noStoreAndNoPrivileges_throwsSecurityExceptionWrapped() {
        stubBadgeLookup(null);

        FulfillmentResource resource = newResource(false, false);
        GraphQLException exception = assertThrows(GraphQLException.class,
                () -> resource.fulfillmentsByDeliveryDate(START, END, emptyContext()));

        assertInstanceOf(SecurityException.class, exception.getCause());
    }

    // --------------------------------------------------
    // allFulfillments / fulfillment / fulfillmentByOrderId
    // --------------------------------------------------

    @Test
    void allFulfillments_emptySelection_buildsPlainQuery() throws Exception {
        FindCall call = stubFulfillmentList(List.of(fulfillment(1L)));

        List<Fulfillment> result = newResource(true, false).allFulfillments(emptyContext());

        assertEquals(1, result.size());
        assertEquals("select distinct f from Fulfillment f ", call.jpql);
    }

    @Test
    void allFulfillments_wantsOrderChunksAndLines_buildsFullFetchQuery() throws Exception {
        FindCall call = stubFulfillmentList(List.of());

        newResource(true, false).allFulfillments(context(true, true, true));

        assertEquals("select distinct f from Fulfillment f join fetch f.order o " +
                "left join fetch f.chunks c left join fetch c.lines l left join fetch l.product ", call.jpql);
    }

    @Test
    void allFulfillments_wantsChunksWithoutLines_buildsChunkFetchOnly() throws Exception {
        FindCall call = stubFulfillmentList(List.of());

        newResource(true, false).allFulfillments(context(true, false, false));

        assertEquals("select distinct f from Fulfillment f left join fetch f.chunks c ", call.jpql);
    }

    @Test
    void fulfillment_found_returnsFulfillment() throws Exception {
        Fulfillment fulfillment = fulfillment(5L);
        stubOptimizedFind("f.id = ?1", fulfillment);

        Fulfillment result = newResource(true, false).fulfillment(5L, emptyContext());

        assertSame(fulfillment, result);
    }

    @Test
    void fulfillment_notFound_throwsNoSuchElement() {
        stubOptimizedFind("f.id = ?1", null);

        FulfillmentResource resource = newResource(true, false);
        assertThrows(NoSuchElementException.class, () -> resource.fulfillment(5L, emptyContext()));
    }

    @Test
    void fulfillmentByOrderId_found_joinsOrderWithoutFetch() throws Exception {
        Fulfillment fulfillment = fulfillment(6L);
        FindCall call = new FindCall();
        PanacheQuery<Fulfillment> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(fulfillment);
        panacheMock.when(() -> PanacheEntityBase.find(
                        argThat(jpql -> jpql.contains("o.orderId = ?1")), any(Object[].class)))
                .thenAnswer(invocation -> {
                    call.jpql = (String) invocation.getArguments()[0];
                    return query;
                });

        Fulfillment result = newResource(true, false).fulfillmentByOrderId("ORD-1", emptyContext());

        assertSame(fulfillment, result);
        assertEquals("select distinct f from Fulfillment f join f.order o where o.orderId = ?1", call.jpql);
    }

    @Test
    void fulfillmentByOrderId_wantsOrder_fetchesOrder() throws Exception {
        FindCall call = new FindCall();
        PanacheQuery<Fulfillment> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(fulfillment(6L));
        panacheMock.when(() -> PanacheEntityBase.find(
                        argThat(jpql -> jpql.contains("o.orderId = ?1")), any(Object[].class)))
                .thenAnswer(invocation -> {
                    call.jpql = (String) invocation.getArguments()[0];
                    return query;
                });

        newResource(true, false).fulfillmentByOrderId("ORD-1", context(false, true, false));

        assertEquals("select distinct f from Fulfillment f join fetch f.order o where o.orderId = ?1", call.jpql);
    }

    @Test
    void fulfillmentByOrderId_notFound_throwsNoSuchElement() {
        stubOptimizedFind("o.orderId = ?1", null);

        FulfillmentResource resource = newResource(true, false);
        assertThrows(NoSuchElementException.class, () -> resource.fulfillmentByOrderId("ORD-X", emptyContext()));
    }

    // --------------------------------------------------
    // createFulfillment
    // --------------------------------------------------

    private void stubOrderLookup(Order order) {
        PanacheQuery<Order> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(order);
        panacheMock.when(() -> PanacheEntityBase.find(eq("orderId"), any(Object[].class)))
                .thenReturn(query);
    }

    private void stubExistingFulfillmentLookup(Fulfillment existing) {
        PanacheQuery<Fulfillment> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(existing);
        panacheMock.when(() -> PanacheEntityBase.find(eq("order"), any(Object[].class)))
                .thenReturn(query);
    }

    @Test
    void createFulfillment_orderNotFound_throwsNoSuchElement() {
        stubOrderLookup(null);

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        input.orderId = "ORD-X";

        FulfillmentResource resource = newResource(false, false);
        assertThrows(NoSuchElementException.class, () -> resource.createFulfillment(input));
    }

    @Test
    void createFulfillment_alreadyExists_throwsGraphQLException() {
        Order order = order("ORD-1");
        stubOrderLookup(order);
        stubExistingFulfillmentLookup(fulfillment(9L));

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        input.orderId = "ORD-1";

        FulfillmentResource resource = newResource(false, false);
        GraphQLException exception = assertThrows(GraphQLException.class, () -> resource.createFulfillment(input));
        assertTrue(exception.getMessage().contains("already exists"));
    }

    @Test
    void createFulfillment_withReservationChunksAndLines_createsGraph() throws Exception {
        Order order = order("ORD-1");
        stubOrderLookup(order);
        stubExistingFulfillmentLookup(null);
        Product product = product(10L);
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(product);

        Instant leasing = Instant.parse("2026-10-14T10:00:00Z");
        FulfillmentResource.ReservationRecord reservation = new FulfillmentResource.ReservationRecord();
        reservation.status = ReservationRecord.ReservationStatus.PROCESSING;
        reservation.leasing = leasing;

        FulfillmentResource.FulfillmentLineRecord lineInput = new FulfillmentResource.FulfillmentLineRecord();
        lineInput.lineNumber = 3;
        lineInput.productId = 10L;
        lineInput.requestedQuantity = new BigDecimal("2.5");

        FulfillmentResource.FulfillmentChunkRecord chunkInput = new FulfillmentResource.FulfillmentChunkRecord();
        chunkInput.type = FulfillmentChunk.PENDING;
        chunkInput.deliveryDate = END;
        chunkInput.lines = List.of(lineInput);

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        input.orderId = "ORD-1";
        input.reservationRecord = reservation;
        input.chunks = List.of(chunkInput);

        Fulfillment result = newResource(false, false).createFulfillment(input);

        assertSame(order, result.order);
        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, result.reservationRecord.status);
        assertEquals(leasing, result.reservationRecord.leasing);
        assertEquals(1, result.chunks.size());
        FulfillmentChunk chunk = result.chunks.iterator().next();
        assertEquals(FulfillmentChunk.PENDING, chunk.type);
        assertEquals(END, chunk.deliveryDate);
        assertEquals(1, chunk.lines.size());
        FulfillmentLine line = chunk.lines.iterator().next();
        assertEquals(3, line.lineNumber);
        assertSame(product, line.product);
        assertEquals(new BigDecimal("2.5"), line.requestedQuantity);
        verify(session).persist(result);
    }

    @Test
    void createFulfillment_productNotFound_throwsNoSuchElement() {
        Order order = order("ORD-1");
        stubOrderLookup(order);
        stubExistingFulfillmentLookup(null);
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(null);

        FulfillmentResource.FulfillmentLineRecord lineInput = new FulfillmentResource.FulfillmentLineRecord();
        lineInput.lineNumber = 1;
        lineInput.productId = 99L;

        FulfillmentResource.FulfillmentChunkRecord chunkInput = new FulfillmentResource.FulfillmentChunkRecord();
        chunkInput.lines = List.of(lineInput);

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        input.orderId = "ORD-1";
        input.chunks = List.of(chunkInput);

        FulfillmentResource resource = newResource(false, false);
        assertThrows(NoSuchElementException.class, () -> resource.createFulfillment(input));
    }

    @Test
    void createFulfillment_emptyChunks_skipsChunkProcessing() throws Exception {
        Order order = order("ORD-1");
        stubOrderLookup(order);
        stubExistingFulfillmentLookup(null);

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        input.orderId = "ORD-1";
        input.chunks = List.of();

        Fulfillment result = newResource(false, false).createFulfillment(input);

        assertSame(order, result.order);
        assertTrue(result.chunks.isEmpty());
        verify(session).persist(result);
    }

    @Test
    void createFulfillment_chunkWithNullLines_createsChunkWithoutLines() throws Exception {
        Order order = order("ORD-1");
        stubOrderLookup(order);
        stubExistingFulfillmentLookup(null);

        FulfillmentResource.FulfillmentChunkRecord chunkInput = new FulfillmentResource.FulfillmentChunkRecord();
        chunkInput.type = FulfillmentChunk.PENDING;
        chunkInput.deliveryDate = END;
        chunkInput.lines = null;

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        input.orderId = "ORD-1";
        input.chunks = List.of(chunkInput);

        Fulfillment result = newResource(false, false).createFulfillment(input);

        assertEquals(1, result.chunks.size());
        FulfillmentChunk chunk = result.chunks.iterator().next();
        assertEquals(FulfillmentChunk.PENDING, chunk.type);
        assertTrue(chunk.lines.isEmpty());
    }

    @Test
    void createFulfillment_chunkWithEmptyLines_createsChunkWithoutLines() throws Exception {
        Order order = order("ORD-1");
        stubOrderLookup(order);
        stubExistingFulfillmentLookup(null);

        FulfillmentResource.FulfillmentChunkRecord chunkInput = new FulfillmentResource.FulfillmentChunkRecord();
        chunkInput.type = FulfillmentChunk.READY;
        chunkInput.deliveryDate = END;
        chunkInput.lines = List.of();

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        input.orderId = "ORD-1";
        input.chunks = List.of(chunkInput);

        Fulfillment result = newResource(false, false).createFulfillment(input);

        assertEquals(1, result.chunks.size());
        FulfillmentChunk chunk = result.chunks.iterator().next();
        assertEquals(FulfillmentChunk.READY, chunk.type);
        assertTrue(chunk.lines.isEmpty());
    }

    @Test
    void createFulfillment_nullChunksAndReservation_createsBareFulfillment() throws Exception {
        Order order = order("ORD-1");
        stubOrderLookup(order);
        stubExistingFulfillmentLookup(null);

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        input.orderId = "ORD-1";

        Fulfillment result = newResource(false, false).createFulfillment(input);

        assertSame(order, result.order);
        assertEquals(ReservationRecord.ReservationStatus.PENDING, result.reservationRecord.status);
        assertTrue(result.chunks.isEmpty());
        verify(session).persist(result);
    }

    // --------------------------------------------------
    // updateFulfillment
    // --------------------------------------------------

    @Test
    void updateFulfillment_notFound_throwsNoSuchElement() {
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(null);

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        FulfillmentResource resource = newResource(false, false);
        assertThrows(NoSuchElementException.class, () -> resource.updateFulfillment(1L, input));
    }

    @Test
    void updateFulfillment_appliesReservationRecord() throws Exception {
        Fulfillment fulfillment = fulfillment(1L);
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

        Instant leasing = Instant.parse("2026-10-14T12:00:00Z");
        FulfillmentResource.ReservationRecord reservation = new FulfillmentResource.ReservationRecord();
        reservation.status = ReservationRecord.ReservationStatus.PROCESSING;
        reservation.leasing = leasing;

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        input.reservationRecord = reservation;

        Fulfillment result = newResource(false, false).updateFulfillment(1L, input);

        assertSame(fulfillment, result);
        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, fulfillment.reservationRecord.status);
        assertEquals(leasing, fulfillment.reservationRecord.leasing);
    }

    @Test
    void updateFulfillment_partialReservationRecord_updatesOnlyProvidedFields() throws Exception {
        Fulfillment fulfillment = fulfillment(1L);
        fulfillment.reservationRecord.status = ReservationRecord.ReservationStatus.PROCESSING;
        Instant originalLeasing = Instant.parse("2026-10-14T09:00:00Z");
        fulfillment.reservationRecord.leasing = originalLeasing;
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

        Instant newLeasing = Instant.parse("2026-10-14T12:00:00Z");
        FulfillmentResource.ReservationRecord reservation = new FulfillmentResource.ReservationRecord();
        reservation.leasing = newLeasing;

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        input.reservationRecord = reservation;

        newResource(false, false).updateFulfillment(1L, input);

        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, fulfillment.reservationRecord.status);
        assertEquals(newLeasing, fulfillment.reservationRecord.leasing);
    }

    @Test
    void updateFulfillment_statusOnlyReservation_updatesStatusKeepsLeasing() throws Exception {
        Fulfillment fulfillment = fulfillment(1L);
        Instant originalLeasing = Instant.parse("2026-10-14T09:00:00Z");
        fulfillment.reservationRecord.leasing = originalLeasing;
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

        FulfillmentResource.ReservationRecord reservation = new FulfillmentResource.ReservationRecord();
        reservation.status = ReservationRecord.ReservationStatus.PROCESSING;

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();
        input.reservationRecord = reservation;

        newResource(false, false).updateFulfillment(1L, input);

        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, fulfillment.reservationRecord.status);
        assertEquals(originalLeasing, fulfillment.reservationRecord.leasing);
    }

    @Test
    void updateFulfillment_nullReservationRecord_leavesFulfillmentUntouched() throws Exception {
        Fulfillment fulfillment = fulfillment(1L);
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

        FulfillmentResource.FulfillmentRecord input = new FulfillmentResource.FulfillmentRecord();

        Fulfillment result = newResource(false, false).updateFulfillment(1L, input);

        assertSame(fulfillment, result);
        assertEquals(ReservationRecord.ReservationStatus.PENDING, fulfillment.reservationRecord.status);
        assertNull(fulfillment.reservationRecord.leasing);
    }

    // --------------------------------------------------
    // updateFulfillmentChunk
    // --------------------------------------------------

    private FulfillmentChunk chunk(Long id) {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.id = id;
        return chunk;
    }

    @Test
    void updateFulfillmentChunk_notFound_throwsNoSuchElement() {
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(null);

        FulfillmentResource.FulfillmentChunkRecord input = new FulfillmentResource.FulfillmentChunkRecord();
        FulfillmentResource resource = newResource(false, false);
        assertThrows(NoSuchElementException.class, () -> resource.updateFulfillmentChunk(1L, input));
    }

    @Test
    void updateFulfillmentChunk_updatesFieldsAndBuilding() throws Exception {
        FulfillmentChunk chunk = chunk(1L);
        Building building = new StoreBuilding();
        building.id = 3L;
        // FulfillmentChunk.findById and Building.findById are inherited statics:
        // both are intercepted through PanacheEntityBase, so dispatch on the id value.
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenAnswer(invocation -> {
            Object id = invocation.getArgument(0);
            return chunk.id.equals(id) ? chunk : building;
        });

        FulfillmentResource.FulfillmentChunkRecord input = new FulfillmentResource.FulfillmentChunkRecord();
        input.type = FulfillmentChunk.READY;
        input.label = "LABEL-1";
        input.deliveryDate = END;
        input.buildingId = 3L;

        FulfillmentChunk result = newResource(false, false).updateFulfillmentChunk(1L, input);

        assertSame(chunk, result);
        assertEquals(FulfillmentChunk.READY, chunk.type);
        assertEquals("LABEL-1", chunk.label);
        assertEquals(END, chunk.deliveryDate);
        assertSame(building, chunk.building);
    }

    @Test
    void updateFulfillmentChunk_nullBuildingId_clearsBuilding() throws Exception {
        FulfillmentChunk chunk = chunk(1L);
        chunk.building = new StoreBuilding();
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(chunk);

        FulfillmentResource.FulfillmentChunkRecord input = new FulfillmentResource.FulfillmentChunkRecord();

        newResource(false, false).updateFulfillmentChunk(1L, input);

        assertNull(chunk.building);
    }

    @Test
    void updateFulfillmentChunk_removesLinesMissingFromInputAndUpdatesKeptOnes() throws Exception {
        FulfillmentChunk chunk = chunk(1L);
        FulfillmentLine kept = line(1L);
        FulfillmentLine removed = line(2L);
        chunk.lines.add(kept);
        chunk.lines.add(removed);

        Product product = product(10L);
        BaseLocation location = mock(BaseLocation.class);
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenAnswer(invocation -> {
            Object id = invocation.getArgument(0);
            if (chunk.id.equals(id)) return chunk;
            if (product.id.equals(id)) return product;
            return location;
        });

        BaseLocation.LocationSpecification locSpec =
                new BaseLocation.LocationSpecification(store(STORE_ID), null, null, null);

        FulfillmentResource.FulfillmentLineRecord lineInput = new FulfillmentResource.FulfillmentLineRecord();
        lineInput.id = 1L;
        lineInput.lineNumber = 7;
        lineInput.productId = 10L;
        lineInput.requestedQuantity = BigDecimal.ONE;
        lineInput.locationId = 55L;

        FulfillmentResource.FulfillmentChunkRecord input = new FulfillmentResource.FulfillmentChunkRecord();
        input.lines = List.of(lineInput);

        try (MockedStatic<FulfillmentChunk> chunkMock = mockStatic(FulfillmentChunk.class)) {
            chunkMock.when(() -> FulfillmentChunk.findCommonSpecification(any())).thenReturn(locSpec);

            FulfillmentChunk result = newResource(false, false).updateFulfillmentChunk(1L, input);

            assertSame(chunk, result);
            assertEquals(1, chunk.lines.size());
            assertTrue(chunk.lines.contains(kept));
            assertFalse(chunk.lines.contains(removed));
            assertEquals(7, kept.lineNumber);
            assertSame(product, kept.product);
            assertEquals(BigDecimal.ONE, kept.requestedQuantity);
            assertSame(location, kept.location);
        }
    }

    @Test
    void updateFulfillmentChunk_lineWithoutProductId_keepsExistingProduct() throws Exception {
        FulfillmentChunk chunk = chunk(1L);
        FulfillmentLine existing = line(1L);
        Product existingProduct = product(10L);
        existing.product = existingProduct;
        chunk.lines.add(existing);

        BaseLocation location = mock(BaseLocation.class);
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenAnswer(invocation -> {
            Object id = invocation.getArgument(0);
            if (chunk.id.equals(id)) return chunk;
            return location;
        });

        BaseLocation.LocationSpecification locSpec =
                new BaseLocation.LocationSpecification(store(STORE_ID), null, null, null);

        FulfillmentResource.FulfillmentLineRecord lineInput = new FulfillmentResource.FulfillmentLineRecord();
        lineInput.id = 1L;
        lineInput.lineNumber = 9;
        lineInput.requestedQuantity = BigDecimal.TEN;
        lineInput.locationId = 55L;

        FulfillmentResource.FulfillmentChunkRecord input = new FulfillmentResource.FulfillmentChunkRecord();
        input.lines = List.of(lineInput);

        try (MockedStatic<FulfillmentChunk> chunkMock = mockStatic(FulfillmentChunk.class)) {
            chunkMock.when(() -> FulfillmentChunk.findCommonSpecification(any())).thenReturn(locSpec);

            newResource(false, false).updateFulfillmentChunk(1L, input);

            assertSame(existingProduct, existing.product);
            assertEquals(9, existing.lineNumber);
            assertSame(location, existing.location);
        }
    }

    @Test
    void updateFulfillmentChunk_lineIdNotFound_throwsNoSuchElement() {
        FulfillmentChunk chunk = chunk(1L);
        chunk.lines.add(line(1L));
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(chunk);

        BaseLocation.LocationSpecification locSpec =
                new BaseLocation.LocationSpecification(store(STORE_ID), null, null, null);

        FulfillmentResource.FulfillmentLineRecord lineInput = new FulfillmentResource.FulfillmentLineRecord();
        lineInput.id = 99L;

        FulfillmentResource.FulfillmentChunkRecord input = new FulfillmentResource.FulfillmentChunkRecord();
        input.lines = List.of(lineInput);

        try (MockedStatic<FulfillmentChunk> chunkMock = mockStatic(FulfillmentChunk.class)) {
            chunkMock.when(() -> FulfillmentChunk.findCommonSpecification(any())).thenReturn(locSpec);

            FulfillmentResource resource = newResource(false, false);
            assertThrows(NoSuchElementException.class, () -> resource.updateFulfillmentChunk(1L, input));
        }
    }

    @Test
    void updateFulfillmentChunk_newLineWithLocationId_resolvesLocationById() throws Exception {
        FulfillmentChunk chunk = chunk(1L);
        Product product = product(10L);
        BaseLocation location = mock(BaseLocation.class);
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenAnswer(invocation -> {
            Object id = invocation.getArgument(0);
            if (chunk.id.equals(id)) return chunk;
            if (product.id.equals(id)) return product;
            return location;
        });

        BaseLocation.LocationSpecification locSpec =
                new BaseLocation.LocationSpecification(store(STORE_ID), null, null, null);

        FulfillmentResource.FulfillmentLineRecord lineInput = new FulfillmentResource.FulfillmentLineRecord();
        lineInput.lineNumber = 5;
        lineInput.productId = 10L;
        lineInput.requestedQuantity = new BigDecimal("4");
        lineInput.locationId = 55L;

        FulfillmentResource.FulfillmentChunkRecord input = new FulfillmentResource.FulfillmentChunkRecord();
        input.lines = List.of(lineInput);

        try (MockedStatic<FulfillmentChunk> chunkMock = mockStatic(FulfillmentChunk.class)) {
            chunkMock.when(() -> FulfillmentChunk.findCommonSpecification(any())).thenReturn(locSpec);

            newResource(false, false).updateFulfillmentChunk(1L, input);

            assertEquals(1, chunk.lines.size());
            FulfillmentLine line = chunk.lines.iterator().next();
            assertEquals(5, line.lineNumber);
            assertSame(product, line.product);
            assertEquals(new BigDecimal("4"), line.requestedQuantity);
            assertSame(location, line.location);
        }
    }

    @Test
    void updateFulfillmentChunk_newLineWithoutLocationId_resolvesClosestLocation() throws Exception {
        FulfillmentChunk chunk = chunk(1L);
        Product product = product(10L);
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenAnswer(invocation -> {
            Object id = invocation.getArgument(0);
            return chunk.id.equals(id) ? chunk : product;
        });

        Store store = store(STORE_ID);
        Building building = new StoreBuilding();
        building.id = 3L;
        BaseLocation.LocationSpecification locSpec =
                new BaseLocation.LocationSpecification(store, building, "A1", "B2");
        ShelfLocation closest = new StoreShelfLocation();
        closest.id = 77L;

        FulfillmentResource.FulfillmentLineRecord lineInput = new FulfillmentResource.FulfillmentLineRecord();
        lineInput.lineNumber = 2;
        lineInput.productId = 10L;
        lineInput.requestedQuantity = BigDecimal.TEN;

        FulfillmentResource.FulfillmentChunkRecord input = new FulfillmentResource.FulfillmentChunkRecord();
        input.lines = List.of(lineInput);

        try (MockedStatic<FulfillmentChunk> chunkMock = mockStatic(FulfillmentChunk.class);
             MockedStatic<ShelfLocation> shelfMock = mockStatic(ShelfLocation.class)) {
            chunkMock.when(() -> FulfillmentChunk.findCommonSpecification(any())).thenReturn(locSpec);
            shelfMock.when(() -> ShelfLocation.findClosestForProduct(any(), any(), any(), any(), any()))
                    .thenReturn(List.of(closest));

            newResource(false, false).updateFulfillmentChunk(1L, input);

            assertEquals(1, chunk.lines.size());
            FulfillmentLine line = chunk.lines.iterator().next();
            assertSame(closest, line.location);
            shelfMock.verify(() -> ShelfLocation.findClosestForProduct(
                    eq(store), eq(product), eq(building), eq("A1"), eq("B2")));
        }
    }

    // --------------------------------------------------
    // deleteFulfillment
    // --------------------------------------------------

    @Test
    void deleteFulfillment_existing_returnsTrue() throws Exception {
        panacheMock.when(() -> PanacheEntityBase.deleteById(any())).thenReturn(true);

        assertTrue(newResource(false, false).deleteFulfillment(1L));
    }

    @Test
    void deleteFulfillment_missing_returnsFalse() throws Exception {
        panacheMock.when(() -> PanacheEntityBase.deleteById(any())).thenReturn(false);

        assertFalse(newResource(false, false).deleteFulfillment(1L));
    }
}
