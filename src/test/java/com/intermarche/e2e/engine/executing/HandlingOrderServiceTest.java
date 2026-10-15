package com.intermarche.e2e.engine.executing;

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
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
import com.intermarche.backoffice.engine.executing.HandlingOrderService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
 * Integration tests for {@link HandlingOrderService}.
 * Covers order listing/reservation, tote management, item picking operations,
 * and execution conclusion.
 */
@QuarkusTest
public class HandlingOrderServiceTest {

    @Inject
    HandlingOrderService handlingOrderService;

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

    private FulfillmentChunk createChunk(Fulfillment fulfillment) {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.READY;
        chunk.label = "Chunk";
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

    private LotLine createLotLine(Lot lot, Product product, StoreShelfLocation location) {
        FulfillmentLine fulfillmentLine = new FulfillmentLine();
        fulfillmentLine.lineNumber = 1;
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

    private ProductCollectingOrder createCollectingOrder(Store store, String prefix, Lot lot) {
        ProductCollectingOrder order = new ProductCollectingOrder();
        order.store = store;
        order.code = prefix + "-ORD-01";
        order.status = BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
        order.persist();
        if (lot != null) {
            order.addLot(lot);
        }
        return order;
    }

    /**
     * Builds a SCHEDULED collecting order backed by one lot with one lot line.
     */
    private ProductCollectingOrder createCollectingOrderWithLot(Store store, Customer customer, String prefix) {
        Order omsOrder = createOmsOrder(store, customer, prefix);
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        Product product = createProduct(prefix, prefix + "-EAN1");
        StoreShelfLocation location = createLocation(product, "AMBIENT", "A01");
        createLotLine(lot, product, location);
        return createCollectingOrder(store, prefix, lot);
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

    // --------------------------------------------------
    // listAvailableOrders Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void listAvailableOrders_shouldReturnOrdersWithoutExecution() {
        Store store = createStore("HS01");
        Customer customer = createCustomer("HS01");
        ProductCollectingOrder order1 = createCollectingOrderWithLot(store, customer, "HS01A");
        ProductCollectingOrder order2 = createCollectingOrderWithLot(store, customer, "HS01B");

        List<HandlingOrderService.HandlingOrderSummaryDTO> available = handlingOrderService.listAvailableOrders();

        List<Long> ids = available.stream().map(dto -> dto.id).toList();
        assertTrue(ids.contains(order1.id));
        assertTrue(ids.contains(order2.id));
        HandlingOrderService.HandlingOrderSummaryDTO dto = available.stream()
                .filter(d -> d.id == order1.id).findFirst().orElseThrow();
        assertEquals("ProductCollectingOrder", dto.type);
        assertEquals("SCHEDULED", dto.status);
    }

    @Test
    @TestTransaction
    void listAvailableOrders_shouldExcludeOrdersWithExecution() {
        Store store = createStore("HS02");
        Customer customer = createCustomer("HS02");
        Employee employee = createEmployee(store, "HS02");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS02");

        handlingOrderService.reserveOrder(order.id, employee.badgeId);

        List<HandlingOrderService.HandlingOrderSummaryDTO> available = handlingOrderService.listAvailableOrders();
        assertFalse(available.stream().anyMatch(dto -> dto.id == order.id));
    }

    // --------------------------------------------------
    // reserveOrder Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void reserveOrder_shouldCreateExecutionWithPickedItems() {
        Store store = createStore("HS03");
        Customer customer = createCustomer("HS03");
        Employee employee = createEmployee(store, "HS03");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS03");

        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);

        assertNotNull(dto.id);
        assertEquals("PickingExecution", dto.type);
        assertEquals(order.id, dto.orderId);
        assertEquals(employee.badgeId, dto.badgeId);
        assertEquals("John Doe", dto.employeeName);
        assertNotNull(dto.createdAt);

        PickingExecution execution = (PickingExecution) HandlingExecution.findById(dto.id);
        assertNotNull(execution);
        assertEquals(1, execution.itemsToPick.size());
        PickedItem item = execution.itemsToPick.iterator().next();
        assertEquals(PickedItem.PickStatus.PENDING, item.status);
    }

    @Test
    @TestTransaction
    void reserveOrder_shouldThrow_whenOrderNotFound() {
        Store store = createStore("HS04");
        Employee employee = createEmployee(store, "HS04");

        assertThrows(IllegalStateException.class,
                () -> handlingOrderService.reserveOrder(999999L, employee.badgeId));
    }

    @Test
    @TestTransaction
    void reserveOrder_shouldThrow_whenOrderNotScheduled() {
        Store store = createStore("HS05");
        Customer customer = createCustomer("HS05");
        Employee employee = createEmployee(store, "HS05");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS05");
        order.status = BaseHandlingOrder.HandlingOrderStatus.OPEN;

        assertThrows(IllegalStateException.class,
                () -> handlingOrderService.reserveOrder(order.id, employee.badgeId));
    }

    @Test
    @TestTransaction
    void reserveOrder_shouldThrow_whenEmployeeNotFound() {
        Store store = createStore("HS06");
        Customer customer = createCustomer("HS06");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS06");

        assertThrows(IllegalStateException.class,
                () -> handlingOrderService.reserveOrder(order.id, "NO-SUCH-BADGE"));
    }

    // --------------------------------------------------
    // cancelReservation Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void cancelReservation_shouldDeleteExecutionAndFreeOrder() {
        Store store = createStore("HS07");
        Customer customer = createCustomer("HS07");
        Employee employee = createEmployee(store, "HS07");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS07");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);

        handlingOrderService.cancelReservation(order.id);

        assertNull(HandlingExecution.findById(dto.id));
        assertTrue(handlingOrderService.listAvailableOrders().stream().anyMatch(d -> d.id == order.id));
    }

    @Test
    @TestTransaction
    void cancelReservation_shouldDoNothing_whenNoExecution() {
        Store store = createStore("HS08");
        Customer customer = createCustomer("HS08");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS08");

        assertDoesNotThrow(() -> handlingOrderService.cancelReservation(order.id));
    }

    // --------------------------------------------------
    // addTote Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void addTote_shouldAddLoadedToteToExecution() {
        Store store = createStore("HS09");
        Customer customer = createCustomer("HS09");
        Employee employee = createEmployee(store, "HS09");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS09");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);
        Tote tote = createTote(store, "HS09");

        handlingOrderService.addTote(dto.id, tote.code, TemperatureZone.AMBIENT);

        PickingExecution execution = (PickingExecution) HandlingExecution.findById(dto.id);
        assertEquals(1, execution.loadedTotes.size());
        LoadedTote loadedTote = execution.loadedTotes.iterator().next();
        assertEquals(tote.id, loadedTote.tote.id);
        assertEquals(TemperatureZone.AMBIENT, loadedTote.storageType);
        assertEquals(LoadedTote.Status.IN_USE, loadedTote.status);
    }

    @Test
    @TestTransaction
    void addTote_shouldThrow_whenToteNotFound() {
        Store store = createStore("HS10");
        Customer customer = createCustomer("HS10");
        Employee employee = createEmployee(store, "HS10");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS10");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);

        assertThrows(IllegalArgumentException.class,
                () -> handlingOrderService.addTote(dto.id, "NO-SUCH-TOTE", TemperatureZone.AMBIENT));
    }

    // --------------------------------------------------
    // removeEmptyTote Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void removeEmptyTote_shouldRemoveToteFromExecution() {
        Store store = createStore("HS11");
        Customer customer = createCustomer("HS11");
        Employee employee = createEmployee(store, "HS11");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS11");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);
        Tote tote = createTote(store, "HS11");
        handlingOrderService.addTote(dto.id, tote.code, TemperatureZone.AMBIENT);

        handlingOrderService.removeEmptyTote(dto.id, tote.code);

        PickingExecution execution = (PickingExecution) HandlingExecution.findById(dto.id);
        assertTrue(execution.loadedTotes.isEmpty());
        assertNull(LoadedTote.find("tote.code = ?1 and status = ?2", tote.code, LoadedTote.Status.IN_USE).firstResult());
    }

    @Test
    @TestTransaction
    void removeEmptyTote_shouldThrow_whenToteContainsItems() {
        Store store = createStore("HS12");
        Customer customer = createCustomer("HS12");
        Employee employee = createEmployee(store, "HS12");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS12");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);
        Tote tote = createTote(store, "HS12");
        handlingOrderService.addTote(dto.id, tote.code, TemperatureZone.AMBIENT);
        PickingExecution execution = (PickingExecution) HandlingExecution.findById(dto.id);
        PickedItem item = execution.itemsToPick.iterator().next();
        handlingOrderService.addItemToTote(dto.id, tote.code, item.id, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> handlingOrderService.removeEmptyTote(dto.id, tote.code));
    }

    @Test
    @TestTransaction
    void removeEmptyTote_shouldThrow_whenToteNotFound() {
        Store store = createStore("HS13");
        Customer customer = createCustomer("HS13");
        Employee employee = createEmployee(store, "HS13");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS13");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);

        assertThrows(IllegalArgumentException.class,
                () -> handlingOrderService.removeEmptyTote(dto.id, "NO-SUCH-TOTE"));
    }

    // --------------------------------------------------
    // addItemToTote Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void addItemToTote_shouldAssignItemToTote() {
        Store store = createStore("HS14");
        Customer customer = createCustomer("HS14");
        Employee employee = createEmployee(store, "HS14");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS14");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);
        Tote tote = createTote(store, "HS14");
        handlingOrderService.addTote(dto.id, tote.code, TemperatureZone.AMBIENT);
        PickingExecution execution = (PickingExecution) HandlingExecution.findById(dto.id);
        PickedItem item = execution.itemsToPick.iterator().next();

        handlingOrderService.addItemToTote(dto.id, tote.code, item.id, null, null);

        LoadedTote loadedTote = LoadedTote.find("tote.code = ?1 and status = ?2", tote.code, LoadedTote.Status.IN_USE).firstResult();
        assertNotNull(loadedTote);
        assertTrue(loadedTote.items.contains(item));
        assertEquals(loadedTote.id, item.loadedTote.id);
        assertEquals(PickedItem.PickStatus.PICKED, item.status);
        assertFalse(execution.itemsToPick.contains(item));
    }

    @Test
    @TestTransaction
    void addItemToTote_shouldSubstituteProduct() {
        Store store = createStore("HS15");
        Customer customer = createCustomer("HS15");
        Employee employee = createEmployee(store, "HS15");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS15");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);
        Tote tote = createTote(store, "HS15");
        handlingOrderService.addTote(dto.id, tote.code, TemperatureZone.AMBIENT);
        PickingExecution execution = (PickingExecution) HandlingExecution.findById(dto.id);
        PickedItem item = execution.itemsToPick.iterator().next();
        Product substitute = createProduct("HS15", "HS15-EAN-SUB");

        handlingOrderService.addItemToTote(dto.id, tote.code, item.id, null, substitute.id);

        assertEquals(substitute.id, item.product.id);
    }

    @Test
    @TestTransaction
    void addItemToTote_shouldThrow_whenItemNotFound() {
        Store store = createStore("HS16");
        Customer customer = createCustomer("HS16");
        Employee employee = createEmployee(store, "HS16");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS16");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);
        Tote tote = createTote(store, "HS16");
        handlingOrderService.addTote(dto.id, tote.code, TemperatureZone.AMBIENT);

        assertThrows(IllegalArgumentException.class,
                () -> handlingOrderService.addItemToTote(dto.id, tote.code, 999999L, null, null));
    }

    // --------------------------------------------------
    // moveItem Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void moveItem_shouldMoveItemBetweenTotes() {
        Store store = createStore("HS17");
        Customer customer = createCustomer("HS17");
        Employee employee = createEmployee(store, "HS17");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS17");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);
        Tote tote1 = createTote(store, "HS17A");
        Tote tote2 = createTote(store, "HS17B");
        handlingOrderService.addTote(dto.id, tote1.code, TemperatureZone.AMBIENT);
        handlingOrderService.addTote(dto.id, tote2.code, TemperatureZone.AMBIENT);
        PickingExecution execution = (PickingExecution) HandlingExecution.findById(dto.id);
        PickedItem item = execution.itemsToPick.iterator().next();
        handlingOrderService.addItemToTote(dto.id, tote1.code, item.id, null, null);

        handlingOrderService.moveItem(dto.id, tote1.code, tote2.code, item.id);

        LoadedTote loadedTote1 = LoadedTote.find("tote.code = ?1 and status = ?2", tote1.code, LoadedTote.Status.IN_USE).firstResult();
        LoadedTote loadedTote2 = LoadedTote.find("tote.code = ?1 and status = ?2", tote2.code, LoadedTote.Status.IN_USE).firstResult();
        assertFalse(loadedTote1.items.contains(item));
        assertTrue(loadedTote2.items.contains(item));
        assertEquals(loadedTote2.id, item.loadedTote.id);
    }

    @Test
    @TestTransaction
    void moveItem_shouldThrow_whenTargetToteNotFound() {
        Store store = createStore("HS18");
        Customer customer = createCustomer("HS18");
        Employee employee = createEmployee(store, "HS18");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS18");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);
        PickingExecution execution = (PickingExecution) HandlingExecution.findById(dto.id);
        PickedItem item = execution.itemsToPick.iterator().next();

        assertThrows(IllegalArgumentException.class,
                () -> handlingOrderService.moveItem(dto.id, null, "NO-SUCH-TOTE", item.id));
    }

    // --------------------------------------------------
    // removeItemFromTote Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void removeItemFromTote_shouldUnassignItem() {
        Store store = createStore("HS19");
        Customer customer = createCustomer("HS19");
        Employee employee = createEmployee(store, "HS19");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS19");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);
        Tote tote = createTote(store, "HS19");
        handlingOrderService.addTote(dto.id, tote.code, TemperatureZone.AMBIENT);
        PickingExecution execution = (PickingExecution) HandlingExecution.findById(dto.id);
        PickedItem item = execution.itemsToPick.iterator().next();
        handlingOrderService.addItemToTote(dto.id, tote.code, item.id, null, null);

        handlingOrderService.removeItemFromTote(dto.id, tote.code, item.id);

        assertNull(item.loadedTote);
        LoadedTote loadedTote = LoadedTote.find("tote.code = ?1 and status = ?2", tote.code, LoadedTote.Status.IN_USE).firstResult();
        assertFalse(loadedTote.items.contains(item));
    }

    // --------------------------------------------------
    // cancelPickedItem Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void cancelPickedItem_shouldSetStatusCanceled() {
        Store store = createStore("HS20");
        Customer customer = createCustomer("HS20");
        Employee employee = createEmployee(store, "HS20");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS20");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);
        PickingExecution execution = (PickingExecution) HandlingExecution.findById(dto.id);
        PickedItem item = execution.itemsToPick.iterator().next();

        handlingOrderService.cancelPickedItem(dto.id, item.id);

        assertEquals(PickedItem.PickStatus.CANCELED, item.status);
    }

    // --------------------------------------------------
    // substituteProduct Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void substituteProduct_shouldUpdateItemProduct() {
        Store store = createStore("HS21");
        Customer customer = createCustomer("HS21");
        Employee employee = createEmployee(store, "HS21");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS21");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);
        PickingExecution execution = (PickingExecution) HandlingExecution.findById(dto.id);
        PickedItem item = execution.itemsToPick.iterator().next();
        Product substitute = createProduct("HS21", "HS21-EAN-SUB");

        handlingOrderService.substituteProduct(dto.id, item.id, substitute.id);

        assertEquals(substitute.id, item.product.id);
    }

    @Test
    @TestTransaction
    void substituteProduct_shouldThrow_whenItemNotInExecution() {
        Store store = createStore("HS22");
        Customer customer = createCustomer("HS22");
        Employee employee = createEmployee(store, "HS22");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS22");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);
        Product substitute = createProduct("HS22", "HS22-EAN-SUB");
        // Item from another execution (or nowhere): use a random id
        assertThrows(IllegalArgumentException.class,
                () -> handlingOrderService.substituteProduct(dto.id, 999999L, substitute.id));
    }

    // --------------------------------------------------
    // concludePickingExecution Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void concludePickingExecution_shouldAssignBufferAndSetDone() {
        Store store = createStore("HS23");
        Customer customer = createCustomer("HS23");
        Employee employee = createEmployee(store, "HS23");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS23");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);
        Tote tote = createTote(store, "HS23");
        Buffer buffer = createBuffer(store, "HS23");
        handlingOrderService.addTote(dto.id, tote.code, TemperatureZone.AMBIENT);
        PickingExecution execution = (PickingExecution) HandlingExecution.findById(dto.id);
        PickedItem item = execution.itemsToPick.iterator().next();
        handlingOrderService.addItemToTote(dto.id, tote.code, item.id, null, null);

        handlingOrderService.concludePickingExecution(dto.id, buffer.id);

        assertEquals(HandlingExecution.ExecutionStatus.DONE, execution.status);
        LoadedTote loadedTote = LoadedTote.find("tote.code = ?1 and status = ?2", tote.code, LoadedTote.Status.IN_USE).firstResult();
        assertEquals(buffer.id, loadedTote.buffer.id);
    }

    @Test
    @TestTransaction
    void concludePickingExecution_shouldThrow_whenItemsStillPending() {
        Store store = createStore("HS24");
        Customer customer = createCustomer("HS24");
        Employee employee = createEmployee(store, "HS24");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS24");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);
        Buffer buffer = createBuffer(store, "HS24");
        // Item left PENDING in the work list

        assertThrows(IllegalStateException.class,
                () -> handlingOrderService.concludePickingExecution(dto.id, buffer.id));
    }

    @Test
    @TestTransaction
    void concludePickingExecution_shouldThrow_whenBufferNotFound() {
        Store store = createStore("HS25");
        Customer customer = createCustomer("HS25");
        Employee employee = createEmployee(store, "HS25");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS25");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);

        assertThrows(IllegalArgumentException.class,
                () -> handlingOrderService.concludePickingExecution(dto.id, 999999L));
    }

    // --------------------------------------------------
    // Batch Execution Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void startBatchExecutions_shouldCreateExecutionsForAllOrders() {
        Store store = createStore("HS26");
        Customer customer = createCustomer("HS26");
        Employee employee = createEmployee(store, "HS26");
        ProductCollectingOrder order1 = createCollectingOrderWithLot(store, customer, "HS26A");
        ProductCollectingOrder order2 = createCollectingOrderWithLot(store, customer, "HS26B");

        HandlingOrderService.BatchStartResponse response =
                handlingOrderService.startBatchExecutions(List.of(order1.id, order2.id), employee.badgeId);

        assertEquals(2, response.executionMap.size());
        assertTrue(response.executionMap.containsKey(order1.id));
        assertTrue(response.executionMap.containsKey(order2.id));
        assertNotNull(response.state);
        assertEquals(2, response.state.executions.size());
        // Each execution has one item to pick
        assertEquals(2, response.state.items.size());
    }

    @Test
    @TestTransaction
    void startBatchExecutions_shouldThrow_whenOrderIdsEmpty() {
        Store store = createStore("HS27");
        Employee employee = createEmployee(store, "HS27");

        assertThrows(IllegalArgumentException.class,
                () -> handlingOrderService.startBatchExecutions(List.of(), employee.badgeId));
    }

    @Test
    @TestTransaction
    void getBatchState_shouldAggregateItemsAndTotes() {
        Store store = createStore("HS28");
        Customer customer = createCustomer("HS28");
        Employee employee = createEmployee(store, "HS28");
        ProductCollectingOrder order = createCollectingOrderWithLot(store, customer, "HS28");
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(order.id, employee.badgeId);
        Tote tote = createTote(store, "HS28");
        handlingOrderService.addTote(dto.id, tote.code, TemperatureZone.AMBIENT);

        HandlingOrderService.PickingExecutionStateDTO state =
                handlingOrderService.getBatchState(List.of(dto.id));

        assertEquals(1, state.executions.size());
        assertEquals(1, state.items.size());
        assertEquals(1, state.totes.size());
        assertEquals(tote.code, state.totes.get(0).code);
        assertEquals("A01", state.items.get(0).aisle);
        assertFalse(state.items.get(0).picked);
    }
}
