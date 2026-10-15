package com.intermarche.e2e.domain.oms;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.TemperatureZone;

/**
 * Integration tests for {@link FulfillmentChunk}.
 * Covers static query methods (findStoreByChunkId, findBuildingByChunkId),
 * findCommonSpecification, and addLine helper.
 */
@QuarkusTest
public class FulfillmentChunkTest {

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

    private FulfillmentLine createLine(Product product, StoreShelfLocation location, int lineNumber) {
        FulfillmentLine line = new FulfillmentLine();
        line.lineNumber = lineNumber;
        line.product = product;
        line.requestedQuantity = new BigDecimal("2.000");
        line.location = location;
        line.persist();
        return line;
    }

    private StoreBuilding createBuilding(Store store, String prefix) {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = prefix + "-BLDG";
        building.address = new Adresse();
        building.persist();
        store.buildings.add(building);
        return building;
    }

    // --------------------------------------------------
    // Constants Tests
    // --------------------------------------------------

    @Test
    void constants_shouldHaveExpectedValues() {
        assertEquals("PENDING", FulfillmentChunk.PENDING);
        assertEquals("READY", FulfillmentChunk.READY);
    }

    // --------------------------------------------------
    // addLine Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void addLine_shouldAddLineToChunk() {
        Store store = createStore("FC01");
        Customer customer = createCustomer("FC01");
        Order order = createOrder(store, customer, "FC01");
        Fulfillment fulfillment = createFulfillment(order);
        FulfillmentChunk chunk = createChunk(fulfillment, FulfillmentChunk.READY);

        Product product = createProduct("FC01", "FC01-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        FulfillmentLine line = createLine(product, loc, 1);

        chunk.addLine(line);

        assertTrue(chunk.lines.contains(line));
        assertEquals(1, chunk.lines.size());
    }

    // --------------------------------------------------
    // findStoreByChunkId Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void findStoreByChunkId_shouldReturnStore() {
        Store store = createStore("FC02");
        Customer customer = createCustomer("FC02");
        Order order = createOrder(store, customer, "FC02");
        Fulfillment fulfillment = createFulfillment(order);
        FulfillmentChunk chunk = createChunk(fulfillment, FulfillmentChunk.READY);

        Store found = FulfillmentChunk.findStoreByChunkId(chunk.id);

        assertNotNull(found);
        assertEquals(store.id, found.id);
    }

    @Test
    @TestTransaction
    void findStoreByChunkId_shouldReturnNull_whenChunkIdIsNull() {
        assertNull(FulfillmentChunk.findStoreByChunkId(null));
    }

    @Test
    @TestTransaction
    void findStoreByChunkId_shouldReturnNull_whenChunkNotFound() {
        assertNull(FulfillmentChunk.findStoreByChunkId(999999L));
    }

    // --------------------------------------------------
    // findBuildingByChunkId Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void findBuildingByChunkId_shouldReturnBuilding() {
        Store store = createStore("FC03");
        Customer customer = createCustomer("FC03");
        Order order = createOrder(store, customer, "FC03");
        Fulfillment fulfillment = createFulfillment(order);
        FulfillmentChunk chunk = createChunk(fulfillment, FulfillmentChunk.READY);
        StoreBuilding building = createBuilding(store, "FC03");
        chunk.building = building;

        Building found = FulfillmentChunk.findBuildingByChunkId(chunk.id);

        assertNotNull(found);
        assertEquals(building.id, found.id);
    }

    @Test
    @TestTransaction
    void findBuildingByChunkId_shouldReturnNull_whenNoBuildingAssigned() {
        Store store = createStore("FC04");
        Customer customer = createCustomer("FC04");
        Order order = createOrder(store, customer, "FC04");
        Fulfillment fulfillment = createFulfillment(order);
        FulfillmentChunk chunk = createChunk(fulfillment, FulfillmentChunk.READY);
        // No building assigned

        Building found = FulfillmentChunk.findBuildingByChunkId(chunk.id);

        assertNull(found);
    }

    @Test
    @TestTransaction
    void findBuildingByChunkId_shouldReturnNull_whenChunkIdIsNull() {
        assertNull(FulfillmentChunk.findBuildingByChunkId(null));
    }

    // --------------------------------------------------
    // findCommonSpecification Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void findCommonSpecification_shouldReturnSpec_whenAllLinesShareLocation() {
        Store store = createStore("FC05");
        Customer customer = createCustomer("FC05");
        Order order = createOrder(store, customer, "FC05");
        Fulfillment fulfillment = createFulfillment(order);
        FulfillmentChunk chunk = createChunk(fulfillment, FulfillmentChunk.READY);
        StoreBuilding building = createBuilding(store, "FC05");

        Product p1 = createProduct("FC05", "FC05-EAN1");
        Product p2 = createProduct("FC05", "FC05-EAN2");
        StoreShelfLocation loc1 = createLocation(p1, "FRESH", "A01");
        StoreShelfLocation loc2 = createLocation(p2, "FRESH", "A01");
        building.locations.add(loc1);
        building.locations.add(loc2);

        chunk.addLine(createLine(p1, loc1, 1));
        chunk.addLine(createLine(p2, loc2, 2));

        BaseLocation.LocationSpecification spec = FulfillmentChunk.findCommonSpecification(chunk);

        assertNotNull(spec);
        assertNotNull(spec.building);
        assertEquals("FRESH", spec.area);
        assertEquals("A01", spec.aisle);
    }

    @Test
    @TestTransaction
    void findCommonSpecification_shouldReturnNullArea_whenLinesInDifferentAreas() {
        Store store = createStore("FC06");
        Customer customer = createCustomer("FC06");
        Order order = createOrder(store, customer, "FC06");
        Fulfillment fulfillment = createFulfillment(order);
        FulfillmentChunk chunk = createChunk(fulfillment, FulfillmentChunk.READY);
        StoreBuilding building = createBuilding(store, "FC06");

        Product p1 = createProduct("FC06", "FC06-EAN1");
        Product p2 = createProduct("FC06", "FC06-EAN2");
        StoreShelfLocation loc1 = createLocation(p1, "FRESH", "A01");
        StoreShelfLocation loc2 = createLocation(p2, "FROZEN", "B02");
        building.locations.add(loc1);
        building.locations.add(loc2);

        chunk.addLine(createLine(p1, loc1, 1));
        chunk.addLine(createLine(p2, loc2, 2));

        BaseLocation.LocationSpecification spec = FulfillmentChunk.findCommonSpecification(chunk);

        assertNotNull(spec);
        // Different areas → common area is null, but building is still common
        assertNotNull(spec.building);
        assertNull(spec.area);
        assertNull(spec.aisle);
    }

    @Test
    @TestTransaction
    void findCommonSpecification_shouldReturnAreaOnly_whenSameAreaDifferentAisles() {
        Store store = createStore("FC08");
        Customer customer = createCustomer("FC08");
        Order order = createOrder(store, customer, "FC08");
        Fulfillment fulfillment = createFulfillment(order);
        FulfillmentChunk chunk = createChunk(fulfillment, FulfillmentChunk.READY);
        StoreBuilding building = createBuilding(store, "FC08");

        Product p1 = createProduct("FC08", "FC08-EAN1");
        Product p2 = createProduct("FC08", "FC08-EAN2");
        StoreShelfLocation loc1 = createLocation(p1, "FRESH", "A01");
        StoreShelfLocation loc2 = createLocation(p2, "FRESH", "A02");
        building.locations.add(loc1);
        building.locations.add(loc2);

        chunk.addLine(createLine(p1, loc1, 1));
        chunk.addLine(createLine(p2, loc2, 2));

        BaseLocation.LocationSpecification spec = FulfillmentChunk.findCommonSpecification(chunk);

        assertNotNull(spec);
        assertNotNull(spec.building);
        assertEquals("FRESH", spec.area);
        assertNull(spec.aisle);
    }

    @Test
    @TestTransaction
    void findCommonSpecification_shouldReturnNullArea_whenDifferentAreasSameAisle() {
        Store store = createStore("FC09");
        Customer customer = createCustomer("FC09");
        Order order = createOrder(store, customer, "FC09");
        Fulfillment fulfillment = createFulfillment(order);
        FulfillmentChunk chunk = createChunk(fulfillment, FulfillmentChunk.READY);
        StoreBuilding building = createBuilding(store, "FC09");

        Product p1 = createProduct("FC09", "FC09-EAN1");
        Product p2 = createProduct("FC09", "FC09-EAN2");
        StoreShelfLocation loc1 = createLocation(p1, "FRESH", "A01");
        StoreShelfLocation loc2 = createLocation(p2, "FROZEN", "A01");
        building.locations.add(loc1);
        building.locations.add(loc2);

        chunk.addLine(createLine(p1, loc1, 1));
        chunk.addLine(createLine(p2, loc2, 2));

        BaseLocation.LocationSpecification spec = FulfillmentChunk.findCommonSpecification(chunk);

        assertNotNull(spec);
        assertNotNull(spec.building);
        // Areas differ -> common area is null, but the aisle code is still common
        assertNull(spec.area);
        assertEquals("A01", spec.aisle);
    }

    @Test
    @TestTransaction
    void findCommonSpecification_shouldReturnAllNull_whenLocationsInDifferentBuildings() {
        Store store = createStore("FC10");
        Customer customer = createCustomer("FC10");
        Order order = createOrder(store, customer, "FC10");
        Fulfillment fulfillment = createFulfillment(order);
        FulfillmentChunk chunk = createChunk(fulfillment, FulfillmentChunk.READY);
        StoreBuilding building1 = createBuilding(store, "FC10A");
        StoreBuilding building2 = createBuilding(store, "FC10B");

        Product p1 = createProduct("FC10", "FC10-EAN1");
        Product p2 = createProduct("FC10", "FC10-EAN2");
        StoreShelfLocation loc1 = createLocation(p1, "FRESH", "A01");
        StoreShelfLocation loc2 = createLocation(p2, "FRESH", "A01");
        building1.locations.add(loc1);
        building2.locations.add(loc2);

        chunk.addLine(createLine(p1, loc1, 1));
        chunk.addLine(createLine(p2, loc2, 2));

        BaseLocation.LocationSpecification spec = FulfillmentChunk.findCommonSpecification(chunk);

        assertNotNull(spec);
        assertNull(spec.building);
        assertNull(spec.area);
        assertNull(spec.aisle);
    }

    @Test
    @TestTransaction
    void findCommonSpecification_shouldReturnAllNull_whenNoLines() {
        Store store = createStore("FC11");
        Customer customer = createCustomer("FC11");
        Order order = createOrder(store, customer, "FC11");
        Fulfillment fulfillment = createFulfillment(order);
        FulfillmentChunk chunk = createChunk(fulfillment, FulfillmentChunk.READY);

        BaseLocation.LocationSpecification spec = FulfillmentChunk.findCommonSpecification(chunk);

        assertNotNull(spec);
        assertNull(spec.store);
        assertNull(spec.building);
        assertNull(spec.area);
        assertNull(spec.aisle);
    }

    // --------------------------------------------------
    // Checksum Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenTypeChanges() {
        Store store = createStore("FC07");
        Customer customer = createCustomer("FC07");
        Order order = createOrder(store, customer, "FC07");
        Fulfillment fulfillment = createFulfillment(order);
        FulfillmentChunk chunk = createChunk(fulfillment, FulfillmentChunk.PENDING);

        int checksum1 = chunk.getChecksum();

        chunk.type = FulfillmentChunk.READY;
        int checksum2 = chunk.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }
}
