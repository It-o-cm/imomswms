package com.intermarche.backoffice.engine.handling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.HandlingOrderSequence;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GroupingOrderManagerFactory}.
 * Covers factory naming, JSON schema validation of the configuration,
 * the {@link GroupingOrderManagerFactory.GroupKey} value object and the
 * {@link GroupingOrderManagerFactory.GroupingOrderManager} processing logic
 * (orphan lot detection, grouping, handler creation/reuse and persistence).
 */
class GroupingOrderManagerFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GroupingOrderManagerFactory factory = new GroupingOrderManagerFactory();

    private JsonNode spec(String json) throws Exception {
        return mapper.readTree(json);
    }

    @AfterEach
    void tearDown() {
        DateTimeProvider.clear();
    }

    // --------------------------------------------------
    // getName
    // --------------------------------------------------

    @Test
    void getName_returnsGrouping() {
        assertEquals("Grouping", factory.getName());
    }

    // --------------------------------------------------
    // build - valid configurations
    // --------------------------------------------------

    @Test
    void build_emptySpec_returnsManager() throws Exception {
        var builder = factory.build(spec("{}"));
        assertNotNull(builder);
        assertInstanceOf(GroupingOrderManagerFactory.GroupingOrderManager.class, builder);
    }

    @Test
    void build_fullSpecWithoutBuffer_returnsManager() throws Exception {
        var builder = factory.build(spec("""
                {"name":"grouping-1","validityDelayMinutes":30,"groupingWindowMinutes":120}
                """));
        assertInstanceOf(GroupingOrderManagerFactory.GroupingOrderManager.class, builder);
    }

    // --------------------------------------------------
    // build - invalid configurations
    // --------------------------------------------------

    @Test
    void build_negativeValidityDelay_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"validityDelayMinutes\":-1}")));
    }

    @Test
    void build_zeroGroupingWindow_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"groupingWindowMinutes\":0}")));
    }

    @Test
    void build_wrongType_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"groupingWindowMinutes\":\"abc\"}")));
    }

    @Test
    void build_arrayInsteadOfObject_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("[1,2,3]")));
    }

    // --------------------------------------------------
    // GroupKey
    // --------------------------------------------------

    @Test
    void groupKey_equalsAndHashCode() {
        Buffer buffer = new Buffer();
        buffer.id = 3L;
        LocalDateTime slot = LocalDateTime.of(2026, 10, 14, 10, 0);

        var k1 = new GroupingOrderManagerFactory.GroupKey(42L, buffer, slot);
        var k2 = new GroupingOrderManagerFactory.GroupKey(42L, buffer, slot);

        assertEquals(k1, k1);
        assertEquals(k1, k2);
        assertEquals(k1.hashCode(), k2.hashCode());
    }

    @Test
    void groupKey_equals_sameBufferIdDifferentInstance() {
        Buffer bufferA = new Buffer();
        bufferA.id = 3L;
        Buffer bufferB = new Buffer();
        bufferB.id = 3L;
        LocalDateTime slot = LocalDateTime.of(2026, 10, 14, 10, 0);

        var k1 = new GroupingOrderManagerFactory.GroupKey(42L, bufferA, slot);
        var k2 = new GroupingOrderManagerFactory.GroupKey(42L, bufferB, slot);

        assertEquals(k1, k2);
    }

    @Test
    void groupKey_notEquals() {
        Buffer buffer = new Buffer();
        buffer.id = 3L;
        LocalDateTime slot = LocalDateTime.of(2026, 10, 14, 10, 0);
        var key = new GroupingOrderManagerFactory.GroupKey(42L, buffer, slot);

        assertNotEquals(key, new GroupingOrderManagerFactory.GroupKey(43L, buffer, slot));
        assertNotEquals(key, new GroupingOrderManagerFactory.GroupKey(42L, buffer, slot.plusHours(1)));
        assertNotEquals(key, new GroupingOrderManagerFactory.GroupKey(42L, null, slot));
        assertNotEquals(key, null);
        assertNotEquals(key, "not-a-key");
    }

    @Test
    void groupKey_toString_containsIdentifiers() {
        Buffer buffer = new Buffer();
        buffer.id = 3L;
        var key = new GroupingOrderManagerFactory.GroupKey(42L, buffer, LocalDateTime.of(2026, 10, 14, 10, 0));

        String s = key.toString();
        assertTrue(s.contains("42"));
        assertTrue(s.contains("3"));
        assertTrue(s.contains("2026-10-14T10:00"));
    }

    @Test
    void groupKey_nullBuffer_hashCodeAndToStringDoNotThrow() {
        var key = new GroupingOrderManagerFactory.GroupKey(42L, null, LocalDateTime.of(2026, 10, 14, 10, 0));

        assertDoesNotThrow(key::hashCode);
        assertDoesNotThrow(key::toString);
    }

    @Test
    void groupKey_nullBuffers_areEqual() {
        LocalDateTime slot = LocalDateTime.of(2026, 10, 14, 10, 0);
        var k1 = new GroupingOrderManagerFactory.GroupKey(42L, null, slot);
        var k2 = new GroupingOrderManagerFactory.GroupKey(42L, null, slot);

        assertEquals(k1, k2);
        assertEquals(k1.hashCode(), k2.hashCode());
    }

    // --------------------------------------------------
    // build - targetBufferCode resolution
    // --------------------------------------------------

    @Test
    void build_withTargetBufferCode_resolvesBuffer() throws Exception {
        Buffer buffer = new Buffer();
        buffer.id = 3L;
        buffer.bufferCode = "BUF-1";

        try (MockedStatic<Buffer> bufferMock = mockStatic(Buffer.class)) {
            bufferMock.when(() -> Buffer.findByCode("BUF-1")).thenReturn(buffer);

            var builder = factory.build(spec("{\"targetBufferCode\":\"BUF-1\"}"));

            assertInstanceOf(GroupingOrderManagerFactory.GroupingOrderManager.class, builder);
            bufferMock.verify(() -> Buffer.findByCode("BUF-1"));
        }
    }

    @Test
    void build_withUnknownTargetBufferCode_buildsManagerWithNullBuffer() throws Exception {
        try (MockedStatic<Buffer> bufferMock = mockStatic(Buffer.class)) {
            bufferMock.when(() -> Buffer.findByCode(anyString())).thenReturn(null);

            var builder = factory.build(spec("{\"targetBufferCode\":\"NOPE\"}"));

            assertNotNull(builder);
        }
    }

    @Test
    void build_invalidSpec_exceptionMessageContainsDetails() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"validityDelayMinutes\":-1}")));

        assertTrue(ex.getMessage().contains("Invalid GroupingOrderManager configuration"));
    }

    // --------------------------------------------------
    // GroupingOrderManager.process
    // --------------------------------------------------

    private static Store storeWithId(Long id, String code) {
        Store store = new Store();
        store.id = id;
        store.code = code;
        return store;
    }

    private static Lot lotWith(Long id, Long customerId, LocalDateTime deliveryDate) {
        Lot lot = new Lot();
        lot.id = id;
        lot.status = Lot.LotStatus.PLANNED_TO_BE_COLLECTED;
        Customer customer = new Customer();
        customer.id = customerId;
        lot.customer = customer;
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.id = id + 1000;
        chunk.deliveryDate = deliveryDate;
        lot.chunk = chunk;
        return lot;
    }

    @SuppressWarnings("unchecked")
    private static Query<Lot> stubLotQuery(Session session, List<Lot> orphanLots) {
        Query<Lot> query = mock(Query.class);
        when(session.createQuery(anyString(), eq(Lot.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(orphanLots);
        return query;
    }

    @SuppressWarnings("unchecked")
    private static Query<GroupingOrder> stubOrderQuery(Session session, List<GroupingOrder> existing) {
        Query<GroupingOrder> query = mock(Query.class);
        when(session.createQuery(anyString(), eq(GroupingOrder.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(existing);
        return query;
    }

    private GroupingOrderManagerFactory.GroupingOrderManager buildManager(String json) throws Exception {
        return (GroupingOrderManagerFactory.GroupingOrderManager) factory.build(mapper.readTree(json));
    }

    @Test
    void process_noOrphanLots_returnsEarlyWithoutPersisting() throws Exception {
        Store store = storeWithId(1L, "S1");
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 10, 14, 12, 0));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<Buffer> bufferMock = mockStatic(Buffer.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            Buffer buffer = new Buffer();
            buffer.id = 3L;
            bufferMock.when(() -> Buffer.findByCode("BUF-1")).thenReturn(buffer);
            Query<Lot> lotQuery = stubLotQuery(session, List.of());

            GroupingOrderManagerFactory.GroupingOrderManager manager = buildManager(
                    "{\"validityDelayMinutes\":30,\"groupingWindowMinutes\":120,\"targetBufferCode\":\"BUF-1\"}");
            manager.process(store);

            verify(lotQuery).setParameter(eq("store"), eq(store));
            verify(lotQuery).setParameter(eq("cutoffDate"), eq(LocalDateTime.of(2026, 10, 14, 12, 30)));
            verify(session, never()).createQuery(anyString(), eq(GroupingOrder.class));
            verify(session, never()).persist(any());
        }
    }

    @Test
    void process_orphanLots_createsNewGroupingOrderAndPersistsIt() throws Exception {
        Store store = storeWithId(1L, "S1");
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 10, 14, 12, 0));
        Customer customer = new Customer();
        customer.id = 5L;
        Buffer buffer = new Buffer();
        buffer.id = 3L;
        Lot lot = lotWith(10L, 5L, LocalDateTime.of(2026, 10, 14, 10, 30));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Buffer> bufferMock = mockStatic(Buffer.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            bufferMock.when(() -> Buffer.findByCode("BUF-1")).thenReturn(buffer);
            stubLotQuery(session, List.of(lot));
            stubOrderQuery(session, List.of());
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(customer);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "GRP")).thenReturn("1410-GRP-0001");

            GroupingOrderManagerFactory.GroupingOrderManager manager = buildManager(
                    "{\"validityDelayMinutes\":30,\"groupingWindowMinutes\":120,\"targetBufferCode\":\"BUF-1\"}");
            manager.process(store);

            ArgumentCaptor<Object> persistCaptor = ArgumentCaptor.forClass(Object.class);
            verify(session).persist(persistCaptor.capture());
            GroupingOrder created = (GroupingOrder) persistCaptor.getValue();

            assertSame(store, created.store);
            assertSame(customer, created.customer);
            assertSame(buffer, created.buffer);
            // Slot is truncated to the hour: 10:30 -> 10:00
            assertEquals(LocalDateTime.of(2026, 10, 14, 10, 0), created.requestedStart);
            // End = slot start + grouping window (120 minutes)
            assertEquals(LocalDateTime.of(2026, 10, 14, 12, 0), created.requestedEnd);
            assertEquals("1410-GRP-0001", created.code);
            assertEquals(BaseHandlingOrder.HandlingOrderStatus.OPEN, created.status);
            assertTrue(created.lots.contains(lot));
        }
    }

    @Test
    void process_lotWithNullCustomerId_createsGroupingOrderWithNullCustomer() throws Exception {
        Store store = storeWithId(1L, "S1");
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 10, 14, 12, 0));
        Buffer buffer = new Buffer();
        buffer.id = 3L;
        // Lot dont le client n'a pas d'identifiant : la clé de groupement a un customerId null
        Lot lot = lotWith(10L, null, LocalDateTime.of(2026, 10, 14, 10, 30));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Buffer> bufferMock = mockStatic(Buffer.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            bufferMock.when(() -> Buffer.findByCode("BUF-1")).thenReturn(buffer);
            stubLotQuery(session, List.of(lot));
            Query<GroupingOrder> orderQuery = stubOrderQuery(session, List.of());
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "GRP")).thenReturn("1410-GRP-0002");

            GroupingOrderManagerFactory.GroupingOrderManager manager = buildManager(
                    "{\"validityDelayMinutes\":30,\"groupingWindowMinutes\":120,\"targetBufferCode\":\"BUF-1\"}");
            manager.process(store);

            // customerId null : Customer.findById ne doit pas être appelé
            panacheMock.verify(() -> PanacheEntityBase.findById(any()), never());
            // Le paramètre customer de la requête vaut null
            verify(orderQuery).setParameter(eq("customer"), isNull());

            ArgumentCaptor<Object> persistCaptor = ArgumentCaptor.forClass(Object.class);
            verify(session).persist(persistCaptor.capture());
            GroupingOrder created = (GroupingOrder) persistCaptor.getValue();

            assertNull(created.customer);
            assertSame(store, created.store);
            assertSame(buffer, created.buffer);
            assertEquals(LocalDateTime.of(2026, 10, 14, 10, 0), created.requestedStart);
            assertEquals(BaseHandlingOrder.HandlingOrderStatus.OPEN, created.status);
            assertTrue(created.lots.contains(lot));
        }
    }

    @Test
    void process_existingOpenOrder_reusesItWithoutGeneratingCode() throws Exception {
        Store store = storeWithId(1L, "S1");
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 10, 14, 12, 0));
        Customer customer = new Customer();
        customer.id = 5L;
        Buffer buffer = new Buffer();
        buffer.id = 3L;
        Lot lot = lotWith(10L, 5L, LocalDateTime.of(2026, 10, 14, 10, 30));
        GroupingOrder existing = new GroupingOrder();
        existing.id = 99L;
        existing.store = store;

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Buffer> bufferMock = mockStatic(Buffer.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            bufferMock.when(() -> Buffer.findByCode("BUF-1")).thenReturn(buffer);
            stubLotQuery(session, List.of(lot));
            stubOrderQuery(session, List.of(existing));
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(customer);

            GroupingOrderManagerFactory.GroupingOrderManager manager = buildManager(
                    "{\"validityDelayMinutes\":30,\"groupingWindowMinutes\":120,\"targetBufferCode\":\"BUF-1\"}");
            manager.process(store);

            sequenceMock.verify(() -> HandlingOrderSequence.getNextCode(any(), anyString()), never());
            verify(session).persist(existing);
            assertTrue(existing.lots.contains(lot));
        }
    }

    @Test
    void process_lotsInSameSlot_shareSingleGroupingOrder() throws Exception {
        Store store = storeWithId(1L, "S1");
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 10, 14, 12, 0));
        Customer customer = new Customer();
        customer.id = 5L;
        Buffer buffer = new Buffer();
        buffer.id = 3L;
        // Both delivery dates truncate to the same 10:00 slot
        Lot lot1 = lotWith(10L, 5L, LocalDateTime.of(2026, 10, 14, 10, 30));
        Lot lot2 = lotWith(11L, 5L, LocalDateTime.of(2026, 10, 14, 10, 45));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Buffer> bufferMock = mockStatic(Buffer.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            bufferMock.when(() -> Buffer.findByCode("BUF-1")).thenReturn(buffer);
            stubLotQuery(session, List.of(lot1, lot2));
            stubOrderQuery(session, List.of());
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(customer);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(any(), anyString())).thenReturn("1410-GRP-0001");

            GroupingOrderManagerFactory.GroupingOrderManager manager = buildManager(
                    "{\"validityDelayMinutes\":30,\"groupingWindowMinutes\":120,\"targetBufferCode\":\"BUF-1\"}");
            manager.process(store);

            ArgumentCaptor<Object> persistCaptor = ArgumentCaptor.forClass(Object.class);
            verify(session, times(1)).persist(persistCaptor.capture());
            GroupingOrder created = (GroupingOrder) persistCaptor.getValue();
            assertEquals(2, created.lots.size());
            assertTrue(created.lots.contains(lot1));
            assertTrue(created.lots.contains(lot2));
        }
    }

    @Test
    void process_lotsInDifferentSlots_createSeparateGroupingOrders() throws Exception {
        Store store = storeWithId(1L, "S1");
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 10, 14, 12, 0));
        Customer customer = new Customer();
        customer.id = 5L;
        Buffer buffer = new Buffer();
        buffer.id = 3L;
        Lot lot1 = lotWith(10L, 5L, LocalDateTime.of(2026, 10, 14, 10, 30));
        Lot lot2 = lotWith(11L, 5L, LocalDateTime.of(2026, 10, 14, 11, 15));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Buffer> bufferMock = mockStatic(Buffer.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            bufferMock.when(() -> Buffer.findByCode("BUF-1")).thenReturn(buffer);
            stubLotQuery(session, List.of(lot1, lot2));
            stubOrderQuery(session, List.of());
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(customer);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(any(), anyString())).thenReturn("1410-GRP-0001");

            GroupingOrderManagerFactory.GroupingOrderManager manager = buildManager(
                    "{\"validityDelayMinutes\":30,\"groupingWindowMinutes\":120,\"targetBufferCode\":\"BUF-1\"}");
            manager.process(store);

            verify(session, times(2)).persist(any(GroupingOrder.class));
        }
    }

    @Test
    void process_lotWithNullDeliveryDate_createsOrderWithoutRequestedEnd() throws Exception {
        Store store = storeWithId(1L, "S1");
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 10, 14, 12, 0));
        Customer customer = new Customer();
        customer.id = 5L;
        Buffer buffer = new Buffer();
        buffer.id = 3L;
        Lot lot = lotWith(10L, 5L, null);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Buffer> bufferMock = mockStatic(Buffer.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            bufferMock.when(() -> Buffer.findByCode("BUF-1")).thenReturn(buffer);
            stubLotQuery(session, List.of(lot));
            stubOrderQuery(session, List.of());
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(customer);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(any(), anyString())).thenReturn("1410-GRP-0001");

            GroupingOrderManagerFactory.GroupingOrderManager manager = buildManager(
                    "{\"validityDelayMinutes\":30,\"groupingWindowMinutes\":120,\"targetBufferCode\":\"BUF-1\"}");
            manager.process(store);

            ArgumentCaptor<Object> persistCaptor = ArgumentCaptor.forClass(Object.class);
            verify(session).persist(persistCaptor.capture());
            GroupingOrder created = (GroupingOrder) persistCaptor.getValue();
            assertNull(created.requestedStart);
            assertNull(created.requestedEnd);
        }
    }

    @Test
    void process_withoutTargetBuffer_usesDefaultsAndNullBuffer() throws Exception {
        Store store = storeWithId(1L, "S1");
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 10, 14, 12, 0));
        Customer customer = new Customer();
        customer.id = 5L;
        Lot lot = lotWith(10L, 5L, LocalDateTime.of(2026, 10, 14, 10, 30));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            Query<Lot> lotQuery = stubLotQuery(session, List.of(lot));
            stubOrderQuery(session, List.of());
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(customer);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(any(), anyString())).thenReturn("1410-GRP-0001");

            // No targetBufferCode: buffer is null, validityDelay defaults to 0, window to 60
            GroupingOrderManagerFactory.GroupingOrderManager manager = buildManager("{}");
            manager.process(store);

            // Default validity delay of 0: cutoff equals the fixed time
            verify(lotQuery).setParameter(eq("cutoffDate"), eq(LocalDateTime.of(2026, 10, 14, 12, 0)));

            ArgumentCaptor<Object> persistCaptor = ArgumentCaptor.forClass(Object.class);
            verify(session).persist(persistCaptor.capture());
            GroupingOrder created = (GroupingOrder) persistCaptor.getValue();
            assertNull(created.buffer);
            assertEquals(LocalDateTime.of(2026, 10, 14, 10, 0), created.requestedStart);
            // Default grouping window of 60 minutes
            assertEquals(LocalDateTime.of(2026, 10, 14, 11, 0), created.requestedEnd);
        }
    }
}
