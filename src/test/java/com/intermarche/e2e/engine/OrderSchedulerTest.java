package com.intermarche.e2e.engine;

import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import com.intermarche.backoffice.domain.wms.handling.PackingOrder;
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
import com.intermarche.backoffice.engine.OrderScheduler;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link OrderScheduler}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to run the backward scheduling
 * algorithm against the real database and rollback changes after each test.
 * <p>
 * The scheduling algorithm works backwards from {@link PackingOrder} deadlines:
 * <ul>
 *   <li>Packing phase: theoretical start = {@code deadLine - duration}, status becomes SCHEDULED.</li>
 *   <li>Grouping / Collecting phases: orders sharing a lot with a downstream order are pulled
 *       back so that they end exactly when the downstream order starts.</li>
 *   <li>"No advance" rule: an order never starts later than its current {@code requestedStart}
 *       (the current requestedStart is an upper bound; orders can only be pulled back).</li>
 *   <li>The original duration ({@code requestedEnd - requestedStart}) is strictly preserved.</li>
 * </ul>
 */
@QuarkusTest
@TestTransaction
public class OrderSchedulerTest {

    @Inject
    OrderScheduler scheduler;

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 14, 12, 0);

    // --------------------------------------------------
    // Fixture Helpers
    // --------------------------------------------------

    private Store createStore(String code) {
        return DomainUtils.createAndPersistStore(code, 48.0, 2.0);
    }

    /**
     * Creates the full chain Customer -> Order -> Fulfillment -> Chunk -> Lot.
     */
    private Lot createLot(Store store, String prefix) {
        Customer customer = new Customer();
        customer.customerId = prefix + "-CUST";
        customer.firstName = "Jane";
        customer.lastName = "Smith";
        customer.email = prefix + "@test.com";
        customer.persist();

        Order order = new Order();
        order.orderId = prefix + "-ORDER";
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
        chunk.label = "Chunk " + prefix;
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

    private PackingOrder createPackingOrder(Store store, String code, Lot lot,
                                            LocalDateTime deadLine,
                                            LocalDateTime requestedStart, LocalDateTime requestedEnd) {
        PackingOrder order = new PackingOrder();
        order.store = store;
        order.code = code;
        order.deadLine = deadLine;
        order.requestedStart = requestedStart;
        order.requestedEnd = requestedEnd;
        if (lot != null) {
            order.addLot(lot);
        }
        order.persist();
        return order;
    }

    private GroupingOrder createGroupingOrder(Store store, String code, Lot lot,
                                              LocalDateTime requestedStart, LocalDateTime requestedEnd) {
        GroupingOrder order = new GroupingOrder();
        order.store = store;
        order.code = code;
        order.requestedStart = requestedStart;
        order.requestedEnd = requestedEnd;
        if (lot != null) {
            order.addLot(lot);
        }
        order.persist();
        return order;
    }

    private ProductCollectingOrder createCollectingOrder(Store store, String code, Lot lot,
                                                         LocalDateTime requestedStart, LocalDateTime requestedEnd) {
        ProductCollectingOrder order = new ProductCollectingOrder();
        order.store = store;
        order.code = code;
        order.requestedStart = requestedStart;
        order.requestedEnd = requestedEnd;
        if (lot != null) {
            order.addLot(lot);
        }
        order.persist();
        return order;
    }

    // --------------------------------------------------
    // Packing phase
    // --------------------------------------------------

    @Test
    void testSchedule_PackingOrder_BasicScheduling() {
        Store store = createStore("SCH_S1");
        Lot lot = createLot(store, "SCH1");
        // duration 30 min, deadline 12:30 -> theoretical start 12:00
        // (initially planned at 13:00: the scheduler pulls it back to 12:00)
        PackingOrder packer = createPackingOrder(store, "SCH_P1", lot,
                BASE.plusMinutes(30), BASE.plusHours(1), BASE.plusHours(1).plusMinutes(30));

        scheduler.scheduleOpenedOrders(store);

        assertEquals(BASE, packer.requestedStart, "requestedStart must be deadLine minus duration");
        assertEquals(BASE.plusMinutes(30), packer.requestedEnd, "requestedEnd must equal the deadline");
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, packer.status);
        assertEquals(Duration.ofMinutes(30), Duration.between(packer.requestedStart, packer.requestedEnd),
                "The original duration must be preserved");
    }

    @Test
    void testSchedule_PackingOrder_NoDeadline_Skipped() {
        Store store = createStore("SCH_S2");
        Lot lot = createLot(store, "SCH2");
        LocalDateTime initialStart = BASE.minusHours(1);
        LocalDateTime initialEnd = BASE.minusMinutes(30);
        PackingOrder packer = createPackingOrder(store, "SCH_P2", lot, null, initialStart, initialEnd);
        // a grouping order sharing the lot must stay untouched (early return: empty availability map)
        GroupingOrder grouper = createGroupingOrder(store, "SCH_G2", lot, BASE.plusHours(1), BASE.plusHours(1).plusMinutes(20));

        scheduler.scheduleOpenedOrders(store);

        assertEquals(BaseHandlingOrder.HandlingOrderStatus.OPEN, packer.status, "A packer without deadLine must be skipped");
        assertEquals(initialStart, packer.requestedStart);
        assertEquals(initialEnd, packer.requestedEnd);
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.OPEN, grouper.status,
                "Upstream phases must not run when no packing order was scheduled");
        assertEquals(BASE.plusHours(1), grouper.requestedStart);
    }

    @Test
    void testSchedule_PackingOrder_NoAdvanceRule_KeepsEarlierRequestedStart() {
        Store store = createStore("SCH_S3");
        Lot lot = createLot(store, "SCH3");
        // theoretical start would be 12:00, but the current requestedStart (11:45) is earlier:
        // the order must never be moved forward, the current requestedStart is kept
        PackingOrder packer = createPackingOrder(store, "SCH_P3", lot,
                BASE.plusMinutes(30), BASE.minusMinutes(15), BASE.plusMinutes(15));

        scheduler.scheduleOpenedOrders(store);

        assertEquals(BASE.minusMinutes(15), packer.requestedStart, "The no-advance rule must keep the current requestedStart");
        assertEquals(BASE.plusMinutes(15), packer.requestedEnd);
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, packer.status);
    }

    @Test
    void testSchedule_PackingOrder_AlreadyScheduled_NotReprocessed() {
        Store store = createStore("SCH_S4");
        Lot lot = createLot(store, "SCH4");
        LocalDateTime initialStart = BASE.minusHours(3);
        LocalDateTime initialEnd = BASE.minusHours(3).plusMinutes(30);
        PackingOrder packer = createPackingOrder(store, "SCH_P4", lot,
                BASE.plusMinutes(30), initialStart, initialEnd);
        packer.status = BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
        // a grouping order sharing the lot: the packer is not OPEN anymore, so no lot constraint exists
        GroupingOrder grouper = createGroupingOrder(store, "SCH_G4", lot, BASE.plusHours(1), BASE.plusHours(1).plusMinutes(20));

        scheduler.scheduleOpenedOrders(store);

        assertEquals(initialStart, packer.requestedStart, "A SCHEDULED packer must not be reprocessed");
        assertEquals(initialEnd, packer.requestedEnd);
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.OPEN, grouper.status,
                "The grouping phase must not run when no OPEN packing order was scheduled");
    }

    @Test
    void testSchedule_PackingOrder_WithoutDuration_ThrowsIllegalState() {
        Store store = createStore("SCH_S5");
        Lot lot = createLot(store, "SCH5");
        PackingOrder packer = createPackingOrder(store, "SCH_P5", lot,
                BASE.plusMinutes(30), null, null);

        assertThrows(IllegalStateException.class, () -> scheduler.scheduleOpenedOrders(store),
                "An order without requestedStart/requestedEnd has no defined duration and cannot be scheduled");
    }

    @Test
    void testSchedule_OtherStoreOrders_Untouched() {
        Store storeA = createStore("SCH_S6A");
        Store storeB = createStore("SCH_S6B");
        Lot lotA = createLot(storeA, "SCH6A");
        Lot lotB = createLot(storeB, "SCH6B");
        PackingOrder packerA = createPackingOrder(storeA, "SCH_P6A", lotA,
                BASE.plusMinutes(30), BASE.plusHours(1), BASE.plusHours(1).plusMinutes(30));
        LocalDateTime initialStartB = BASE.plusHours(1);
        LocalDateTime initialEndB = BASE.plusHours(1).plusMinutes(30);
        PackingOrder packerB = createPackingOrder(storeB, "SCH_P6B", lotB,
                BASE.plusMinutes(30), initialStartB, initialEndB);

        scheduler.scheduleOpenedOrders(storeA);

        assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, packerA.status);
        assertEquals(BASE, packerA.requestedStart, "The packer of the scheduled store must be pulled back to its theoretical start");
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.OPEN, packerB.status,
                "Orders of another store must not be scheduled");
        assertEquals(initialStartB, packerB.requestedStart);
        assertEquals(initialEndB, packerB.requestedEnd);
    }

    // --------------------------------------------------
    // Grouping phase
    // --------------------------------------------------

    @Test
    void testSchedule_GroupingPhase_PulledBackToPackingStart() {
        Store store = createStore("SCH_S7");
        Lot lot = createLot(store, "SCH7");
        // packing start = 12:00 (deadline 12:30, duration 30 min, initially planned at 13:00)
        PackingOrder packer = createPackingOrder(store, "SCH_P7", lot,
                BASE.plusMinutes(30), BASE.plusHours(1), BASE.plusHours(1).plusMinutes(30));
        // grouper duration 20 min, initially planned at 13:00 -> must be pulled back to 11:40
        GroupingOrder grouper = createGroupingOrder(store, "SCH_G7", lot,
                BASE.plusHours(1), BASE.plusHours(1).plusMinutes(20));

        scheduler.scheduleOpenedOrders(store);

        assertEquals(BASE.minusMinutes(20), grouper.requestedStart,
                "The grouper must start duration minutes before the packing order");
        assertEquals(BASE, grouper.requestedEnd, "The grouper must end exactly when the packing order starts");
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, grouper.status);
        assertEquals(Duration.ofMinutes(20), Duration.between(grouper.requestedStart, grouper.requestedEnd),
                "The original duration must be preserved");
    }

    @Test
    void testSchedule_GroupingPhase_NoAdvanceRule_KeepsEarlierRequestedStart() {
        Store store = createStore("SCH_S8");
        Lot lot = createLot(store, "SCH8");
        PackingOrder packer = createPackingOrder(store, "SCH_P8", lot,
                BASE.plusMinutes(30), BASE.plusHours(1), BASE.plusHours(1).plusMinutes(30));
        // theoretical start would be 11:40, but the current requestedStart (11:30) is earlier: it must be kept
        GroupingOrder grouper = createGroupingOrder(store, "SCH_G8", lot,
                BASE.minusMinutes(30), BASE.minusMinutes(10));

        scheduler.scheduleOpenedOrders(store);

        assertEquals(BASE.minusMinutes(30), grouper.requestedStart,
                "The no-advance rule must keep the current requestedStart");
        assertEquals(BASE.minusMinutes(10), grouper.requestedEnd);
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, grouper.status);
    }

    @Test
    void testSchedule_TwoGroupersSameLot_OverlapWhenNoAdvanceWins() {
        Store store = createStore("SCH_S9");
        Lot lot = createLot(store, "SCH9");
        PackingOrder packer = createPackingOrder(store, "SCH_P9", lot,
                BASE.plusMinutes(30), BASE.plusHours(1), BASE.plusHours(1).plusMinutes(30));
        // both groupers share the same lot: same theoretical start (11:30), same duration (30 min)
        GroupingOrder g1 = createGroupingOrder(store, "SCH_G9A", lot,
                BASE.plusHours(1), BASE.plusHours(1).plusMinutes(30));
        GroupingOrder g2 = createGroupingOrder(store, "SCH_G9B", lot,
                BASE.plusHours(1), BASE.plusHours(1).plusMinutes(30));

        scheduler.scheduleOpenedOrders(store);

        // whichever is processed first gets 11:30; the second cannot fit sequentially (max 11:00)
        // and the no-advance rule wins: both end up at 11:30 (overlap)
        assertEquals(BASE.minusMinutes(30), g1.requestedStart);
        assertEquals(BASE.minusMinutes(30), g2.requestedStart);
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, g1.status);
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, g2.status);
    }

    @Test
    void testSchedule_TwoGroupersDifferentLots_SequentialNoOverlap() {
        Store store = createStore("SCH_S10");
        Lot lot1 = createLot(store, "SCH10A");
        Lot lot2 = createLot(store, "SCH10B");
        // packing starts: 12:00 on lot1, 13:00 on lot2 (both initially planned later)
        createPackingOrder(store, "SCH_P10A", lot1,
                BASE.plusMinutes(30), BASE.plusHours(1), BASE.plusHours(1).plusMinutes(30));
        createPackingOrder(store, "SCH_P10B", lot2,
                BASE.plusMinutes(90), BASE.plusHours(2), BASE.plusHours(2).plusMinutes(30));
        // G1 on lot1: theoretical 11:30 ; G2 on lot2: theoretical 12:30
        GroupingOrder g1 = createGroupingOrder(store, "SCH_G10A", lot1,
                BASE.plusHours(3), BASE.plusHours(3).plusMinutes(30));
        GroupingOrder g2 = createGroupingOrder(store, "SCH_G10B", lot2,
                BASE.plusHours(4), BASE.plusHours(4).plusMinutes(30));

        scheduler.scheduleOpenedOrders(store);

        // G2 (latest theoretical start) is processed first at 12:30; G1 fits before it at 11:30
        assertEquals(BASE.plusMinutes(30), g2.requestedStart);
        assertEquals(BASE.minusMinutes(30), g1.requestedStart);
        assertFalse(g1.requestedEnd.isAfter(g2.requestedStart),
                "Sequential orders must not overlap: G1 must end before G2 starts");
    }

    // --------------------------------------------------
    // Collecting phase
    // --------------------------------------------------

    @Test
    void testSchedule_CollectingPhase_ChainPropagation() {
        Store store = createStore("SCH_S11");
        Lot lot1 = createLot(store, "SCH11A");
        Lot lot2 = createLot(store, "SCH11B");
        // packing start = 12:00 on lot1 (initially planned at 13:00)
        PackingOrder packer = createPackingOrder(store, "SCH_P11", lot1,
                BASE.plusMinutes(30), BASE.plusHours(1), BASE.plusHours(1).plusMinutes(30));
        // grouper on lot1 + lot2, duration 20 min -> pulled back to 11:40
        GroupingOrder grouper = new GroupingOrder();
        grouper.store = store;
        grouper.code = "SCH_G11";
        grouper.requestedStart = BASE.plusHours(1);
        grouper.requestedEnd = BASE.plusHours(1).plusMinutes(20);
        grouper.addLot(lot1);
        grouper.addLot(lot2);
        grouper.persist();
        // collector on lot2, duration 10 min, initially planned at 14:00 -> pulled back to 11:30
        ProductCollectingOrder collector = createCollectingOrder(store, "SCH_C11", lot2,
                BASE.plusHours(2), BASE.plusHours(2).plusMinutes(10));

        scheduler.scheduleOpenedOrders(store);

        assertEquals(BASE.minusMinutes(20), grouper.requestedStart);
        assertEquals(BASE.minusMinutes(30), collector.requestedStart,
                "The collector must start duration minutes before the grouping order");
        assertEquals(grouper.requestedStart, collector.requestedEnd,
                "The collector must end exactly when the grouping order starts");
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, collector.status);
        assertEquals(Duration.ofMinutes(10), Duration.between(collector.requestedStart, collector.requestedEnd),
                "The original duration must be preserved");
    }

    @Test
    void testSchedule_CollectingPhase_PackingOrderNotTreatedAsCollector() {
        Store store = createStore("SCH_S12");
        Lot lot = createLot(store, "SCH12");
        // a single packing order: the collecting phase must exclude it (TYPE(o) <> PackingOrder)
        PackingOrder packer = createPackingOrder(store, "SCH_P12", lot,
                BASE.plusMinutes(30), BASE.plusHours(1), BASE.plusHours(1).plusMinutes(30));

        scheduler.scheduleOpenedOrders(store);

        assertEquals(BASE, packer.requestedStart, "The packing order must only be scheduled by the packing phase");
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, packer.status);
    }

    @Test
    void testSchedule_NoPackingOrders_UpstreamOrdersUntouched() {
        Store store = createStore("SCH_S13");
        Lot lot = createLot(store, "SCH13");
        LocalDateTime initialStart = BASE.plusHours(1);
        LocalDateTime initialEnd = BASE.plusHours(1).plusMinutes(20);
        GroupingOrder grouper = createGroupingOrder(store, "SCH_G13", lot, initialStart, initialEnd);
        ProductCollectingOrder collector = createCollectingOrder(store, "SCH_C13", lot, initialStart, initialEnd);

        scheduler.scheduleOpenedOrders(store);

        assertEquals(BaseHandlingOrder.HandlingOrderStatus.OPEN, grouper.status,
                "Without packing orders, the grouping phase must not run");
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.OPEN, collector.status,
                "Without packing orders, the collecting phase must not run");
        assertEquals(initialStart, grouper.requestedStart);
        assertEquals(initialStart, collector.requestedStart);
    }
}
