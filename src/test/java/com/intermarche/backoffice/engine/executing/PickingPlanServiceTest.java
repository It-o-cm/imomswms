package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PickingPlanService}.
 * <p>
 * Complements the e2e suite (com.intermarche.e2e.engine.executing.PickingPlanServiceTest)
 * with edge cases: null products/quantities/weights, missing locations, null lots,
 * non-preparing orders, executions without items, and route group ordering.
 */
class PickingPlanServiceTest {

    private PickingPlanService service;
    private PickingRouteOptimizer routeOptimizer;

    @BeforeEach
    void setUp() {
        service = new PickingPlanService();
        routeOptimizer = mock(PickingRouteOptimizer.class);
        service.routeOptimizer = routeOptimizer;
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private static Product product(Long id, String name, String weight, String volume) {
        Product product = new Product();
        product.id = id;
        product.name = name;
        product.referenceWeight = weight != null ? new BigDecimal(weight) : null;
        product.referenceVolume = volume != null ? new BigDecimal(volume) : null;
        product.temperatureZone = TemperatureZone.AMBIENT;
        return product;
    }

    private static StoreBuilding building(Long id, String code) {
        StoreBuilding building = new StoreBuilding();
        building.id = id;
        building.buildingCode = code;
        return building;
    }

    private static Lot lot(Building building, LotLine... lines) {
        Lot lot = new Lot();
        if (building != null) {
            FulfillmentChunk chunk = new FulfillmentChunk();
            chunk.building = building;
            lot.chunk = chunk;
        }
        lot.lines = new LinkedHashSet<>(List.of(lines));
        return lot;
    }

    private static LotLine line(Long id, Product product, String qty, BaseLocation location) {
        LotLine line = new LotLine();
        line.id = id;
        line.product = product;
        line.quantity = qty != null ? new BigDecimal(qty) : null;
        if (location != null) {
            FulfillmentLine fulfillmentLine = new FulfillmentLine();
            fulfillmentLine.location = location;
            line.fulfillmentLine = fulfillmentLine;
        }
        return line;
    }

    private static StoreShelfLocation shelf(String aisle, String area) {
        StoreShelfLocation location = new StoreShelfLocation();
        location.aisle = aisle;
        location.area = area;
        location.unit = "U";
        location.position = "01";
        return location;
    }

    private static ProductCollectingOrder order(Long id, String code, Lot... lots) {
        ProductCollectingOrder order = new ProductCollectingOrder();
        order.id = id;
        order.code = code;
        order.lots = new LinkedHashSet<>(List.of(lots));
        return order;
    }

    private static PickedItem item(Long id, LotLine lotLine, Product product, String qty) {
        PickedItem item = new PickedItem();
        item.id = id;
        item.lotLine = lotLine;
        item.product = product;
        item.quantity = qty != null ? new BigDecimal(qty) : null;
        item.status = PickedItem.PickStatus.PENDING;
        return item;
    }

    private static PickingExecution execution(BaseHandlingOrder handlingOrder, PickedItem... items) {
        PickingExecution execution = new PickingExecution();
        execution.handlingOrder = handlingOrder;
        execution.itemsToPick = new LinkedHashSet<>(List.of(items));
        return execution;
    }

    @SuppressWarnings("unchecked")
    private static PanacheQuery<HandlingExecution> executionQuery(HandlingExecution execution) {
        PanacheQuery<HandlingExecution> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(execution);
        return query;
    }

    /**
     * Stubs the two Panache queries used by generatePickingPlan for a single order.
     * <p>
     * The PanacheQuery mock must be created (and stubbed) BEFORE the mockStatic
     * stubbing starts, otherwise Mockito reports an UnfinishedStubbingException.
     */
    private static void stubPlanQueries(MockedStatic<PanacheEntityBase> panacheMock,
                                        BaseHandlingOrder order, HandlingExecution execution) {
        PanacheQuery<HandlingExecution> query = executionQuery(execution);
        panacheMock.when(() -> PanacheEntityBase.list(eq("id in ?1"), any(Object[].class)))
                .thenReturn(List.of(order));
        panacheMock.when(() -> PanacheEntityBase.find(eq("handlingOrder"), any(Object[].class)))
                .thenReturn(query);
    }

    private void stubRoute(List<String> optimizedAisles, int distance) {
        when(routeOptimizer.optimizeRoute(any(Building.class), any(), anyList())).thenReturn(optimizedAisles);
        when(routeOptimizer.calculateTotalRouteDistance(any(Building.class), any(), anyList())).thenReturn(distance);
    }

    // --------------------------------------------------
    // findMergeableOrders
    // --------------------------------------------------

    @Test
    void findMergeableOrders_candidateWithNullCode_isFilteredOut() {
        ProductCollectingOrder primary = order(1L, "ORD-1");
        primary.store = new Store();
        ProductCollectingOrder noCode = order(2L, null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(1L)).thenReturn(primary);
            panacheMock.when(() -> PanacheEntityBase.list(eq("store = ?1 and status = ?2 and id != ?3"), any(Object[].class)))
                    .thenReturn(List.of(noCode));

            List<PickingPlanService.MergedOrderDTO> result = service.findMergeableOrders(1L, "abc");

            assertTrue(result.isEmpty());
        }
    }

    // --------------------------------------------------
    // generatePickingPlan - Scenario B (LotLines)
    // --------------------------------------------------

    @Test
    void generatePickingPlan_lotLineWithNullProduct_mapsUnknownAndSkipsCapacity() {
        StoreBuilding building = building(1L, "B1");
        LotLine line = line(10L, null, "5", shelf("A1", "AREA"));
        ProductCollectingOrder order = order(1L, "ORD-1", lot(building, line));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, null);
            stubRoute(List.of("A1"), 7);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(1L);
            assertNotNull(plan);
            assertEquals(1, plan.summary.totalItems);
            PickingPlanService.RouteStepDTO step = plan.route.get(0).steps.get(0);
            assertEquals("Unknown", step.productName);
            assertNull(step.temperatureZone);
            assertEquals(0, plan.capacity.totalWeightKg.compareTo(BigDecimal.ZERO));
            assertEquals(0, plan.capacity.totalVolumeLiters.compareTo(BigDecimal.ZERO));
        }
    }

    @Test
    void generatePickingPlan_lotLineWithNullQuantity_skipsCapacity() {
        StoreBuilding building = building(1L, "B1");
        Product product = product(1L, "P1", "2", "3");
        LotLine line = line(10L, product, null, shelf("A1", "AREA"));
        ProductCollectingOrder order = order(1L, "ORD-1", lot(building, line));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, null);
            stubRoute(List.of("A1"), 7);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(1L);
            assertNotNull(plan);
            PickingPlanService.RouteStepDTO step = plan.route.get(0).steps.get(0);
            assertNull(step.quantity);
            assertEquals(0, plan.capacity.totalWeightKg.compareTo(BigDecimal.ZERO));
            assertEquals(0, plan.capacity.totalVolumeLiters.compareTo(BigDecimal.ZERO));
        }
    }

    @Test
    void generatePickingPlan_lotLineWithNullWeightAndVolume_usesZero() {
        StoreBuilding building = building(1L, "B1");
        Product product = product(1L, "P1", null, null);
        LotLine line = line(10L, product, "5", shelf("A1", "AREA"));
        ProductCollectingOrder order = order(1L, "ORD-1", lot(building, line));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, null);
            stubRoute(List.of("A1"), 7);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(1L);
            assertNotNull(plan);
            assertEquals(0, plan.capacity.totalWeightKg.compareTo(BigDecimal.ZERO));
            assertEquals(0, plan.capacity.totalVolumeLiters.compareTo(BigDecimal.ZERO));
        }
    }

    @Test
    void generatePickingPlan_lotLineWithoutFulfillmentLine_hasNoAisleAndNoRoute() {
        StoreBuilding building = building(1L, "B1");
        Product product = product(1L, "P1", "2", "3");
        LotLine line = line(10L, product, "5", null);
        ProductCollectingOrder order = order(1L, "ORD-1", lot(building, line));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, null);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(1L);
            assertNotNull(plan);
            // The step exists but has no aisle: no route group, no distance
            assertEquals(1, plan.summary.totalItems);
            assertTrue(plan.route.isEmpty());
            assertEquals(0, plan.summary.estimatedDistanceMeters);
            verify(routeOptimizer, never()).optimizeRoute(any(), any(), any());
            verify(routeOptimizer, never()).calculateTotalRouteDistance(any(), any(), any());
        }
    }

    @Test
    void generatePickingPlan_lotLineWithNonShelfLocation_hasNoAisle() {
        StoreBuilding building = building(1L, "B1");
        Product product = product(1L, "P1", "2", "3");
        LotLine line = line(10L, product, "5", mock(BaseLocation.class));
        ProductCollectingOrder order = order(1L, "ORD-1", lot(building, line));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, null);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(1L);
            assertNotNull(plan);
            assertEquals(1, plan.summary.totalItems);
            assertTrue(plan.route.isEmpty());
            assertEquals(0, plan.summary.estimatedDistanceMeters);
        }
    }

    @Test
    void generatePickingPlan_orderWithNullLots_createsNoSteps() {
        ProductCollectingOrder order = order(1L, "ORD-1");
        order.lots = null;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, null);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            assertTrue(response.buildingPlans.isEmpty());
        }
    }

    @Test
    void generatePickingPlan_lotWithNullLines_createsNoSteps() {
        StoreBuilding building = building(1L, "B1");
        Lot lot = lot(building);
        lot.lines = null;
        ProductCollectingOrder order = order(1L, "ORD-1", lot);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, null);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            assertTrue(response.buildingPlans.isEmpty());
        }
    }

    @Test
    void generatePickingPlan_nonPreparingOrder_createsNoSteps() {
        BaseHandlingOrder order = mock(BaseHandlingOrder.class);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, null);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            assertTrue(response.buildingPlans.isEmpty());
        }
    }

    @Test
    void generatePickingPlan_lotWithNullChunk_resolvesNullBuilding() {
        Product product = product(1L, "P1", "2", "3");
        Lot lot = lot(null, line(10L, product, "5", shelf("A1", "AREA")));
        ProductCollectingOrder order = order(1L, "ORD-1", lot);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, null);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            assertTrue(response.buildingPlans.isEmpty());
        }
    }

    @Test
    void generatePickingPlan_nullLotInOrder_isSkipped() {
        ProductCollectingOrder order = order(1L, "ORD-1");
        Set<Lot> lots = new HashSet<>();
        lots.add(null);
        order.lots = lots;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, null);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            assertTrue(response.buildingPlans.isEmpty());
        }
    }

    @Test
    void generatePickingPlan_aisleInOptimizedRouteButNotInSteps_isSkipped() {
        StoreBuilding building = building(1L, "B1");
        Product product = product(1L, "P1", "2", "3");
        LotLine line = line(10L, product, "5", shelf("A1", "AREA"));
        ProductCollectingOrder order = order(1L, "ORD-1", lot(building, line));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, null);
            // The optimizer returns an extra aisle that has no step
            stubRoute(List.of("A1", "A9"), 7);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(1L);
            assertNotNull(plan);
            assertEquals(1, plan.route.size());
            assertEquals("A1", plan.route.get(0).aisleCode);
        }
    }

    @Test
    void generatePickingPlan_aisleInStepsButNotInOptimizedRoute_isAppended() {
        StoreBuilding building = building(1L, "B1");
        Product product = product(1L, "P1", "2", "3");
        LotLine line1 = line(10L, product, "5", shelf("A1", "AREA1"));
        LotLine line2 = line(11L, product, "3", shelf("A2", "AREA2"));
        ProductCollectingOrder order = order(1L, "ORD-1", lot(building, line1, line2));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, null);
            // The optimizer only returns A1: A2 must be appended after the optimized groups
            stubRoute(List.of("A1"), 7);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(1L);
            assertNotNull(plan);
            assertEquals(2, plan.route.size());
            assertEquals("A1", plan.route.get(0).aisleCode);
            assertEquals("AREA1", plan.route.get(0).area);
            assertEquals("A2", plan.route.get(1).aisleCode);
            assertEquals("AREA2", plan.route.get(1).area);
            assertEquals(1, plan.route.get(1).steps.size());
        }
    }

    // --------------------------------------------------
    // generatePickingPlan - Scenario A (PickedItems)
    // --------------------------------------------------

    @Test
    void generatePickingPlan_executionWithNullItemsToPick_createsNoSteps() {
        ProductCollectingOrder order = order(1L, "ORD-1");
        PickingExecution execution = execution(order);
        execution.itemsToPick = null;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, execution);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            assertTrue(response.buildingPlans.isEmpty());
        }
    }

    @Test
    void generatePickingPlan_itemWithNullLotLine_isSkipped() {
        ProductCollectingOrder order = order(1L, "ORD-1");
        PickedItem item = item(1L, null, product(1L, "P1", "2", "3"), "5");
        PickingExecution execution = execution(order, item);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, execution);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            assertTrue(response.buildingPlans.isEmpty());
        }
    }

    @Test
    void generatePickingPlan_itemWithNullProduct_mapsUnknownAndSkipsCapacity() {
        StoreBuilding building = building(1L, "B1");
        LotLine line = line(10L, product(1L, "P1", "2", "3"), "5", shelf("A1", "AREA"));
        ProductCollectingOrder order = order(1L, "ORD-1", lot(building, line));
        PickedItem item = item(1L, line, null, "5");
        PickingExecution execution = execution(order, item);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, execution);
            stubRoute(List.of("A1"), 7);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(1L);
            assertNotNull(plan);
            assertEquals(1, plan.summary.totalItems);
            PickingPlanService.RouteStepDTO step = plan.route.get(0).steps.get(0);
            assertEquals(1L, step.pickedItemId);
            assertEquals("Unknown", step.productName);
            assertNull(step.temperatureZone);
            assertEquals(0, plan.capacity.totalWeightKg.compareTo(BigDecimal.ZERO));
            assertEquals(0, plan.capacity.totalVolumeLiters.compareTo(BigDecimal.ZERO));
        }
    }

    @Test
    void generatePickingPlan_itemWithNullQuantity_skipsCapacity() {
        StoreBuilding building = building(1L, "B1");
        Product product = product(1L, "P1", "2", "3");
        LotLine line = line(10L, product, "5", shelf("A1", "AREA"));
        ProductCollectingOrder order = order(1L, "ORD-1", lot(building, line));
        PickedItem item = item(1L, line, product, null);
        PickingExecution execution = execution(order, item);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, execution);
            stubRoute(List.of("A1"), 7);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(1L);
            assertNotNull(plan);
            PickingPlanService.RouteStepDTO step = plan.route.get(0).steps.get(0);
            assertNull(step.quantity);
            assertEquals(0, plan.capacity.totalWeightKg.compareTo(BigDecimal.ZERO));
            assertEquals(0, plan.capacity.totalVolumeLiters.compareTo(BigDecimal.ZERO));
        }
    }

    @Test
    void generatePickingPlan_itemWithNullWeightAndVolume_usesZero() {
        StoreBuilding building = building(1L, "B1");
        Product product = product(1L, "P1", null, null);
        LotLine line = line(10L, product, "5", shelf("A1", "AREA"));
        ProductCollectingOrder order = order(1L, "ORD-1", lot(building, line));
        PickedItem item = item(1L, line, product, "5");
        PickingExecution execution = execution(order, item);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, execution);
            stubRoute(List.of("A1"), 7);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(1L);
            assertNotNull(plan);
            assertEquals(0, plan.capacity.totalWeightKg.compareTo(BigDecimal.ZERO));
            assertEquals(0, plan.capacity.totalVolumeLiters.compareTo(BigDecimal.ZERO));
        }
    }

    @Test
    void generatePickingPlan_itemWithoutFulfillmentLine_hasNoAisle() {
        StoreBuilding building = building(1L, "B1");
        Product product = product(1L, "P1", "2", "3");
        LotLine line = line(10L, product, "5", null);
        ProductCollectingOrder order = order(1L, "ORD-1", lot(building, line));
        PickedItem item = item(1L, line, product, "5");
        PickingExecution execution = execution(order, item);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, execution);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(1L);
            assertNotNull(plan);
            assertEquals(1, plan.summary.totalItems);
            assertTrue(plan.route.isEmpty());
            assertEquals(0, plan.summary.estimatedDistanceMeters);
            verify(routeOptimizer, never()).optimizeRoute(any(), any(), any());
        }
    }

    @Test
    void generatePickingPlan_itemWithNonShelfLocation_hasNoAisle() {
        StoreBuilding building = building(1L, "B1");
        Product product = product(1L, "P1", "2", "3");
        LotLine line = line(10L, product, "5", mock(BaseLocation.class));
        ProductCollectingOrder order = order(1L, "ORD-1", lot(building, line));
        PickedItem item = item(1L, line, product, "5");
        PickingExecution execution = execution(order, item);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, execution);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(1L);
            assertNotNull(plan);
            assertEquals(1, plan.summary.totalItems);
            assertTrue(plan.route.isEmpty());
            assertEquals(0, plan.summary.estimatedDistanceMeters);
        }
    }

    @Test
    void generatePickingPlan_executionWithNonPreparingHandlingOrder_buildsEmptyCache() {
        Product product = product(1L, "P1", "2", "3");
        LotLine line = line(10L, product, "5", shelf("A1", "AREA"));
        ProductCollectingOrder order = order(1L, "ORD-1");
        PickedItem item = item(1L, line, product, "5");
        PickingExecution execution = execution(mock(BaseHandlingOrder.class), item);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, execution);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            assertTrue(response.buildingPlans.isEmpty());
        }
    }

    @Test
    void generatePickingPlan_executionWithNullLotsInHandlingOrder_buildsEmptyCache() {
        Product product = product(1L, "P1", "2", "3");
        LotLine line = line(10L, product, "5", shelf("A1", "AREA"));
        ProductCollectingOrder handlingOrder = order(1L, "ORD-1");
        handlingOrder.lots = null;
        ProductCollectingOrder order = order(2L, "ORD-2");
        PickedItem item = item(1L, line, product, "5");
        PickingExecution execution = execution(handlingOrder, item);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, execution);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(2L), null);

            assertTrue(response.buildingPlans.isEmpty());
        }
    }

    @Test
    void generatePickingPlan_executionWithLotWithNullLines_buildsCacheWithoutLines() {
        StoreBuilding building = building(1L, "B1");
        Product product = product(1L, "P1", "2", "3");
        LotLine line = line(10L, product, "5", shelf("A1", "AREA"));
        Lot lot = lot(building);
        lot.lines = null;
        ProductCollectingOrder handlingOrder = order(1L, "ORD-1", lot);
        ProductCollectingOrder order = order(2L, "ORD-2");
        PickedItem item = item(1L, line, product, "5");
        PickingExecution execution = execution(handlingOrder, item);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, execution);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(2L), null);

            assertTrue(response.buildingPlans.isEmpty());
        }
    }

    @Test
    void generatePickingPlan_executionWithNullLotInHandlingOrder_isSkipped() {
        Product product = product(1L, "P1", "2", "3");
        LotLine line = line(10L, product, "5", shelf("A1", "AREA"));
        ProductCollectingOrder handlingOrder = order(1L, "ORD-1");
        Set<Lot> lots = new HashSet<>();
        lots.add(null);
        handlingOrder.lots = lots;
        ProductCollectingOrder order = order(2L, "ORD-2");
        PickedItem item = item(1L, line, product, "5");
        PickingExecution execution = execution(handlingOrder, item);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, execution);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(2L), null);

            assertTrue(response.buildingPlans.isEmpty());
        }
    }

    @Test
    void generatePickingPlan_itemWithNullLocation_hasNoAisle() {
        StoreBuilding building = building(1L, "B1");
        Product product = product(1L, "P1", "2", "3");
        LotLine line = line(10L, product, "5", null);
        line.fulfillmentLine = new FulfillmentLine();
        // location stays null: instanceof ShelfLocation is false
        ProductCollectingOrder order = order(1L, "ORD-1", lot(building, line));
        PickedItem item = item(1L, line, product, "5");
        PickingExecution execution = execution(order, item);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubPlanQueries(panacheMock, order, execution);

            PickingPlanService.BatchPickingPlanResponse response = service.generatePickingPlan(List.of(1L), null);

            PickingPlanService.PickingPlanResponse plan = response.buildingPlans.get(1L);
            assertNotNull(plan);
            assertEquals(1, plan.summary.totalItems);
            assertTrue(plan.route.isEmpty());
            assertEquals(0, plan.summary.estimatedDistanceMeters);
        }
    }
}
