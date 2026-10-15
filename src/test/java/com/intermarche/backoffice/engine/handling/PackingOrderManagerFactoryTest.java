package com.intermarche.backoffice.engine.handling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.HandlingOrderSequence;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.handling.PackingOrder;
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
 * Unit tests for {@link PackingOrderManagerFactory}.
 * Covers factory naming, JSON schema validation of the configuration,
 * the {@link PackingOrderManagerFactory.GroupKey} value object and the
 * {@link PackingOrderManagerFactory.PackingOrderManager} processing logic
 * (orphan lot detection, grouping, handler creation/reuse and persistence).
 */
class PackingOrderManagerFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PackingOrderManagerFactory factory = new PackingOrderManagerFactory();

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
    void getName_returnsPacking() {
        assertEquals("Packing", factory.getName());
    }

    // --------------------------------------------------
    // build - valid configurations
    // --------------------------------------------------

    @Test
    void build_emptySpec_returnsManager() throws Exception {
        var builder = factory.build(spec("{}"));
        assertNotNull(builder);
        assertInstanceOf(PackingOrderManagerFactory.PackingOrderManager.class, builder);
    }

    @Test
    void build_fullSpec_returnsManager() throws Exception {
        var builder = factory.build(spec("""
                {"name":"packing-1","validityDelayMinutes":30,"groupingWindowMinutes":120,"deadlineDelayMinutes":15}
                """));
        assertInstanceOf(PackingOrderManagerFactory.PackingOrderManager.class, builder);
    }

    @Test
    void build_zeroValues_returnsManager() throws Exception {
        var builder = factory.build(spec("""
                {"validityDelayMinutes":0,"groupingWindowMinutes":1,"deadlineDelayMinutes":0}
                """));
        assertInstanceOf(PackingOrderManagerFactory.PackingOrderManager.class, builder);
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
    void build_negativeDeadlineDelay_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"deadlineDelayMinutes\":-5}")));
    }

    @Test
    void build_wrongType_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"validityDelayMinutes\":\"abc\"}")));
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
        Store store = new Store();
        store.id = 7L;
        LocalDateTime slot = LocalDateTime.of(2026, 10, 14, 10, 0);

        var k1 = new PackingOrderManagerFactory.GroupKey(42L, store, slot);
        var k2 = new PackingOrderManagerFactory.GroupKey(42L, store, slot);

        assertEquals(k1, k1);
        assertEquals(k1, k2);
        assertEquals(k1.hashCode(), k2.hashCode());
    }

    @Test
    void groupKey_equals_sameStoreIdDifferentInstance() {
        Store storeA = new Store();
        storeA.id = 7L;
        Store storeB = new Store();
        storeB.id = 7L;
        LocalDateTime slot = LocalDateTime.of(2026, 10, 14, 10, 0);

        var k1 = new PackingOrderManagerFactory.GroupKey(42L, storeA, slot);
        var k2 = new PackingOrderManagerFactory.GroupKey(42L, storeB, slot);

        assertEquals(k1, k2);
    }

    @Test
    void groupKey_notEquals() {
        Store store = new Store();
        store.id = 7L;
        LocalDateTime slot = LocalDateTime.of(2026, 10, 14, 10, 0);
        var key = new PackingOrderManagerFactory.GroupKey(42L, store, slot);

        assertNotEquals(key, new PackingOrderManagerFactory.GroupKey(43L, store, slot));
        assertNotEquals(key, new PackingOrderManagerFactory.GroupKey(42L, store, slot.plusHours(1)));
        assertNotEquals(key, new PackingOrderManagerFactory.GroupKey(null, store, slot));
        assertNotEquals(key, null);
        assertNotEquals(key, "not-a-key");
    }

    @Test
    void groupKey_toString_containsIdentifiers() {
        Store store = new Store();
        store.id = 7L;
        var key = new PackingOrderManagerFactory.GroupKey(42L, store, LocalDateTime.of(2026, 10, 14, 10, 0));

        String s = key.toString();
        assertTrue(s.contains("42"));
        assertTrue(s.contains("7"));
        assertTrue(s.contains("2026-10-14T10:00"));
    }

    @Test
    void groupKey_notEquals_differentStore() {
        Store storeA = new Store();
        storeA.id = 7L;
        Store storeB = new Store();
        storeB.id = 8L;
        LocalDateTime slot = LocalDateTime.of(2026, 10, 14, 10, 0);

        var k1 = new PackingOrderManagerFactory.GroupKey(42L, storeA, slot);
        var k2 = new PackingOrderManagerFactory.GroupKey(42L, storeB, slot);

        assertNotEquals(k1, k2);
    }

    // --------------------------------------------------
    // PackingOrderManager.process
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
        lot.store = new Store();
        lot.store.id = 1L;
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
    private static Query<PackingOrder> stubOrderQuery(Session session, List<PackingOrder> existing) {
        Query<PackingOrder> query = mock(Query.class);
        when(session.createQuery(anyString(), eq(PackingOrder.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(existing);
        return query;
    }

    private PackingOrderManagerFactory.PackingOrderManager buildManager(String json) throws Exception {
        return (PackingOrderManagerFactory.PackingOrderManager) factory.build(mapper.readTree(json));
    }

    @Test
    void process_noOrphanLots_returnsEarlyWithoutPersisting() throws Exception {
        Store store = storeWithId(1L, "S1");
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 10, 14, 12, 0));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            Query<Lot> lotQuery = stubLotQuery(session, List.of());

            PackingOrderManagerFactory.PackingOrderManager manager = buildManager(
                    "{\"validityDelayMinutes\":30,\"groupingWindowMinutes\":60,\"deadlineDelayMinutes\":15}");
            manager.process(store);

            verify(lotQuery).setParameter(eq("store"), eq(store));
            verify(lotQuery).setParameter(eq("cutoffDate"), eq(LocalDateTime.of(2026, 10, 14, 12, 30)));
            verify(session, never()).createQuery(anyString(), eq(PackingOrder.class));
            verify(session, never()).persist(any());
        }
    }

    @Test
    void process_orphanLot_createsNewPackingOrderAndPersistsIt() throws Exception {
        Store store = storeWithId(1L, "S1");
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 10, 14, 12, 0));
        Customer customer = new Customer();
        customer.id = 5L;
        Lot lot = lotWith(10L, 5L, LocalDateTime.of(2026, 10, 14, 10, 30));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubLotQuery(session, List.of(lot));
            stubOrderQuery(session, List.of());
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(customer);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PAK")).thenReturn("1410-PAK-0001");

            PackingOrderManagerFactory.PackingOrderManager manager = buildManager(
                    "{\"validityDelayMinutes\":30,\"groupingWindowMinutes\":60,\"deadlineDelayMinutes\":15}");
            manager.process(store);

            ArgumentCaptor<Object> persistCaptor = ArgumentCaptor.forClass(Object.class);
            verify(session).persist(persistCaptor.capture());
            PackingOrder created = (PackingOrder) persistCaptor.getValue();

            assertSame(store, created.store);
            assertSame(customer, created.customer);
            // Slot is truncated to the hour: 10:30 -> 10:00
            assertEquals(LocalDateTime.of(2026, 10, 14, 10, 0), created.requestedStart);
            // End = slot start + grouping window (60 minutes)
            assertEquals(LocalDateTime.of(2026, 10, 14, 11, 0), created.requestedEnd);
            // Deadline = slot start - deadline delay (15 minutes)
            assertEquals(LocalDateTime.of(2026, 10, 14, 9, 45), created.deadLine);
            assertEquals("1410-PAK-0001", created.code);
            assertEquals(BaseHandlingOrder.HandlingOrderStatus.OPEN, created.status);
            assertTrue(created.lots.contains(lot));
            assertEquals(Lot.LotStatus.PLANNED_TO_BE_PACKED, lot.status);
        }
    }

    @Test
    void process_lotWithNullDeliveryDate_slotStartIsNull() throws Exception {
        Store store = storeWithId(1L, "S1");
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 10, 14, 12, 0));
        Customer customer = new Customer();
        customer.id = 5L;
        Lot lot = lotWith(10L, 5L, null);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubLotQuery(session, List.of(lot));
            Query<PackingOrder> orderQuery = stubOrderQuery(session, List.of());
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(customer);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PAK")).thenReturn("1410-PAK-0002");

            PackingOrderManagerFactory.PackingOrderManager manager = buildManager(
                    "{\"validityDelayMinutes\":30,\"groupingWindowMinutes\":60,\"deadlineDelayMinutes\":15}");
            manager.process(store);

            // No delivery date -> no slot: requestedStart stays null, end/deadline are not computed
            verify(orderQuery).setParameter(eq("requestedStart"), isNull());

            ArgumentCaptor<Object> persistCaptor = ArgumentCaptor.forClass(Object.class);
            verify(session).persist(persistCaptor.capture());
            PackingOrder created = (PackingOrder) persistCaptor.getValue();

            assertNull(created.requestedStart);
            assertNull(created.requestedEnd);
            assertNull(created.deadLine);
            assertSame(customer, created.customer);
            assertTrue(created.lots.contains(lot));
        }
    }

    @Test
    void process_lotWithNullCustomerId_createsPackingOrderWithNullCustomer() throws Exception {
        Store store = storeWithId(1L, "S1");
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 10, 14, 12, 0));
        Lot lot = lotWith(10L, null, LocalDateTime.of(2026, 10, 14, 10, 30));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubLotQuery(session, List.of(lot));
            Query<PackingOrder> orderQuery = stubOrderQuery(session, List.of());
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PAK")).thenReturn("1410-PAK-0003");

            PackingOrderManagerFactory.PackingOrderManager manager = buildManager(
                    "{\"validityDelayMinutes\":30,\"groupingWindowMinutes\":60,\"deadlineDelayMinutes\":15}");
            manager.process(store);

            // customerId null : Customer.findById must not be called
            panacheMock.verify(() -> PanacheEntityBase.findById(any()), never());
            verify(orderQuery).setParameter(eq("customer"), isNull());

            ArgumentCaptor<Object> persistCaptor = ArgumentCaptor.forClass(Object.class);
            verify(session).persist(persistCaptor.capture());
            PackingOrder created = (PackingOrder) persistCaptor.getValue();

            assertNull(created.customer);
            assertSame(store, created.store);
            assertEquals(LocalDateTime.of(2026, 10, 14, 10, 0), created.requestedStart);
            assertTrue(created.lots.contains(lot));
        }
    }

    @Test
    void process_existingOpenOrder_reusesItWithoutGeneratingCode() throws Exception {
        Store store = storeWithId(1L, "S1");
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 10, 14, 12, 0));
        Customer customer = new Customer();
        customer.id = 5L;
        Lot lot = lotWith(10L, 5L, LocalDateTime.of(2026, 10, 14, 10, 30));
        PackingOrder existing = new PackingOrder();
        existing.id = 99L;
        existing.store = store;

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubLotQuery(session, List.of(lot));
            stubOrderQuery(session, List.of(existing));
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(customer);

            PackingOrderManagerFactory.PackingOrderManager manager = buildManager(
                    "{\"validityDelayMinutes\":30,\"groupingWindowMinutes\":60,\"deadlineDelayMinutes\":15}");
            manager.process(store);

            sequenceMock.verify(() -> HandlingOrderSequence.getNextCode(any(), anyString()), never());
            verify(session).persist(existing);
            assertTrue(existing.lots.contains(lot));
            assertEquals(Lot.LotStatus.PLANNED_TO_BE_PACKED, lot.status);
        }
    }
}
