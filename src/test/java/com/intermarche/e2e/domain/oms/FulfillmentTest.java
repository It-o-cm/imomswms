package com.intermarche.e2e.domain.oms;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.ReservationRecord.ReservationStatus;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.store.Store;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.persistence.PersistenceException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import com.intermarche.backoffice.domain.ReservationRecord;

/**
 * Integration tests for {@link Fulfillment}.
 * Covers persistence, unique constraint on order, addChunk,
 * getChecksum, and the static reserveBatch leasing logic.
 */
@QuarkusTest
public class FulfillmentTest {

    // --------------------------------------------------
    // Fixture Helpers
    // --------------------------------------------------

    private Store createStore(String prefix) {
        Store store = new Store();
        store.code = prefix + "-S";
        store.name = "Store " + prefix;
        store.address = new Adresse();
        store.address.streetLine1 = "1 rue de Test";
        store.address.city = "Lyon";
        store.address.postalCode = "69001";
        store.address.country = "France";
        store.persist();
        return store;
    }

    private Customer createCustomer(String prefix) {
        Customer customer = new Customer();
        customer.customerId = prefix + "-CUST";
        customer.firstName = "Jane";
        customer.lastName = "Smith";
        customer.email = prefix + "-cust@test.com";
        customer.persist();
        return customer;
    }

    private Order createOrder(Store store, Customer customer, String prefix) {
        Order order = new Order();
        order.orderId = prefix + "-ORDER";
        order.orderDate = LocalDateTime.now();
        order.channel = "ECOMMERCE";
        order.status = "VALIDATED";
        order.store = store;
        order.customer = customer;
        order.persist();
        return order;
    }

    private Fulfillment createFulfillment(Order order) {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.order = order;
        fulfillment.persist();
        return fulfillment;
    }

    private FulfillmentChunk createChunk(Fulfillment fulfillment, String type) {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = type;
        chunk.label = "Test";
        chunk.persist();
        fulfillment.chunks.add(chunk);
        return chunk;
    }

    // --------------------------------------------------
    // Persistence Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void persist_shouldSucceed_withValidData() {
        Store store = createStore("FF01");
        Customer customer = createCustomer("FF01");
        Order order = createOrder(store, customer, "FF01");

        Fulfillment fulfillment = createFulfillment(order);

        assertNotNull(fulfillment.id);
        assertEquals(order.id, fulfillment.order.id);
        assertNotNull(fulfillment.reservationRecord);
        assertEquals(ReservationStatus.PENDING, fulfillment.reservationRecord.status);
        assertTrue(fulfillment.chunks.isEmpty());
    }

    @Test
    @TestTransaction
    void persist_shouldFail_whenOrderAlreadyHasFulfillment() {
        Store store = createStore("FF02");
        Customer customer = createCustomer("FF02");
        Order order = createOrder(store, customer, "FF02");
        createFulfillment(order);

        Fulfillment duplicate = new Fulfillment();
        duplicate.order = order;

        assertThrows(PersistenceException.class, () -> {
            duplicate.persist();
            Panache.getEntityManager().flush();
        });
    }

    // --------------------------------------------------
    // addChunk Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void addChunk_shouldAddChunkToSet() {
        Store store = createStore("FF03");
        Customer customer = createCustomer("FF03");
        Order order = createOrder(store, customer, "FF03");
        Fulfillment fulfillment = createFulfillment(order);

        FulfillmentChunk chunk = createChunk(fulfillment, FulfillmentChunk.PENDING);

        assertTrue(fulfillment.chunks.contains(chunk));
        assertEquals(1, fulfillment.chunks.size());
    }

    @Test
    @TestTransaction
    void addChunk_shouldSupportMultipleChunks() {
        Store store = createStore("FF04");
        Customer customer = createCustomer("FF04");
        Order order = createOrder(store, customer, "FF04");
        Fulfillment fulfillment = createFulfillment(order);

        createChunk(fulfillment, FulfillmentChunk.PENDING);
        createChunk(fulfillment, FulfillmentChunk.READY);
        createChunk(fulfillment, FulfillmentChunk.PENDING);

        assertEquals(3, fulfillment.chunks.size());
    }

    // --------------------------------------------------
    // Checksum Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenReservationStatusChanges() {
        Store store = createStore("FF05");
        Customer customer = createCustomer("FF05");
        Order order = createOrder(store, customer, "FF05");
        Fulfillment fulfillment = createFulfillment(order);

        int checksum1 = fulfillment.getChecksum();
        fulfillment.reservationRecord.status = ReservationStatus.PROCESSING;
        int checksum2 = fulfillment.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenChunksAdded() {
        Store store = createStore("FF06");
        Customer customer = createCustomer("FF06");
        Order order = createOrder(store, customer, "FF06");
        Fulfillment fulfillment = createFulfillment(order);

        int checksum1 = fulfillment.getChecksum();
        createChunk(fulfillment, FulfillmentChunk.PENDING);
        int checksum2 = fulfillment.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    // --------------------------------------------------
    // reserveBatch Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void reserveBatch_shouldReservePendingFulfillments() {
        Store store = createStore("FF07");
        Customer customer = createCustomer("FF07");
        Order order1 = createOrder(store, customer, "FF07A");
        Order order2 = createOrder(store, customer, "FF07B");
        Fulfillment f1 = createFulfillment(order1);
        Fulfillment f2 = createFulfillment(order2);

        List<Long> reservedIds = Fulfillment.reserveBatch(60, 10, "");

        assertEquals(2, reservedIds.size());
        assertTrue(reservedIds.contains(f1.id));
        assertTrue(reservedIds.contains(f2.id));
        assertEquals(ReservationStatus.PROCESSING, f1.reservationRecord.status);
        assertEquals(ReservationStatus.PROCESSING, f2.reservationRecord.status);
        assertNotNull(f1.reservationRecord.leasing);
        assertNotNull(f1.reservationRecord.consideration);
    }

    @Test
    @TestTransaction
    void reserveBatch_shouldRespectBatchSize() {
        Store store = createStore("FF08");
        Customer customer = createCustomer("FF08");
        createFulfillment(createOrder(store, customer, "FF08A"));
        createFulfillment(createOrder(store, customer, "FF08B"));
        createFulfillment(createOrder(store, customer, "FF08C"));

        List<Long> reservedIds = Fulfillment.reserveBatch(60, 2, "");

        assertEquals(2, reservedIds.size());
    }

    @Test
    @TestTransaction
    void reserveBatch_shouldNotReserveAlreadyProcessingWithValidLease() {
        Store store = createStore("FF09");
        Customer customer = createCustomer("FF09");
        Order order = createOrder(store, customer, "FF09");
        Fulfillment fulfillment = createFulfillment(order);

        List<Long> firstBatch = Fulfillment.reserveBatch(60, 10, "");
        assertEquals(1, firstBatch.size());
        Panache.getEntityManager().flush();

        // Second reservation attempt: lease is still valid, nothing to reserve
        List<Long> secondBatch = Fulfillment.reserveBatch(60, 10, "");

        assertTrue(secondBatch.isEmpty());
        assertNotNull(fulfillment.reservationRecord.leasing);
    }

    @Test
    @TestTransaction
    void reserveBatch_shouldApplyExtraWhereClause() {
        Store store = createStore("FF10");
        Customer customer = createCustomer("FF10");
        Order order1 = createOrder(store, customer, "FF10A");
        Order order2 = createOrder(store, customer, "FF10B");
        Fulfillment f1 = createFulfillment(order1);
        createFulfillment(order2);

        // Documented contract: the extra clause is a plain SQL fragment (e.g. "store_id = 1")
        List<Long> reservedIds = Fulfillment.reserveBatch(60, 10, "order_id = " + order1.id);

        assertEquals(1, reservedIds.size());
        assertEquals(f1.id, reservedIds.get(0));
    }

    @Test
    @TestTransaction
    void reserveBatch_shouldReturnEmpty_whenNothingPending() {
        Store store = createStore("FF11");
        Customer customer = createCustomer("FF11");
        Order order = createOrder(store, customer, "FF11");
        Fulfillment fulfillment = createFulfillment(order);
        fulfillment.reservationRecord.status = ReservationStatus.DONE;

        List<Long> reservedIds = Fulfillment.reserveBatch(60, 10, "");

        assertTrue(reservedIds.isEmpty());
    }
}
