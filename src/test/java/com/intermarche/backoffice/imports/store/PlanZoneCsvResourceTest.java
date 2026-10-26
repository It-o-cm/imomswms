package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.PlanZone;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.TemperatureZone;
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
 * Unit tests for {@link PlanZoneCsvResource}.
 * Focuses on the point parsing, the chunk context building, the 1-by-1 fallback
 * context, the population logic and the checksum consistency with the entity.
 */
class PlanZoneCsvResourceTest {

    private final PlanZoneCsvResource resource = new PlanZoneCsvResource();

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

    private static PlanZone zone(String code, Long id) {
        PlanZone zone = new PlanZone();
        zone.id = id;
        zone.code = code;
        return zone;
    }

    @SuppressWarnings("unchecked")
    private static <T> PanacheQuery<T> queryReturning(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    // --------------------------------------------------
    // parsePoints
    // --------------------------------------------------

    @Test
    void parsePoints_null_returnsEmpty() {
        assertTrue(PlanZoneCsvResource.parsePoints(null).isEmpty());
    }

    @Test
    void parsePoints_blank_returnsEmpty() {
        assertTrue(PlanZoneCsvResource.parsePoints("   ").isEmpty());
    }

    @Test
    void parsePoints_valid_returnsPointsInOrder() {
        List<double[]> points = PlanZoneCsvResource.parsePoints("-293,47;-188,47;-183,120");

        assertEquals(3, points.size());
        assertArrayEquals(new double[]{-293.0, 47.0}, points.get(0), 0.0001);
        assertArrayEquals(new double[]{-188.0, 47.0}, points.get(1), 0.0001);
        assertArrayEquals(new double[]{-183.0, 120.0}, points.get(2), 0.0001);
    }

    @Test
    void parsePoints_malformedPoints_areSkipped() {
        List<double[]> points = PlanZoneCsvResource.parsePoints("1,2;abc,def;3,4;5");

        assertEquals(2, points.size());
        assertArrayEquals(new double[]{1.0, 2.0}, points.get(0), 0.0001);
        assertArrayEquals(new double[]{3.0, 4.0}, points.get(1), 0.0001);
    }

    // --------------------------------------------------
    // processChunkWithFallback
    // --------------------------------------------------

    @Test
    void processChunkWithFallback_nullBuildingCode_skipsBuildingFetch() {
        // Only one column: safeGet(parts, 1) is out of bounds -> null
        List<ImporterCsvResource.LineData> lines = List.of(line("Z1"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)))
                    .thenReturn(List.of());

            Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("Z1"), new int[]{0, 0}, new ArrayList<>());

            // No building code in any line: the building fetch is skipped entirely
            panacheMock.verify(() -> PanacheEntityBase.list(eq("buildingCode IN ?1"), any(Object[].class)), never());
            assertTrue(ctx.isEmpty());
        }
    }

    @Test
    void processChunkWithFallback_withBuildingCode_fetchesZonesAndBuildings() {
        Building building = building("B1", 7L);
        PlanZone zone = zone("Z1", 100L);
        List<ImporterCsvResource.LineData> lines = List.of(
                line("Z1", "B1", "Rayon Frais", "#0891b2", "CHILLED", "0,0;10,0;10,10", "0", "0"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)))
                    .thenReturn(List.of(zone));
            panacheMock.when(() -> PanacheEntityBase.list(eq("buildingCode IN ?1"), any(Object[].class)))
                    .thenReturn(List.of(building));

            Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("Z1"), new int[]{0, 0}, new ArrayList<>());

            assertSame(zone, ctx.get("Z1"));
            assertSame(building, ctx.get("BUILDING_B1"));
        }
    }

    // --------------------------------------------------
    // prepareContextForLine (1-by-1 fallback context)
    // --------------------------------------------------

    @Test
    void prepareContextForLine_zoneFound_cachesZoneAndBuilding() {
        PlanZone zone = zone("Z1", 100L);
        Building building = building("B1", 7L);
        PanacheQuery<PlanZone> zoneQuery = queryReturning(zone);
        PanacheQuery<Building> buildingQuery = queryReturning(building);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                    .thenReturn(zoneQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);

            Map<String, Object> ctx = resource.prepareContextForLine(
                    line("Z1", "B1", "Rayon Frais", "#0891b2", "CHILLED", "0,0;10,0;10,10", "0", "0"));

            assertSame(zone, ctx.get("Z1"));
            assertSame(building, ctx.get("BUILDING_B1"));
        }
    }

    @Test
    void prepareContextForLine_nullBuildingCode_skipsBuildingLookup() {
        PlanZone zone = zone("Z1", 100L);
        PanacheQuery<PlanZone> zoneQuery = queryReturning(zone);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                    .thenReturn(zoneQuery);

            // Only one column: no building code
            Map<String, Object> ctx = resource.prepareContextForLine(line("Z1"));

            assertSame(zone, ctx.get("Z1"));
            assertEquals(1, ctx.size());
        }
    }

    // --------------------------------------------------
    // populateZone
    // --------------------------------------------------

    @Test
    void populateZone_setsAllFields() {
        PlanZone zone = new PlanZone();

        resource.populateZone(
                new String[]{"Z1", "B1", "Rayon Frais", "#0891b2", "CHILLED", "-293,47;-188,47;-183,120", "1.5", "-2.5"},
                zone);

        assertEquals("Rayon Frais", zone.label);
        assertEquals("#0891b2", zone.color);
        assertEquals(TemperatureZone.CHILLED, zone.temperatureZone);
        assertEquals(3, zone.vertices.size());
        assertEquals(0, zone.vertices.get(0).position);
        assertEquals(-293.0, zone.vertices.get(0).x, 0.0001);
        assertEquals(47.0, zone.vertices.get(0).y, 0.0001);
        assertEquals(2, zone.vertices.get(2).position);
        assertEquals(-183.0, zone.vertices.get(2).x, 0.0001);
        assertSame(zone, zone.vertices.get(0).zone);
        assertEquals(1.5, zone.labelOffsetX, 0.0001);
        assertEquals(-2.5, zone.labelOffsetY, 0.0001);
    }

    @Test
    void populateZone_blankTemperatureZone_setsNull() {
        PlanZone zone = new PlanZone();

        resource.populateZone(new String[]{"Z1", "B1", "Z", "#fff", "", "0,0;10,0;10,10", "", ""}, zone);

        assertNull(zone.temperatureZone);
        assertNull(zone.labelOffsetX);
        assertNull(zone.labelOffsetY);
    }

    @Test
    void populateZone_tooFewVertices_throws() {
        PlanZone zone = new PlanZone();

        assertThrows(IllegalArgumentException.class,
                () -> resource.populateZone(new String[]{"Z1", "B1", "Z", "#fff", "", "0,0;10,0", "0", "0"}, zone));
    }

    @Test
    void populateZone_replacesExistingVertices() {
        PlanZone zone = new PlanZone();
        resource.populateZone(new String[]{"Z1", "B1", "Z", "#fff", "", "0,0;10,0;10,10", "0", "0"}, zone);
        assertEquals(3, zone.vertices.size());

        resource.populateZone(new String[]{"Z1", "B1", "Z", "#fff", "", "0,0;20,0;20,20;0,20", "0", "0"}, zone);

        assertEquals(4, zone.vertices.size());
        assertEquals(20.0, zone.vertices.get(1).x, 0.0001);
    }

    // --------------------------------------------------
    // computeChecksum
    // --------------------------------------------------

    @Test
    void computeChecksum_matchesEntityChecksum() {
        String[] parts = {"Z1", "B1", "Rayon Frais", "#0891b2", "CHILLED", "-293,47;-188,47;-183,120", "1.5", "-2.5"};

        PlanZone zone = new PlanZone();
        zone.code = "Z1";
        zone.building = building("B1", 7L);
        resource.populateZone(parts, zone);

        assertEquals(zone.getChecksum(), resource.computeChecksum(parts, "Z1"));
    }

    @Test
    void computeChecksum_changesWhenVertexOrderChanges() {
        String[] parts = {"Z1", "B1", "Z", "#fff", "", "0,0;10,0;10,10", "0", "0"};
        int checksum1 = resource.computeChecksum(parts, "Z1");

        parts[5] = "10,10;10,0;0,0";
        int checksum2 = resource.computeChecksum(parts, "Z1");

        assertNotEquals(checksum1, checksum2);
    }
}
