package com.intermarche.backoffice.engine.handling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.HandlingOrderSequence;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProductCollectingOrderManagerFactory.ProductCollectingOrderManager}.
 * <p>
 * Covers candidate lot retrieval (JPQL parameters), grouping by customer/store/building/time slot,
 * the multi-dimensional bin-packing logic (Quantity/Weight/Volume min &amp; max constraints),
 * order creation, persistence and lot status updates.
 */
class ProductCollectingOrderManagerTest {

    private static final String NO_CONSTRAINTS = "{\"buildingCode\":\"B1\",\"eans\":[\"EAN-1\"]}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProductCollectingOrderManagerFactory factory = new ProductCollectingOrderManagerFactory();

    @AfterEach
    void tearDown() {
        DateTimeProvider.clear();
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private ProductCollectingOrderManagerFactory.ProductCollectingOrderManager buildManager(String json) throws Exception {
        return (ProductCollectingOrderManagerFactory.ProductCollectingOrderManager) factory.build(mapper.readTree(json));
    }

    private static Store storeWithId(Long id, String code) {
        Store store = new Store();
        store.id = id;
        store.code = code;
        return store;
    }

    private static Building building(String code) {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = code;
        return building;
    }

    private static Lot lotWith(Long id, Long customerId, Long storeId, Building building, LocalDateTime deliveryDate) {
        Lot lot = new Lot();
        lot.id = id;
        lot.status = Lot.LotStatus.PENDING;
        Customer customer = new Customer();
        customer.id = customerId;
        lot.customer = customer;
        Store lotStore = new Store();
        lotStore.id = storeId;
        lot.store = lotStore;
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.id = id + 1000;
        chunk.deliveryDate = deliveryDate;
        chunk.building = building;
        lot.chunk = chunk;
        return lot;
    }

    private static LotLine lineWith(String qty, String refWeight, String refVolume) {
        Product product = new Product();
        product.ean = "EAN-1";
        product.referenceWeight = refWeight != null ? new BigDecimal(refWeight) : null;
        product.referenceVolume = refVolume != null ? new BigDecimal(refVolume) : null;
        FulfillmentLine fulfillmentLine = new FulfillmentLine();
        fulfillmentLine.requestedQuantity = new BigDecimal(qty);
        fulfillmentLine.product = product;
        LotLine line = new LotLine();
        line.fulfillmentLine = fulfillmentLine;
        return line;
    }

    @SuppressWarnings("unchecked")
    private static Query<Lot> stubLotQuery(Session session, List<Lot> lots) {
        Query<Lot> query = mock(Query.class);
        when(session.createQuery(anyString(), eq(Lot.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(lots);
        return query;
    }

    private static List<ProductCollectingOrder> capturePersistedOrders(Session session, int expectedTimes) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(session, times(expectedTimes)).persist(captor.capture());
        return captor.getAllValues().stream()
                .map(ProductCollectingOrder.class::cast)
                .collect(Collectors.toList());
    }

    // --------------------------------------------------
    // process - candidate retrieval
    // --------------------------------------------------

    @Test
    void process_noCandidateLots_returnsEarlyWithoutPersisting() throws Exception {
        Store store = storeWithId(1L, "S1");
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 10, 14, 12, 0));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            Query<Lot> lotQuery = stubLotQuery(session, List.of());

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager =
                    buildManager("{\"buildingCode\":\"B1\",\"eans\":[\"EAN-1\"],\"validityDelayMinutes\":30}");
            manager.process(store);

            verify(lotQuery).setParameter(eq("store"), eq(store));
            verify(lotQuery).setParameter(eq("cutoffDate"), eq(LocalDateTime.of(2026, 10, 14, 12, 30)));
            verify(lotQuery).setParameter(eq("bCode"), eq("B1"));
            verify(lotQuery).setParameter(eq("eans"), eq(Set.of("EAN-1")));
            verify(session, never()).persist(any());
            verify(session, never()).flush();
        }
    }

    // --------------------------------------------------
    // process - order creation & persistence
    // --------------------------------------------------

    @Test
    void process_singleLotMeetingMinRequirements_persistsOrder() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        Lot lot = lotWith(10L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 30));
        lot.addLine(lineWith("5", "0.5", "1.0"));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO")).thenReturn("1410-PRO-0001");
            stubLotQuery(session, List.of(lot));

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(
                    "{\"buildingCode\":\"B1\",\"eans\":[\"EAN-1\"],\"quantityMin\":1,\"quantityMax\":100,\"groupingWindowMinutes\":60}");
            manager.process(store);

            List<ProductCollectingOrder> persisted = capturePersistedOrders(session, 1);
            ProductCollectingOrder created = persisted.get(0);

            assertSame(store, created.store);
            assertSame(building, created.building);
            assertEquals(List.of("EAN-1"), created.eans);
            // Slot: 10:30 -> truncated to 10:00 -> +1 hour -> 11:00
            assertEquals(LocalDateTime.of(2026, 10, 14, 11, 0), created.requestedStart);
            // End = slot start + grouping window (60 minutes)
            assertEquals(LocalDateTime.of(2026, 10, 14, 12, 0), created.requestedEnd);
            assertEquals("1410-PRO-0001", created.code);
            assertEquals(BaseHandlingOrder.HandlingOrderStatus.OPEN, created.status);
            assertTrue(created.lots.contains(lot));
            // addLot sets PLANNED_TO_BE_PACKED, then setStatusToLot overrides it
            assertEquals(Lot.LotStatus.PLANNED_TO_BE_COLLECTED, lot.status);
            verify(session).flush();
        }
    }

    @Test
    void process_twoLotsFittingCapacity_shareSingleOrder() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        Lot lot1 = lotWith(10L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 30));
        lot1.addLine(lineWith("5", "0.5", "1.0"));
        Lot lot2 = lotWith(11L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 45));
        lot2.addLine(lineWith("5", "0.5", "1.0"));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO")).thenReturn("1410-PRO-0001");
            stubLotQuery(session, List.of(lot1, lot2));

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(
                    "{\"buildingCode\":\"B1\",\"eans\":[\"EAN-1\"],\"quantityMin\":1,\"quantityMax\":100}");
            manager.process(store);

            List<ProductCollectingOrder> persisted = capturePersistedOrders(session, 1);
            ProductCollectingOrder created = persisted.get(0);
            assertEquals(2, created.lots.size());
            assertTrue(created.lots.contains(lot1));
            assertTrue(created.lots.contains(lot2));
        }
    }

    @Test
    void process_capacityExceededByQuantity_createsSecondOrder() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        Lot lot1 = lotWith(10L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 30));
        lot1.addLine(lineWith("8", null, null));
        Lot lot2 = lotWith(11L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 45));
        lot2.addLine(lineWith("5", null, null));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO"))
                    .thenReturn("1410-PRO-0001", "1410-PRO-0002");
            stubLotQuery(session, List.of(lot1, lot2));

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(
                    "{\"buildingCode\":\"B1\",\"eans\":[\"EAN-1\"],\"quantityMin\":1,\"quantityMax\":10}");
            manager.process(store);

            List<ProductCollectingOrder> persisted = capturePersistedOrders(session, 2);
            // 8 + 5 = 13 > 10: the second lot overflows into a new order
            assertEquals(1, persisted.get(0).lots.size());
            assertTrue(persisted.get(0).lots.contains(lot1));
            assertEquals(1, persisted.get(1).lots.size());
            assertTrue(persisted.get(1).lots.contains(lot2));
            assertEquals("1410-PRO-0001", persisted.get(0).code);
            assertEquals("1410-PRO-0002", persisted.get(1).code);
        }
    }

    @Test
    void process_capacityExceededByWeight_createsSecondOrder() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        Lot lot1 = lotWith(10L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 30));
        lot1.addLine(lineWith("1", "8.0", null));
        Lot lot2 = lotWith(11L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 40));
        lot2.addLine(lineWith("1", "1.0", null));
        Lot lot3 = lotWith(12L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 50));
        lot3.addLine(lineWith("1", "5.0", null));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO"))
                    .thenReturn("1410-PRO-0001", "1410-PRO-0002");
            stubLotQuery(session, List.of(lot1, lot2, lot3));

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(
                    "{\"buildingCode\":\"B1\",\"eans\":[\"EAN-1\"],\"weightMax\":10.0}");
            manager.process(store);

            List<ProductCollectingOrder> persisted = capturePersistedOrders(session, 2);
            // Weights: 8 + 1 = 9 <= 10 (fits), then 9 + 5 = 14 > 10 (overflow)
            assertEquals(2, persisted.get(0).lots.size());
            assertTrue(persisted.get(0).lots.contains(lot1));
            assertTrue(persisted.get(0).lots.contains(lot2));
            assertEquals(1, persisted.get(1).lots.size());
            assertTrue(persisted.get(1).lots.contains(lot3));
        }
    }

    @Test
    void process_capacityExceededByVolume_createsSecondOrder() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        Lot lot1 = lotWith(10L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 30));
        lot1.addLine(lineWith("1", null, "8.0"));
        Lot lot2 = lotWith(11L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 40));
        lot2.addLine(lineWith("1", null, "1.0"));
        Lot lot3 = lotWith(12L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 50));
        lot3.addLine(lineWith("1", null, "5.0"));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO"))
                    .thenReturn("1410-PRO-0001", "1410-PRO-0002");
            stubLotQuery(session, List.of(lot1, lot2, lot3));

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(
                    "{\"buildingCode\":\"B1\",\"eans\":[\"EAN-1\"],\"volumeMax\":10.0}");
            manager.process(store);

            List<ProductCollectingOrder> persisted = capturePersistedOrders(session, 2);
            // Volumes: 8 + 1 = 9 <= 10 (fits), then 9 + 5 = 14 > 10 (overflow)
            assertEquals(2, persisted.get(0).lots.size());
            assertTrue(persisted.get(0).lots.contains(lot1));
            assertTrue(persisted.get(0).lots.contains(lot2));
            assertEquals(1, persisted.get(1).lots.size());
            assertTrue(persisted.get(1).lots.contains(lot3));
        }
    }

    // --------------------------------------------------
    // process - min requirements not met (discard)
    // --------------------------------------------------

    @Test
    void process_belowQuantityMin_discardsOrder() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        Lot lot = lotWith(10L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 30));
        lot.addLine(lineWith("5", "0.5", "1.0"));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO")).thenReturn("1410-PRO-0001");
            stubLotQuery(session, List.of(lot));

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(
                    "{\"buildingCode\":\"B1\",\"eans\":[\"EAN-1\"],\"quantityMin\":10,\"quantityMax\":100}");
            manager.process(store);

            // 5 < quantityMin 10: the order is discarded, never persisted
            verify(session, never()).persist(any());
            // The lot status is still updated and flushed
            assertEquals(Lot.LotStatus.PLANNED_TO_BE_COLLECTED, lot.status);
            verify(session).flush();
        }
    }

    @Test
    void process_belowWeightMin_discardsOrder() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        Lot lot = lotWith(10L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 30));
        lot.addLine(lineWith("1", "5.0", null));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO")).thenReturn("1410-PRO-0001");
            stubLotQuery(session, List.of(lot));

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(
                    "{\"buildingCode\":\"B1\",\"eans\":[\"EAN-1\"],\"weightMin\":10.0,\"weightMax\":100.0}");
            manager.process(store);

            // Weight 5.0 < weightMin 10.0: the order is discarded
            verify(session, never()).persist(any());
            assertEquals(Lot.LotStatus.PLANNED_TO_BE_COLLECTED, lot.status);
            verify(session).flush();
        }
    }

    @Test
    void process_belowVolumeMin_discardsOrder() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        Lot lot = lotWith(10L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 30));
        lot.addLine(lineWith("1", null, "5.0"));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO")).thenReturn("1410-PRO-0001");
            stubLotQuery(session, List.of(lot));

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(
                    "{\"buildingCode\":\"B1\",\"eans\":[\"EAN-1\"],\"volumeMin\":10.0,\"volumeMax\":100.0}");
            manager.process(store);

            // Volume 5.0 < volumeMin 10.0: the order is discarded
            verify(session, never()).persist(any());
            assertEquals(Lot.LotStatus.PLANNED_TO_BE_COLLECTED, lot.status);
            verify(session).flush();
        }
    }

    // --------------------------------------------------
    // process - metrics edge cases
    // --------------------------------------------------

    @Test
    void process_noConstraints_groupsAllLotsInSingleOrder() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        Lot lot1 = lotWith(10L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 30));
        lot1.addLine(lineWith("5", "0.5", "1.0"));
        Lot lot2 = lotWith(11L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 45));
        lot2.addLine(lineWith("5", "0.5", "1.0"));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO")).thenReturn("1410-PRO-0001");
            stubLotQuery(session, List.of(lot1, lot2));

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(NO_CONSTRAINTS);
            manager.process(store);

            // No max constraint: both lots fit in a single order; no min constraint: it is persisted
            List<ProductCollectingOrder> persisted = capturePersistedOrders(session, 1);
            assertEquals(2, persisted.get(0).lots.size());
            assertTrue(persisted.get(0).lots.contains(lot1));
            assertTrue(persisted.get(0).lots.contains(lot2));
        }
    }

    @Test
    void process_quantityMaxOnlyWithoutMin_persistsOrder() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        Lot lot = lotWith(10L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 30));
        lot.addLine(lineWith("5", "0.5", "1.0"));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO")).thenReturn("1410-PRO-0001");
            stubLotQuery(session, List.of(lot));

            // quantityMax without quantityMin: the dimension is active, min defaults to 0 (always met)
            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(
                    "{\"buildingCode\":\"B1\",\"eans\":[\"EAN-1\"],\"quantityMax\":10}");
            manager.process(store);

            List<ProductCollectingOrder> persisted = capturePersistedOrders(session, 1);
            assertTrue(persisted.get(0).lots.contains(lot));
        }
    }

    @Test
    void process_lotWithNullLines_zeroMetricsPersisted() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        Lot lot = lotWith(10L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 30));
        lot.lines = null;

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO")).thenReturn("1410-PRO-0001");
            stubLotQuery(session, List.of(lot));

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(NO_CONSTRAINTS);
            manager.process(store);

            List<ProductCollectingOrder> persisted = capturePersistedOrders(session, 1);
            assertTrue(persisted.get(0).lots.contains(lot));
        }
    }

    @Test
    void process_lotLineWithNullFulfillmentLine_lineSkipped() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        Lot lot = lotWith(10L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 30));
        LotLine line = new LotLine();
        line.fulfillmentLine = null;
        lot.addLine(line);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO")).thenReturn("1410-PRO-0001");
            stubLotQuery(session, List.of(lot));

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(NO_CONSTRAINTS);
            manager.process(store);

            // The line is skipped: metrics stay at zero, order is still persisted (no min constraint)
            List<ProductCollectingOrder> persisted = capturePersistedOrders(session, 1);
            assertTrue(persisted.get(0).lots.contains(lot));
        }
    }

    @Test
    void process_lotLineWithNullProduct_lineSkipped() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        Lot lot = lotWith(10L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 30));
        FulfillmentLine fulfillmentLine = new FulfillmentLine();
        fulfillmentLine.requestedQuantity = new BigDecimal("2");
        fulfillmentLine.product = null;
        LotLine line = new LotLine();
        line.fulfillmentLine = fulfillmentLine;
        lot.addLine(line);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO")).thenReturn("1410-PRO-0001");
            stubLotQuery(session, List.of(lot));

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(NO_CONSTRAINTS);
            manager.process(store);

            List<ProductCollectingOrder> persisted = capturePersistedOrders(session, 1);
            assertTrue(persisted.get(0).lots.contains(lot));
        }
    }

    @Test
    void process_productWithNullReferenceDimensions_zeroWeightAndVolume() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        Lot lot = lotWith(10L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 30));
        lot.addLine(lineWith("2", null, null));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO")).thenReturn("1410-PRO-0001");
            stubLotQuery(session, List.of(lot));

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(NO_CONSTRAINTS);
            manager.process(store);

            // Null reference weight/volume contribute 0.0 to the metrics
            List<ProductCollectingOrder> persisted = capturePersistedOrders(session, 1);
            assertTrue(persisted.get(0).lots.contains(lot));
        }
    }

    // --------------------------------------------------
    // process - grouping & sorting
    // --------------------------------------------------

    @Test
    void process_lotWithNullDeliveryDate_noTimeWindow() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        Lot lot = lotWith(10L, 5L, 1L, building, null);
        lot.addLine(lineWith("2", "0.5", "1.0"));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO")).thenReturn("1410-PRO-0001");
            stubLotQuery(session, List.of(lot));

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(NO_CONSTRAINTS);
            manager.process(store);

            // Null delivery date: no slot, so no requested start/end window
            List<ProductCollectingOrder> persisted = capturePersistedOrders(session, 1);
            assertNull(persisted.get(0).requestedStart);
            assertNull(persisted.get(0).requestedEnd);
        }
    }

    @Test
    void process_lotsInDifferentSlots_createSeparateOrders() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        Lot lot1 = lotWith(10L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 30));
        lot1.addLine(lineWith("2", "0.5", "1.0"));
        Lot lot2 = lotWith(11L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 11, 15));
        lot2.addLine(lineWith("2", "0.5", "1.0"));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO"))
                    .thenReturn("1410-PRO-0001", "1410-PRO-0002");
            stubLotQuery(session, List.of(lot1, lot2));

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(NO_CONSTRAINTS);
            manager.process(store);

            // 10:30 -> slot 11:00, 11:15 -> slot 12:00: two groups, two orders
            List<ProductCollectingOrder> persisted = capturePersistedOrders(session, 2);
            Set<LocalDateTime> starts = persisted.stream()
                    .map(order -> order.requestedStart)
                    .collect(Collectors.toSet());
            assertEquals(Set.of(LocalDateTime.of(2026, 10, 14, 11, 0), LocalDateTime.of(2026, 10, 14, 12, 0)), starts);
            verify(session, times(2)).flush();
        }
    }

    @Test
    void process_lotsSortedByIdBeforeProcessing() throws Exception {
        Store store = storeWithId(1L, "S1");
        Building building = building("B1");
        // Provided out of order: id 11 first, then id 10 (same slot: both truncate to 11:00)
        Lot lot11 = lotWith(11L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 30));
        lot11.addLine(lineWith("8", null, null));
        Lot lot10 = lotWith(10L, 5L, 1L, building, LocalDateTime.of(2026, 10, 14, 10, 45));
        lot10.addLine(lineWith("5", null, null));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<HandlingOrderSequence> sequenceMock = mockStatic(HandlingOrderSequence.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            sequenceMock.when(() -> HandlingOrderSequence.getNextCode(store, "PRO"))
                    .thenReturn("1410-PRO-0001", "1410-PRO-0002");
            stubLotQuery(session, List.of(lot11, lot10));

            ProductCollectingOrderManagerFactory.ProductCollectingOrderManager manager = buildManager(
                    "{\"buildingCode\":\"B1\",\"eans\":[\"EAN-1\"],\"quantityMin\":1,\"quantityMax\":10}");
            manager.process(store);

            List<ProductCollectingOrder> persisted = capturePersistedOrders(session, 2);
            // Sorted by id: lot10 (qty 5) opens the first order, lot11 (qty 8) overflows (5 + 8 > 10)
            assertEquals(1, persisted.get(0).lots.size());
            assertTrue(persisted.get(0).lots.contains(lot10));
            assertEquals(1, persisted.get(1).lots.size());
            assertTrue(persisted.get(1).lots.contains(lot11));
        }
    }
}
