package com.intermarche.e2e.engine;

import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.engine.FulfillmentEngine;
import com.intermarche.backoffice.engine.FulfillmentPollingConsumer;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link FulfillmentPollingConsumer}.
 * <p>
 * The scheduler is disabled ({@link SchedulerDisabledTestProfile}): the consumer is driven
 * manually. The {@link FulfillmentEngine} is mocked to isolate the consumer logic
 * (reservation batching, per-fulfillment processing, failure handling).
 * <p>
 * Uses {@link TestTransaction} to rollback changes after each test.
 */
@QuarkusTest
@TestProfile(SchedulerDisabledTestProfile.class)
@TestTransaction
public class FulfillmentPollingConsumerTest {

    @Inject
    FulfillmentPollingConsumer consumer;

    @InjectMock
    FulfillmentEngine fulfillmentEngine;

    // --------------------------------------------------
    // Fixture Helpers
    // --------------------------------------------------

    private Fulfillment createFulfillment(Store store, String orderId) {
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
        fulfillment.persist();
        return fulfillment;
    }

    // --------------------------------------------------
    // reserveBatch
    // --------------------------------------------------

    @Test
    void testReserveBatch_UnreservableFulfillment_IsNotReserved() {
        Store store = DomainUtils.createAndPersistStore("FPC_S1", 48.0, 2.0);
        Fulfillment fulfillment = createFulfillment(store, "ORD_FPC1");
        // PROCESSING with a lease expiring in the future: not reservable
        fulfillment.reservationRecord.status = ReservationRecord.ReservationStatus.PROCESSING;
        fulfillment.reservationRecord.leasing = Instant.now().plusSeconds(3600);

        List<Long> reserved = consumer.reserveBatch();

        assertNotNull(reserved);
        assertFalse(reserved.contains(fulfillment.id),
                "A fulfillment with an active lease must not be reserved");
    }

    @Test
    void testReserveBatch_ReservesPendingFulfillments() {
        Store store = DomainUtils.createAndPersistStore("FPC_S2", 48.0, 2.0);
        Fulfillment f1 = createFulfillment(store, "ORD_FPC1");
        Fulfillment f2 = createFulfillment(store, "ORD_FPC2");

        List<Long> reserved = consumer.reserveBatch();

        assertTrue(reserved.contains(f1.id));
        assertTrue(reserved.contains(f2.id));
        // a lease must be applied: status PROCESSING, leasing in the future, consideration set
        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, f1.reservationRecord.status);
        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, f2.reservationRecord.status);
        assertNotNull(f1.reservationRecord.leasing, "A lease expiration must be set");
        assertTrue(f1.reservationRecord.leasing.isAfter(Instant.now()),
                "The lease must expire in the future");
        assertNotNull(f1.reservationRecord.consideration, "The consideration instant must be set");
    }

    @Test
    void testReserveBatch_DoesNotReserveTwice() {
        Store store = DomainUtils.createAndPersistStore("FPC_S3", 48.0, 2.0);
        Fulfillment fulfillment = createFulfillment(store, "ORD_FPC3");

        List<Long> firstBatch = consumer.reserveBatch();
        assertTrue(firstBatch.contains(fulfillment.id));

        // the record is now PROCESSING with a fresh lease and a future consideration: not reservable
        List<Long> secondBatch = consumer.reserveBatch();
        assertFalse(secondBatch.contains(fulfillment.id),
                "An already reserved fulfillment must not be reserved again");
    }

    @Test
    void testReserveBatch_ReservesExpiredLease() {
        Store store = DomainUtils.createAndPersistStore("FPC_S4", 48.0, 2.0);
        Fulfillment fulfillment = createFulfillment(store, "ORD_FPC4");
        // simulate an expired lease: PROCESSING but the lease expired one hour ago
        fulfillment.reservationRecord.status = ReservationRecord.ReservationStatus.PROCESSING;
        fulfillment.reservationRecord.leasing = Instant.now().minusSeconds(3600);

        List<Long> reserved = consumer.reserveBatch();

        assertTrue(reserved.contains(fulfillment.id),
                "A fulfillment with an expired lease must be reservable again");
        assertTrue(fulfillment.reservationRecord.leasing.isAfter(Instant.now()),
                "A new lease must be applied");
    }

    @Test
    void testReserveBatch_RespectsBatchSize() {
        Store store = DomainUtils.createAndPersistStore("FPC_S5", 48.0, 2.0);
        for (int i = 0; i < 12; i++) {
            createFulfillment(store, "ORD_FPC5_" + i);
        }

        List<Long> reserved = consumer.reserveBatch();

        assertEquals(10, reserved.size(), "The batch size is limited to 10 fulfillments");
    }

    // --------------------------------------------------
    // processBatch / processSingleFulfillment
    // --------------------------------------------------

    @Test
    void testProcessBatch_CallsEngineForEachFulfillment() throws Exception {
        Store store = DomainUtils.createAndPersistStore("FPC_S6", 48.0, 2.0);
        Fulfillment f1 = createFulfillment(store, "ORD_FPC6A");
        Fulfillment f2 = createFulfillment(store, "ORD_FPC6B");
        List<Long> reserved = consumer.reserveBatch();
        assertTrue(reserved.contains(f1.id));
        assertTrue(reserved.contains(f2.id));

        consumer.processBatch(reserved);

        verify(fulfillmentEngine, times(1)).process(f1);
        verify(fulfillmentEngine, times(1)).process(f2);
    }

    @Test
    void testProcessSingleFulfillment_EngineSuccess() throws Exception {
        Store store = DomainUtils.createAndPersistStore("FPC_S7", 48.0, 2.0);
        Fulfillment fulfillment = createFulfillment(store, "ORD_FPC7");
        doNothing().when(fulfillmentEngine).process(any(Fulfillment.class));

        consumer.processSingleFulfillment(fulfillment);

        verify(fulfillmentEngine, times(1)).process(fulfillment);
        // the mocked engine does not change the status: it must stay PENDING
        assertEquals(ReservationRecord.ReservationStatus.PENDING, fulfillment.reservationRecord.status);
    }

    @Test
    void testProcessSingleFulfillment_EngineThrows_MarksFailed() throws Exception {
        Store store = DomainUtils.createAndPersistStore("FPC_S8", 48.0, 2.0);
        Fulfillment fulfillment = createFulfillment(store, "ORD_FPC8");
        doThrow(new RuntimeException("engine failure")).when(fulfillmentEngine).process(any(Fulfillment.class));

        consumer.processSingleFulfillment(fulfillment);

        assertEquals(ReservationRecord.ReservationStatus.FAILED, fulfillment.reservationRecord.status,
                "A processing failure must mark the fulfillment as FAILED");
    }

    // --------------------------------------------------
    // pollAndProcess (full cycle)
    // --------------------------------------------------

    @Test
    void testPollAndProcess_ProcessesPendingFulfillments() throws Exception {
        Store store = DomainUtils.createAndPersistStore("FPC_S9", 48.0, 2.0);
        Fulfillment fulfillment = createFulfillment(store, "ORD_FPC9");
        doNothing().when(fulfillmentEngine).process(any(Fulfillment.class));

        consumer.pollAndProcess();

        verify(fulfillmentEngine, times(1)).process(fulfillment);
        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, fulfillment.reservationRecord.status,
                "The fulfillment must have been reserved (PROCESSING) before processing");
    }

    @Test
    void testPollAndProcess_UnreservableFulfillment_IsNotProcessed() throws Exception {
        Store store = DomainUtils.createAndPersistStore("FPC_S10", 48.0, 2.0);
        Fulfillment fulfillment = createFulfillment(store, "ORD_FPC10");
        // PROCESSING with a lease expiring in the future: not reservable, hence not processed
        fulfillment.reservationRecord.status = ReservationRecord.ReservationStatus.PROCESSING;
        fulfillment.reservationRecord.leasing = Instant.now().plusSeconds(3600);

        consumer.pollAndProcess();

        verify(fulfillmentEngine, never()).process(argThat(f -> f != null && f.id.equals(fulfillment.id)));
    }
}
