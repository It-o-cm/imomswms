package com.intermarche.e2e.engine.executing;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.engine.executing.PickingRouteOptimizer;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PickingRouteOptimizer}.
 * <p>
 * Topology used in tests (linear):
 * <pre>
 *   A01 (10m) --&gt; A02 (20m) --&gt; A03 (15m)
 * </pre>
 * Distances (sum of aisle lengths along the Dijkstra path):
 * <ul>
 *   <li>dist(A01, A02) = 10 + 20 = 30</li>
 *   <li>dist(A01, A03) = 10 + 20 + 15 = 45</li>
 *   <li>dist(A02, A03) = 20 + 15 = 35</li>
 * </ul>
 */
@QuarkusTest
public class PickingRouteOptimizerTest {

    @Inject
    PickingRouteOptimizer optimizer;

    /**
     * Creates a store with a building and 3 linearly connected aisles.
     * Each test uses a unique prefix to avoid unique constraint violations.
     */
    private StoreBuilding createTestBuilding(String prefix) {
        Store store = new Store();
        store.code = prefix + "-S";
        store.name = "Store " + prefix;
        store.address = new Adresse();
        store.address.streetLine1 = "1 rue du Test";
        store.address.city = "Lyon";
        store.address.postalCode = "69001";
        store.address.country = "France";
        store.persist();

        StoreBuilding building = new StoreBuilding();
        building.buildingCode = prefix + "-STORE";
        building.address = new Adresse();
        building.persist();
        store.buildings.add(building);

        createAisle(building, prefix + "-A01", 10.0, null, prefix + "-A02");
        createAisle(building, prefix + "-A02", 20.0, prefix + "-A01", prefix + "-A03");
        createAisle(building, prefix + "-A03", 15.0, prefix + "-A02", null);

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

    // --------------------------------------------------
    // optimizeRoute — Edge Cases
    // --------------------------------------------------

    @Test
    @TestTransaction
    void optimizeRoute_shouldReturnEmptyList_whenTargetAislesIsNull() {
        StoreBuilding building = createTestBuilding("T01");
        List<String> route = optimizer.optimizeRoute(building, "T01-A01", null);
        assertNotNull(route);
        assertTrue(route.isEmpty());
    }

    @Test
    @TestTransaction
    void optimizeRoute_shouldReturnEmptyList_whenTargetAislesIsEmpty() {
        StoreBuilding building = createTestBuilding("T02");
        List<String> route = optimizer.optimizeRoute(building, "T02-A01", List.of());
        assertNotNull(route);
        assertTrue(route.isEmpty());
    }

    @Test
    @TestTransaction
    void optimizeRoute_shouldReturnSingleAisle_whenOnlyOneTarget() {
        StoreBuilding building = createTestBuilding("T03");
        List<String> route = optimizer.optimizeRoute(building, "T03-A01", List.of("T03-A02"));
        assertEquals(1, route.size());
        assertEquals("T03-A02", route.get(0));
    }

    // --------------------------------------------------
    // optimizeRoute — Route Validity
    // --------------------------------------------------

    @Test
    @TestTransaction
    void optimizeRoute_shouldVisitAllTargetAisles() {
        StoreBuilding building = createTestBuilding("T04");
        List<String> targets = List.of("T04-A01", "T04-A02", "T04-A03");
        List<String> route = optimizer.optimizeRoute(building, "T04-A01", targets);

        assertEquals(3, route.size());
        assertTrue(route.containsAll(targets), "Route must contain all target aisles");
        assertEquals(targets.size(), route.stream().distinct().count(), "Route must not contain duplicates");
    }

    @Test
    @TestTransaction
    void optimizeRoute_shouldProduceValidRoute_whenStartNodeProvided() {
        StoreBuilding building = createTestBuilding("T05");
        // From A01, targets are A02 and A03
        // Note: Nearest Insertion may insert nodes before the first one,
        // so we only assert route validity (all targets, no duplicates).
        List<String> targets = List.of("T05-A02", "T05-A03");
        List<String> route = optimizer.optimizeRoute(building, "T05-A01", targets);

        assertEquals(2, route.size());
        assertTrue(route.containsAll(targets));
        assertEquals(2, route.stream().distinct().count());
    }

    @Test
    @TestTransaction
    void optimizeRoute_shouldWorkWithoutStartNode() {
        StoreBuilding building = createTestBuilding("T06");
        List<String> targets = List.of("T06-A01", "T06-A02", "T06-A03");
        List<String> route = optimizer.optimizeRoute(building, null, targets);

        assertEquals(3, route.size());
        assertTrue(route.containsAll(targets));
    }

    @Test
    @TestTransaction
    void optimizeRoute_shouldWorkWithBlankStartNode() {
        StoreBuilding building = createTestBuilding("T07");
        List<String> targets = List.of("T07-A02", "T07-A03");
        List<String> route = optimizer.optimizeRoute(building, "  ", targets);

        assertEquals(2, route.size());
        assertTrue(route.containsAll(targets));
    }

    @Test
    @TestTransaction
    void optimizeRoute_shouldHandleStartNodeNotInTargets() {
        StoreBuilding building = createTestBuilding("T08");
        List<String> route = optimizer.optimizeRoute(building, "T08-A01", List.of("T08-A03"));

        assertEquals(1, route.size());
        assertEquals("T08-A03", route.get(0));
    }

    // --------------------------------------------------
    // calculateTotalRouteDistance
    // --------------------------------------------------

    @Test
    @TestTransaction
    void calculateTotalRouteDistance_shouldReturnZero_whenRouteIsNull() {
        StoreBuilding building = createTestBuilding("T09");
        int distance = optimizer.calculateTotalRouteDistance(building, "T09-A01", null);
        assertEquals(0, distance);
    }

    @Test
    @TestTransaction
    void calculateTotalRouteDistance_shouldReturnZero_whenRouteIsEmpty() {
        StoreBuilding building = createTestBuilding("T10");
        int distance = optimizer.calculateTotalRouteDistance(building, "T10-A01", List.of());
        assertEquals(0, distance);
    }

    @Test
    @TestTransaction
    void calculateTotalRouteDistance_shouldCalculateCorrectDistance_forLinearRoute() {
        StoreBuilding building = createTestBuilding("T11");
        // Route: A01 -> A02 -> A03
        // dist(A01,A02) = 30, dist(A02,A03) = 35, total = 65
        int distance = optimizer.calculateTotalRouteDistance(building, "T11-A01", List.of("T11-A02", "T11-A03"));
        assertEquals(65, distance);
    }

    @Test
    @TestTransaction
    void calculateTotalRouteDistance_shouldIncludeStartToFirst_whenStartDiffersFromFirst() {
        StoreBuilding building = createTestBuilding("T12");
        // Route: A02 -> A03, starting from A01
        // dist(A01,A02) = 30, dist(A02,A03) = 35, total = 65
        int distance = optimizer.calculateTotalRouteDistance(building, "T12-A01", List.of("T12-A02", "T12-A03"));
        assertEquals(65, distance);
    }

    @Test
    @TestTransaction
    void calculateTotalRouteDistance_shouldUseFirstAisleAsStart_whenStartNodeIsNull() {
        StoreBuilding building = createTestBuilding("T13");
        // Route: A02 -> A03, no start node → effective start = A02
        // dist(A02,A02) = 0, dist(A02,A03) = 35, total = 35
        int distance = optimizer.calculateTotalRouteDistance(building, null, List.of("T13-A02", "T13-A03"));
        assertEquals(35, distance);
    }

    @Test
    @TestTransaction
    void calculateTotalRouteDistance_shouldReturnZero_forSingleAisleRouteWithSameStart() {
        StoreBuilding building = createTestBuilding("T14");
        // Route: A02, start = A02 → dist(A02,A02) = 0
        int distance = optimizer.calculateTotalRouteDistance(building, "T14-A02", List.of("T14-A02"));
        assertEquals(0, distance);
    }

    @Test
    @TestTransaction
    void calculateTotalRouteDistance_shouldCalculateDistance_whenStartIsFirstAisle() {
        StoreBuilding building = createTestBuilding("T15");
        // Route: A01 -> A02 -> A03, start = A01
        // dist(A01,A01)=0, dist(A01,A02)=30, dist(A02,A03)=35 → 65
        int distance = optimizer.calculateTotalRouteDistance(building, "T15-A01", List.of("T15-A01", "T15-A02", "T15-A03"));
        assertEquals(65, distance);
    }

    // --------------------------------------------------
    // optimizeRoute + calculateTotalRouteDistance — Consistency
    // --------------------------------------------------

    @Test
    @TestTransaction
    void optimizedRoute_shouldHaveFiniteDistance() {
        StoreBuilding building = createTestBuilding("T16");
        List<String> targets = List.of("T16-A01", "T16-A02", "T16-A03");
        List<String> route = optimizer.optimizeRoute(building, "T16-A01", targets);

        int distance = optimizer.calculateTotalRouteDistance(building, "T16-A01", route);
        assertTrue(distance > 0, "Optimized route should have a positive distance");
    }
}
