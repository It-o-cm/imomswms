package com.intermarche.e2e.engine.handling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.handling.PackingOrder;
import com.intermarche.backoffice.engine.handling.BaseOrderHandlerBuilderFactory;
import com.intermarche.backoffice.engine.handling.PackingOrderManagerFactory;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PackingOrderManagerFactory.PackingOrderManager#process(Store)}.
 * <p>
 * Regression tests for the cutoff date computation: a lot whose delivery date is in the
 * future but within the configured {@code validityDelayMinutes} window MUST be picked up.
 */
@QuarkusTest
class PackingOrderManagerFactoryProcessTest {

    private final ObjectMapper mapper = new ObjectMapper();

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

    private Order createOmsOrder(Store store, Customer customer, String prefix) {
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

    private FulfillmentChunk createChunk(Fulfillment fulfillment, LocalDateTime deliveryDate) {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.READY;
        chunk.label = "Chunk";
        chunk.deliveryDate = deliveryDate;
        chunk.fulfillment = fulfillment;
        chunk.persist();
        return chunk;
    }

    private Lot createLot(Store store, Customer customer, Fulfillment fulfillment, FulfillmentChunk chunk) {
        Lot lot = new Lot();
        lot.store = store;
        lot.customer = customer;
        lot.fulfillment = fulfillment;
        lot.chunk = chunk;
        lot.persist();
        return lot;
    }

    private BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder buildManager(String spec) throws Exception {
        JsonNode specNode = mapper.readTree(spec);
        return new PackingOrderManagerFactory().build(specNode);
    }

    // --------------------------------------------------
    // process
    // --------------------------------------------------

    @Test
    @TestTransaction
    void process_lotWithinValidityWindow_createsPackingOrder() throws Exception {
        Store store = createStore("PACK");
        Customer customer = createCustomer("PACK");
        Order omsOrder = createOmsOrder(store, customer, "PACK");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        // Delivery in 30 minutes: in the future, but within the 60 minutes validity window
        LocalDateTime deliveryDate = LocalDateTime.now().plusMinutes(30);
        FulfillmentChunk chunk = createChunk(fulfillment, deliveryDate);
        Lot lot = createLot(store, customer, fulfillment, chunk);

        var manager = buildManager("{\"validityDelayMinutes\":60,\"groupingWindowMinutes\":60,\"deadlineDelayMinutes\":15}");

        manager.process(store);

        List<PackingOrder> orders = PackingOrder.listAll();
        assertEquals(1, orders.size());
        PackingOrder order = orders.get(0);
        assertEquals(store.id, order.store.id);
        assertEquals(customer.id, order.customer.id);
        LocalDateTime slotStart = deliveryDate.withMinute(0).withSecond(0).withNano(0);
        assertEquals(slotStart, order.requestedStart);
        assertEquals(slotStart.plusMinutes(60), order.requestedEnd);
        assertEquals(slotStart.minusMinutes(15), order.deadLine);
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.OPEN, order.status);
        assertNotNull(order.code);
        assertTrue(order.lots.contains(lot));
        assertEquals(Lot.LotStatus.PLANNED_TO_BE_PACKED, lot.status);
    }

    @Test
    @TestTransaction
    void process_lotOutsideValidityWindow_createsNoOrder() throws Exception {
        Store store = createStore("PACK");
        Customer customer = createCustomer("PACK");
        Order omsOrder = createOmsOrder(store, customer, "PACK");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        // Delivery in 2 hours: outside the 60 minutes validity window
        LocalDateTime deliveryDate = LocalDateTime.now().plusMinutes(120);
        FulfillmentChunk chunk = createChunk(fulfillment, deliveryDate);
        Lot lot = createLot(store, customer, fulfillment, chunk);

        var manager = buildManager("{\"validityDelayMinutes\":60,\"groupingWindowMinutes\":60}");

        manager.process(store);

        assertEquals(0, PackingOrder.count());
        assertEquals(Lot.LotStatus.PENDING, lot.status);
    }

    @Test
    @TestTransaction
    void process_lotWithoutDeliveryDate_createsNoOrder() throws Exception {
        Store store = createStore("PACK");
        Customer customer = createCustomer("PACK");
        Order omsOrder = createOmsOrder(store, customer, "PACK");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment, null);
        Lot lot = createLot(store, customer, fulfillment, chunk);

        var manager = buildManager("{\"validityDelayMinutes\":60}");

        manager.process(store);

        assertEquals(0, PackingOrder.count());
        assertEquals(Lot.LotStatus.PENDING, lot.status);
    }

    @Test
    @TestTransaction
    void process_lotAlreadyAssignedToOpenOrder_isNotReprocessed() throws Exception {
        Store store = createStore("PACK");
        Customer customer = createCustomer("PACK");
        Order omsOrder = createOmsOrder(store, customer, "PACK");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment, LocalDateTime.now().plusMinutes(30));
        Lot lot = createLot(store, customer, fulfillment, chunk);

        // Pre-existing OPEN packing order already covering the lot
        PackingOrder existing = new PackingOrder();
        existing.store = store;
        existing.customer = customer;
        existing.status = BaseHandlingOrder.HandlingOrderStatus.OPEN;
        existing.persist();
        existing.addLot(lot);

        var manager = buildManager("{\"validityDelayMinutes\":60}");

        manager.process(store);

        // No new order created, the existing one is untouched
        assertEquals(1, PackingOrder.count());
        List<PackingOrder> orders = PackingOrder.listAll();
        assertEquals(existing.id, orders.get(0).id);
    }

    @Test
    @TestTransaction
    void process_noLots_createsNoOrder() throws Exception {
        Store store = createStore("PACK");

        var manager = buildManager("{\"validityDelayMinutes\":60}");

        manager.process(store);

        assertEquals(0, PackingOrder.count());
    }
}
