package com.intermarche.e2e.engine;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.Configuration;
import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.ReservationRecord;
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
import com.intermarche.backoffice.domain.store.WarehouseBuilding;
import com.intermarche.backoffice.domain.store.WarehouseShelfLocation;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.engine.FulfillmentEngine;
import com.intermarche.backoffice.engine.FulfillmentPollingConsumer;
import com.intermarche.backoffice.engine.fulfillers.BaseFulfillerFactory;
import com.intermarche.backoffice.engine.fulfillers.DefaultFulfillerFactory;
import com.intermarche.backoffice.engine.fulfillers.ProductGroupingFulfillerFactory;
import com.intermarche.backoffice.engine.fulfillers.ZoneGroupingFulfillerFactory;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link FulfillmentEngine}.
 * <p>
 * Covers the fulfiller registry, the per-store fulfiller cache, the full
 * processing pipeline (PENDING chunks routed to READY chunks, reservation
 * marked DONE, Lots created) and the failure paths (missing/invalid
 * configuration emitting a CRITICAL event in an independent transaction,
 * unroutable lines keeping the fulfillment PENDING with a HIGH event).
 * <p>
 * Note on failure-path tests: only the Store (and its Configuration) is
 * committed, in an independent transaction, because the CRITICAL event is
 * published in its own transaction and must be able to refetch the store.
 * The Fulfillment itself stays in the test transaction (rolled back at the
 * end) so that the background {@code FulfillmentPollingConsumer} never
 * reserves it concurrently.
 * <p>
 * The whole class runs on a dedicated in-memory database
 * ({@link FulfillmentEngineTestProfile}) so that the committed stores never
 * interfere with the other test classes sharing the default test database.
 */
@QuarkusTest
@TestProfile(FulfillmentEngineTestProfile.class)
public class FulfillmentEngineTest {

    @Inject
    FulfillmentEngine engine;

    @BeforeEach
    void clearFulfillerCache() {
        // The engine is @ApplicationScoped: its cache survives across tests
        engine.evictAll();
    }

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

    private StoreBuilding createBuilding(Store store, String prefix) {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = prefix + "-BLDG";
        building.address = new Adresse();
        building.persist();
        store.buildings.add(building);
        return building;
    }

    private WarehouseBuilding createWarehouse(Store store, String prefix) {
        WarehouseBuilding building = new WarehouseBuilding();
        building.buildingCode = prefix + "-WH";
        building.address = new Adresse();
        building.persist();
        store.buildings.add(building);
        return building;
    }

    private Configuration createFulfillerConfig(Store store, String specification) {
        Configuration config = new Configuration();
        config.name = "Fulfiller-" + store.code;
        config.type = BaseFulfillerFactory.CONFIGURATION;
        config.specification = specification;
        config.priority = 1;
        config.persist();
        config.stores.add(store);
        return config;
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

    private Product createProduct(String prefix, String weight, String volume) {
        Product product = new Product();
        product.ean = prefix + "-EAN";
        product.name = "Product " + prefix;
        product.productType = ProductType.UNIT;
        product.temperatureZone = TemperatureZone.AMBIENT;
        product.referenceWeight = new BigDecimal(weight);
        product.referenceVolume = new BigDecimal(volume);
        product.persist();
        return product;
    }

    private StoreShelfLocation createShopLocation(Product product, StoreBuilding building, String area, String aisle) {
        StoreShelfLocation loc = new StoreShelfLocation();
        loc.product = product;
        loc.facings = 1;
        loc.area = area;
        loc.aisle = aisle;
        loc.unit = "A";
        loc.elevation = "1";
        loc.position = "01";
        loc.persist();
        building.locations.add(loc);
        return loc;
    }

    private WarehouseShelfLocation createWarehouseLocation(Product product, WarehouseBuilding building, String area, String aisle) {
        WarehouseShelfLocation loc = new WarehouseShelfLocation();
        loc.product = product;
        loc.area = area;
        loc.aisle = aisle;
        loc.unit = "A";
        loc.elevation = "1";
        loc.position = "01";
        loc.persist();
        building.locations.add(loc);
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

    private FulfillmentChunk createPendingChunk(Fulfillment fulfillment) {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.PENDING;
        chunk.label = "Initial";
        chunk.persist();
        fulfillment.addChunk(chunk);
        return chunk;
    }

    private FulfillmentLine addLine(FulfillmentChunk chunk, Product product, String qty, int lineNumber) {
        FulfillmentLine line = new FulfillmentLine();
        line.lineNumber = lineNumber;
        line.product = product;
        line.requestedQuantity = new BigDecimal(qty);
        line.persist();
        chunk.addLine(line);
        return line;
    }

    private Fulfillment createFulfillmentWithPendingChunk(Store store, String prefix) {
        Customer customer = createCustomer(prefix);
        Order order = createOmsOrder(store, customer, prefix);
        Fulfillment fulfillment = createFulfillment(order);
        createPendingChunk(fulfillment);
        return fulfillment;
    }

    private void assertSingleCriticalEvent(Long fulfillmentId) {
        List<Event> events = Event.findByTarget(Fulfillment.class, fulfillmentId);
        assertEquals(1, events.size(), "A CRITICAL event must be persisted in an independent transaction");
        Event event = events.get(0);
        assertEquals(Event.UrgencyLevel.CRITICAL, event.urgencyLevel);
        assertEquals("Erreur Critique de Fulfillment", event.label);
        assertEquals("FULFILLMENT-" + fulfillmentId, event.targetEntityCode);
        assertEquals(Fulfillment.class.getName(), event.targetEntityType);
    }

    // --------------------------------------------------
    // Factory Registry Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void getFulfillersForStore_shouldBuildEveryRegisteredFactory() {
        Store store = createStore("FE08");
        createFulfillerConfig(store, """
                [{"type":"Default"},{"type":"ProductGrouping"},{"type":"ZoneGrouping","buildingCode":"FE08-BLDG"}]
                """);

        List<BaseFulfillerFactory.BaseFulfiller> fulfillers = engine.getFulfillersForStore(store);

        assertEquals(3, fulfillers.size());
        assertInstanceOf(DefaultFulfillerFactory.DefaultFulfiller.class, fulfillers.get(0));
        assertInstanceOf(ProductGroupingFulfillerFactory.ProductGroupingFulfiller.class, fulfillers.get(1));
        assertInstanceOf(ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller.class, fulfillers.get(2));
    }

    // --------------------------------------------------
    // getFulfillersForStore / Cache Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void getFulfillersForStore_shouldReturnEmptyList_whenStoreIsNull() {
        assertTrue(engine.getFulfillersForStore(null).isEmpty());
    }

    @Test
    @TestTransaction
    void getFulfillersForStore_shouldReturnEmptyList_whenStoreHasNoId() {
        assertTrue(engine.getFulfillersForStore(new Store()).isEmpty());
    }

    @Test
    @TestTransaction
    void getFulfillersForStore_shouldReturnEmptyList_whenStoreIsUnknownInDatabase() {
        Store ghost = new Store();
        ghost.id = 999999L;
        assertTrue(engine.getFulfillersForStore(ghost).isEmpty());
    }

    @Test
    @TestTransaction
    void getFulfillersForStore_shouldBuildFulfillersAndCacheThem() {
        Store store = createStore("FE05");
        createFulfillerConfig(store, "[{\"type\":\"Default\"},{\"type\":\"Default\"}]");

        List<BaseFulfillerFactory.BaseFulfiller> first = engine.getFulfillersForStore(store);
        assertEquals(2, first.size());
        assertInstanceOf(DefaultFulfillerFactory.DefaultFulfiller.class, first.get(0));

        List<BaseFulfillerFactory.BaseFulfiller> second = engine.getFulfillersForStore(store);
        assertSame(first, second, "The second call must return the cached list");
    }

    @Test
    @TestTransaction
    void evictCacheForStore_shouldForceARebuild() {
        Store store = createStore("FE06");
        createFulfillerConfig(store, "[{\"type\":\"Default\"}]");

        List<BaseFulfillerFactory.BaseFulfiller> first = engine.getFulfillersForStore(store);
        engine.evictCacheForStore(store.id);
        List<BaseFulfillerFactory.BaseFulfiller> second = engine.getFulfillersForStore(store);

        assertNotSame(first, second);
        assertEquals(1, second.size());
    }

    @Test
    @TestTransaction
    void evictAll_shouldClearTheWholeCache() {
        Store store = createStore("FE07");
        createFulfillerConfig(store, "[{\"type\":\"Default\"}]");

        List<BaseFulfillerFactory.BaseFulfiller> first = engine.getFulfillersForStore(store);
        engine.evictAll();
        List<BaseFulfillerFactory.BaseFulfiller> second = engine.getFulfillersForStore(store);

        assertNotSame(first, second);
        assertEquals(1, second.size());
    }

    // --------------------------------------------------
    // process — Happy Path Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void process_shouldCompleteFulfillmentAndCreateLots_whenAllChunksBecomeReady() throws Exception {
        Store store = createStore("FE01");
        WarehouseBuilding warehouse = createWarehouse(store, "FE01");
        StoreBuilding shop = createBuilding(store, "FE01");
        createFulfillerConfig(store, "[{\"type\":\"Default\"}]");
        Customer customer = createCustomer("FE01");
        Order order = createOmsOrder(store, customer, "FE01");
        Fulfillment fulfillment = createFulfillment(order);

        Product warehouseProduct = createProduct("FE01A", "1.000", "2.000");
        Product shopProduct = createProduct("FE01B", "3.000", "4.000");
        WarehouseShelfLocation warehouseLocation = createWarehouseLocation(warehouseProduct, warehouse, "WH-A", "A01");
        StoreShelfLocation shopLocation = createShopLocation(shopProduct, shop, "SHOP-A", "B01");

        FulfillmentChunk chunk = createPendingChunk(fulfillment);
        chunk.deliveryDate = LocalDateTime.of(2026, 10, 20, 8, 0);
        addLine(chunk, warehouseProduct, "2.000", 1);
        addLine(chunk, shopProduct, "5.000", 2);

        engine.process(fulfillment);

        // The fulfillment is fully processed
        assertEquals(ReservationRecord.ReservationStatus.DONE, fulfillment.reservationRecord.status);
        // The initial PENDING chunk has been replaced by one READY chunk per building
        assertEquals(0, FulfillmentChunk.count("fulfillment = ?1 and type = ?2", fulfillment, FulfillmentChunk.PENDING));
        assertEquals(2, FulfillmentChunk.count("fulfillment = ?1 and type = ?2", fulfillment, FulfillmentChunk.READY));
        assertEquals(2, fulfillment.chunks.size());
        assertTrue(fulfillment.chunks.stream().allMatch(c -> FulfillmentChunk.READY.equals(c.type)));

        Map<String, FulfillmentChunk> chunkByBuilding = fulfillment.chunks.stream()
                .collect(Collectors.toMap(c -> c.building.buildingCode, c -> c));
        FulfillmentChunk warehouseChunk = chunkByBuilding.get("FE01-WH");
        FulfillmentChunk shopChunk = chunkByBuilding.get("FE01-BLDG");
        assertNotNull(warehouseChunk);
        assertNotNull(shopChunk);
        assertEquals("Default", warehouseChunk.label);
        assertEquals(LocalDateTime.of(2026, 10, 20, 8, 0), warehouseChunk.deliveryDate);

        // Lines have been moved with their resolved location
        FulfillmentLine warehouseLine = warehouseChunk.lines.iterator().next();
        assertEquals(warehouseProduct.id, warehouseLine.product.id);
        assertEquals(0, warehouseLine.requestedQuantity.compareTo(new BigDecimal("2.000")));
        assertEquals(warehouseLocation.id, warehouseLine.location.id);
        FulfillmentLine shopLine = shopChunk.lines.iterator().next();
        assertEquals(shopProduct.id, shopLine.product.id);
        assertEquals(0, shopLine.requestedQuantity.compareTo(new BigDecimal("5.000")));
        assertEquals(shopLocation.id, shopLine.location.id);

        // One Lot per READY chunk, with mirrored LotLines
        List<Lot> lots = Lot.find("fulfillment", fulfillment).list();
        assertEquals(2, lots.size());
        for (Lot lot : lots) {
            assertEquals(Lot.LotStatus.PENDING, lot.status);
            assertEquals(store.id, lot.store.id);
            assertEquals(customer.id, lot.customer.id);
            assertEquals(fulfillment.id, lot.fulfillment.id);
            assertTrue(fulfillment.chunks.contains(lot.chunk));
            assertEquals(1, lot.lines.size());
            LotLine lotLine = lot.lines.iterator().next();
            assertNotNull(lotLine.fulfillmentLine);
            FulfillmentLine chunkLine = lot.chunk.lines.iterator().next();
            assertEquals(chunkLine.product.id, lotLine.product.id);
            assertEquals(0, chunkLine.requestedQuantity.compareTo(lotLine.quantity));
        }
    }

    @Test
    @TestTransaction
    void process_shouldApplyEveryConfiguredFulfiller() throws Exception {
        Store store = createStore("FE04");
        StoreBuilding building = createBuilding(store, "FE04");
        createFulfillerConfig(store, "[{\"type\":\"Default\"},{\"type\":\"Default\"}]");
        Customer customer = createCustomer("FE04");
        Order order = createOmsOrder(store, customer, "FE04");
        Fulfillment fulfillment = createFulfillment(order);
        Product product = createProduct("FE04A", "1.000", "1.000");
        createShopLocation(product, building, "SHOP-A", "A01");
        FulfillmentChunk chunk = createPendingChunk(fulfillment);
        addLine(chunk, product, "2.000", 1);

        engine.process(fulfillment);

        // The second Default fulfiller finds no PENDING chunk left: no error, the fulfillment completes
        assertEquals(ReservationRecord.ReservationStatus.DONE, fulfillment.reservationRecord.status);
        assertEquals(1, fulfillment.chunks.size());
        assertEquals(1, Lot.count("fulfillment", fulfillment));
    }

    @Test
    @TestTransaction
    void process_shouldMarkDoneImmediately_whenFulfillmentHasNoChunks() throws Exception {
        Store store = createStore("FE03");
        createFulfillerConfig(store, "[{\"type\":\"Default\"}]");
        Customer customer = createCustomer("FE03");
        Order order = createOmsOrder(store, customer, "FE03");
        Fulfillment fulfillment = createFulfillment(order);

        engine.process(fulfillment);

        assertEquals(ReservationRecord.ReservationStatus.DONE, fulfillment.reservationRecord.status);
        assertEquals(0, Lot.count("fulfillment", fulfillment));
        assertTrue(Event.findByTarget(Fulfillment.class, fulfillment.id).isEmpty());
    }

    // --------------------------------------------------
    // process — Blocking Path Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void process_shouldKeepPendingAndEmitHighEvent_whenProductHasNoLocation() throws Exception {
        Store store = createStore("FE02");
        createBuilding(store, "FE02");
        createFulfillerConfig(store, "[{\"type\":\"Default\"}]");
        Customer customer = createCustomer("FE02");
        Order order = createOmsOrder(store, customer, "FE02");
        Fulfillment fulfillment = createFulfillment(order);
        Product unlocatedProduct = createProduct("FE02A", "1.000", "1.000"); // no location in any building

        FulfillmentChunk chunk = createPendingChunk(fulfillment);
        addLine(chunk, unlocatedProduct, "3.000", 1);

        engine.process(fulfillment);

        // The line could not be routed: the chunk stays PENDING and the reservation is not DONE
        assertEquals(ReservationRecord.ReservationStatus.PENDING, fulfillment.reservationRecord.status);
        assertEquals(1, fulfillment.chunks.size());
        FulfillmentChunk remaining = fulfillment.chunks.iterator().next();
        assertEquals(FulfillmentChunk.PENDING, remaining.type);
        assertEquals(1, remaining.lines.size());
        assertEquals(0, Lot.count("fulfillment", fulfillment));

        // A HIGH urgency event has been emitted in the current transaction
        List<Event> events = Event.findByTarget(Fulfillment.class, fulfillment.id);
        assertEquals(1, events.size());
        assertEquals(Event.UrgencyLevel.HIGH, events.get(0).urgencyLevel);
        assertEquals("Fulfillment Incomplet (Non READY)", events.get(0).label);
        assertTrue(events.get(0).description.contains("Chunk ID " + chunk.id));
    }

    // --------------------------------------------------
    // process — Configuration Failure Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void process_shouldEmitCriticalEventAndRethrow_whenConfigurationIsMissing() throws Exception {
        Long storeId = QuarkusTransaction.requiringNew().call(() -> createStore("FE10").id);
        Fulfillment fulfillment = createFulfillmentWithPendingChunk(Store.findById(storeId), "FE10");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> engine.process(fulfillment));
        assertTrue(ex.getMessage().contains("FE10-S"));

        assertSingleCriticalEvent(fulfillment.id);
    }

    @Test
    @TestTransaction
    void process_shouldEmitCriticalEventAndRethrow_whenFactoryIsUnknown() throws Exception {
        Long storeId = QuarkusTransaction.requiringNew().call(() -> {
            Store store = createStore("FE12");
            createFulfillerConfig(store, "[{\"type\":\"NoSuchFulfiller\"}]");
            return store.id;
        });
        Fulfillment fulfillment = createFulfillmentWithPendingChunk(Store.findById(storeId), "FE12");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine.process(fulfillment));
        assertTrue(ex.getMessage().contains("NoSuchFulfiller"));

        assertSingleCriticalEvent(fulfillment.id);
    }

    @Test
    @TestTransaction
    void process_shouldEmitCriticalEventAndRethrow_whenSpecificationIsNotAnArray() throws Exception {
        Long storeId = QuarkusTransaction.requiringNew().call(() -> {
            Store store = createStore("FE13");
            createFulfillerConfig(store, "{\"type\":\"Default\"}");
            return store.id;
        });
        Fulfillment fulfillment = createFulfillmentWithPendingChunk(Store.findById(storeId), "FE13");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine.process(fulfillment));
        assertTrue(ex.getMessage().contains("not a valid JSON array"));

        assertSingleCriticalEvent(fulfillment.id);
    }

    @Test
    @TestTransaction
    void process_shouldEmitCriticalEventAndRethrow_whenElementMissesTypeField() throws Exception {
        Long storeId = QuarkusTransaction.requiringNew().call(() -> {
            Store store = createStore("FE14");
            createFulfillerConfig(store, "[{\"name\":\"noType\"}]");
            return store.id;
        });
        Fulfillment fulfillment = createFulfillmentWithPendingChunk(Store.findById(storeId), "FE14");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine.process(fulfillment));
        assertTrue(ex.getMessage().contains("missing 'type' field"));

        assertSingleCriticalEvent(fulfillment.id);
    }
}
