package com.intermarche.e2e.engine.handling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.ZoneCollectingOrder;
import com.intermarche.backoffice.engine.handling.BaseOrderHandlerBuilderFactory;
import com.intermarche.backoffice.engine.handling.ZoneCollectingOrderManagerFactory;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager#process(Store)}.
 * <p>
 * Covers the volume-mode bin packing: order creation when the minimum is met, order
 * discard when the minimum is not met, and the area/aisle location filtering
 * (a lot whose lines are not all in the configured area must be excluded).
 */
@QuarkusTest
class ZoneCollectingOrderManagerFactoryProcessTest {

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

    private StoreBuilding createBuilding(String code) {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = code;
        building.address = new Adresse();
        building.persist();
        return building;
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

    private Product createProduct(String prefix, String ean, BigDecimal referenceVolume) {
        Product product = new Product();
        product.ean = ean;
        product.name = "Product " + prefix;
        product.productType = ProductType.UNIT;
        product.temperatureZone = TemperatureZone.AMBIENT;
        product.referenceVolume = referenceVolume;
        product.persist();
        return product;
    }

    private StoreShelfLocation createLocation(Product product, String area, String aisle) {
        StoreShelfLocation loc = new StoreShelfLocation();
        loc.product = product;
        loc.facings = 1;
        loc.area = area;
        loc.aisle = aisle;
        loc.unit = "A";
        loc.elevation = "1";
        loc.position = "01";
        loc.persist();
        return loc;
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

    private FulfillmentChunk createChunk(Fulfillment fulfillment, LocalDateTime deliveryDate, StoreBuilding building) {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.READY;
        chunk.label = "Chunk";
        chunk.deliveryDate = deliveryDate;
        chunk.building = building;
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

    private LotLine addLotLine(Lot lot, Product product, StoreShelfLocation location, int lineNumber) {
        FulfillmentLine fulfillmentLine = new FulfillmentLine();
        fulfillmentLine.lineNumber = lineNumber;
        fulfillmentLine.product = product;
        fulfillmentLine.requestedQuantity = new BigDecimal("5.000");
        fulfillmentLine.location = location;
        fulfillmentLine.persist();
        LotLine lotLine = new LotLine();
        lotLine.product = product;
        lotLine.quantity = new BigDecimal("5.000");
        lotLine.setFulfillmentLine(fulfillmentLine);
        lotLine.persist();
        lot.addLine(lotLine);
        return lotLine;
    }

    private BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder buildManager(String spec) throws Exception {
        JsonNode specNode = mapper.readTree(spec);
        return new ZoneCollectingOrderManagerFactory().build(specNode);
    }

    // --------------------------------------------------
    // process - volume mode
    // --------------------------------------------------

    @Test
    @TestTransaction
    void process_volumeAboveMin_createsOrder() throws Exception {
        Store store = createStore("ZC");
        StoreBuilding building = createBuilding("ZC-B1");
        Customer customer = createCustomer("ZC");
        // 5 units * 2.0 volume = 10.0 total volume
        Product product = createProduct("ZC", "ZC-EAN-1", new BigDecimal("2.0"));
        StoreShelfLocation location = createLocation(product, "A1", "A01");
        Order omsOrder = createOmsOrder(store, customer, "ZC");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment, LocalDateTime.now().plusMinutes(30), building);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        addLotLine(lot, product, location, 1);

        var manager = buildManager(
                "{\"buildingCode\":\"ZC-B1\",\"area\":\"A1\",\"aisle\":\"A01\",\"volumeMin\":1.0,\"volumeMax\":100.0,\"validityDelayMinutes\":60}");

        manager.process(store);

        List<ZoneCollectingOrder> orders = ZoneCollectingOrder.listAll();
        assertEquals(1, orders.size());
        ZoneCollectingOrder order = orders.get(0);
        assertEquals(store.id, order.store.id);
        assertEquals(building.id, order.building.id);
        assertEquals("A1", order.area);
        assertEquals("A01", order.aisle);
        assertTrue(order.lots.contains(lot));
        assertEquals(Lot.LotStatus.PLANNED_TO_BE_COLLECTED, lot.status);
    }

    @Test
    @TestTransaction
    void process_volumeBelowMin_discardsOrder() throws Exception {
        Store store = createStore("ZC");
        StoreBuilding building = createBuilding("ZC-B1");
        Customer customer = createCustomer("ZC");
        // 5 units * 2.0 volume = 10.0 total volume, but the minimum is 1000
        Product product = createProduct("ZC", "ZC-EAN-1", new BigDecimal("2.0"));
        StoreShelfLocation location = createLocation(product, "A1", "A01");
        Order omsOrder = createOmsOrder(store, customer, "ZC");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment, LocalDateTime.now().plusMinutes(30), building);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        addLotLine(lot, product, location, 1);

        var manager = buildManager(
                "{\"buildingCode\":\"ZC-B1\",\"area\":\"A1\",\"aisle\":\"A01\",\"volumeMin\":1000.0,\"volumeMax\":2000.0,\"validityDelayMinutes\":60}");

        manager.process(store);

        assertEquals(0, ZoneCollectingOrder.count());
        // The lot status is still updated even if the order is discarded
        assertEquals(Lot.LotStatus.PLANNED_TO_BE_COLLECTED, lot.status);
    }

    @Test
    @TestTransaction
    void process_areaMismatch_createsNoOrder() throws Exception {
        Store store = createStore("ZC");
        StoreBuilding building = createBuilding("ZC-B1");
        Customer customer = createCustomer("ZC");
        Product product = createProduct("ZC", "ZC-EAN-1", new BigDecimal("2.0"));
        StoreShelfLocation location = createLocation(product, "A1", "A01");
        Order omsOrder = createOmsOrder(store, customer, "ZC");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment, LocalDateTime.now().plusMinutes(30), building);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        addLotLine(lot, product, location, 1);

        // The lot is in area A1, the configuration targets area B2
        var manager = buildManager(
                "{\"buildingCode\":\"ZC-B1\",\"area\":\"B2\",\"volumeMin\":1.0,\"volumeMax\":100.0,\"validityDelayMinutes\":60}");

        manager.process(store);

        assertEquals(0, ZoneCollectingOrder.count());
        assertEquals(Lot.LotStatus.PENDING, lot.status);
    }

    @Test
    @TestTransaction
    void process_lotWithMixedAreas_createsNoOrder() throws Exception {
        Store store = createStore("ZC");
        StoreBuilding building = createBuilding("ZC-B1");
        Customer customer = createCustomer("ZC");
        Product product = createProduct("ZC", "ZC-EAN-1", new BigDecimal("2.0"));
        StoreShelfLocation locationA1 = createLocation(product, "A1", "A01");
        StoreShelfLocation locationA2 = createLocation(product, "A2", "A02");
        Order omsOrder = createOmsOrder(store, customer, "ZC");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment, LocalDateTime.now().plusMinutes(30), building);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        // One line in the configured area, one line in another area
        addLotLine(lot, product, locationA1, 1);
        addLotLine(lot, product, locationA2, 2);

        var manager = buildManager(
                "{\"buildingCode\":\"ZC-B1\",\"area\":\"A1\",\"volumeMin\":1.0,\"volumeMax\":100.0,\"validityDelayMinutes\":60}");

        manager.process(store);

        // All lines of the lot must be in the configured area
        assertEquals(0, ZoneCollectingOrder.count());
        assertEquals(Lot.LotStatus.PENDING, lot.status);
    }

    @Test
    @TestTransaction
    void process_lotOutsideValidityWindow_createsNoOrder() throws Exception {
        Store store = createStore("ZC");
        StoreBuilding building = createBuilding("ZC-B1");
        Customer customer = createCustomer("ZC");
        Product product = createProduct("ZC", "ZC-EAN-1", new BigDecimal("2.0"));
        StoreShelfLocation location = createLocation(product, "A1", "A01");
        Order omsOrder = createOmsOrder(store, customer, "ZC");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        // Delivery in 2 hours: outside the 60 minutes validity window
        FulfillmentChunk chunk = createChunk(fulfillment, LocalDateTime.now().plusMinutes(120), building);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        addLotLine(lot, product, location, 1);

        var manager = buildManager(
                "{\"buildingCode\":\"ZC-B1\",\"area\":\"A1\",\"volumeMin\":1.0,\"volumeMax\":100.0,\"validityDelayMinutes\":60}");

        manager.process(store);

        assertEquals(0, ZoneCollectingOrder.count());
        assertEquals(Lot.LotStatus.PENDING, lot.status);
    }

    @Test
    @TestTransaction
    void process_weightMode_createsOrder() throws Exception {
        Store store = createStore("ZC");
        StoreBuilding building = createBuilding("ZC-B1");
        Customer customer = createCustomer("ZC");
        // 5 units * 1.5 kg = 7.5 kg total weight
        Product product = createProduct("ZC", "ZC-EAN-1", new BigDecimal("2.0"));
        product.referenceWeight = new BigDecimal("1.5");
        StoreShelfLocation location = createLocation(product, "A1", "A01");
        Order omsOrder = createOmsOrder(store, customer, "ZC");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment, LocalDateTime.now().plusMinutes(30), building);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        addLotLine(lot, product, location, 1);

        var manager = buildManager(
                "{\"buildingCode\":\"ZC-B1\",\"weightMin\":1.0,\"weightMax\":50.0,\"validityDelayMinutes\":60}");

        manager.process(store);

        List<ZoneCollectingOrder> orders = ZoneCollectingOrder.listAll();
        assertEquals(1, orders.size());
        assertTrue(orders.get(0).lots.contains(lot));
    }
}
