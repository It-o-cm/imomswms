package com.intermarche.e2e.domain.executing;

import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.store.Tote;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.TemperatureZone;

/**
 * Integration tests for {@link PickingExecution}.
 * Covers tote management, item picking (full/split/substitution), item removal with merge,
 * cancellation, and execution conclusion.
 */
@QuarkusTest
public class PickingExecutionTest {

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

    private ProductCollectingOrder createOrder(Store store, String prefix) {
        ProductCollectingOrder order = new ProductCollectingOrder();
        order.store = store;
        order.code = prefix + "-ORD-001";
        order.persist();
        return order;
    }

    private PickingExecution createExecution(ProductCollectingOrder order, Employee employee) {
        PickingExecution execution = new PickingExecution();
        execution.handlingOrder = order;
        execution.employee = employee;
        execution.persist();
        return execution;
    }

    private Tote createTote(Store store, String prefix, Tote.ToteType type) {
        Tote tote = new Tote();
        tote.code = prefix + "-TOTE";
        tote.volume = new BigDecimal("45.000");
        tote.toteType = type;
        tote.store = store;
        tote.persist();
        return tote;
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

    private LotLine createLotLine(Product product, StoreShelfLocation location) {
        LotLine lotLine = new LotLine();
        lotLine.product = product;
        lotLine.location = location;
        lotLine.quantity = new BigDecimal("5.000");
        lotLine.persist();
        return lotLine;
    }

    private PickedItem createPickedItem(LotLine lotLine, Product product, String qty) {
        PickedItem item = new PickedItem();
        item.lotLine = lotLine;
        item.product = product;
        item.quantity = new BigDecimal(qty);
        item.status = PickedItem.PickStatus.PENDING;
        item.persist();
        return item;
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
        buffer.ambientCapacity = 10;
        buffer.chilledCapacity = 5;
        buffer.frozenCapacity = 5;
        buffer.persist();
        return buffer;
    }

    /**
     * Creates the full chain Customer → Order → Fulfillment → Chunk → FulfillmentLine → Lot → LotLine
     * and links the Lot to the given ProductCollectingOrder. Returns the LotLine.
     */
    private LotLine createFullChain(ProductCollectingOrder order, Store store, Product product,
                                    StoreShelfLocation location, String prefix) {
        Customer customer = new Customer();
        customer.customerId = prefix + "-CUST";
        customer.firstName = "Jane";
        customer.lastName = "Smith";
        customer.email = prefix + "-cust@test.com";
        customer.persist();

        Order customerOrder = new Order();
        customerOrder.orderId = prefix + "-ORDER";
        customerOrder.orderDate = LocalDateTime.now();
        customerOrder.channel = "ECOMMERCE";
        customerOrder.status = "VALIDATED";
        customerOrder.store = store;
        customerOrder.customer = customer;
        customerOrder.persist();

        Fulfillment fulfillment = new Fulfillment();
        fulfillment.order = customerOrder;
        fulfillment.persist();

        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.READY;
        chunk.label = "Test";
        chunk.persist();
        fulfillment.chunks.add(chunk);

        FulfillmentLine fLine = new FulfillmentLine();
        fLine.lineNumber = 1;
        fLine.product = product;
        fLine.requestedQuantity = new BigDecimal("5.000");
        fLine.location = location;
        fLine.persist();
        chunk.lines.add(fLine);

        Lot lot = new Lot();
        lot.store = store;
        lot.chunk = chunk;
        lot.fulfillment = fulfillment;
        lot.customer = customer;
        lot.persist();

        LotLine lotLine = new LotLine();
        lotLine.product = product;
        lotLine.location = location;
        lotLine.setFulfillmentLine(fLine);
        lotLine.quantity = new BigDecimal("5.000");
        lotLine.persist();
        lot.addLine(lotLine);

        order.addLot(lot);

        return lotLine;
    }

    // --------------------------------------------------
    // addTote Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void addTote_shouldCreateLoadedTote_whenToteTypeIsCompatible() {
        Store store = createStore("PE01");
        Employee emp = createEmployee(store, "PE01");
        ProductCollectingOrder order = createOrder(store, "PE01");
        PickingExecution execution = createExecution(order, emp);
        Tote tote = createTote(store, "PE01", Tote.ToteType.STANDARD);

        LoadedTote loadedTote = execution.addTote(tote, TemperatureZone.AMBIENT);

        assertNotNull(loadedTote);
        assertEquals(TemperatureZone.AMBIENT, loadedTote.storageType);
        assertEquals(LoadedTote.Status.IN_USE, loadedTote.status);
        assertTrue(execution.loadedTotes.contains(loadedTote));
    }

    @Test
    @TestTransaction
    void addTote_shouldThrow_whenToteIsNull() {
        Store store = createStore("PE02");
        Employee emp = createEmployee(store, "PE02");
        ProductCollectingOrder order = createOrder(store, "PE02");
        PickingExecution execution = createExecution(order, emp);

        assertThrows(IllegalArgumentException.class,
                () -> execution.addTote(null, TemperatureZone.AMBIENT));
    }

    @Test
    @TestTransaction
    void addTote_shouldThrow_whenUsageNotAllowedForToteType() {
        Store store = createStore("PE03");
        Employee emp = createEmployee(store, "PE03");
        ProductCollectingOrder order = createOrder(store, "PE03");
        PickingExecution execution = createExecution(order, emp);
        // STANDARD tote does not allow CHILLED
        Tote tote = createTote(store, "PE03", Tote.ToteType.STANDARD);

        assertThrows(IllegalArgumentException.class,
                () -> execution.addTote(tote, TemperatureZone.CHILLED));
    }

    @Test
    @TestTransaction
    void addTote_shouldAllowChilled_whenToteIsInsulated() {
        Store store = createStore("PE04");
        Employee emp = createEmployee(store, "PE04");
        ProductCollectingOrder order = createOrder(store, "PE04");
        PickingExecution execution = createExecution(order, emp);
        Tote tote = createTote(store, "PE04", Tote.ToteType.INSULATED);

        LoadedTote loadedTote = execution.addTote(tote, TemperatureZone.CHILLED);

        assertNotNull(loadedTote);
        assertEquals(TemperatureZone.CHILLED, loadedTote.storageType);
    }

    // --------------------------------------------------
    // removeEmptyTote Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void removeEmptyTote_shouldRemoveTote_whenItIsEmpty() {
        Store store = createStore("PE05");
        Employee emp = createEmployee(store, "PE05");
        ProductCollectingOrder order = createOrder(store, "PE05");
        PickingExecution execution = createExecution(order, emp);
        Tote tote = createTote(store, "PE05", Tote.ToteType.STANDARD);
        execution.addTote(tote, TemperatureZone.AMBIENT);

        assertEquals(1, execution.loadedTotes.size());

        execution.removeEmptyTote(tote);

        assertTrue(execution.loadedTotes.isEmpty());
    }

    @Test
    @TestTransaction
    void removeEmptyTote_shouldThrow_whenToteIsNull() {
        Store store = createStore("PE06");
        Employee emp = createEmployee(store, "PE06");
        ProductCollectingOrder order = createOrder(store, "PE06");
        PickingExecution execution = createExecution(order, emp);

        assertThrows(IllegalArgumentException.class,
                () -> execution.removeEmptyTote(null));
    }

    @Test
    @TestTransaction
    void removeEmptyTote_shouldThrow_whenToteNotInExecution() {
        Store store = createStore("PE07");
        Employee emp = createEmployee(store, "PE07");
        ProductCollectingOrder order = createOrder(store, "PE07");
        PickingExecution execution = createExecution(order, emp);
        Tote tote = createTote(store, "PE07", Tote.ToteType.STANDARD);
        // Tote was never added to the execution

        assertThrows(IllegalArgumentException.class,
                () -> execution.removeEmptyTote(tote));
    }

    @Test
    @TestTransaction
    void removeEmptyTote_shouldThrow_whenToteContainsItems() {
        Store store = createStore("PE08");
        Employee emp = createEmployee(store, "PE08");
        ProductCollectingOrder order = createOrder(store, "PE08");
        PickingExecution execution = createExecution(order, emp);
        Tote tote = createTote(store, "PE08", Tote.ToteType.STANDARD);
        LoadedTote loadedTote = execution.addTote(tote, TemperatureZone.AMBIENT);

        // Add an item to the tote
        Product product = createProduct("PE08", "PE08-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(product, loc);
        PickedItem item = createPickedItem(lotLine, product, "2.000");
        execution.addItemToPick(item);
        execution.addItemToTote(loadedTote, item, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> execution.removeEmptyTote(tote));
    }

    // --------------------------------------------------
    // addItemToTote Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void addItemToTote_shouldMoveFullItem_whenQuantityIsNull() {
        Store store = createStore("PE09");
        Employee emp = createEmployee(store, "PE09");
        ProductCollectingOrder order = createOrder(store, "PE09");
        PickingExecution execution = createExecution(order, emp);
        Tote tote = createTote(store, "PE09", Tote.ToteType.STANDARD);
        LoadedTote loadedTote = execution.addTote(tote, TemperatureZone.AMBIENT);

        Product product = createProduct("PE09", "PE09-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(product, loc);
        PickedItem item = createPickedItem(lotLine, product, "3.000");
        execution.addItemToPick(item);

        execution.addItemToTote(loadedTote, item, null, null);

        assertFalse(execution.itemsToPick.contains(item));
        assertTrue(loadedTote.items.contains(item));
        assertEquals(item, item.loadedTote != null ? item : null);
        assertEquals(PickedItem.PickStatus.PICKED, item.status);
        assertEquals(loadedTote, item.loadedTote);
    }

    @Test
    @TestTransaction
    void addItemToTote_shouldSplitItem_whenQuantityIsLessThanTotal() {
        Store store = createStore("PE10");
        Employee emp = createEmployee(store, "PE10");
        ProductCollectingOrder order = createOrder(store, "PE10");
        PickingExecution execution = createExecution(order, emp);
        Tote tote = createTote(store, "PE10", Tote.ToteType.STANDARD);
        LoadedTote loadedTote = execution.addTote(tote, TemperatureZone.AMBIENT);

        Product product = createProduct("PE10", "PE10-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(product, loc);
        PickedItem item = createPickedItem(lotLine, product, "5.000");
        execution.addItemToPick(item);

        // Move only 2 out of 5
        execution.addItemToTote(loadedTote, item, new BigDecimal("2.000"), null);

        // Original item should remain in work list with reduced quantity
        assertTrue(execution.itemsToPick.contains(item));
        assertEquals(0, new BigDecimal("3.000").compareTo(item.quantity));

        // A new item should be in the tote with quantity 2
        assertEquals(1, loadedTote.items.size());
        PickedItem movedItem = loadedTote.items.iterator().next();
        assertEquals(0, new BigDecimal("2.000").compareTo(movedItem.quantity));
        assertEquals(PickedItem.PickStatus.PICKED, movedItem.status);
        assertEquals(loadedTote, movedItem.loadedTote);
    }

    @Test
    @TestTransaction
    void addItemToTote_shouldSubstituteProduct_whenNewProductProvided() {
        Store store = createStore("PE11");
        Employee emp = createEmployee(store, "PE11");
        ProductCollectingOrder order = createOrder(store, "PE11");
        PickingExecution execution = createExecution(order, emp);
        Tote tote = createTote(store, "PE11", Tote.ToteType.STANDARD);
        LoadedTote loadedTote = execution.addTote(tote, TemperatureZone.AMBIENT);

        Product originalProduct = createProduct("PE11", "PE11-EAN-ORIG");
        Product substituteProduct = createProduct("PE11", "PE11-EAN-SUB");
        StoreShelfLocation loc = createLocation(originalProduct, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(originalProduct, loc);
        PickedItem item = createPickedItem(lotLine, originalProduct, "1.000");
        execution.addItemToPick(item);

        execution.addItemToTote(loadedTote, item, null, substituteProduct);

        assertEquals(substituteProduct.id, item.product.id);
    }

    @Test
    @TestTransaction
    void addItemToTote_shouldThrow_whenQuantityExceedsItemQuantity() {
        Store store = createStore("PE12");
        Employee emp = createEmployee(store, "PE12");
        ProductCollectingOrder order = createOrder(store, "PE12");
        PickingExecution execution = createExecution(order, emp);
        Tote tote = createTote(store, "PE12", Tote.ToteType.STANDARD);
        LoadedTote loadedTote = execution.addTote(tote, TemperatureZone.AMBIENT);

        Product product = createProduct("PE12", "PE12-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(product, loc);
        PickedItem item = createPickedItem(lotLine, product, "2.000");
        execution.addItemToPick(item);

        assertThrows(IllegalArgumentException.class,
                () -> execution.addItemToTote(loadedTote, item, new BigDecimal("5.000"), null));
    }

    @Test
    @TestTransaction
    void addItemToTote_shouldThrow_whenToteNotInExecution() {
        Store store = createStore("PE13");
        Employee emp = createEmployee(store, "PE13");
        ProductCollectingOrder order = createOrder(store, "PE13");
        PickingExecution execution = createExecution(order, emp);

        // Create a LoadedTote that is NOT part of this execution
        Tote tote = createTote(store, "PE13", Tote.ToteType.STANDARD);
        LoadedTote foreignTote = LoadedTote.useAnEmptyTote(tote);
        foreignTote.storageType = TemperatureZone.AMBIENT;
        foreignTote.persist();

        Product product = createProduct("PE13", "PE13-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(product, loc);
        PickedItem item = createPickedItem(lotLine, product, "1.000");
        execution.addItemToPick(item);

        assertThrows(IllegalArgumentException.class,
                () -> execution.addItemToTote(foreignTote, item, null, null));
    }

    @Test
    @TestTransaction
    void addItemToTote_shouldThrow_whenItemNotInWorkList() {
        Store store = createStore("PE14");
        Employee emp = createEmployee(store, "PE14");
        ProductCollectingOrder order = createOrder(store, "PE14");
        PickingExecution execution = createExecution(order, emp);
        Tote tote = createTote(store, "PE14", Tote.ToteType.STANDARD);
        LoadedTote loadedTote = execution.addTote(tote, TemperatureZone.AMBIENT);

        Product product = createProduct("PE14", "PE14-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(product, loc);
        PickedItem item = createPickedItem(lotLine, product, "1.000");
        // Item is NOT added to itemsToPick

        assertThrows(IllegalArgumentException.class,
                () -> execution.addItemToTote(loadedTote, item, null, null));
    }

    // --------------------------------------------------
    // removeItemFromTote Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void removeItemFromTote_shouldReturnItemToWorkList_whenNoExistingItem() {
        Store store = createStore("PE15");
        Employee emp = createEmployee(store, "PE15");
        ProductCollectingOrder order = createOrder(store, "PE15");
        PickingExecution execution = createExecution(order, emp);
        Tote tote = createTote(store, "PE15", Tote.ToteType.STANDARD);
        LoadedTote loadedTote = execution.addTote(tote, TemperatureZone.AMBIENT);

        Product product = createProduct("PE15", "PE15-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(product, loc);
        PickedItem item = createPickedItem(lotLine, product, "2.000");
        execution.addItemToPick(item);
        execution.addItemToTote(loadedTote, item, null, null);

        // Item is now in the tote, not in work list
        assertFalse(execution.itemsToPick.contains(item));
        assertTrue(loadedTote.items.contains(item));

        execution.removeItemFromTote(loadedTote, item);

        // Item should be back in work list
        assertTrue(execution.itemsToPick.contains(item));
        assertFalse(loadedTote.items.contains(item));
        assertNull(item.loadedTote);
        assertEquals(PickedItem.PickStatus.PENDING, item.status);
    }

    @Test
    @TestTransaction
    void removeItemFromTote_shouldMergeQuantities_whenExistingItemForSameLotLine() {
        Store store = createStore("PE16");
        Employee emp = createEmployee(store, "PE16");
        ProductCollectingOrder order = createOrder(store, "PE16");
        PickingExecution execution = createExecution(order, emp);
        Tote tote = createTote(store, "PE16", Tote.ToteType.STANDARD);
        LoadedTote loadedTote = execution.addTote(tote, TemperatureZone.AMBIENT);

        Product product = createProduct("PE16", "PE16-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(product, loc);

        // Create an item with quantity 5, split it: move 2 to tote, leaving 3 in work list
        PickedItem item = createPickedItem(lotLine, product, "5.000");
        execution.addItemToPick(item);
        execution.addItemToTote(loadedTote, item, new BigDecimal("2.000"), null);

        // Now: work list has item with qty=3, tote has new item with qty=2
        PickedItem toteItem = loadedTote.items.iterator().next();
        assertEquals(0, new BigDecimal("2.000").compareTo(toteItem.quantity));
        assertEquals(0, new BigDecimal("3.000").compareTo(item.quantity));

        // Remove the tote item — should merge back: 3 + 2 = 5
        execution.removeItemFromTote(loadedTote, toteItem);

        assertEquals(1, execution.itemsToPick.size());
        PickedItem mergedItem = execution.itemsToPick.iterator().next();
        assertEquals(0, new BigDecimal("5.000").compareTo(mergedItem.quantity));
        assertTrue(loadedTote.items.isEmpty());
    }

    @Test
    @TestTransaction
    void removeItemFromTote_shouldThrow_whenItemNotInTote() {
        Store store = createStore("PE17");
        Employee emp = createEmployee(store, "PE17");
        ProductCollectingOrder order = createOrder(store, "PE17");
        PickingExecution execution = createExecution(order, emp);
        Tote tote = createTote(store, "PE17", Tote.ToteType.STANDARD);
        LoadedTote loadedTote = execution.addTote(tote, TemperatureZone.AMBIENT);

        Product product = createProduct("PE17", "PE17-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(product, loc);
        PickedItem item = createPickedItem(lotLine, product, "1.000");
        execution.addItemToPick(item);
        // Item is in work list, NOT in tote

        assertThrows(IllegalArgumentException.class,
                () -> execution.removeItemFromTote(loadedTote, item));
    }

    // --------------------------------------------------
    // cancelItem Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void cancelItem_shouldSetStatusToCanceled() {
        Store store = createStore("PE18");
        Employee emp = createEmployee(store, "PE18");
        ProductCollectingOrder order = createOrder(store, "PE18");
        PickingExecution execution = createExecution(order, emp);

        Product product = createProduct("PE18", "PE18-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(product, loc);
        PickedItem item = createPickedItem(lotLine, product, "1.000");
        execution.addItemToPick(item);

        execution.cancelItem(item);

        assertEquals(PickedItem.PickStatus.CANCELED, item.status);
    }

    @Test
    @TestTransaction
    void cancelItem_shouldThrow_whenItemNotInWorkList() {
        Store store = createStore("PE19");
        Employee emp = createEmployee(store, "PE19");
        ProductCollectingOrder order = createOrder(store, "PE19");
        PickingExecution execution = createExecution(order, emp);

        Product product = createProduct("PE19", "PE19-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(product, loc);
        PickedItem item = createPickedItem(lotLine, product, "1.000");
        // Item NOT added to work list

        assertThrows(IllegalArgumentException.class,
                () -> execution.cancelItem(item));
    }

    @Test
    @TestTransaction
    void cancelItem_shouldThrow_whenItemIsNull() {
        Store store = createStore("PE20");
        Employee emp = createEmployee(store, "PE20");
        ProductCollectingOrder order = createOrder(store, "PE20");
        PickingExecution execution = createExecution(order, emp);

        assertThrows(IllegalArgumentException.class,
                () -> execution.cancelItem(null));
    }

    // --------------------------------------------------
    // concludeExecution Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void concludeExecution_shouldSetStatusDone_whenAllItemsPickedOrCanceled() {
        Store store = createStore("PE21");
        Employee emp = createEmployee(store, "PE21");
        ProductCollectingOrder order = createOrder(store, "PE21");
        PickingExecution execution = createExecution(order, emp);
        Tote tote = createTote(store, "PE21", Tote.ToteType.STANDARD);
        LoadedTote loadedTote = execution.addTote(tote, TemperatureZone.AMBIENT);
        Buffer buffer = createBuffer(store, "PE21");

        Product product = createProduct("PE21", "PE21-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(product, loc);
        PickedItem item = createPickedItem(lotLine, product, "1.000");
        execution.addItemToPick(item);
        execution.addItemToTote(loadedTote, item, null, null);

        // All items are PICKED (in tote), none PENDING in work list
        execution.concludeExecution(buffer);

        assertEquals(HandlingExecution.ExecutionStatus.DONE, execution.status);
        assertEquals(buffer, loadedTote.buffer);
    }

    @Test
    @TestTransaction
    void concludeExecution_shouldThrow_whenItemsStillPending() {
        Store store = createStore("PE22");
        Employee emp = createEmployee(store, "PE22");
        ProductCollectingOrder order = createOrder(store, "PE22");
        PickingExecution execution = createExecution(order, emp);
        Buffer buffer = createBuffer(store, "PE22");

        Product product = createProduct("PE22", "PE22-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(product, loc);
        PickedItem item = createPickedItem(lotLine, product, "1.000");
        execution.addItemToPick(item);
        // Item is still PENDING

        assertThrows(IllegalStateException.class,
                () -> execution.concludeExecution(buffer));
    }

    @Test
    @TestTransaction
    void concludeExecution_shouldSucceed_whenAllItemsCanceled() {
        Store store = createStore("PE23");
        Employee emp = createEmployee(store, "PE23");
        ProductCollectingOrder order = createOrder(store, "PE23");
        PickingExecution execution = createExecution(order, emp);
        Buffer buffer = createBuffer(store, "PE23");

        Product product = createProduct("PE23", "PE23-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(product, loc);
        PickedItem item = createPickedItem(lotLine, product, "1.000");
        execution.addItemToPick(item);
        execution.cancelItem(item);

        // Item is CANCELED, not PENDING
        execution.concludeExecution(buffer);

        assertEquals(HandlingExecution.ExecutionStatus.DONE, execution.status);
    }

    // --------------------------------------------------
    // moveItemToTote Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void moveItemToTote_shouldMoveItemBetweenTotes() {
        Store store = createStore("PE24");
        Employee emp = createEmployee(store, "PE24");
        ProductCollectingOrder order = createOrder(store, "PE24");
        PickingExecution execution = createExecution(order, emp);
        Tote tote1 = createTote(store, "PE24A", Tote.ToteType.STANDARD);
        Tote tote2 = createTote(store, "PE24B", Tote.ToteType.STANDARD);
        LoadedTote sourceTote = execution.addTote(tote1, TemperatureZone.AMBIENT);
        LoadedTote targetTote = execution.addTote(tote2, TemperatureZone.AMBIENT);

        Product product = createProduct("PE24", "PE24-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createFullChain(order, store, product, loc, "PE24");
        PickedItem item = createPickedItem(lotLine, product, "1.000");
        execution.addItemToPick(item);
        execution.addItemToTote(sourceTote, item, null, null);

        // Move from source to target
        execution.moveItemToTote(sourceTote, targetTote, item);

        assertFalse(sourceTote.items.contains(item));
        assertTrue(targetTote.items.contains(item));
        assertEquals(targetTote, item.loadedTote);
    }

    @Test
    @TestTransaction
    void moveItemToTote_shouldThrow_whenTargetToteNotInExecution() {
        Store store = createStore("PE25");
        Employee emp = createEmployee(store, "PE25");
        ProductCollectingOrder order = createOrder(store, "PE25");
        PickingExecution execution = createExecution(order, emp);
        Tote tote1 = createTote(store, "PE25A", Tote.ToteType.STANDARD);
        LoadedTote sourceTote = execution.addTote(tote1, TemperatureZone.AMBIENT);

        // Foreign tote not in execution
        Tote foreignToteEntity = createTote(store, "PE25B", Tote.ToteType.STANDARD);
        LoadedTote foreignTote = LoadedTote.useAnEmptyTote(foreignToteEntity);
        foreignTote.storageType = TemperatureZone.AMBIENT;
        foreignTote.persist();

        Product product = createProduct("PE25", "PE25-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");
        LotLine lotLine = createLotLine(product, loc);
        PickedItem item = createPickedItem(lotLine, product, "1.000");
        execution.addItemToPick(item);
        execution.addItemToTote(sourceTote, item, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> execution.moveItemToTote(sourceTote, foreignTote, item));
    }

    // --------------------------------------------------
    // getAisleCodesFromItemsToPick Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void getAisleCodesFromItemsToPick_shouldReturnEmptySet_whenNoItems() {
        Store store = createStore("PE26");
        Employee emp = createEmployee(store, "PE26");
        ProductCollectingOrder order = createOrder(store, "PE26");
        PickingExecution execution = createExecution(order, emp);

        assertTrue(execution.getAisleCodesFromItemsToPick().isEmpty());
    }

    // --------------------------------------------------
    // Default Status Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void defaultStatus_shouldBeInProgress() {
        Store store = createStore("PE27");
        Employee emp = createEmployee(store, "PE27");
        ProductCollectingOrder order = createOrder(store, "PE27");
        PickingExecution execution = createExecution(order, emp);

        assertEquals(HandlingExecution.ExecutionStatus.IN_PROGRESS, execution.status);
    }

    @Test
    void pickStatus_shouldHaveExpectedValues() {
        assertEquals(3, PickedItem.PickStatus.values().length);
        assertNotNull(PickedItem.PickStatus.valueOf("PENDING"));
        assertNotNull(PickedItem.PickStatus.valueOf("PICKED"));
        assertNotNull(PickedItem.PickStatus.valueOf("CANCELED"));
    }
}
