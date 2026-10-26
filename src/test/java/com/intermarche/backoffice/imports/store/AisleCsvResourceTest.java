package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.PlanNode;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.imports.ImporterCsvResource;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AisleCsvResource}.
 * Focuses on the edge branches of chunk context building and the 1-by-1 fallback context.
 */
class AisleCsvResourceTest {

    private final AisleCsvResource resource = new AisleCsvResource();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private static ImporterCsvResource.LineData line(String... parts) {
        return new ImporterCsvResource.LineData(2, parts.length > 0 ? parts[0] : null, parts);
    }

    private static Building building(String code, Long id) {
        StoreBuilding building = new StoreBuilding();
        building.id = id;
        building.buildingCode = code;
        return building;
    }

    private static Aisle aisle(String code, Long id) {
        Aisle aisle = new Aisle();
        aisle.id = id;
        aisle.code = code;
        return aisle;
    }

    @SuppressWarnings("unchecked")
    private static <T> PanacheQuery<T> queryReturning(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    // --------------------------------------------------
    // processChunkWithFallback
    // --------------------------------------------------

    @Test
    void processChunkWithFallback_nullBuildingCode_skipsBuildingFetch() {
        // Only one column: safeGet(parts, 1) is out of bounds -> null
        List<ImporterCsvResource.LineData> lines = List.of(line("A1"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)))
                    .thenReturn(List.of());

            Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("A1"), new int[]{0, 0}, new ArrayList<>());

            // No building code in any line: the building fetch is skipped entirely
            panacheMock.verify(() -> PanacheEntityBase.list(eq("buildingCode IN ?1"), any(Object[].class)), never());
            assertTrue(ctx.isEmpty());
        }
    }

    @Test
    void processChunkWithFallback_withBuildingCode_fetchesAislesAndBuildings() {
        Building building = building("B1", 7L);
        Aisle aisle = aisle("A1", 100L);
        List<ImporterCsvResource.LineData> lines = List.of(line("A1", "B1", "12.5", "PICKING", "", ""));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)))
                    .thenReturn(List.of(aisle));
            panacheMock.when(() -> PanacheEntityBase.list(eq("buildingCode IN ?1"), any(Object[].class)))
                    .thenReturn(List.of(building));

            Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("A1"), new int[]{0, 0}, new ArrayList<>());

            assertSame(aisle, ctx.get("A1"));
            assertSame(building, ctx.get("BUILDING_B1"));
        }
    }

    // --------------------------------------------------
    // prepareContextForLine (1-by-1 fallback context)
    // --------------------------------------------------

    @Test
    void prepareContextForLine_aisleFound_cachesAisleAndBuilding() {
        Aisle aisle = aisle("A1", 100L);
        Building building = building("B1", 7L);
        PanacheQuery<Aisle> aisleQuery = queryReturning(aisle);
        PanacheQuery<Building> buildingQuery = queryReturning(building);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                    .thenReturn(aisleQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);

            Map<String, Object> ctx = resource.prepareContextForLine(line("A1", "B1", "12.5", "PICKING", "", ""));

            assertSame(aisle, ctx.get("A1"));
            assertSame(building, ctx.get("BUILDING_B1"));
        }
    }

    @Test
    void prepareContextForLine_nullBuildingCode_skipsBuildingLookup() {
        Aisle aisle = aisle("A1", 100L);
        PanacheQuery<Aisle> aisleQuery = queryReturning(aisle);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                    .thenReturn(aisleQuery);

            // Only one column: no building code
            Map<String, Object> ctx = resource.prepareContextForLine(line("A1"));

            assertSame(aisle, ctx.get("A1"));
            assertEquals(1, ctx.size());
        }
    }

    // --------------------------------------------------
    // processChunkWithFallback (geometry columns)
    // --------------------------------------------------

    @Test
    void processChunkWithFallback_noNodeCodes_skipsNodeFetch() {
        List<ImporterCsvResource.LineData> lines = List.of(line("A1", "B1", "12.5", "PICKING", "", ""));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)))
                    .thenReturn(List.of());
            panacheMock.when(() -> PanacheEntityBase.list(eq("buildingCode IN ?1"), any(Object[].class)))
                    .thenReturn(List.of());

            resource.processChunkWithFallback(lines, Set.of("A1"), new int[]{0, 0}, new ArrayList<>());

            // No head/tail node codes: the node fetch is skipped entirely
            panacheMock.verify(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)), times(1));
        }
    }

    @Test
    void processChunkWithFallback_withNodeCodes_fetchesNodes() {
        PlanNode head = planNode("N_HEAD", 50L);
        PlanNode tail = planNode("N_TAIL", 51L);
        List<ImporterCsvResource.LineData> lines = List.of(
                line("A1", "B1", "12.5", "PICKING", "", "", "LINEAR", "STRAIGHT", "N_HEAD", "N_TAIL", ""));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)))
                    .thenReturn(List.of());
            panacheMock.when(() -> PanacheEntityBase.list(eq("buildingCode IN ?1"), any(Object[].class)))
                    .thenReturn(List.of());
            // The node fetch reuses the "code IN ?1" query: aisles first, then nodes
            panacheMock.when(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)))
                    .thenReturn(List.of(), List.of(head, tail));

            Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("A1"), new int[]{0, 0}, new ArrayList<>());

            assertSame(head, ctx.get("NODE_N_HEAD"));
            assertSame(tail, ctx.get("NODE_N_TAIL"));
        }
    }

    // --------------------------------------------------
    // prepareContextForLine (geometry columns)
    // --------------------------------------------------

    @Test
    void prepareContextForLine_withNodeCodes_fetchesNodes() {
        PlanNode head = planNode("N_HEAD", 50L);
        // Query mocks are built before the static stubbing: creating a mock inside
        // a thenReturn(...) argument would break the ongoing static stubbing.
        PanacheQuery<Aisle> aisleQuery = queryReturning(null);
        PanacheQuery<PlanNode> nodeQuery = queryReturning(head);
        PanacheQuery<Building> buildingQuery = queryReturning(building("B1", 7L));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                    .thenReturn(aisleQuery, nodeQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);

            Map<String, Object> ctx = resource.prepareContextForLine(
                    line("A1", "B1", "12.5", "PICKING", "", "", "LINEAR", "STRAIGHT", "N_HEAD", "", ""));

            assertSame(head, ctx.get("NODE_N_HEAD"));
            assertFalse(ctx.containsKey("NODE_N_TAIL"));
        }
    }

    @Test
    void prepareContextForLine_unknownNode_skipsNodeEntry() {
        PanacheQuery<Aisle> aisleQuery = queryReturning(null);
        PanacheQuery<PlanNode> nodeQuery = queryReturning(null);
        PanacheQuery<Building> buildingQuery = queryReturning(building("B1", 7L));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                    .thenReturn(aisleQuery, nodeQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);

            Map<String, Object> ctx = resource.prepareContextForLine(
                    line("A1", "B1", "12.5", "PICKING", "", "", "LINEAR", "STRAIGHT", "N_GHOST", "", ""));

            assertFalse(ctx.containsKey("NODE_N_GHOST"));
        }
    }

    // --------------------------------------------------
    // populateAisle (geometry columns)
    // --------------------------------------------------

    @Test
    void populateAisle_withGeometry_setsAllFields() {
        Aisle aisle = new Aisle();
        aisle.code = "A1";
        Map<String, Object> context = new HashMap<>();
        context.put("NODE_N_HEAD", planNode("N_HEAD", 50L));
        context.put("NODE_N_TAIL", planNode("N_TAIL", 51L));

        resource.populateAisle(
                new String[]{"A1", "B1", "12.5", "PICKING", "H", "T", "CIRCULAR", "CURVED", "N_HEAD", "N_TAIL", "-403,-268;-433,62"},
                aisle, context);

        assertEquals(12.5, aisle.lengthMeters, 0.0001);
        assertEquals("PICKING", aisle.aisleType);
        assertEquals("H", aisle.connectedToHead);
        assertEquals("T", aisle.connectedToTail);
        assertEquals("CIRCULAR", aisle.flowType);
        assertEquals("CURVED", aisle.shapeType);
        assertEquals("N_HEAD", aisle.headNode.code);
        assertEquals("N_TAIL", aisle.tailNode.code);
        assertEquals(2, aisle.waypoints.size());
        assertEquals(0, aisle.waypoints.get(0).position);
        assertEquals(-403.0, aisle.waypoints.get(0).x, 0.0001);
        assertEquals(-268.0, aisle.waypoints.get(0).y, 0.0001);
        assertEquals(1, aisle.waypoints.get(1).position);
        assertSame(aisle, aisle.waypoints.get(0).aisle);
    }

    @Test
    void populateAisle_blankGeometryColumns_clearsFields() {
        Aisle aisle = new Aisle();
        aisle.code = "A1";
        aisle.flowType = "LINEAR";
        aisle.shapeType = "STRAIGHT";
        aisle.headNode = planNode("N_OLD", 60L);
        aisle.waypoints.add(waypoint(aisle, 0, 1.0, 2.0));

        // 6 columns only: the geometry columns are absent
        resource.populateAisle(new String[]{"A1", "B1", "12.5", "PICKING", "", ""}, aisle, new HashMap<>());

        assertNull(aisle.flowType);
        assertNull(aisle.shapeType);
        assertNull(aisle.headNode);
        assertNull(aisle.tailNode);
        assertTrue(aisle.waypoints.isEmpty());
    }

    @Test
    void populateAisle_unknownNode_throws() {
        Aisle aisle = new Aisle();
        aisle.code = "A1";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.populateAisle(
                        new String[]{"A1", "B1", "12.5", "PICKING", "", "", "LINEAR", "STRAIGHT", "N_GHOST", "", ""},
                        aisle, new HashMap<>()));

        assertTrue(ex.getMessage().contains("N_GHOST"));
    }

    @Test
    void populateAisle_replacesWaypoints() {
        Aisle aisle = new Aisle();
        aisle.code = "A1";
        aisle.waypoints.add(waypoint(aisle, 0, 1.0, 2.0));

        resource.populateAisle(
                new String[]{"A1", "B1", "12.5", "PICKING", "", "", "LINEAR", "STRAIGHT", "", "", "5,6;7,8"},
                aisle, new HashMap<>());

        assertEquals(2, aisle.waypoints.size());
        assertEquals(5.0, aisle.waypoints.get(0).x, 0.0001);
        assertEquals(8.0, aisle.waypoints.get(1).y, 0.0001);
    }

    // --------------------------------------------------
    // computeChecksum (geometry columns)
    // --------------------------------------------------

    @Test
    void computeChecksum_withGeometry_matchesEntityChecksum() {
        String[] parts = {"A1", "B1", "12.5", "PICKING", "H", "T", "CIRCULAR", "CURVED", "N_HEAD", "N_TAIL", "-403,-268;-433,62"};

        Aisle aisle = new Aisle();
        aisle.code = "A1";
        aisle.building = building("B1", 7L);
        Map<String, Object> context = new HashMap<>();
        context.put("NODE_N_HEAD", planNode("N_HEAD", 50L));
        context.put("NODE_N_TAIL", planNode("N_TAIL", 51L));
        resource.populateAisle(parts, aisle, context);

        assertEquals(aisle.getChecksum(), resource.computeChecksum(parts, "A1"));
    }

    @Test
    void computeChecksum_withoutGeometry_matchesEntityChecksum() {
        String[] parts = {"A1", "B1", "12.5", "PICKING", "H", "T"};

        Aisle aisle = new Aisle();
        aisle.code = "A1";
        aisle.building = building("B1", 7L);
        resource.populateAisle(parts, aisle, new HashMap<>());

        assertEquals(aisle.getChecksum(), resource.computeChecksum(parts, "A1"));
    }

    @Test
    void computeChecksum_changesWhenWaypointOrderChanges() {
        String[] parts = {"A1", "B1", "12.5", "PICKING", "", "", "LINEAR", "STRAIGHT", "", "", "1,2;3,4"};
        int checksum1 = resource.computeChecksum(parts, "A1");

        parts[10] = "3,4;1,2";
        int checksum2 = resource.computeChecksum(parts, "A1");

        assertNotEquals(checksum1, checksum2);
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private static PlanNode planNode(String code, Long id) {
        PlanNode node = new PlanNode();
        node.id = id;
        node.code = code;
        return node;
    }

    private static com.intermarche.backoffice.domain.store.AisleWaypoint waypoint(Aisle aisle, int position, double x, double y) {
        com.intermarche.backoffice.domain.store.AisleWaypoint waypoint = new com.intermarche.backoffice.domain.store.AisleWaypoint();
        waypoint.aisle = aisle;
        waypoint.position = position;
        waypoint.x = x;
        waypoint.y = y;
        return waypoint;
    }
}
