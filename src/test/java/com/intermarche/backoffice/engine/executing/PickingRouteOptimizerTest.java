package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link PickingRouteOptimizer} (nearest-insertion heuristic + Dijkstra).
 * {@code Aisle.list("building", building)} is stubbed; no Quarkus, no database.
 */
class PickingRouteOptimizerTest {

    private final StoreBuilding building = new StoreBuilding();

    private Aisle newAisle(String code, Double lengthMeters, String connectedToHead, String connectedToTail) {
        Aisle aisle = new Aisle();
        aisle.code = code;
        aisle.lengthMeters = lengthMeters;
        aisle.connectedToHead = connectedToHead;
        aisle.connectedToTail = connectedToTail;
        aisle.building = building;
        return aisle;
    }

    private MockedStatic<PanacheEntityBase> stubAisles(List<Aisle> aisles) {
        MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
        panacheMock.when(() -> PanacheEntityBase.list(eq("building"), any(Object[].class)))
                .thenReturn(aisles);
        return panacheMock;
    }

    /**
     * Linear topology: A - B - C, each 10m.
     * dist(A,B)=20, dist(B,C)=20, dist(A,C)=30 (path A,B,C).
     */
    private List<Aisle> linearAisles() {
        return List.of(
                newAisle("A", 10.0, null, "B"),
                newAisle("B", 10.0, "A", "C"),
                newAisle("C", 10.0, "B", null)
        );
    }

    @Test
    void optimizeRoute_nullOrEmptyTargets_returnsEmpty() {
        PickingRouteOptimizer optimizer = new PickingRouteOptimizer();
        assertTrue(optimizer.optimizeRoute(building, "A", null).isEmpty());
        assertTrue(optimizer.optimizeRoute(building, "A", List.of()).isEmpty());
    }

    @Test
    void optimizeRoute_nullBuilding_allDistancesZero() {
        PickingRouteOptimizer optimizer = new PickingRouteOptimizer();
        // No aisles loaded: every distance is 0, each insertion lands at position 0
        assertEquals(List.of("C", "B", "A"), optimizer.optimizeRoute(null, "S", List.of("A", "B", "C")));
    }

    @Test
    void optimizeRoute_nullStartNode_usesFirstTargetAsVirtualStart() {
        PickingRouteOptimizer optimizer = new PickingRouteOptimizer();
        try (MockedStatic<PanacheEntityBase> panacheMock = stubAisles(linearAisles())) {
            assertEquals(List.of("A", "B"), optimizer.optimizeRoute(building, null, List.of("A", "B")));
        }
    }

    @Test
    void optimizeRoute_blankStartNode_usesFirstTargetAsVirtualStart() {
        PickingRouteOptimizer optimizer = new PickingRouteOptimizer();
        try (MockedStatic<PanacheEntityBase> panacheMock = stubAisles(linearAisles())) {
            assertEquals(List.of("A", "B"), optimizer.optimizeRoute(building, "  ", List.of("A", "B")));
        }
    }

    @Test
    void optimizeRoute_singleTarget_returnsIt() {
        PickingRouteOptimizer optimizer = new PickingRouteOptimizer();
        try (MockedStatic<PanacheEntityBase> panacheMock = stubAisles(linearAisles())) {
            assertEquals(List.of("B"), optimizer.optimizeRoute(building, "A", List.of("B")));
        }
    }

    @Test
    void optimizeRoute_nearestInsertion_picksNearestFirstThenCheapestInsertion() {
        PickingRouteOptimizer optimizer = new PickingRouteOptimizer();
        try (MockedStatic<PanacheEntityBase> panacheMock = stubAisles(linearAisles())) {
            // Nearest to A is B (20 < 30); inserting C at position 0 (cost 30) beats appending (cost 40)
            assertEquals(List.of("C", "B"), optimizer.optimizeRoute(building, "A", List.of("C", "B")));
        }
    }

    @Test
    void optimizeRoute_unconnectedTarget_hasZeroDistance() {
        PickingRouteOptimizer optimizer = new PickingRouteOptimizer();
        List<Aisle> aisles = List.of(
                newAisle("A", 10.0, null, null),
                newAisle("B", 10.0, null, null)
        );
        try (MockedStatic<PanacheEntityBase> panacheMock = stubAisles(aisles)) {
            // No path between A and B: Dijkstra exhausts the queue, distance is 0
            assertEquals(List.of("B"), optimizer.optimizeRoute(building, "A", List.of("B")));
        }
    }

    @Test
    void optimizeRoute_startNodeNotInMap_hasNoNeighbors() {
        PickingRouteOptimizer optimizer = new PickingRouteOptimizer();
        try (MockedStatic<PanacheEntityBase> panacheMock = stubAisles(linearAisles())) {
            assertEquals(List.of("A"), optimizer.optimizeRoute(building, "ENTRANCE", List.of("A")));
        }
    }

    @Test
    void optimizeRoute_neighborNotInMap_edgeCostTreatsItAsZeroLength() {
        PickingRouteOptimizer optimizer = new PickingRouteOptimizer();
        List<Aisle> aisles = List.of(newAisle("A", 10.0, null, "GATE"));
        try (MockedStatic<PanacheEntityBase> panacheMock = stubAisles(aisles)) {
            // GATE is not an aisle: edge cost = len(A)/2 + 0, path distance = len(A)
            assertEquals(List.of("GATE"), optimizer.optimizeRoute(building, "A", List.of("GATE")));
        }
    }

    @Test
    void optimizeRoute_nullLengthMeters_treatedAsZero() {
        PickingRouteOptimizer optimizer = new PickingRouteOptimizer();
        List<Aisle> aisles = List.of(
                newAisle("A", null, null, "B"),
                newAisle("B", null, "A", null)
        );
        try (MockedStatic<PanacheEntityBase> panacheMock = stubAisles(aisles)) {
            assertEquals(List.of("B"), optimizer.optimizeRoute(building, "A", List.of("B")));
        }
    }

    @Test
    void calculateTotalRouteDistance_nullOrEmptyRoute_returnsZero() {
        PickingRouteOptimizer optimizer = new PickingRouteOptimizer();
        assertEquals(0, optimizer.calculateTotalRouteDistance(building, "A", null));
        assertEquals(0, optimizer.calculateTotalRouteDistance(building, "A", List.of()));
    }

    @Test
    void calculateTotalRouteDistance_nullStart_usesFirstRouteNode() {
        PickingRouteOptimizer optimizer = new PickingRouteOptimizer();
        try (MockedStatic<PanacheEntityBase> panacheMock = stubAisles(linearAisles())) {
            assertEquals(20, optimizer.calculateTotalRouteDistance(building, null, List.of("A", "B")));
        }
    }

    @Test
    void calculateTotalRouteDistance_blankStart_usesFirstRouteNode() {
        PickingRouteOptimizer optimizer = new PickingRouteOptimizer();
        try (MockedStatic<PanacheEntityBase> panacheMock = stubAisles(linearAisles())) {
            assertEquals(20, optimizer.calculateTotalRouteDistance(building, " ", List.of("A", "B")));
        }
    }

    @Test
    void calculateTotalRouteDistance_sumsLegDistances() {
        PickingRouteOptimizer optimizer = new PickingRouteOptimizer();
        try (MockedStatic<PanacheEntityBase> panacheMock = stubAisles(linearAisles())) {
            // A->A = 0, A->B = 20, B->C = 20
            assertEquals(40, optimizer.calculateTotalRouteDistance(building, "A", List.of("A", "B", "C")));
        }
    }

    @Test
    void calculateTotalRouteDistance_nullBuilding_returnsZero() {
        PickingRouteOptimizer optimizer = new PickingRouteOptimizer();
        assertEquals(0, optimizer.calculateTotalRouteDistance(null, "A", List.of("A", "B")));
    }

    /**
     * Topology where node X is relaxed twice with a decreasing distance:
     * S - A - X (dist 7) is found first, then S - B - C - X (dist 6) improves it.
     * The stale queue entry X(7) is polled afterwards and skipped by the lazy-deletion check.
     * <p>
     * Edge cost = lenFrom/2 + lenTo/2; path distance = sum of aisle lengths on the path.
     */
    @Test
    void calculateTotalRouteDistance_staleQueueEntry_isSkipped() {
        PickingRouteOptimizer optimizer = new PickingRouteOptimizer();
        List<Aisle> aisles = List.of(
                newAisle("S", 2.0, null, "A,B"),
                newAisle("A", 5.0, "S", "X"),
                newAisle("B", 2.0, "S", "C"),
                newAisle("C", 2.0, "B", "X"),
                newAisle("X", 2.0, "A,C", "W"),
                newAisle("W", 2.0, "X", null)
        );
        try (MockedStatic<PanacheEntityBase> panacheMock = stubAisles(aisles)) {
            // Shortest path S -> B -> C -> X -> W: 2+2+2+2+2 = 10 (via A would be 11)
            assertEquals(10, optimizer.calculateTotalRouteDistance(building, "S", List.of("W")));
        }
    }
}
