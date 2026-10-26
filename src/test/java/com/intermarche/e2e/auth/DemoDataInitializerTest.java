package com.intermarche.e2e.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.auth.DemoDataInitializer;
import com.intermarche.backoffice.domain.stock.StockAccount;
import com.intermarche.backoffice.domain.stock.StockMovement;
import com.intermarche.backoffice.domain.stock.ExpectedSupply;
import com.intermarche.backoffice.domain.stock.StockCommitment;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import com.intermarche.backoffice.domain.Configuration;
import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.OrderLine;
import com.intermarche.backoffice.domain.order.OrderRequest;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductFamily;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.AisleWaypoint;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.PlanNode;
import com.intermarche.backoffice.domain.store.PlanNodeType;
import com.intermarche.backoffice.domain.store.PlanZone;
import com.intermarche.backoffice.domain.store.PlanZoneVertex;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.StoreGroup;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.domain.store.Tote;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import com.intermarche.backoffice.domain.wms.handling.PackingOrder;
import com.intermarche.backoffice.domain.wms.handling.PreparingOrder;
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
import com.intermarche.backoffice.domain.wms.handling.ZoneCollectingOrder;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link DemoDataInitializer}.
 * <p>
 * Verifies that the demo data set is created correctly, that all objects are dated
 * with the current day, that entities are linked properly, and that seeding is
 * idempotent and can be disabled via configuration.
 */
@QuarkusTest
public class DemoDataInitializerTest {

    @Inject
    DemoDataInitializer initializer;

    @Inject
    com.intermarche.backoffice.engine.executing.PickingPlanService pickingPlans;

    @Inject
    TemporalStockService stocks;

    /**
     * Resets the {@code demoDataEnabled} flag to its test default (false) after each test,
     * since the bean is application-scoped and shared between tests.
     */
    @AfterEach
    void resetDemoDataEnabled() {
        initializer.setDemoDataEnabled(false);
    }

    @Test
    @TestTransaction
    void replenishmentDemo_shouldLinkTruckToReserveThenShelves_withoutCreatingPhysicalStock() {
        initializer.seedDemoData();
        var truck = com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder.<com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder>find("code", "REA-DEMO-CAM").firstResult();
        var shelves = com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder.<com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder>find("code", "REA-DEMO-RAY").firstResult();
        assertNotNull(truck);
        assertNotNull(shelves);
        assertEquals(8, truck.supplies.size());
        assertEquals(8, shelves.supplies.size());
        assertEquals(truck, shelves.predecessor);
        assertEquals(truck.destinationBuilding, shelves.sourceBuilding);
        assertTrue(shelves.requestedStart.isAfter(truck.requestedEnd));
        long movements=StockMovement.count();
        long supplies=ExpectedSupply.count();
        initializer.seedDemoData();
        assertEquals(movements,StockMovement.count());
        assertEquals(supplies,ExpectedSupply.count());
        for (var line:truck.supplies) {
            assertNull(line.source);
            assertEquals(0,line.quantity.compareTo(new BigDecimal("48")));
            assertEquals(0,line.received.signum());
            var transfer=shelves.supplies.stream().filter(l -> l.account.id.equals(line.account.id)).findFirst().orElseThrow();
            assertEquals(line.destination,transfer.source);
            assertNotEquals(transfer.source,transfer.destination);
            assertEquals(0,transfer.quantity.compareTo(new BigDecimal("12")));
            // A real receipt credits reserve; the next receipt moves 12 units, without creating extra stock.
            stocks.receive(line.id,"TEST-TRUCK-"+line.id,line.quantity,DateTimeProvider.now(),true);
            stocks.receive(transfer.id,"TEST-SHELVES-"+transfer.id,transfer.quantity,DateTimeProvider.now(),true);
            var reserveMovement=StockMovement.<StockMovement>list("account = ?1 and location = ?2",line.account,line.destination);
            assertEquals(0,reserveMovement.stream().map(m -> m.quantity).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(new BigDecimal("36")));
        }
    }

    @Test @TestTransaction
    void warehouseDemoHasConnectedPlanStorageForEveryProduct_andPreservesExistingData() {
        initializer.seedDemoData();
        com.intermarche.backoffice.domain.store.WarehouseBuilding warehouse=com.intermarche.backoffice.domain.store.WarehouseBuilding.find("buildingCode","LYO-042-WH").firstResult();
        assertNotNull(warehouse.planImage);assertFalse(warehouse.planImage.isBlank());
        assertEquals(Product.count(),warehouse.locations.stream().map(l -> l.product.id).distinct().count());
        assertEquals(16,PlanNode.count("building",warehouse));
        assertEquals(21,Aisle.count("building",warehouse));
        assertEquals(5,PlanZone.count("building",warehouse));
        assertEquals(2,Buffer.count("building",warehouse));
        List<Aisle> aisles=Aisle.listByBuilding(warehouse);
        Set<Long> reachable=new HashSet<>();reachable.add(aisles.get(0).headNode.id);
        boolean progress=true;while(progress) {progress=false;for(Aisle a:aisles) {if(reachable.contains(a.headNode.id)) progress|=reachable.add(a.tailNode.id);if(reachable.contains(a.tailNode.id)) progress|=reachable.add(a.headNode.id);}}
        assertEquals(16,reachable.size());
        for(var location:warehouse.locations) {
            assertEquals(location.product.temperatureZone.name(),location.area);
            assertTrue(aisles.stream().anyMatch(a -> a.code.equals(location.aisle)));
        }
        long movements=StockMovement.count();long locations=warehouse.locations.size();String image=warehouse.planImage;
        PlanNode point=PlanNode.listByBuilding(warehouse).get(0);point.x+=5;double edited=point.x;
        initializer.seedDemoData();
        assertEquals(edited,point.x);assertEquals(locations,warehouse.locations.size());assertEquals(movements,StockMovement.count());assertEquals(image,warehouse.planImage);
    }

    @Test
    @TestTransaction
    void complexPickingDemo_shouldCoverMultipleAislesAndRepeatBays() {
        initializer.seedDemoData();
        ProductCollectingOrder order = ProductCollectingOrder.find("code", "PRO-DEMO-OPT").firstResult();
        assertNotNull(order);
        assertEquals(12, order.lots.size());
        assertEquals(63, order.lots.stream().mapToInt(lot -> lot.lines.size()).sum());
        Set<String> aisles = new HashSet<>();
        Map<String, Integer> bays = new HashMap<>();
        for (Lot lot : order.lots) {
            for (LotLine line : lot.lines) {
                ShelfLocation location = (ShelfLocation) lot.chunk.building.findLocationByProductId(line.product.id);
                assertNotNull(location);
                aisles.add(location.aisle);
                bays.merge(location.aisle + ":" + location.unit, 1, Integer::sum);
            }
        }
        assertTrue(aisles.size() >= 4, "Expected multiple aisles, got " + aisles);
        assertTrue(bays.values().stream().anyMatch(count -> count >= 3));
        var plan = pickingPlans.generatePickingPlan(List.of(order.id), null);
        var buildingPlan = plan.buildingPlans.values().iterator().next();
        assertNotNull(buildingPlan.mapRoute);
        assertNotNull(buildingPlan.mapRoute.distanceMeters);
        assertEquals(0, buildingPlan.mapRoute.disconnected);
        assertEquals(0, buildingPlan.mapRoute.unlocated);
        var ordered = buildingPlan.route.stream().flatMap(group -> group.steps.stream()).toList();
        assertEquals(63, ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            assertEquals(i + 1, ordered.get(i).stepNumber);
            assertNotNull(ordered.get(i).mapPoint);
            assertNotNull(ordered.get(i).shelfPoint);
        }
        assertEquals(Math.round(buildingPlan.mapRoute.distanceMeters), buildingPlan.summary.estimatedDistanceMeters);
        assertTrue(buildingPlan.mapRoute.distanceMeters <= buildingPlan.mapRoute.originalDistanceMeters);
        long count = ProductCollectingOrder.count();
        initializer.seedDemoData();
        assertEquals(count, ProductCollectingOrder.count());
    }

    // --------------------------------------------------
    // seedDemoData
    // --------------------------------------------------

    /**
     * Verifies that seeding creates the expected number of entities of each type.
     */
    @Test
    @TestTransaction
    void seedDemoData_shouldCreateAllEntities() {
        initializer.seedDemoData();

        assertEquals(1, Store.count());
        assertEquals(1, StoreGroup.count());
        assertEquals(1, StoreBuilding.count());
        assertEquals(49, Aisle.count("building.buildingCode", "LYO-042-SHOP"));
        assertEquals(33, PlanNode.count("building.buildingCode", "LYO-042-SHOP"));
        assertEquals(0, AisleWaypoint.count());
        assertEquals(5, PlanZone.count("building.buildingCode", "LYO-042-SHOP"));
        assertEquals(60, PlanZoneVertex.count());
        assertEquals(58, Product.count());
        assertEquals(58, StoreShelfLocation.count());
        // Includes the supervision scenario: one deactivated buffer and three pickers (DemoSupervisionScenarioTest),
        // and the activity scenario: one dock buffer, one picker and seven totes (DemoActivityScenarioTest).
        assertEquals(7, Buffer.count());
        assertEquals(9, Employee.count());
        assertEquals(40, Tote.count());
        assertEquals(22, Tote.count("toteType", Tote.ToteType.STANDARD));
        assertEquals(18, Tote.count("toteType", Tote.ToteType.INSULATED));
        assertEquals(4, ProductFamily.count());
        assertEquals(5, Customer.count());
        // Orders: 12 base orders and 19 activity orders, one line each, one of them not routed (no lot).
        assertEquals(31, Order.count());
        assertEquals(82, OrderLine.count());
        assertEquals(12, OrderRequest.count());
        assertEquals(31, Fulfillment.count());
        assertEquals(31, FulfillmentChunk.count());
        assertEquals(82, FulfillmentLine.count());
        assertEquals(30, Lot.count());
        assertEquals(81, LotLine.count());
        assertEquals(39, BaseHandlingOrder.count());
        assertEquals(4, GroupingOrder.count());
        assertEquals(2, PackingOrder.count());
        assertEquals(7, ProductCollectingOrder.count());
        assertEquals(2, ZoneCollectingOrder.count());
        assertEquals(3, Configuration.count());
        assertEquals(6, Event.count());
    }

    /**
     * Verifies that all created objects are dated with the current day.
     * <p>
     * Uses a fixed time via {@link DateTimeProvider} for deterministic assertions.
     */
    @Test
    @TestTransaction
    void seedDemoData_shouldSetDatesToToday() {
        LocalDateTime fixedNow = LocalDateTime.of(2026, 10, 14, 10, 30);
        DateTimeProvider.setFixedDateTime(fixedNow);
        try {
            initializer.seedDemoData();

            Store store = Store.findByCode("LYO-042");
            assertNotNull(store);
            assertEquals(fixedNow, store.createdAt);
            assertEquals(fixedNow, store.updatedAt);

            Order order = Order.find("orderId", "CMD-2026-0451").firstResult();
            assertNotNull(order);
            assertEquals(fixedNow, order.orderDate);
            assertEquals(fixedNow, order.createdAt);

            // Backdated orders keep their reception date as order date and audit date
            Order backdatedOrder = Order.find("orderId", "CMD-2026-0456").firstResult();
            assertNotNull(backdatedOrder);
            assertEquals(fixedNow.minusDays(1), backdatedOrder.orderDate);
            assertEquals(fixedNow.minusDays(1), backdatedOrder.createdAt);

            Order backdatedOrder2 = Order.find("orderId", "CMD-2026-0459").firstResult();
            assertNotNull(backdatedOrder2);
            assertEquals(fixedNow.minusDays(2), backdatedOrder2.orderDate);
            assertEquals(fixedNow.minusDays(2), backdatedOrder2.createdAt);

            // Order requests are backdated to the order reception date
            OrderRequest todayRequest = OrderRequest.find("orderNumber", "CMD-2026-0451").firstResult();
            assertNotNull(todayRequest);
            assertEquals(fixedNow, todayRequest.createdAt);

            OrderRequest backdatedRequest = OrderRequest.find("orderNumber", "CMD-2026-0456").firstResult();
            assertNotNull(backdatedRequest);
            assertEquals(fixedNow.minusDays(1), backdatedRequest.createdAt);

            OrderRequest backdatedRequest2 = OrderRequest.find("orderNumber", "CMD-2026-0459").firstResult();
            assertNotNull(backdatedRequest2);
            assertEquals(fixedNow.minusDays(2), backdatedRequest2.createdAt);

            Employee employee = Employee.findByBadgeId("100234");
            assertNotNull(employee);
            assertEquals(fixedNow, employee.createdAt);

            Event event = Event.find("label", "Commande reçue").firstResult();
            assertNotNull(event);
            assertEquals(fixedNow, event.createdAt);

            // Fulfillments and lots follow the order timeline
            Fulfillment fulfillment = Fulfillment.find("order.orderId", "CMD-2026-0451").firstResult();
            assertNotNull(fulfillment);
            assertEquals(fixedNow, fulfillment.createdAt);

            Fulfillment backdatedFulfillment = Fulfillment.find("order.orderId", "CMD-2026-0456").firstResult();
            assertNotNull(backdatedFulfillment);
            assertEquals(fixedNow.minusDays(1), backdatedFulfillment.createdAt);

            Lot lot = Lot.find("fulfillment.order.orderId", "CMD-2026-0451").firstResult();
            assertNotNull(lot);
            assertEquals(fixedNow, lot.createdAt);

            // Handling orders are scheduled today
            List<BaseHandlingOrder> handlingOrders = BaseHandlingOrder.listAll();
            assertEquals(39, handlingOrders.size());
            for (BaseHandlingOrder handlingOrder : handlingOrders) {
                assertEquals(handlingOrder.code.startsWith("OPT-ANN-")?fixedNow.toLocalDate().plusDays(1):fixedNow.toLocalDate(), handlingOrder.requestedStart.toLocalDate());
                assertNotNull(handlingOrder.requestedEnd);
            }
        } finally {
            DateTimeProvider.clear();
        }
    }

    /**
     * Verifies that entities are linked correctly (store/building, group/store,
     * employee/store, tote/store, family/products, order/lines, config/store, event/target).
     */
    @Test
    @TestTransaction
    void seedDemoData_shouldLinkEntitiesCorrectly() {
        initializer.seedDemoData();

        Store store = Store.findByCode("LYO-042");
        assertNotNull(store);
        assertEquals("Intermarché Lyon Part-Dieu", store.name);
        assertEquals(3, store.buildings.size());

        StoreGroup group = StoreGroup.findByCode("LYO");
        assertNotNull(group);
        assertTrue(group.stores.contains(store));

        StoreBuilding building = StoreBuilding.find("buildingCode", "LYO-042-SHOP").firstResult();
        assertEquals("LYO-042-SHOP", building.buildingCode);
        assertEquals(58, building.locations.stream().map(l->org.hibernate.Hibernate.unproxy(l,com.intermarche.backoffice.domain.store.ShelfLocation.class)).filter(StoreShelfLocation.class::isInstance).count());

        Employee employee = Employee.findByBadgeId("100234");
        assertNotNull(employee);
        assertEquals(store.id, employee.store.id);
        assertEquals(Employee.EmployeeRole.MANAGER, employee.role);

        Tote tote = Tote.findByCode("TOTE-1042-01");
        assertNotNull(tote);
        assertEquals(store.id, tote.store.id);

        ProductFamily family = ProductFamily.findByCode("EPICERIE");
        assertNotNull(family);
        assertEquals(20, family.products.size());

        Order order = Order.find("orderId", "CMD-2026-0451").firstResult();
        assertNotNull(order);
        assertEquals(store.id, order.store.id);
        assertEquals(2, order.lines.size());

        Configuration config = Configuration.find("name", "Fulfillment standard").firstResult();
        assertNotNull(config);
        assertEquals("FULFILLER", config.type);
        assertTrue(config.stores.contains(store));

        Event event = Event.find("label", "Commande reçue").firstResult();
        assertNotNull(event);
        assertEquals(store.id, event.store.id);
        assertEquals(OrderRequest.class.getName(), event.targetEntityType);
        assertEquals("CMD-2026-0451", event.targetEntityCode);
    }

    /**
     * Verifies that the demo store plan is created correctly: nodes with their
     * geometry, aisles with their geometric layer (flow/shape, head/tail nodes)
     * and polygonal zones, all scoped to the demo building.
     * <p>
     * The plan matches the generated plan image: a central spine (CEN_01..CEN_13)
     * from the entrance to the fridges, crossed by the department aisles, whose
     * extremities are chained by a perimeter rocade (ROC_*). The logical graph
     * between aisles mirrors the geometry: each spine segment is connected to
     * the cross aisles crossing it, and each cross aisle is connected to the
     * rocade segments meeting at its extremity.
     */
    @Test
    @TestTransaction
    void seedDemoData_shouldCreateStorePlan() {
        initializer.seedDemoData();

        StoreBuilding building = StoreBuilding.find("buildingCode", "LYO-042-SHOP").firstResult();

        // Nodes: positioned extremities with their type and label
        List<PlanNode> nodes = PlanNode.listByBuilding(building);
        assertEquals(33, nodes.size());

        PlanNode entrance = PlanNode.findByCode(building, "ENTRANCE");
        assertNotNull(entrance);
        assertEquals(PlanNodeType.ENTRANCE, entrance.nodeType);
        assertEquals("Entrée", entrance.label);
        assertEquals(0.0, entrance.x, 0.0001);
        assertEquals(512.0, entrance.y, 0.0001);

        PlanNode crossing = PlanNode.findByCode(building, "JC_EPI2");
        assertNotNull(crossing);
        assertEquals(PlanNodeType.INTERSECTION, crossing.nodeType);
        assertEquals(0.0, crossing.x, 0.0001);
        assertEquals(0.0, crossing.y, 0.0001);

        // Aisle extremity: an intersection, chained to its neighbours by the rocade
        PlanNode deadEnd = PlanNode.findByCode(building, "END_SUR1");
        assertNotNull(deadEnd);
        assertEquals(PlanNodeType.INTERSECTION, deadEnd.nodeType);
        assertEquals(-736.0, deadEnd.x, 0.0001);
        assertEquals(400.0, deadEnd.y, 0.0001);

        // Aisles: geometric layer (flow/shape, head/tail nodes) + logical graph
        List<Aisle> aisles = Aisle.listByBuilding(building);
        assertEquals(49, aisles.size());

        // All aisles are straight linear segments without waypoints
        for (Aisle aisle : aisles) {
            assertEquals("LINEAR", aisle.flowType);
            assertEquals("STRAIGHT", aisle.shapeType);
            assertTrue(aisle.waypoints.isEmpty());
        }

        // First spine segment: from the entrance to the first crossing
        Aisle cen01 = Aisle.findByCode(building, "CEN_01");
        assertNotNull(cen01);
        assertEquals(14.0, cen01.lengthMeters, 0.0001);
        assertEquals("ENTRANCE", cen01.headNode.code);
        assertEquals("JC_SUR1", cen01.tailNode.code);
        // Logical graph: connected to the next spine segment and the SUR1 cross aisle
        assertNull(cen01.connectedToHead);
        assertEquals("CEN_02,SUR1", cen01.connectedToTail);

        Aisle cen02 = Aisle.findByCode(building, "CEN_02");
        assertNotNull(cen02);
        assertEquals("CEN_01,SUR1", cen02.connectedToHead);
        assertEquals("CEN_03,DRO1", cen02.connectedToTail);

        // Last spine segment: up to the fridges crossing
        Aisle cen13 = Aisle.findByCode(building, "CEN_13");
        assertNotNull(cen13);
        assertEquals("CEN_12,EPI5_W,EPI5_E", cen13.connectedToHead);
        assertEquals("FRA_W,FRA_E", cen13.connectedToTail);
        assertEquals("JC_FRA", cen13.tailNode.code);

        // A cross aisle: from its extremity (chained to the rocade) to the central spine
        Aisle epi2w = Aisle.findByCode(building, "EPI2_W");
        assertNotNull(epi2w);
        assertEquals(92.0, epi2w.lengthMeters, 0.0001);
        assertEquals("END_EPI2_W", epi2w.headNode.code);
        assertEquals("JC_EPI2", epi2w.tailNode.code);
        assertEquals("ROC_W05,ROC_W06", epi2w.connectedToHead);
        assertEquals("CEN_09,CEN_10,EPI2_E", epi2w.connectedToTail);

        // Zones: polygonal areas with their vertices and temperature zone
        List<PlanZone> zones = PlanZone.listByBuilding(building);
        assertEquals(5, zones.size());

        PlanZone fresh = PlanZone.findByCode(building, "FRESH_AREA");
        assertNotNull(fresh);
        assertEquals("Rayon Frais", fresh.label);
        assertEquals("#0891b2", fresh.color);
        assertEquals(TemperatureZone.CHILLED, fresh.temperatureZone);
        assertEquals(4, fresh.vertices.size());
        assertEquals(-800.0, fresh.vertices.get(0).x, 0.0001);
        assertEquals(-448.0, fresh.vertices.get(0).y, 0.0001);
        assertEquals(0, fresh.vertices.get(0).position);

        PlanZone frozen = PlanZone.findByCode(building, "FROZEN_AREA");
        assertNotNull(frozen);
        assertEquals("Surgelés", frozen.label);
        assertEquals("#4f46e5", frozen.color);
        assertEquals(TemperatureZone.FROZEN, frozen.temperatureZone);
        assertEquals(4, frozen.vertices.size());
        assertEquals(-800.0, frozen.vertices.get(0).x, 0.0001);
        assertEquals(160.0, frozen.vertices.get(0).y, 0.0001);

        PlanZone epicerieWest = PlanZone.findByCode(building, "EPICERIE_WEST");
        assertNotNull(epicerieWest);
        assertEquals("Épicerie", epicerieWest.label);
        assertEquals(TemperatureZone.AMBIENT, epicerieWest.temperatureZone);
        assertEquals(4, epicerieWest.vertices.size());

        PlanZone epicerieEast = PlanZone.findByCode(building, "EPICERIE_EAST");
        assertNotNull(epicerieEast);
        assertEquals("Épicerie", epicerieEast.label);
        assertEquals(TemperatureZone.AMBIENT, epicerieEast.temperatureZone);
        assertEquals(4, epicerieEast.vertices.size());

        PlanZone droguerie = PlanZone.findByCode(building, "DROGUERIE_AREA");
        assertNotNull(droguerie);
        assertEquals("Droguerie", droguerie.label);
        assertEquals(TemperatureZone.HOUSEHOLD, droguerie.temperatureZone);
        assertEquals(4, droguerie.vertices.size());
    }

    /**
     * Verifies that the logical graph (connectedToHead/connectedToTail) mirrors
     * the geometry for every aisle: the codes connected to the head extremity are
     * exactly the other aisles sharing the head node (null at a dead-end), and
     * likewise for the tail extremity. This also guarantees the graph is
     * symmetric: if an aisle lists another at one extremity, the other aisle
     * lists it back at the shared node.
     */
    @Test
    @TestTransaction
    void seedDemoData_shouldMirrorGeometryInLogicalGraph() {
        initializer.seedDemoData();

        StoreBuilding building = StoreBuilding.find("buildingCode", "LYO-042-SHOP").firstResult();
        List<Aisle> aisles = Aisle.listByBuilding(building);
        assertEquals(49, aisles.size());

        // Aisle codes sharing each plan node (as head or tail extremity)
        Map<String, Set<String>> atNode = new HashMap<>();
        for (Aisle aisle : aisles) {
            atNode.computeIfAbsent(aisle.headNode.code, k -> new HashSet<>()).add(aisle.code);
            atNode.computeIfAbsent(aisle.tailNode.code, k -> new HashSet<>()).add(aisle.code);
        }

        for (Aisle aisle : aisles) {
            Set<String> expectedHead = new HashSet<>(atNode.getOrDefault(aisle.headNode.code, Set.of()));
            expectedHead.remove(aisle.code);
            Set<String> expectedTail = new HashSet<>(atNode.getOrDefault(aisle.tailNode.code, Set.of()));
            expectedTail.remove(aisle.code);

            assertEquals(expectedHead, splitCodes(aisle.connectedToHead),
                    "connectedToHead of " + aisle.code + " must mirror the aisles at its head node");
            assertEquals(expectedTail, splitCodes(aisle.connectedToTail),
                    "connectedToTail of " + aisle.code + " must mirror the aisles at its tail node");
        }
    }

    private static Set<String> splitCodes(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<String> codes = new HashSet<>();
        for (String part : csv.split(",")) codes.add(part.trim());
        return codes;
    }

    /**
     * Verifies that the demo building is seeded with a store plan background image:
     * the PNG is a realistic store floor plan generated by the seeder (no static
     * file; only the department labels come from the shelf locations), stored
     * base64-encoded, and centered on the editor world origin.
     */
    @Test
    @TestTransaction
    void seedDemoData_shouldSeedPlanImage() throws Exception {
        initializer.seedDemoData();

        StoreBuilding building = StoreBuilding.find("buildingCode", "LYO-042-SHOP").firstResult();

        assertNotNull(building.planImage);
        byte[] decoded = Base64.getDecoder().decode(building.planImage);
        // PNG magic number
        assertEquals((byte) 0x89, decoded[0]);
        assertEquals((byte) 'P', decoded[1]);
        assertEquals((byte) 'N', decoded[2]);
        assertEquals((byte) 'G', decoded[3]);

        // The seeded image is a valid PNG, generated from the plan data (not blank)
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(decoded));
        assertNotNull(image, "Seeded plan image is not a valid PNG");
        // 200 m x 120 m at 8 px/m + 40 px padding on each side
        assertEquals(1680, image.getWidth());
        assertEquals(1040, image.getHeight());
        boolean hasContent = false;
        for (int y = 0; y < image.getHeight() && !hasContent; y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) {
                    hasContent = true;
                    break;
                }
            }
        }
        assertTrue(hasContent, "Seeded plan image is blank");

        // The image is centered on the editor world origin
        assertEquals(-840.0, building.planImageX, 0.0001);
        assertEquals(-520.0, building.planImageY, 0.0001);
    }

    /**
     * Verifies that the extremities of the cross aisles are chained by a
     * perimeter rocade (ROC_W01..ROC_W09 on the west side, ROC_E01..ROC_E08 on
     * the east side): a picker can walk from one aisle end to the next without
     * returning to the central spine. The rocade forms two continuous chains
     * (west: END_SUR1 &rarr; END_FRA_W, east: END_DRO1 &rarr; END_FRA_E), every
     * aisle extremity node is an intersection (no dead-end remains), and every
     * extremity node has at least two connected aisles.
     */
    @Test
    @TestTransaction
    void seedDemoData_shouldConnectAisleEndsWithRocade() {
        initializer.seedDemoData();

        StoreBuilding building = StoreBuilding.find("buildingCode", "LYO-042-SHOP").firstResult();
        List<Aisle> aisles = Aisle.listByBuilding(building);
        Map<String, Aisle> byCode = new HashMap<>();
        for (Aisle aisle : aisles) {
            byCode.put(aisle.code, aisle);
        }

        // The 17 rocade segments exist
        String[] rocadeWest = {"ROC_W01", "ROC_W02", "ROC_W03", "ROC_W04", "ROC_W05",
                "ROC_W06", "ROC_W07", "ROC_W08", "ROC_W09"};
        String[] rocadeEast = {"ROC_E01", "ROC_E02", "ROC_E03", "ROC_E04", "ROC_E05",
                "ROC_E06", "ROC_E07", "ROC_E08"};
        for (String code : rocadeWest) {
            assertNotNull(byCode.get(code), "Rocade segment missing: " + code);
        }
        for (String code : rocadeEast) {
            assertNotNull(byCode.get(code), "Rocade segment missing: " + code);
        }

        // No dead-end remains: every aisle extremity node is an intersection
        for (PlanNode node : PlanNode.listByBuilding(building)) {
            if (node.code.startsWith("END_")) {
                assertEquals(PlanNodeType.INTERSECTION, node.nodeType,
                        node.code + " must be an intersection (chained by the rocade)");
            }
        }

        // Every extremity node has at least two connected aisles (the cross
        // aisle + at least one rocade segment)
        Map<String, Set<String>> atNode = new HashMap<>();
        for (Aisle aisle : aisles) {
            atNode.computeIfAbsent(aisle.headNode.code, k -> new HashSet<>()).add(aisle.code);
            atNode.computeIfAbsent(aisle.tailNode.code, k -> new HashSet<>()).add(aisle.code);
        }
        for (PlanNode node : PlanNode.listByBuilding(building)) {
            if (node.code.startsWith("END_")) {
                assertTrue(atNode.getOrDefault(node.code, Set.of()).size() >= 2,
                        node.code + " must be connected to at least 2 aisles");
            }
        }

        // The west rocade chains END_SUR1 -> ... -> END_FRA_W through the graph
        assertRocadeChain(byCode, rocadeWest, "END_SUR1", "END_FRA_W");
        // The east rocade chains END_DRO1 -> ... -> END_FRA_E through the graph
        assertRocadeChain(byCode, rocadeEast, "END_DRO1", "END_FRA_E");
    }

    /**
     * Walks a rocade chain through the logical graph: starting from the first
     * extremity node, each rocade segment must be reachable from the current
     * node (listed in the connectedToHead/connectedToTail of an aisle at that
     * node) and must connect it to the next node, until the last extremity.
     *
     * @param byCode the aisles keyed by code.
     * @param chain  the ordered rocade segment codes.
     * @param from   the code of the first extremity node.
     * @param to     the code of the last extremity node.
     */
    private static void assertRocadeChain(Map<String, Aisle> byCode, String[] chain, String from, String to) {
        // Aisle codes reachable from each node through the logical graph
        Map<String, Set<String>> graphAtNode = new HashMap<>();
        for (Aisle aisle : byCode.values()) {
            for (String code : splitCodes(aisle.connectedToHead)) {
                graphAtNode.computeIfAbsent(aisle.headNode.code, k -> new HashSet<>()).add(code);
            }
            for (String code : splitCodes(aisle.connectedToTail)) {
                graphAtNode.computeIfAbsent(aisle.tailNode.code, k -> new HashSet<>()).add(code);
            }
        }
        String current = from;
        for (String segment : chain) {
            Aisle aisle = byCode.get(segment);
            // The segment is reachable from the current node through the logical graph
            assertTrue(graphAtNode.getOrDefault(current, Set.of()).contains(segment),
                    segment + " must be reachable from " + current + " through the logical graph");
            // The segment touches the current node at one of its extremities
            boolean atHead = aisle.headNode.code.equals(current);
            boolean atTail = aisle.tailNode.code.equals(current);
            assertTrue(atHead || atTail, segment + " must start from " + current);
            // The other extremity is the next node of the chain
            current = atHead ? aisle.tailNode.code : aisle.headNode.code;
        }
        assertEquals(to, current, "The rocade chain must end at " + to);
    }

    /**
     * Verifies that shelf locations form a coherent store layout: every product is
     * placed in exactly one location, each location sits in an existing aisle of the
     * building, and the area/aisle match the product temperature zone
     * (AMBIENT &rarr; EPICERIE on EPI2..EPI5, CHILLED &rarr; FRAIS on FRA_W/FRA_E,
     * FROZEN &rarr; SURGELES on SUR2/SUR3, HOUSEHOLD &rarr; DROGUERIE on DRO1..DRO3).
     * <p>
     * Also checks the generated location code format, the validity of elevation,
     * position and facings, and that the locations used by the zone collecting
     * orders (FRAIS/FRA_W and EPICERIE/EPI2_W) are populated.
     */
    @Test
    @TestTransaction
    void seedDemoData_shouldPlaceProductsInShelvesCoherently() {
        initializer.seedDemoData();

        StoreBuilding building = StoreBuilding.find("buildingCode", "LYO-042-SHOP").firstResult();

        Map<TemperatureZone, String> expectedArea = Map.of(
                TemperatureZone.AMBIENT, "EPICERIE",
                TemperatureZone.CHILLED, "FRAIS",
                TemperatureZone.FROZEN, "SURGELES",
                TemperatureZone.HOUSEHOLD, "DROGUERIE");
        Map<TemperatureZone, Set<String>> expectedAisles = Map.of(
                TemperatureZone.AMBIENT, Set.of("EPI2_W", "EPI2_E", "EPI3_W", "EPI3_E",
                        "EPI4_W", "EPI4_E", "EPI5_W", "EPI5_E"),
                TemperatureZone.CHILLED, Set.of("FRA_W", "FRA_E"),
                TemperatureZone.FROZEN, Set.of("SUR2", "SUR3"),
                TemperatureZone.HOUSEHOLD, Set.of("DRO1", "DRO2", "DRO3"));

        List<StoreShelfLocation> locations = StoreShelfLocation.<StoreShelfLocation>listAll();
        assertEquals(58, locations.size());

        // Chaque produit est placé dans exactement une localisation
        for (Product product : Product.<Product>listAll()) {
            List<StoreShelfLocation> productLocations = StoreShelfLocation.find("product.id", product.id).list();
            assertEquals(1, productLocations.size(), "Produit sans localisation unique: " + product.ean);
            assertEquals(product.id, productLocations.get(0).product.id);
        }

        Set<Long> buildingLocationIds = new HashSet<>();
        for (ShelfLocation location : building.locations) {
            buildingLocationIds.add(location.id);
        }

        for (StoreShelfLocation location : locations) {
            // L'allée référencée existe dans le bâtiment
            assertNotNull(Aisle.findByCode(building, location.aisle),
                    "Allée inconnue: " + location.aisle);

            // La zone et l'allée sont cohérentes avec la température du produit
            TemperatureZone zone = location.product.temperatureZone;
            assertEquals(expectedArea.get(zone), location.area,
                    "Zone incohérente pour " + location.product.ean);
            assertTrue(expectedAisles.get(zone).contains(location.aisle),
                    "Allée incohérente pour " + location.product.ean + " (" + zone + ")");

            // Le code de localisation suit le format AREA-AISLE-UNIT-ELEVATION-POSITION
            assertEquals(location.area + "-" + location.aisle + "-" + location.unit + "-"
                    + location.elevation + "-" + location.position, location.locationCode);

            // Niveaux, positions et facings valides
            int elevation = Integer.parseInt(location.elevation);
            assertTrue(elevation >= 1 && elevation <= 4, "Élévation invalide: " + location.locationCode);
            assertTrue(Integer.parseInt(location.position) >= 1, "Position invalide: " + location.locationCode);
            assertNotNull(location.facings);
            assertTrue(location.facings >= 1, "Facings invalide: " + location.locationCode);

            // La localisation est bien rattachée au bâtiment
            assertTrue(buildingLocationIds.contains(location.id),
                    "Localisation hors bâtiment: " + location.locationCode);
        }

        // Les localisations utilisées par les ordres de collecte par zone sont peuplées
        assertFalse(StoreShelfLocation.find("area = ?1 and aisle = ?2", "FRAIS", "FRA_W").list().isEmpty());
        assertFalse(StoreShelfLocation.find("area = ?1 and aisle = ?2", "EPICERIE", "EPI2_W").list().isEmpty());
    }

    /**
     * Verifies that the demo FULFILLER configuration is a valid JSON array of
     * fulfiller definitions (Default, ZoneGrouping, ProductGrouping), as expected
     * by the fulfillment engine and the storepilot configuration tile.
     */
    @Test
    @TestTransaction
    void seedDemoData_shouldCreateValidFulfillerConfiguration() throws Exception {
        initializer.seedDemoData();

        Configuration config = Configuration.find("name", "Fulfillment standard").firstResult();
        assertNotNull(config);
        assertEquals("FULFILLER", config.type);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode spec = mapper.readTree(config.specification);
        assertTrue(spec.isArray());
        assertEquals(3, spec.size());

        Set<String> types = new HashSet<>();
        for (JsonNode element : spec) {
            assertTrue(element.has("type"));
            types.add(element.path("type").asText());
            assertFalse(element.path("name").asText().isEmpty());
        }
        assertEquals(Set.of("Default", "ZoneGrouping", "ProductGrouping"), types);

        // The Default fulfiller targets the demo building
        JsonNode defaultSpec = findByType(spec, "Default");
        assertTrue(defaultSpec.path("buildingCodes").isArray());
        assertEquals("LYO-042-SHOP", defaultSpec.path("buildingCodes").get(0).asText());

        // The ZoneGrouping fulfiller requires a building code
        JsonNode zoneSpec = findByType(spec, "ZoneGrouping");
        assertEquals("LYO-042-SHOP", zoneSpec.path("buildingCode").asText());
        assertEquals("FRAIS", zoneSpec.path("area").asText());
        assertEquals("FRA_W", zoneSpec.path("aisle").asText());

        // The ProductGrouping fulfiller targets the EPICERIE family
        JsonNode productSpec = findByType(spec, "ProductGrouping");
        assertTrue(productSpec.path("families").isArray());
        assertEquals("EPICERIE", productSpec.path("families").get(0).asText());
    }

    /**
     * Finds the first element of a JSON array whose {@code type} field matches the given value.
     *
     * @param array the JSON array.
     * @param type  the type to look up.
     * @return the matching element.
     */
    private JsonNode findByType(JsonNode array, String type) {
        for (JsonNode element : array) {
            if (type.equals(element.path("type").asText())) {
                return element;
            }
        }
        throw new AssertionError("No element with type " + type + " in " + array);
    }

    /**
     * Verifies that demo fulfillments are created as already processed (DONE) with
     * a single READY chunk per order, whose lines mirror the order lines and carry
     * the shelf location of each product.
     */
    @Test
    @TestTransaction
    void seedDemoData_shouldCreateFulfillmentsAsDoneWithReadyChunks() {
        initializer.seedDemoData();

        // The activity scenario (ACT- orders, DemoActivityScenarioTest) adds its own fulfillments, one of them not routed.
        List<Fulfillment> fulfillments = Fulfillment.<Fulfillment>listAll().stream().filter(f -> !f.order.orderId.startsWith("ACT-")).toList();
        assertEquals(12, fulfillments.size());
        assertEquals(19, Fulfillment.count("order.orderId like 'ACT-%'"));
        for (Fulfillment fulfillment : fulfillments) {
            // Already processed: the polling consumer only reserves PENDING fulfillments
            assertEquals(ReservationRecord.ReservationStatus.DONE, fulfillment.reservationRecord.status);
            assertNotNull(fulfillment.order);
            assertEquals(1, fulfillment.chunks.size());

            FulfillmentChunk chunk = fulfillment.chunks.iterator().next();
            assertEquals(FulfillmentChunk.READY, chunk.type);
            assertEquals("Default", chunk.label);
            assertNotNull(chunk.deliveryDate);
            assertNotNull(chunk.building);
            assertEquals("LYO-042-SHOP", chunk.building.buildingCode);

            // One fulfillment line per order line, with matching product and quantity
            assertEquals(fulfillment.order.lines.size(), chunk.lines.size());
            for (FulfillmentLine line : chunk.lines) {
                assertNotNull(line.product);
                assertNotNull(line.location);
                assertTrue(line.requestedQuantity.compareTo(BigDecimal.ZERO) > 0);
                boolean matchesOrderLine = fulfillment.order.lines.stream().anyMatch(ol ->
                        ol.product.id.equals(line.product.id)
                                && ol.requestedQuantity.compareTo(line.requestedQuantity) == 0
                                && ol.lineNumber == line.lineNumber);
                assertTrue(matchesOrderLine, "Fulfillment line does not match any order line");
            }
        }

        // The fulfillment of a backdated order carries the backdated delivery slot
        Fulfillment backdated = Fulfillment.find("order.orderId", "CMD-2026-0456").firstResult();
        assertNotNull(backdated);
        FulfillmentChunk backdatedChunk = backdated.chunks.iterator().next();
        assertEquals(backdated.order.orderDate.plusHours(5), backdatedChunk.deliveryDate);
    }

    /**
     * Verifies that demo lots are created with final statuses (never PENDING or
     * PLANNED_TO_BE_COLLECTED, so the order handler builders never pick them up),
     * and that their lines mirror the fulfillment lines.
     */
    @Test
    @TestTransaction
    void seedDemoData_shouldCreateLotsWithFinalStatuses() {
        initializer.seedDemoData();

        // The activity scenario (DemoActivityScenarioTest) deliberately adds pending and collected lots to supervise.
        List<Lot> lots = Lot.<Lot>listAll().stream().filter(l -> !l.fulfillment.order.orderId.startsWith("ACT-")).toList();
        assertEquals(12, lots.size());
        for (Lot lot : lots) {
            assertNotNull(lot.store);
            assertNotNull(lot.chunk);
            assertNotNull(lot.fulfillment);
            assertNotNull(lot.customer);
            assertEquals(lot.fulfillment.order.customer.id, lot.customer.id);
            // Final statuses only: the builders only consume PENDING / PLANNED_TO_BE_COLLECTED lots
            assertTrue(Set.of(Lot.LotStatus.COLLECTED, Lot.LotStatus.PACKED, Lot.LotStatus.DELIVERED)
                    .contains(lot.status), "Unexpected lot status: " + lot.status);

            // One lot line per fulfillment line of the chunk
            assertEquals(lot.chunk.lines.size(), lot.lines.size());
            for (LotLine lotLine : lot.lines) {
                assertNotNull(lotLine.fulfillmentLine);
                assertNotNull(lotLine.product);
                assertNotNull(lotLine.location);
                assertEquals(lotLine.fulfillmentLine.requestedQuantity, lotLine.quantity);
            }
        }

        // Status mapping follows the order status
        Lot shippedLot = Lot.find("fulfillment.order.orderId", "CMD-2026-0453").firstResult();
        assertNotNull(shippedLot);
        assertEquals(Lot.LotStatus.DELIVERED, shippedLot.status);

        Lot inProgressLot = Lot.find("fulfillment.order.orderId", "CMD-2026-0452").firstResult();
        assertNotNull(inProgressLot);
        assertEquals(Lot.LotStatus.PACKED, inProgressLot.status);

        Lot validatedLot = Lot.find("fulfillment.order.orderId", "CMD-2026-0451").firstResult();
        assertNotNull(validatedLot);
        assertEquals(Lot.LotStatus.COLLECTED, validatedLot.status);
    }

    /**
     * Verifies that demo handling orders are created with varied types and statuses,
     * unique business codes, assignees, and lots linked to fulfillments.
     * <p>
     * Statuses are never OPEN (the scheduler only replans OPEN orders), and
     * PROCESSING orders have a requested end in the future (no late event emitted).
     */
    @Test
    @TestTransaction
    void seedDemoData_shouldCreateHandlingOrders() {
        initializer.seedDemoData();

        List<BaseHandlingOrder> all = BaseHandlingOrder.listAll();
        assertEquals(39, all.size());
        // The activity scenario (ACT- orders, DemoActivityScenarioTest) has its own unassigned orders and pending lots.
        assertEquals(10, all.stream().filter(o -> o.code.startsWith("ACT-")).count());
        List<BaseHandlingOrder> orders = all.stream().filter(o -> !o.code.startsWith("ACT-")).toList();

        Set<String> codes = new HashSet<>();
        Set<String> prefixes = new HashSet<>();
        for (BaseHandlingOrder order : orders) {
            assertNotNull(order.store);
            if(order.code.startsWith("AFF-") || order.code.startsWith("OPT-ANN-") || order.code.equals("SUP-RAY-SANS"))assertNull(order.assignee);else assertNotNull(order.assignee);
            assertNotNull(order.requestedStart);
            assertNotNull(order.requestedEnd);
            assertNotNull(order.code);
            assertTrue(order.code.length() <= 15);
            assertTrue(codes.add(order.code), "Duplicate handling order code: " + order.code);
            // Never OPEN: the scheduler only replans OPEN orders
            assertNotEquals(BaseHandlingOrder.HandlingOrderStatus.OPEN, order.status);
            if (order.status == BaseHandlingOrder.HandlingOrderStatus.PROCESSING) {
                // No "late order" event is emitted for PROCESSING orders ended in the past
                assertTrue(order.requestedEnd.isAfter(LocalDateTime.now()));
            }
            prefixes.add(order.getPrefix());
        }
        assertEquals(Set.of("GRP", "PAK", "PRO", "ZON", "REA"), prefixes);

        // Two orders of each type, plus the complex product collecting demo and the supervision grouping
        assertEquals(3, GroupingOrder.count("code not like 'ACT-%'"));
        assertEquals(2, PackingOrder.count());
        assertEquals(3, ProductCollectingOrder.count("code not like 'ACT-%'"));
        assertEquals(2, ZoneCollectingOrder.count());

        // Grouping orders target the demo buffer and carry a customer
        for (GroupingOrder grouping : GroupingOrder.<GroupingOrder>list("code not like 'ACT-%'")) {
            assertNotNull(grouping.buffer);
            // The supervision scenario deliberately targets a deactivated buffer.
            assertEquals(grouping.code.startsWith("SUP-") ? "SUP-BUF-HS" : "LYO-042-BUF-01", grouping.buffer.bufferCode);
            assertNotNull(grouping.customer);
            assertFalse(grouping.lots.isEmpty());
            for (Lot lot : grouping.lots) {
                assertEquals(grouping.customer.id, lot.customer.id);
            }
        }

        // Packing orders carry a deadline and a building
        for (PackingOrder packing : PackingOrder.<PackingOrder>listAll()) {
            assertNotNull(packing.deadLine);
            assertNotNull(packing.building);
            assertNotNull(packing.customer);
            assertFalse(packing.lots.isEmpty());
        }

        // Product collecting orders carry EANs
        for (ProductCollectingOrder collecting : ProductCollectingOrder.<ProductCollectingOrder>list("code not like 'ACT-%'")) {
            assertNotNull(collecting.building);
            assertFalse(collecting.eans.isEmpty());
            assertFalse(collecting.lots.isEmpty());
        }

        // Zone collecting orders carry an area and an aisle
        for (ZoneCollectingOrder collecting : ZoneCollectingOrder.<ZoneCollectingOrder>listAll()) {
            assertNotNull(collecting.building);
            assertNotNull(collecting.area);
            assertNotNull(collecting.aisle);
            assertFalse(collecting.lots.isEmpty());
        }

        // Lots referenced by handling orders keep their final status
        for (BaseHandlingOrder order : orders) {
            if (order instanceof com.intermarche.backoffice.domain.wms.handling.PreparingOrder preparing) {
                for (Lot lot : preparing.lots) {
                    assertTrue(Set.of(Lot.LotStatus.COLLECTED, Lot.LotStatus.PACKED, Lot.LotStatus.DELIVERED)
                            .contains(lot.status), "Lot status was overridden: " + lot.status);
                }
            }
        }
    }

    /**
     * Verifies that demo handling orders produce Gantt dependencies: the Gantt draws
     * an arrow between two orders sharing a lot when the first one ends before the
     * second one starts. The demo data must contain a realistic
     * collecting &rarr; packing &rarr; grouping chain so the Gantt displays arrows.
     * <p>
     * Direct dependencies are counted before the transitive reduction applied by
     * the Gantt: their presence guarantees that arrows are drawn.
     */
    @Test
    @TestTransaction
    void seedDemoData_shouldCreateHandlingOrderDependenciesForGantt() {
        initializer.seedDemoData();

        List<PreparingOrder> orders = PreparingOrder.<PreparingOrder>listAll();

        // Map each lot to the handling orders referencing it (same logic as the Gantt)
        Map<Long, List<PreparingOrder>> ordersByLot = new HashMap<>();
        for (PreparingOrder order : orders) {
            for (Lot lot : order.lots) {
                ordersByLot.computeIfAbsent(lot.id, k -> new ArrayList<>()).add(order);
            }
        }

        // Direct dependencies: orders sharing a lot, the first ending before the second starts
        List<PreparingOrder[]> directDeps = new ArrayList<>();
        ordersByLot.values().forEach(sharing -> {
            for (PreparingOrder a : sharing) {
                for (PreparingOrder b : sharing) {
                    if (a != b && !a.requestedEnd.isAfter(b.requestedStart)) {
                        directDeps.add(new PreparingOrder[]{a, b});
                    }
                }
            }
        });

        assertTrue(directDeps.size() >= 2,
                "Expected Gantt dependencies between handling orders, got: " + directDeps.size());

        // The realistic chain exists: a collecting order precedes a packing order,
        // which precedes a grouping order
        boolean collectingToPacking = directDeps.stream().anyMatch(d ->
                d[0] instanceof ProductCollectingOrder && d[1] instanceof PackingOrder);
        boolean packingToGrouping = directDeps.stream().anyMatch(d ->
                d[0] instanceof PackingOrder && d[1] instanceof GroupingOrder);
        assertTrue(collectingToPacking, "Expected a collecting -> packing dependency for the Gantt");
        assertTrue(packingToGrouping, "Expected a packing -> grouping dependency for the Gantt");
    }

    /**
     * Verifies that demo order requests are created as already processed (DONE)
     * with a valid JSON specification, so the polling consumer never picks them
     * up and never marks them as FAILED.
     */
    @Test
    @TestTransaction
    void seedDemoData_shouldCreateOrderRequestsAsDoneWithValidSpecification() throws Exception {
        initializer.seedDemoData();

        List<OrderRequest> requests = OrderRequest.listAll();
        assertEquals(12, requests.size());
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Double> expectedTotals = Map.ofEntries(
                Map.entry("CMD-2026-0451", 10.50),
                Map.entry("CMD-2026-0452", 10.10),
                Map.entry("CMD-2026-0453", 6.50),
                Map.entry("CMD-2026-0454", 27.50),
                Map.entry("CMD-2026-0455", 29.65),
                Map.entry("CMD-2026-0456", 18.50),
                Map.entry("CMD-2026-0457", 50.70),
                Map.entry("CMD-2026-0458", 23.10),
                Map.entry("CMD-2026-0459", 13.90),
                Map.entry("CMD-2026-0460", 25.70),
                Map.entry("CMD-2026-0461", 5.60),
                Map.entry("CMD-2026-0462", 46.70));
        for (OrderRequest request : requests) {
            assertEquals(ReservationRecord.ReservationStatus.DONE, request.reservationRecord.status);

            JsonNode spec = mapper.readTree(request.specification);
            assertEquals(request.orderNumber, spec.path("orderHeader").path("orderId").asText());
            assertEquals(request.storeCode, spec.path("orderHeader").path("store").path("code").asText());
            assertEquals("LYO-042", spec.path("orderHeader").path("store").path("code").asText());
            assertTrue(spec.path("lines").isArray());
            assertFalse(spec.path("lines").isEmpty());
            assertTrue(spec.path("customer").path("customerId").asText().startsWith("CLI-"));
            assertTrue(spec.path("fulfillment").path("slot").path("start").asText().contains("+"));

            // Each line must carry the full product details expected by the frontend
            double linesTotal = 0;
            for (JsonNode line : spec.path("lines")) {
                assertFalse(line.path("product").path("name").asText().isEmpty());
                assertTrue(line.path("product").path("ean").asText().matches("\\d{13}"));
                assertFalse(line.path("product").path("unitName").asText().isEmpty());
                assertTrue(line.path("pricing").path("unitPrice").asDouble() > 0);
                assertFalse(line.path("logistics").path("temperatureZone").asText().isEmpty());
                linesTotal += line.path("requestedQuantity").asDouble()
                        * line.path("pricing").path("unitPrice").asDouble();
            }
            // The summary total must match the sum of the line totals
            assertEquals(linesTotal, spec.path("summary").path("totalPriceInclTax").asDouble(), 0.001);
            assertEquals(expectedTotals.get(request.orderNumber),
                    spec.path("summary").path("totalPriceInclTax").asDouble(), 0.001);

            // Orders with options must expose them in the specification
            JsonNode options = spec.path("options");
            switch (request.orderNumber) {
                case "CMD-2026-0454" -> {
                    assertEquals(1, options.size());
                    assertEquals("OPT_EXP", options.get(0).path("code").asText());
                    assertEquals("Livraison Express", options.get(0).path("name").asText());
                }
                case "CMD-2026-0455", "CMD-2026-0462" -> {
                    assertEquals(1, options.size());
                    assertEquals("OPT_GIFT", options.get(0).path("code").asText());
                    assertEquals("Carton Cadeau", options.get(0).path("name").asText());
                }
                case "CMD-2026-0457" -> {
                    assertEquals(2, options.size());
                    assertEquals("OPT_EXP", options.get(0).path("code").asText());
                    assertEquals("OPT_COOL", options.get(1).path("code").asText());
                    assertEquals("Caisse Réfrigérée", options.get(1).path("name").asText());
                }
                default -> assertTrue(options.isMissingNode() || options.isEmpty(),
                        "No options expected for " + request.orderNumber);
            }
        }
    }

    /**
     * Verifies that calling seeding twice does not create duplicates.
     */
    @Test
    @TestTransaction
    void seedDemoData_shouldBeIdempotent() {
        initializer.seedDemoData();
        long storeCount = Store.count();
        long productCount = Product.count();
        long orderCount = Order.count();

        initializer.seedDemoData();

        assertEquals(storeCount, Store.count());
        assertEquals(productCount, Product.count());
        assertEquals(orderCount, Order.count());
    }

    /**
     * Verifies that seeding is skipped when stores already exist in the database.
     */
    @Test
    @TestTransaction
    void seedDemoData_shouldSkip_whenStoresExist() {
        Store existing = new Store();
        existing.code = "EXISTING";
        existing.name = "Existing Store";
        existing.persist();

        initializer.seedDemoData();

        assertEquals(1, Store.count());
        assertNull(Store.findByCode("LYO-042"));
    }

    // --------------------------------------------------
    // onStartup
    // --------------------------------------------------

    /**
     * Verifies that {@code onStartup} does nothing when seeding is disabled.
     */
    @Test
    @TestTransaction
    void onStartup_shouldSkip_whenDisabled() {
        initializer.setDemoDataEnabled(false);

        initializer.onStartup(null);

        assertEquals(0, Store.count());
    }

    /**
     * Verifies that {@code onStartup} triggers seeding when enabled.
     */
    @Test
    @TestTransaction
    void onStartup_shouldSeed_whenEnabled() {
        initializer.setDemoDataEnabled(true);

        initializer.onStartup(null);

        assertEquals(1, Store.count());
        assertNotNull(Store.findByCode("LYO-042"));
    }
    @Test @TestTransaction
    void everySeedProductHasPhysicalStockAndNutellaHasATemporalScenario() {
        initializer.seedDemoData();
        Store store=Store.findByCode("LYO-042");
        assertEquals(Product.count(),StockAccount.count("store",store));
        for(Product product:Product.<Product>listAll()) {
            StockAccount account=StockAccount.find("store = ?1 and product = ?2",store,product).firstResult();
            assertNotNull(account,product.ean);
            assertFalse(stocks.positions(account.id).isEmpty(),product.ean);
            assertTrue(stocks.availability(account.id,null,DateTimeProvider.now()).physical.signum()>0,product.ean);
        }
        Product nutella=Product.findByEan("3017620422003");
        StockAccount account=StockAccount.find("store = ?1 and product = ?2",store,nutella).firstResult();
        var timeline=stocks.availability(account.id,null,DateTimeProvider.now());
        assertEquals(0,new BigDecimal("33").compareTo(timeline.physical));
        assertEquals(0,BigDecimal.ZERO.compareTo(timeline.periods.get(0).availableToPromise));
        assertTrue(timeline.protectionRequired);
        assertNotNull(timeline.nextAvailableAt);
    }

    @Test @TestTransaction
    void reseedingPreservesStockActivityAndDoesNotDuplicateFutureFlows() {
        initializer.seedDemoData();
        Store store=Store.findByCode("LYO-042");Product product=Product.findByEan("3560070057003");
        StockAccount account=StockAccount.find("store = ?1 and product = ?2",store,product).firstResult();
        Long locationId=stocks.positions(account.id).get(0).locationId;
        stocks.adjust(account.id,locationId,"TEST-POS-SALE",new BigDecimal("-2"),DateTimeProvider.now());
        BigDecimal before=stocks.availability(account.id,null,DateTimeProvider.now()).physical;
        long movements=StockMovement.count();
        long supplies=ExpectedSupply.count();
        long commitments=StockCommitment.count();
        initializer.seedDemoData();
        assertEquals(0,before.compareTo(stocks.availability(account.id,null,DateTimeProvider.now()).physical));
        assertEquals(movements,StockMovement.count());
        assertEquals(supplies,ExpectedSupply.count());
        assertEquals(commitments,StockCommitment.count());
    }

    @Test @TestTransaction
    void seedsUnassignedHandlingOrdersOnceWithRealSupplyLines(){
        initializer.seedDemoData();
        var orders=com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder.<com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder>list("code like ?1","AFF-%");assertEquals(4,orders.size());
        assertTrue(orders.stream().allMatch(o->o.assignee==null && o.requestedStart!=null && o.requestedEnd.isAfter(o.requestedStart) && !o.supplies.isEmpty()));
        initializer.seedDemoData();assertEquals(4,com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder.count("code like ?1","AFF-%"));
    }

    @Test @TestTransaction
    void existingDemoWithoutStockIsEnrichedOnNextSeed() {
        initializer.seedDemoData();
        StockCommitment.deleteAll();
        com.intermarche.backoffice.domain.replenishment.ReplenishmentDefinition.<com.intermarche.backoffice.domain.replenishment.ReplenishmentDefinition>listAll().forEach(d -> d.lines.forEach(l -> l.forecast = null));
        com.intermarche.backoffice.domain.replenishment.ReplenishmentDefinition.flush();
        var replenishments=com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder.<com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder>listAll();
        replenishments.forEach(o -> com.intermarche.backoffice.domain.handling.HandlingExecution.delete("handlingOrder",o));
        replenishments.forEach(o -> {o.supplies.clear();o.predecessor=null;});
        replenishments.forEach(o -> o.persistAndFlush());
        com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder.deleteAll();
        ExpectedSupply.deleteAll();
        StockMovement.deleteAll();
        StockAccount.deleteAll();
        long products=Product.count();long locations=StoreShelfLocation.count();
        initializer.seedDemoData();
        assertEquals(products,StockAccount.count());
        assertEquals(products,Product.count());assertEquals(locations,StoreShelfLocation.count());
    }

}
