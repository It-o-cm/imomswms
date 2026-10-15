package com.intermarche.e2e.domain.executing;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.executing.GroupingExecution;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.domain.store.Tote;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;

/**
 * Integration tests for {@link GroupingExecution}.
 * Covers constructor integrity validation (lot lines, picked items, tote purity),
 * moveToteToDestination, and concludeExecution destination verification.
 */
@QuarkusTest
public class GroupingExecutionTest {

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

    private Employee createEmployee(Store store, String prefix) {
        Employee emp = new Employee();
        emp.firstName = "John";
        emp.lastName = "Doe";
        emp.password = "$2a$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ012345"; // 60 chars
        emp.email = prefix + "@test.com";
        emp.role = Employee.EmployeeRole.PICKER;
        emp.store = store;
        emp.badgeId = prefix + "-BADGE";
        emp.persist();
        return emp;
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

    private FulfillmentChunk createChunk(Fulfillment fulfillment) {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.READY;
        chunk.label = "Chunk";
        chunk.fulfillment = fulfillment;
        chunk.persist();
        return chunk;
    }

    private Buffer createBuffer(Store store, String prefix) {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = prefix + "-BLDG";
        building.address = new Adresse();
        building.persist();
        store.buildings.add(building);

        Buffer buffer = new Buffer();
        buffer.building = building;
        buffer.purpose = Buffer.BufferPurpose.STAGING;
        buffer.bufferCode = prefix + "-BUF";
        buffer.locationDescription = "Buffer " + prefix;
        buffer.ambientCapacity = 10;
        buffer.chilledCapacity = 5;
        buffer.frozenCapacity = 5;
        buffer.persist();
        return buffer;
    }

    private Tote createTote(Store store, String prefix) {
        Tote tote = new Tote();
        tote.code = prefix + "-TOTE";
        tote.volume = new BigDecimal("45.000");
        tote.toteType = Tote.ToteType.STANDARD;
        tote.store = store;
        tote.persist();
        return tote;
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

    private LotLine createLotLine(Lot lot, Product product, StoreShelfLocation location) {
        LotLine lotLine = new LotLine();
        lotLine.product = product;
        lotLine.location = location;
        lotLine.quantity = new BigDecimal("5.000");
        lotLine.persist();
        lot.addLine(lotLine);
        return lotLine;
    }

    private LoadedTote createLoadedTote(Tote tote) {
        LoadedTote loadedTote = new LoadedTote();
        loadedTote.tote = tote;
        loadedTote.storageType = TemperatureZone.AMBIENT;
        loadedTote.status = LoadedTote.Status.IN_USE;
        loadedTote.persist();
        return loadedTote;
    }

    private PickedItem createPickedItem(LotLine lotLine, Product product, LoadedTote loadedTote,
                                        PickedItem.PickStatus status) {
        PickedItem item = new PickedItem();
        item.lotLine = lotLine;
        item.product = product;
        item.quantity = new BigDecimal("5.000");
        item.status = status;
        item.loadedTote = loadedTote;
        item.persist();
        return item;
    }

    private GroupingOrder createGroupingOrder(Store store, Customer customer, Buffer buffer, String prefix) {
        GroupingOrder order = new GroupingOrder();
        order.store = store;
        order.customer = customer;
        order.buffer = buffer;
        order.code = prefix + "-GRP-01";
        order.persist();
        return order;
    }

    /**
     * Builds a fully valid grouping context: one order, one lot, one lot line,
     * one PICKED item inside an IN_USE LoadedTote.
     */
    private GroupingOrder createValidGroupingOrder(Store store, Customer customer, Buffer buffer, String prefix) {
        Order omsOrder = createOrder(store, customer, prefix);
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        Product product = createProduct(prefix, prefix + "-EAN1");
        StoreShelfLocation location = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(lot, product, location);
        Tote tote = createTote(store, prefix);
        LoadedTote loadedTote = createLoadedTote(tote);
        createPickedItem(lotLine, product, loadedTote, PickedItem.PickStatus.PICKED);

        GroupingOrder groupingOrder = createGroupingOrder(store, customer, buffer, prefix);
        groupingOrder.addLot(lot);
        return groupingOrder;
    }

    // --------------------------------------------------
    // Constructor Validation Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void constructor_shouldSucceed_withValidSetup() {
        Store store = createStore("GE01");
        Customer customer = createCustomer("GE01");
        Buffer buffer = createBuffer(store, "GE01");
        Employee employee = createEmployee(store, "GE01");
        GroupingOrder order = createValidGroupingOrder(store, customer, buffer, "GE01");

        GroupingExecution execution = new GroupingExecution(order, employee);

        assertEquals(order.id, execution.handlingOrder.id);
        assertEquals(employee.id, execution.employee.id);
        // The execution must track the LoadedTotes involved in the grouping
        assertEquals(1, execution.loadedTotes.size());
    }

    @Test
    @TestTransaction
    void constructor_shouldThrow_whenNoLots() {
        Store store = createStore("GE02");
        Customer customer = createCustomer("GE02");
        Buffer buffer = createBuffer(store, "GE02");
        Employee employee = createEmployee(store, "GE02");
        GroupingOrder order = createGroupingOrder(store, customer, buffer, "GE02");

        assertThrows(IllegalStateException.class, () -> new GroupingExecution(order, employee));
    }

    @Test
    @TestTransaction
    void constructor_shouldThrow_whenLotHasNoLines() {
        Store store = createStore("GE03");
        Customer customer = createCustomer("GE03");
        Buffer buffer = createBuffer(store, "GE03");
        Employee employee = createEmployee(store, "GE03");
        Order omsOrder = createOrder(store, customer, "GE03");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        GroupingOrder order = createGroupingOrder(store, customer, buffer, "GE03");
        order.addLot(lot); // lot without lines

        assertThrows(IllegalStateException.class, () -> new GroupingExecution(order, employee));
    }

    @Test
    @TestTransaction
    void constructor_shouldThrow_whenMissingPickedItems() {
        Store store = createStore("GE04");
        Customer customer = createCustomer("GE04");
        Buffer buffer = createBuffer(store, "GE04");
        Employee employee = createEmployee(store, "GE04");
        Order omsOrder = createOrder(store, customer, "GE04");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        Product product = createProduct("GE04", "GE04-EAN1");
        StoreShelfLocation location = createLocation(product, "AMBIENT", "A01");
        createLotLine(lot, product, location); // no PickedItem for this line
        GroupingOrder order = createGroupingOrder(store, customer, buffer, "GE04");
        order.addLot(lot);

        assertThrows(IllegalStateException.class, () -> new GroupingExecution(order, employee));
    }

    @Test
    @TestTransaction
    void constructor_shouldThrow_whenItemStatusIsPending() {
        Store store = createStore("GE05");
        Customer customer = createCustomer("GE05");
        Buffer buffer = createBuffer(store, "GE05");
        Employee employee = createEmployee(store, "GE05");
        Order omsOrder = createOrder(store, customer, "GE05");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        Product product = createProduct("GE05", "GE05-EAN1");
        StoreShelfLocation location = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(lot, product, location);
        Tote tote = createTote(store, "GE05");
        LoadedTote loadedTote = createLoadedTote(tote);
        createPickedItem(lotLine, product, loadedTote, PickedItem.PickStatus.PENDING);
        GroupingOrder order = createGroupingOrder(store, customer, buffer, "GE05");
        order.addLot(lot);

        assertThrows(IllegalStateException.class, () -> new GroupingExecution(order, employee));
    }

    @Test
    @TestTransaction
    void constructor_shouldThrow_whenItemHasNoLoadedTote() {
        Store store = createStore("GE06");
        Customer customer = createCustomer("GE06");
        Buffer buffer = createBuffer(store, "GE06");
        Employee employee = createEmployee(store, "GE06");
        Order omsOrder = createOrder(store, customer, "GE06");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        Product product = createProduct("GE06", "GE06-EAN1");
        StoreShelfLocation location = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(lot, product, location);
        createPickedItem(lotLine, product, null, PickedItem.PickStatus.PICKED);
        GroupingOrder order = createGroupingOrder(store, customer, buffer, "GE06");
        order.addLot(lot);

        assertThrows(IllegalStateException.class, () -> new GroupingExecution(order, employee));
    }

    @Test
    @TestTransaction
    void constructor_shouldThrow_whenLoadedToteNotInUse() {
        Store store = createStore("GE07");
        Customer customer = createCustomer("GE07");
        Buffer buffer = createBuffer(store, "GE07");
        Employee employee = createEmployee(store, "GE07");
        Order omsOrder = createOrder(store, customer, "GE07");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        Product product = createProduct("GE07", "GE07-EAN1");
        StoreShelfLocation location = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(lot, product, location);
        Tote tote = createTote(store, "GE07");
        LoadedTote loadedTote = createLoadedTote(tote);
        loadedTote.status = LoadedTote.Status.DELIVERED;
        createPickedItem(lotLine, product, loadedTote, PickedItem.PickStatus.PICKED);
        GroupingOrder order = createGroupingOrder(store, customer, buffer, "GE07");
        order.addLot(lot);

        assertThrows(IllegalStateException.class, () -> new GroupingExecution(order, employee));
    }

    @Test
    @TestTransaction
    void constructor_shouldThrow_whenTotePurityViolated() {
        Store store = createStore("GE08");
        Customer customer = createCustomer("GE08");
        Buffer buffer = createBuffer(store, "GE08");
        Employee employee = createEmployee(store, "GE08");
        Order omsOrder = createOrder(store, customer, "GE08");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        Product product = createProduct("GE08", "GE08-EAN1");
        StoreShelfLocation location = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(lot, product, location);
        Tote tote = createTote(store, "GE08");
        LoadedTote loadedTote = createLoadedTote(tote);
        createPickedItem(lotLine, product, loadedTote, PickedItem.PickStatus.PICKED);

        // Foreign item from ANOTHER lot line inside the same physical tote
        Lot foreignLot = createLot(store, customer, fulfillment, chunk);
        Product foreignProduct = createProduct("GE08", "GE08-EAN2");
        LotLine foreignLotLine = createLotLine(foreignLot, foreignProduct, location);
        createPickedItem(foreignLotLine, foreignProduct, loadedTote, PickedItem.PickStatus.PICKED);

        GroupingOrder order = createGroupingOrder(store, customer, buffer, "GE08");
        order.addLot(lot); // only the first lot is part of this grouping

        assertThrows(IllegalStateException.class, () -> new GroupingExecution(order, employee));
    }

    @Test
    @TestTransaction
    void constructor_shouldAcceptCanceledItem() {
        Store store = createStore("GE09");
        Customer customer = createCustomer("GE09");
        Buffer buffer = createBuffer(store, "GE09");
        Employee employee = createEmployee(store, "GE09");
        Order omsOrder = createOrder(store, customer, "GE09");
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        Product product = createProduct("GE09", "GE09-EAN1");
        StoreShelfLocation location = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(lot, product, location);
        Tote tote = createTote(store, "GE09");
        LoadedTote loadedTote = createLoadedTote(tote);
        createPickedItem(lotLine, product, loadedTote, PickedItem.PickStatus.CANCELED);
        GroupingOrder order = createGroupingOrder(store, customer, buffer, "GE09");
        order.addLot(lot);

        GroupingExecution execution = assertDoesNotThrow(() -> new GroupingExecution(order, employee));
        assertNotNull(execution);
    }

    // --------------------------------------------------
    // moveToteToDestination Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void moveToteToDestination_shouldSetBufferToOrderDestination() {
        Store store = createStore("GE10");
        Customer customer = createCustomer("GE10");
        Buffer buffer = createBuffer(store, "GE10");
        Employee employee = createEmployee(store, "GE10");
        GroupingOrder order = createValidGroupingOrder(store, customer, buffer, "GE10");
        GroupingExecution execution = new GroupingExecution(order, employee);

        // Rebuild the tote reference used by the valid setup
        Tote tote = Tote.find("code", "GE10-TOTE").firstResult();
        LoadedTote loadedTote = LoadedTote.find("tote = ?1 and status = ?2", tote, LoadedTote.Status.IN_USE).firstResult();
        assertNull(loadedTote.buffer);

        execution.moveToteToDestination(tote);

        assertEquals(buffer.id, loadedTote.buffer.id);
    }

    @Test
    @TestTransaction
    void moveToteToDestination_shouldThrow_whenNoActiveLoadedTote() {
        Store store = createStore("GE11");
        Customer customer = createCustomer("GE11");
        Buffer buffer = createBuffer(store, "GE11");
        Employee employee = createEmployee(store, "GE11");
        GroupingOrder order = createValidGroupingOrder(store, customer, buffer, "GE11");
        GroupingExecution execution = new GroupingExecution(order, employee);

        Tote unknownTote = createTote(store, "GE11X"); // never loaded

        assertThrows(IllegalArgumentException.class, () -> execution.moveToteToDestination(unknownTote));
    }

    @Test
    @TestTransaction
    void moveToteToDestination_shouldThrow_whenNoGroupingOrder() {
        Store store = createStore("GE12");
        Tote tote = createTote(store, "GE12");
        GroupingExecution execution = new GroupingExecution(); // no order attached

        assertThrows(IllegalStateException.class, () -> execution.moveToteToDestination(tote));
    }

    // --------------------------------------------------
    // concludeExecution Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void concludeExecution_shouldThrow_whenToteNotAtDestination() {
        Store store = createStore("GE13");
        Customer customer = createCustomer("GE13");
        Buffer buffer = createBuffer(store, "GE13");
        Employee employee = createEmployee(store, "GE13");
        GroupingOrder order = createValidGroupingOrder(store, customer, buffer, "GE13");
        GroupingExecution execution = new GroupingExecution(order, employee);

        // No tote has been moved to the destination buffer
        assertThrows(IllegalStateException.class, execution::concludeExecution);
        assertNotEquals(BaseHandlingOrder.HandlingOrderStatus.DONE, order.status);
    }

    @Test
    @TestTransaction
    void concludeExecution_shouldSucceed_whenAllTotesAtDestination() {
        Store store = createStore("GE14");
        Customer customer = createCustomer("GE14");
        Buffer buffer = createBuffer(store, "GE14");
        Employee employee = createEmployee(store, "GE14");
        GroupingOrder order = createValidGroupingOrder(store, customer, buffer, "GE14");
        GroupingExecution execution = new GroupingExecution(order, employee);

        Tote tote = Tote.find("code", "GE14-TOTE").firstResult();
        execution.moveToteToDestination(tote);

        execution.concludeExecution();

        assertEquals(BaseHandlingOrder.HandlingOrderStatus.DONE, order.status);
    }

    @Test
    @TestTransaction
    void concludeExecution_shouldThrow_whenOrderHasNoBuffer() {
        Store store = createStore("GE15");
        Customer customer = createCustomer("GE15");
        Employee employee = createEmployee(store, "GE15");
        GroupingOrder order = createValidGroupingOrder(store, customer, null, "GE15");
        GroupingExecution execution = new GroupingExecution(order, employee);

        assertThrows(IllegalStateException.class, execution::concludeExecution);
    }

    @Test
    @TestTransaction
    void concludeExecution_shouldThrow_whenNoGroupingOrder() {
        GroupingExecution execution = new GroupingExecution(); // no order attached

        assertThrows(IllegalStateException.class, execution::concludeExecution);
    }
}
