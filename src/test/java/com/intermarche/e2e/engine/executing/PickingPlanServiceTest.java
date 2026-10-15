package com.intermarche.e2e.engine.executing;

import com.intermarche.backoffice.domain.Adresse;
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
import com.intermarche.backoffice.engine.executing.PickingPlanService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.TemperatureZone;

/**
 * Integration tests for {@link PickingPlanService}.
 * Covers mergeable order discovery and picking plan generation
 * (initial planning from LotLines and re-planning from PickedItems).
 */
@QuarkusTest
public class PickingPlanServiceTest {

    @Inject
    PickingPlanService pickingPlanService;

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

    private StoreBuilding createBuilding(Store store, String prefix) {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = prefix + "-BLDG";
        building.address = new Adresse();
        building.persist();
        store.buildings.add(building);
        return building;
    }

    private Aisle createAisle(StoreBuilding building, String code, double length, String head, String tail) {
        Aisle aisle = new Aisle();
        aisle.code = code;
        aisle.lengthMeters = length;
        aisle.connectedToHead = head;
        aisle.connectedToTail = tail;
        aisle.building = building;
        aisle.persist();
        return aisle;
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

    private FulfillmentChunk createChunk(Fulfillment fulfillment, Building building) {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.READY;
        chunk.label = "Chunk";
        chunk.fulfillment = fulfillment;
        chunk.building = building;
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

    private LotLine createLotLine(Lot lot, Product product, StoreShelfLocation location, String qty) {
        FulfillmentLine fulfillmentLine = new FulfillmentLine();
        fulfillmentLine.lineNumber = 1;
        fulfillmentLine.product = product;
        fulfillmentLine.requestedQuantity = new BigDecimal(qty);
        fulfillmentLine.location = location;
        fulfillmentLine.persist();
        LotLine lotLine = new LotLine();
        lotLine.product = product;
        lotLine.quantity = new BigDecimal(qty);
        lotLine.setFulfillmentLine(fulfillmentLine);
        lotLine.persist();
        lot.addLine(lotLine);
        return lotLine;
    }

    private ProductCollectingOrder createCollectingOrder(Store store, String prefix,
                                                         BaseHandlingOrder.HandlingOrderStatus status, Lot lot) {
        ProductCollectingOrder order = new ProductCollectingOrder();
        order.store = store;
        order.code = prefix + "-ORD-01";
        order.status = status;
        order.persist();
        if (lot != null) {
            order.addLot(lot);
        }
        return order;
    }

    /**
     * Builds a SCHEDULED collecting order backed by one lot with one lot line
     * located in the given aisle of the given building.
     */
    private ProductCollectingOrder createOrderWithLine(Store store, Customer customer, String prefix,
                                                       Building building, String aisleCode,
                                                       String weight, String volume, String qty) {
        Order omsOrder = createOmsOrder(store, customer, prefix);
        Fulfillment fulfillment = createFulfillment(omsOrder);
        FulfillmentChunk chunk = createChunk(fulfillment, building);
        Lot lot = createLot(store, customer, fulfillment, chunk);
        Product product = createProduct(prefix, weight, volume);
        StoreShelfLocation location = createLocation(product, "AMBIENT", aisleCode);
        createLotLine(lot, product, location, qty);
        return createCollectingOrder(store, prefix, BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, lot);
    }

    // --------------------------------------------------
    // findMergeableOrders Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void findMergeableOrders_shouldReturnEmptyList_whenPrimaryOrderNotFound() {
        List<PickingPlanService.MergedOrderDTO> result = pickingPlanService.findMergeableOrders(999999L, null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @TestTransaction
    void findMergeableOrders_shouldReturnScheduledOrdersFromSameStore() {
        Store store = createStore("PP01");
        ProductCollectingOrder primary = createCollectingOrder(store, "PP01A", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, null);
        ProductCollectingOrder candidate1 = createCollectingOrder(store, "PP01B", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, null);
        ProductCollectingOrder candidate2 = createCollectingOrder(store, "PP01C", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, null);

        List<PickingPlanService.MergedOrderDTO> result = pickingPlanService.findMergeableOrders(primary.id, null);

        List<Long> ids = result.stream().map(dto -> dto.id).toList();
        assertEquals(2, result.size());
        assertTrue(ids.contains(candidate1.id));
        assertTrue(ids.contains(candidate2.id));
        assertFalse(ids.contains(primary.id), "Primary order must be excluded");
    }

    @Test
    @TestTransaction
    void findMergeableOrders_shouldExcludeOrdersFromOtherStores() {
        Store store = createStore("PP02");
        Store otherStore = createStore("PP02X");
        ProductCollectingOrder primary = createCollectingOrder(store, "PP02A", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, null);
        ProductCollectingOrder sameStore = createCollectingOrder(store, "PP02B", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, null);
        createCollectingOrder(otherStore, "PP02C", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, null);

        List<PickingPlanService.MergedOrderDTO> result = pickingPlanService.findMergeableOrders(primary.id, null);

        List<Long> ids = result.stream().map(dto -> dto.id).toList();
        assertEquals(1, result.size());
        assertTrue(ids.contains(sameStore.id));
    }

    @Test
    @TestTransaction
    void findMergeableOrders_shouldExcludeOrdersNotScheduled() {
        Store store = createStore("PP03");
        ProductCollectingOrder primary = createCollectingOrder(store, "PP03A", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, null);
        ProductCollectingOrder scheduled = createCollectingOrder(store, "PP03B", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, null);
        createCollectingOrder(store, "PP03C", BaseHandlingOrder.HandlingOrderStatus.OPEN, null);
        createCollectingOrder(store, "PP03D", BaseHandlingOrder.HandlingOrderStatus.DONE, null);

        List<PickingPlanService.MergedOrderDTO> result = pickingPlanService.findMergeableOrders(primary.id, null);

        List<Long> ids = result.stream().map(dto -> dto.id).toList();
        assertEquals(1, result.size());
        assertTrue(ids.contains(scheduled.id));
    }

    @Test
    @TestTransaction
    void findMergeableOrders_shouldFilterByCodeSearch() {
        Store store = createStore("PP04");
        ProductCollectingOrder primary = createCollectingOrder(store, "PP04A", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, null);
        createCollectingOrder(store, "PP04B", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, null);
        ProductCollectingOrder match = createCollectingOrder(store, "PP04C", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, null);

        // Case-insensitive partial match on the order code
        List<PickingPlanService.MergedOrderDTO> result = pickingPlanService.findMergeableOrders(primary.id, "ord-01");

        // Both PP04B-ORD-01 and PP04C-ORD-01 match "ord-01"; use a more specific search
        assertTrue(result.stream().allMatch(dto -> dto.code.toLowerCase().contains("ord-01")));
        assertEquals(2, result.size());

        List<PickingPlanService.MergedOrderDTO> specific = pickingPlanService.findMergeableOrders(primary.id, "pp04c");
        assertEquals(1, specific.size());
        assertEquals(match.id, specific.get(0).id);
    }

    @Test
    @TestTransaction
    void findMergeableOrders_shouldReturnAllCandidates_whenSearchIsBlank() {
        Store store = createStore("PP05");
        ProductCollectingOrder primary = createCollectingOrder(store, "PP05A", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, null);
        createCollectingOrder(store, "PP05B", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, null);
        createCollectingOrder(store, "PP05C", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, null);

        List<PickingPlanService.MergedOrderDTO> result = pickingPlanService.findMergeableOrders(primary.id, "   ");

        assertEquals(2, result.size());
    }

    @Test
    @TestTransaction
    void findMergeableOrders_shouldMapDtoFields() {
        Store store = createStore("PP06");
        ProductCollectingOrder primary = createCollectingOrder(store, "PP06A", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, null);
        ProductCollectingOrder candidate = createCollectingOrder(store, "PP06B", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, null);
        candidate.requestedEnd = LocalDateTime.of(2026, 10, 20, 10, 0);

        List<PickingPlanService.MergedOrderDTO> result = pickingPlanService.findMergeableOrders(primary.id, null);

        assertEquals(1, result.size());
        PickingPlanService.MergedOrderDTO dto = result.get(0);
        assertEquals(candidate.id, dto.id);
        assertEquals(candidate.code, dto.code);
        assertEquals(LocalDateTime.of(2026, 10, 20, 10, 0), dto.deadline);
    }

    // --------------------------------------------------
    // generatePickingPlan Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void generatePickingPlan_shouldReturnEmptyResponse_whenOrderIdsEmpty() {
        PickingPlanService.BatchPickingPlanResponse response = pickingPlanService.generatePickingPlan(List.of(), null);

        assertNotNull(response);
        assertTrue(response.buildingPlans.isEmpty());
        assertEquals(0, response.globalSummary.totalItems);
        assertEquals(0, response.globalSummary.estimatedDistanceMeters);
    }

    @Test
    @TestTransaction
    void generatePickingPlan_shouldBuildPlanFromLotLines_whenNoExecution() {
        Store store = createStore("PP07");
        StoreBuilding building = createBuilding(store, "PP07");
        Customer customer = createCustomer("PP07");
        ProductCollectingOrder order = createOrderWithLine(store, customer, "PP07A", building, "PP07-A01",
                "2.000", "3.000", "5.000");
        Lot lot = order.lots.iterator().next();
        LotLine lotLine = lot.lines.iterator().next();

        PickingPlanService.BatchPickingPlanResponse response = pickingPlanService.generatePickingPlan(List.of(order.id), null);

        assertEquals(1, response.buildingPlans.size());
        PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(building.id);
        assertNotNull(plan);
        assertEquals(building.buildingCode, plan.buildingCode);
        assertEquals(1, plan.summary.totalItems);
        assertEquals(1, plan.route.size());
        PickingPlanService.RouteGroupDTO group = plan.route.get(0);
        assertEquals("PP07-A01", group.aisleCode);
        assertEquals(1, group.steps.size());
        PickingPlanService.RouteStepDTO step = group.steps.get(0);
        assertEquals(lotLine.id, step.pickedItemId, "Initial planning uses the LotLine id");
        assertEquals(order.code, step.sourceOrderCode);
        assertEquals(order.id, step.sourceOrderId);
        assertEquals(0, step.quantity.compareTo(new BigDecimal("5.000")));
        assertEquals(0, plan.capacity.totalWeightKg.compareTo(new BigDecimal("10"))); // 2 * 5
        assertEquals(0, plan.capacity.totalVolumeLiters.compareTo(new BigDecimal("15"))); // 3 * 5
        assertEquals(1, response.globalSummary.totalItems);
    }

    @Test
    @TestTransaction
    void generatePickingPlan_shouldGroupPlansByBuilding() {
        Store store = createStore("PP08");
        StoreBuilding building1 = createBuilding(store, "PP08A");
        StoreBuilding building2 = createBuilding(store, "PP08B");
        Customer customer = createCustomer("PP08");
        ProductCollectingOrder order1 = createOrderWithLine(store, customer, "PP08A", building1, "PP08-A01",
                "1.000", "1.000", "2.000");
        ProductCollectingOrder order2 = createOrderWithLine(store, customer, "PP08B", building2, "PP08-B01",
                "1.000", "1.000", "3.000");

        PickingPlanService.BatchPickingPlanResponse response =
                pickingPlanService.generatePickingPlan(List.of(order1.id, order2.id), null);

        assertEquals(2, response.buildingPlans.size());
        assertTrue(response.buildingPlans.containsKey(building1.id));
        assertTrue(response.buildingPlans.containsKey(building2.id));
        assertEquals(1, response.buildingPlans.get(building1.id).summary.totalItems);
        assertEquals(1, response.buildingPlans.get(building2.id).summary.totalItems);
        assertEquals(2, response.globalSummary.totalItems);
    }

    @Test
    @TestTransaction
    void generatePickingPlan_shouldSkipLinesWithoutBuilding() {
        Store store = createStore("PP09");
        Customer customer = createCustomer("PP09");
        // Chunk without building -> no building can be resolved -> no steps
        ProductCollectingOrder order = createOrderWithLine(store, customer, "PP09A", null, "PP09-A01",
                "1.000", "1.000", "2.000");

        PickingPlanService.BatchPickingPlanResponse response = pickingPlanService.generatePickingPlan(List.of(order.id), null);

        assertTrue(response.buildingPlans.isEmpty());
        assertEquals(0, response.globalSummary.totalItems);
    }

    @Test
    @TestTransaction
    void generatePickingPlan_shouldUsePickedItems_whenExecutionExists() {
        Store store = createStore("PP10");
        StoreBuilding building = createBuilding(store, "PP10");
        Customer customer = createCustomer("PP10");
        Employee employee = createEmployee(store, "PP10");
        ProductCollectingOrder order = createOrderWithLine(store, customer, "PP10A", building, "PP10-A01",
                "2.000", "3.000", "5.000");
        HandlingOrderService.HandlingExecutionDTO executionDto = handlingOrderService.reserveOrder(order.id, employee.badgeId);
        HandlingExecution execution = HandlingExecution.findById(executionDto.id);
        PickedItem item = ((com.intermarche.backoffice.client.PickingExecution) execution).itemsToPick.iterator().next();

        PickingPlanService.BatchPickingPlanResponse response = pickingPlanService.generatePickingPlan(List.of(order.id), null);

        assertEquals(1, response.buildingPlans.size());
        PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(building.id);
        assertNotNull(plan);
        assertEquals(1, plan.summary.totalItems);
        PickingPlanService.RouteStepDTO step = plan.route.get(0).steps.get(0);
        assertEquals(item.id, step.pickedItemId, "Re-planning during execution uses the PickedItem id");
        assertEquals("PP10-A01", plan.route.get(0).aisleCode);
    }

    @Test
    @TestTransaction
    void generatePickingPlan_shouldOrderRouteGroupsByOptimizedRoute() {
        Store store = createStore("PP11");
        StoreBuilding building = createBuilding(store, "PP11");
        // Linear topology: A01 (10m) --> A02 (20m)
        createAisle(building, "PP11-A01", 10.0, null, "PP11-A02");
        createAisle(building, "PP11-A02", 20.0, "PP11-A01", null);
        Customer customer = createCustomer("PP11");
        ProductCollectingOrder order = createOrderWithLine(store, customer, "PP11A", building, "PP11-A02",
                "1.000", "1.000", "2.000");
        // Second line in aisle A01 (added to the same lot)
        Lot lot = order.lots.iterator().next();
        Product product2 = createProduct("PP11B", "1.000", "1.000");
        StoreShelfLocation location2 = createLocation(product2, "AMBIENT", "PP11-A01");
        createLotLine(lot, product2, location2, "3.000");

        PickingPlanService.BatchPickingPlanResponse response =
                pickingPlanService.generatePickingPlan(List.of(order.id), "PP11-A01");

        PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(building.id);
        assertNotNull(plan);
        assertEquals(2, plan.summary.totalItems);
        assertEquals(2, plan.route.size());
        // Starting from A01, the nearest aisle is A01 itself, then A02
        assertEquals("PP11-A01", plan.route.get(0).aisleCode);
        assertEquals("PP11-A02", plan.route.get(1).aisleCode);
        // dist(A01, A01) = 0 + dist(A01, A02) = 10 + 20 = 30
        assertEquals(30, plan.summary.estimatedDistanceMeters);
        assertEquals(30, response.globalSummary.estimatedDistanceMeters);
    }

    @Test
    @TestTransaction
    void generatePickingPlan_shouldAggregateCapacityAcrossSteps() {
        Store store = createStore("PP12");
        StoreBuilding building = createBuilding(store, "PP12");
        Customer customer = createCustomer("PP12");
        ProductCollectingOrder order = createOrderWithLine(store, customer, "PP12A", building, "PP12-A01",
                "2.000", "3.000", "5.000");
        Lot lot = order.lots.iterator().next();
        Product product2 = createProduct("PP12B", "4.000", "5.000");
        StoreShelfLocation location2 = createLocation(product2, "AMBIENT", "PP12-A01");
        createLotLine(lot, product2, location2, "2.000");

        PickingPlanService.BatchPickingPlanResponse response = pickingPlanService.generatePickingPlan(List.of(order.id), null);

        PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(building.id);
        assertNotNull(plan);
        assertEquals(2, plan.summary.totalItems);
        // weight: 2*5 + 4*2 = 18 ; volume: 3*5 + 5*2 = 25
        assertEquals(0, plan.capacity.totalWeightKg.compareTo(new BigDecimal("18")));
        assertEquals(0, plan.capacity.totalVolumeLiters.compareTo(new BigDecimal("25")));
        assertEquals(2, response.globalSummary.totalItems);
    }
}
