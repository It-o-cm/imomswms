package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.PlanNode;
import com.intermarche.backoffice.domain.store.PlanNodeType;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.imports.ImporterCsvResource;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PlanNodeCsvResource}.
 * Focuses on the chunk context building, the 1-by-1 fallback context, the
 * population logic and the checksum consistency with the entity.
 */
class PlanNodeCsvResourceTest {

    private final PlanNodeCsvResource resource = new PlanNodeCsvResource();

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

    private static PlanNode node(String code, Long id) {
        PlanNode node = new PlanNode();
        node.id = id;
        node.code = code;
        return node;
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
        List<ImporterCsvResource.LineData> lines = List.of(line("N1"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)))
                    .thenReturn(List.of());

            Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("N1"), new int[]{0, 0}, new ArrayList<>());

            // No building code in any line: the building fetch is skipped entirely
            panacheMock.verify(() -> PanacheEntityBase.list(eq("buildingCode IN ?1"), any(Object[].class)), never());
            assertTrue(ctx.isEmpty());
        }
    }

    @Test
    void processChunkWithFallback_withBuildingCode_fetchesNodesAndBuildings() {
        Building building = building("B1", 7L);
        PlanNode node = node("N1", 100L);
        List<ImporterCsvResource.LineData> lines = List.of(line("N1", "B1", "Entrée", "ENTRANCE", "-83", "242"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)))
                    .thenReturn(List.of(node));
            panacheMock.when(() -> PanacheEntityBase.list(eq("buildingCode IN ?1"), any(Object[].class)))
                    .thenReturn(List.of(building));

            Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("N1"), new int[]{0, 0}, new ArrayList<>());

            assertSame(node, ctx.get("N1"));
            assertSame(building, ctx.get("BUILDING_B1"));
        }
    }

    // --------------------------------------------------
    // prepareContextForLine (1-by-1 fallback context)
    // --------------------------------------------------

    @Test
    void prepareContextForLine_nodeFound_cachesNodeAndBuilding() {
        PlanNode node = node("N1", 100L);
        Building building = building("B1", 7L);
        PanacheQuery<PlanNode> nodeQuery = queryReturning(node);
        PanacheQuery<Building> buildingQuery = queryReturning(building);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                    .thenReturn(nodeQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);

            Map<String, Object> ctx = resource.prepareContextForLine(line("N1", "B1", "Entrée", "ENTRANCE", "-83", "242"));

            assertSame(node, ctx.get("N1"));
            assertSame(building, ctx.get("BUILDING_B1"));
        }
    }

    @Test
    void prepareContextForLine_nullBuildingCode_skipsBuildingLookup() {
        PlanNode node = node("N1", 100L);
        PanacheQuery<PlanNode> nodeQuery = queryReturning(node);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                    .thenReturn(nodeQuery);

            // Only one column: no building code
            Map<String, Object> ctx = resource.prepareContextForLine(line("N1"));

            assertSame(node, ctx.get("N1"));
            assertEquals(1, ctx.size());
        }
    }

    // --------------------------------------------------
    // populateNode
    // --------------------------------------------------

    @Test
    void populateNode_setsAllFields() {
        PlanNode node = new PlanNode();

        resource.populateNode(new String[]{"N1", "B1", "Entrée", "ENTRANCE", "-83.5", "242"}, node);

        assertEquals("Entrée", node.label);
        assertEquals(PlanNodeType.ENTRANCE, node.nodeType);
        assertEquals(-83.5, node.x, 0.0001);
        assertEquals(242.0, node.y, 0.0001);
    }

    @Test
    void populateNode_blankType_setsNullNodeType() {
        PlanNode node = new PlanNode();

        resource.populateNode(new String[]{"N1", "B1", "", "", "", ""}, node);

        assertNull(node.label);
        assertNull(node.nodeType);
        assertNull(node.x);
        assertNull(node.y);
    }

    @Test
    void populateNode_invalidType_throws() {
        PlanNode node = new PlanNode();

        assertThrows(IllegalArgumentException.class,
                () -> resource.populateNode(new String[]{"N1", "B1", "X", "UNKNOWN", "0", "0"}, node));
    }

    // --------------------------------------------------
    // computeChecksum
    // --------------------------------------------------

    @Test
    void computeChecksum_matchesEntityChecksum() {
        String[] parts = {"N1", "B1", "Entrée", "ENTRANCE", "-83.5", "242"};

        PlanNode node = new PlanNode();
        node.code = "N1";
        node.building = building("B1", 7L);
        resource.populateNode(parts, node);

        assertEquals(node.getChecksum(), resource.computeChecksum(parts, "N1"));
    }

    @Test
    void computeChecksum_changesWhenFieldChanges() {
        String[] parts = {"N1", "B1", "Entrée", "ENTRANCE", "-83.5", "242"};
        int checksum1 = resource.computeChecksum(parts, "N1");

        parts[4] = "-84";
        int checksum2 = resource.computeChecksum(parts, "N1");

        assertNotEquals(checksum1, checksum2);
    }
}
