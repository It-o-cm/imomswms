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
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
import com.intermarche.backoffice.engine.handling.BaseOrderHandlerBuilderFactory;
import com.intermarche.backoffice.engine.handling.ProductCollectingOrderManagerFactory;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ProductCollectingOrderManagerFactory.ProductCollectingOrderManager#process(Store)}.
 * <p>
 * Regression test for the strict EAN constraint: a lot containing ANY product outside the
 * configured EAN list MUST be excluded, even if it also contains products from the list.
 */
@QuarkusTest
class ProductCollectingOrderManagerFactoryProcessTest {

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

    private Product createProduct(String prefix, String ean) {
        Product product = new Product();
        product.ean = ean;
        product.name = "Product " + prefix;
        product.productType = ProductType.UNIT;
        product.temperatureZone = TemperatureZone.AMBIENT;
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
        return new ProductCollectingOrderManagerFactory().build(specNode);
    }

    // --------------------------------------------------
    // process
    // --------------------------------------------------

    @Test
    @TestTransaction
    void process_lotWithOnlyConfiguredEans_createsOrder() throws Exception {
        Store store = createStore("PC");
        StoreBuilding building = createBuilding("PC-B1");
        Customer customer = createCustomer("PC");
        Product product = createProduct("PC", "PC-EAN-IN");
        StoreShelfLocation location = createLocation(product, "A1", "A01");
        Order omsOrder = createOmsOrder(store, customer, "PC");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment, LocalDateTime.now().plusMinutes(30), building);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        addLotLine(lot, product, location, 1);

        var manager = buildManager(
                "{\"buildingCode\":\"PC-B1\",\"eans\":[\"PC-EAN-IN\"],\"quantityMin\":1,\"quantityMax\":100,\"validityDelayMinutes\":60}");

        manager.process(store);

        List<ProductCollectingOrder> orders = ProductCollectingOrder.listAll();
        assertEquals(1, orders.size());
        ProductCollectingOrder order = orders.get(0);
        assertEquals(store.id, order.store.id);
        assertEquals(building.id, order.building.id);
        assertEquals(List.of("PC-EAN-IN"), order.eans);
        assertTrue(order.lots.contains(lot));
        assertEquals(Lot.LotStatus.PLANNED_TO_BE_COLLECTED, lot.status);
    }

    @Test
    @TestTransaction
    void process_lotWithForeignEanLine_createsNoOrder() throws Exception {
        Store store = createStore("PC");
        StoreBuilding building = createBuilding("PC-B1");
        Customer customer = createCustomer("PC");
        Product productIn = createProduct("PC", "PC-EAN-IN");
        Product productOut = createProduct("PC", "PC-EAN-OUT");
        StoreShelfLocation location = createLocation(productIn, "A1", "A01");
        Order omsOrder = createOmsOrder(store, customer, "PC");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment, LocalDateTime.now().plusMinutes(30), building);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        // The lot contains one line with a configured EAN and one line with a foreign EAN
        addLotLine(lot, productIn, location, 1);
        addLotLine(lot, productOut, location, 2);

        var manager = buildManager(
                "{\"buildingCode\":\"PC-B1\",\"eans\":[\"PC-EAN-IN\"],\"quantityMin\":1,\"quantityMax\":100,\"validityDelayMinutes\":60}");

        manager.process(store);

        // Strict constraint: the lot must contain ONLY products from the EAN list
        assertEquals(0, ProductCollectingOrder.count());
        assertEquals(Lot.LotStatus.PENDING, lot.status);
    }

    @Test
    @TestTransaction
    void process_lotOutsideValidityWindow_createsNoOrder() throws Exception {
        Store store = createStore("PC");
        StoreBuilding building = createBuilding("PC-B1");
        Customer customer = createCustomer("PC");
        Product product = createProduct("PC", "PC-EAN-IN");
        StoreShelfLocation location = createLocation(product, "A1", "A01");
        Order omsOrder = createOmsOrder(store, customer, "PC");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        // Delivery in 2 hours: outside the 60 minutes validity window
        FulfillmentChunk chunk = createChunk(fulfillment, LocalDateTime.now().plusMinutes(120), building);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        addLotLine(lot, product, location, 1);

        var manager = buildManager(
                "{\"buildingCode\":\"PC-B1\",\"eans\":[\"PC-EAN-IN\"],\"quantityMin\":1,\"quantityMax\":100,\"validityDelayMinutes\":60}");

        manager.process(store);

        assertEquals(0, ProductCollectingOrder.count());
        assertEquals(Lot.LotStatus.PENDING, lot.status);
    }

    @Test
    @TestTransaction
    void process_lotInOtherBuilding_createsNoOrder() throws Exception {
        Store store = createStore("PC");
        StoreBuilding building = createBuilding("PC-B1");
        StoreBuilding otherBuilding = createBuilding("PC-B2");
        Customer customer = createCustomer("PC");
        Product product = createProduct("PC", "PC-EAN-IN");
        StoreShelfLocation location = createLocation(product, "A1", "A01");
        Order omsOrder = createOmsOrder(store, customer, "PC");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment, LocalDateTime.now().plusMinutes(30), otherBuilding);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        addLotLine(lot, product, location, 1);

        var manager = buildManager(
                "{\"buildingCode\":\"PC-B1\",\"eans\":[\"PC-EAN-IN\"],\"quantityMin\":1,\"quantityMax\":100,\"validityDelayMinutes\":60}");

        manager.process(store);

        assertEquals(0, ProductCollectingOrder.count());
        assertEquals(Lot.LotStatus.PENDING, lot.status);
    }

    @Test
    @TestTransaction
    void process_quantityBelowMin_discardsOrder() throws Exception {
        Store store = createStore("PC");
        StoreBuilding building = createBuilding("PC-B1");
        Customer customer = createCustomer("PC");
        Product product = createProduct("PC", "PC-EAN-IN");
        StoreShelfLocation location = createLocation(product, "A1", "A01");
        Order omsOrder = createOmsOrder(store, customer, "PC");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment, LocalDateTime.now().plusMinutes(30), building);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        addLotLine(lot, product, location, 1); // quantity 5

        // quantityMin 10 > actual quantity 5 -> the order does not meet the minimum
        var manager = buildManager(
                "{\"buildingCode\":\"PC-B1\",\"eans\":[\"PC-EAN-IN\"],\"quantityMin\":10,\"quantityMax\":100,\"validityDelayMinutes\":60}");

        manager.process(store);

        assertEquals(0, ProductCollectingOrder.count());
        // The lot status is still updated even if the order is discarded
        assertEquals(Lot.LotStatus.PLANNED_TO_BE_COLLECTED, lot.status);
    }
}
