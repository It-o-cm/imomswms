package com.intermarche.backoffice.engine;

import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import com.intermarche.backoffice.domain.wms.handling.PackingOrder;
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderScheduler} backward scheduling.
 * Panache statics ({@code PackingOrder.find}, {@code GroupingOrder.find}, {@code CollectingOrder.find})
 * are stubbed per JPQL query; no Quarkus, no database.
 */
class OrderSchedulerTest {

    private static final String PACKING_JPQL =
            "SELECT DISTINCT o FROM PackingOrder o JOIN FETCH o.lots WHERE o.store = ?1 AND o.status = ?2";
    private static final String GROUPING_JPQL =
            "SELECT DISTINCT o FROM GroupingOrder o JOIN FETCH o.lots WHERE o.store = ?1 AND o.status = ?2";
    private static final String COLLECTING_JPQL =
            "SELECT DISTINCT o FROM CollectingOrder o JOIN FETCH o.lots WHERE o.store = ?1 AND o.status = ?2 AND TYPE(o) <> PackingOrder";

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 15, 12, 0);

    private OrderScheduler scheduler;
    private Store store;

    @BeforeEach
    void setUp() {
        scheduler = new OrderScheduler();
        store = new Store();
        store.id = 1L;
        store.code = "STORE-1";
    }

    private Lot newLot(long id) {
        Lot lot = new Lot();
        lot.id = id;
        return lot;
    }

    private PackingOrder newPackingOrder(long id, LocalDateTime start, LocalDateTime end, LocalDateTime deadLine, Lot... lots) {
        PackingOrder order = new PackingOrder();
        order.id = id;
        order.store = store;
        order.code = "PACK-" + id;
        order.requestedStart = start;
        order.requestedEnd = end;
        order.deadLine = deadLine;
        for (Lot lot : lots) {
            order.addLot(lot);
        }
        return order;
    }

    private GroupingOrder newGroupingOrder(long id, LocalDateTime start, LocalDateTime end, Lot... lots) {
        GroupingOrder order = new GroupingOrder();
        order.id = id;
        order.store = store;
        order.code = "GRP-" + id;
        order.requestedStart = start;
        order.requestedEnd = end;
        for (Lot lot : lots) {
            order.addLot(lot);
        }
        return order;
    }

    private ProductCollectingOrder newCollectingOrder(long id, LocalDateTime start, LocalDateTime end, Lot... lots) {
        ProductCollectingOrder order = new ProductCollectingOrder();
        order.id = id;
        order.store = store;
        order.code = "PRO-" + id;
        order.requestedStart = start;
        order.requestedEnd = end;
        for (Lot lot : lots) {
            order.addLot(lot);
        }
        return order;
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleOpenedOrders_noPackingOrders_returnsEarly() {
        PanacheQuery<PackingOrder> packingQuery = mock(PanacheQuery.class);
        when(packingQuery.list()).thenReturn(List.of());

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq(PACKING_JPQL), any(Object[].class)))
                    .thenReturn(packingQuery);

            scheduler.scheduleOpenedOrders(store);

            panacheMock.verify(() -> PanacheEntityBase.find(eq(PACKING_JPQL), any(Object[].class)), times(1));
            panacheMock.verify(() -> PanacheEntityBase.find(eq(GROUPING_JPQL), any(Object[].class)), never());
            panacheMock.verify(() -> PanacheEntityBase.find(eq(COLLECTING_JPQL), any(Object[].class)), never());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleOpenedOrders_packingOrderWithoutDeadline_isSkipped() {
        PackingOrder packer = newPackingOrder(1L, T0.minusHours(3), T0.minusHours(1), null, newLot(1L));
        PanacheQuery<PackingOrder> packingQuery = mock(PanacheQuery.class);
        when(packingQuery.list()).thenReturn(List.of(packer));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq(PACKING_JPQL), any(Object[].class)))
                    .thenReturn(packingQuery);

            scheduler.scheduleOpenedOrders(store);

            // Skipped: no schedule change, status stays OPEN, grouping phase never runs
            assertEquals(T0.minusHours(3), packer.requestedStart);
            assertEquals(T0.minusHours(1), packer.requestedEnd);
            assertEquals(BaseHandlingOrder.HandlingOrderStatus.OPEN, packer.status);
            panacheMock.verify(() -> PanacheEntityBase.find(eq(GROUPING_JPQL), any(Object[].class)), never());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleOpenedOrders_fullFlow_schedulesAllPhasesBackward() {
        Lot lot1 = newLot(1L);
        Lot lot2 = newLot(2L);
        // Packing: duration 2h, deadline T0 -> theoretical start T0-2h, no-advance keeps T0-3h
        PackingOrder packer = newPackingOrder(1L, T0.minusHours(3), T0.minusHours(1), T0, lot1, lot2);
        // Grouping: lot2 (1h), lot1 (2h)
        GroupingOrder grouper1 = newGroupingOrder(1L, T0.minusHours(9), T0.minusHours(7), lot1);
        GroupingOrder grouper2 = newGroupingOrder(2L, T0.minusHours(6), T0.minusHours(5), lot2);
        // Collecting: lot1 (1h)
        ProductCollectingOrder collector = newCollectingOrder(1L, T0.minusHours(12), T0.minusHours(11), lot1);

        PanacheQuery<PackingOrder> packingQuery = mock(PanacheQuery.class);
        when(packingQuery.list()).thenReturn(List.of(packer));
        PanacheQuery<GroupingOrder> groupingQuery = mock(PanacheQuery.class);
        when(groupingQuery.list()).thenReturn(List.of(grouper1, grouper2));
        PanacheQuery<ProductCollectingOrder> collectingQuery = mock(PanacheQuery.class);
        when(collectingQuery.list()).thenReturn(List.of(collector));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq(PACKING_JPQL), any(Object[].class)))
                    .thenReturn(packingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq(GROUPING_JPQL), any(Object[].class)))
                    .thenReturn(groupingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq(COLLECTING_JPQL), any(Object[].class)))
                    .thenReturn(collectingQuery);

            scheduler.scheduleOpenedOrders(store);

            // Packing phase: no-advance keeps requestedStart T0-3h, end = start + 2h
            assertEquals(T0.minusHours(3), packer.requestedStart);
            assertEquals(T0.minusHours(1), packer.requestedEnd);
            assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, packer.status);

            // Grouping phase (sorted desc by theoretical start: grouper2 T0-4h first, then grouper1 T0-5h)
            assertEquals(T0.minusHours(6), grouper2.requestedStart);
            assertEquals(T0.minusHours(5), grouper2.requestedEnd);
            assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, grouper2.status);
            assertEquals(T0.minusHours(9), grouper1.requestedStart);
            assertEquals(T0.minusHours(7), grouper1.requestedEnd);
            assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, grouper1.status);

            // Collecting phase: lot1 available at T0-9h -> theoretical T0-10h, no-advance keeps T0-12h
            assertEquals(T0.minusHours(12), collector.requestedStart);
            assertEquals(T0.minusHours(11), collector.requestedEnd);
            assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, collector.status);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleOpenedOrders_noAdvanceRule_keepsRequestedStart() {
        Lot lot1 = newLot(1L);
        // Deadline T0, duration 2h -> theoretical start T0-2h which is AFTER requestedStart T0-3h
        PackingOrder packer = newPackingOrder(1L, T0.minusHours(3), T0.minusHours(1), T0, lot1);
        PanacheQuery<PackingOrder> packingQuery = mock(PanacheQuery.class);
        when(packingQuery.list()).thenReturn(List.of(packer));
        PanacheQuery<GroupingOrder> groupingQuery = mock(PanacheQuery.class);
        when(groupingQuery.list()).thenReturn(List.of());
        PanacheQuery<ProductCollectingOrder> collectingQuery = mock(PanacheQuery.class);
        when(collectingQuery.list()).thenReturn(List.of());

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq(PACKING_JPQL), any(Object[].class)))
                    .thenReturn(packingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq(GROUPING_JPQL), any(Object[].class)))
                    .thenReturn(groupingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq(COLLECTING_JPQL), any(Object[].class)))
                    .thenReturn(collectingQuery);

            scheduler.scheduleOpenedOrders(store);

            // No-advance: start stays at requestedStart, end = start + original duration
            assertEquals(T0.minusHours(3), packer.requestedStart);
            assertEquals(T0.minusHours(1), packer.requestedEnd);
            assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, packer.status);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleOpenedOrders_theoreticalStartBeforeRequestedStart_movesStartBack() {
        Lot lot1 = newLot(1L);
        // Deadline T0, duration 2h -> theoretical start T0-2h which is BEFORE requestedStart T0-1h
        PackingOrder packer = newPackingOrder(1L, T0.minusHours(1), T0.plusHours(1), T0, lot1);
        PanacheQuery<PackingOrder> packingQuery = mock(PanacheQuery.class);
        when(packingQuery.list()).thenReturn(List.of(packer));
        PanacheQuery<GroupingOrder> groupingQuery = mock(PanacheQuery.class);
        when(groupingQuery.list()).thenReturn(List.of());
        PanacheQuery<ProductCollectingOrder> collectingQuery = mock(PanacheQuery.class);
        when(collectingQuery.list()).thenReturn(List.of());

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq(PACKING_JPQL), any(Object[].class)))
                    .thenReturn(packingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq(GROUPING_JPQL), any(Object[].class)))
                    .thenReturn(groupingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq(COLLECTING_JPQL), any(Object[].class)))
                    .thenReturn(collectingQuery);

            scheduler.scheduleOpenedOrders(store);

            // Pushed back in time to the theoretical start, duration preserved
            assertEquals(T0.minusHours(2), packer.requestedStart);
            assertEquals(T0, packer.requestedEnd);
            assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, packer.status);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleOpenedOrders_groupingConflict_overlapIsKept() {
        Lot lot1 = newLot(1L);
        Lot lot2 = newLot(2L);
        PackingOrder packer = newPackingOrder(1L, T0.minusHours(3), T0.minusHours(1), T0, lot1, lot2);
        // grouper2 (1h): theoretical T0-4h, processed first -> finalStart T0-4h
        GroupingOrder grouper2 = newGroupingOrder(2L, T0.minusHours(0).minusMinutes(30), T0.plusMinutes(30), lot2);
        // grouper1 (2h): theoretical T0-5h, processed second -> maxAllowed T0-6h -> conflict, keeps T0-5h
        GroupingOrder grouper1 = newGroupingOrder(1L, T0.minusHours(1).minusMinutes(30), T0.plusMinutes(30), lot1);

        PanacheQuery<PackingOrder> packingQuery = mock(PanacheQuery.class);
        when(packingQuery.list()).thenReturn(List.of(packer));
        PanacheQuery<GroupingOrder> groupingQuery = mock(PanacheQuery.class);
        when(groupingQuery.list()).thenReturn(List.of(grouper1, grouper2));
        PanacheQuery<ProductCollectingOrder> collectingQuery = mock(PanacheQuery.class);
        when(collectingQuery.list()).thenReturn(List.of());

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq(PACKING_JPQL), any(Object[].class)))
                    .thenReturn(packingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq(GROUPING_JPQL), any(Object[].class)))
                    .thenReturn(groupingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq(COLLECTING_JPQL), any(Object[].class)))
                    .thenReturn(collectingQuery);

            scheduler.scheduleOpenedOrders(store);

            // grouper2 processed first (later theoretical start)
            assertEquals(T0.minusHours(4), grouper2.requestedStart);
            assertEquals(T0.minusHours(3), grouper2.requestedEnd);
            // grouper1 cannot fit sequentially: overlap kept at its no-advance start
            assertEquals(T0.minusHours(5), grouper1.requestedStart);
            assertEquals(T0.minusHours(3), grouper1.requestedEnd);
            assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, grouper1.status);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleOpenedOrders_groupingOrderWithoutSharedLot_isNotScheduled() {
        Lot lot1 = newLot(1L);
        Lot lotOther = newLot(99L);
        PackingOrder packer = newPackingOrder(1L, T0.minusHours(3), T0.minusHours(1), T0, lot1);
        GroupingOrder unaffected = newGroupingOrder(1L, T0.minusHours(9), T0.minusHours(7), lotOther);

        PanacheQuery<PackingOrder> packingQuery = mock(PanacheQuery.class);
        when(packingQuery.list()).thenReturn(List.of(packer));
        PanacheQuery<GroupingOrder> groupingQuery = mock(PanacheQuery.class);
        when(groupingQuery.list()).thenReturn(List.of(unaffected));
        PanacheQuery<ProductCollectingOrder> collectingQuery = mock(PanacheQuery.class);
        when(collectingQuery.list()).thenReturn(List.of());

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq(PACKING_JPQL), any(Object[].class)))
                    .thenReturn(packingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq(GROUPING_JPQL), any(Object[].class)))
                    .thenReturn(groupingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq(COLLECTING_JPQL), any(Object[].class)))
                    .thenReturn(collectingQuery);

            scheduler.scheduleOpenedOrders(store);

            // Not affected by downstream constraints: untouched
            assertEquals(T0.minusHours(9), unaffected.requestedStart);
            assertEquals(T0.minusHours(7), unaffected.requestedEnd);
            assertEquals(BaseHandlingOrder.HandlingOrderStatus.OPEN, unaffected.status);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleOpenedOrders_missingRequestedEnd_throwsIllegalState() {
        PackingOrder packer = newPackingOrder(1L, T0.minusHours(1), null, T0, newLot(1L));
        PanacheQuery<PackingOrder> packingQuery = mock(PanacheQuery.class);
        when(packingQuery.list()).thenReturn(List.of(packer));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq(PACKING_JPQL), any(Object[].class)))
                    .thenReturn(packingQuery);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> scheduler.scheduleOpenedOrders(store));
            assertTrue(ex.getMessage().contains("cannot be scheduled"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleOpenedOrders_missingRequestedStart_throwsIllegalState() {
        PackingOrder packer = newPackingOrder(1L, null, T0.plusHours(1), T0, newLot(1L));
        PanacheQuery<PackingOrder> packingQuery = mock(PanacheQuery.class);
        when(packingQuery.list()).thenReturn(List.of(packer));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq(PACKING_JPQL), any(Object[].class)))
                    .thenReturn(packingQuery);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> scheduler.scheduleOpenedOrders(store));
            assertTrue(ex.getMessage().contains("cannot be scheduled"));
        }
    }

    @Test
    void scheduleOpenedOrders_nullStore_throwsNPE() {
        // store.code est déréférencé sans garde dans le log d'entrée
        assertThrows(NullPointerException.class, () -> scheduler.scheduleOpenedOrders(null));
    }
}
