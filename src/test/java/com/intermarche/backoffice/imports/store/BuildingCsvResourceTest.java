package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.GPSPosition;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.WarehouseBuilding;
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
 * Unit tests for {@link BuildingCsvResource}.
 * Focuses on the edge branches of chunk context building, the 1-by-1 fallback
 * context and the GPS/address population of existing buildings.
 */
class BuildingCsvResourceTest {

    private final BuildingCsvResource resource = new BuildingCsvResource();

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
        building.checksum = -999; // never matches an incoming checksum: forces the update path
        return building;
    }

    private static Store store(String code, Long id) {
        Store store = new Store();
        store.id = id;
        store.code = code;
        return store;
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
    void processChunkWithFallback_nullStoreCode_skipsStoreExtraction() {
        // Only one column: safeGet(parts, 1) is out of bounds -> null
        List<ImporterCsvResource.LineData> lines = List.of(line("B1"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("buildingCode IN ?1"), any(Object[].class)))
                    .thenReturn(List.of());
            panacheMock.when(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)))
                    .thenReturn(List.of());

            Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("B1"), new int[]{0, 0}, new ArrayList<>());

            assertTrue(ctx.isEmpty());
        }
    }

    @Test
    void processChunkWithFallback_withCodes_cachesBuildingsAndStores() {
        Building building = building("B1", 7L);
        Store store = store("S1", 3L);
        List<ImporterCsvResource.LineData> lines = List.of(
                line("B1", "S1", "STORE", "1 Main St", "", "75000", "Lyon", "FR", "45.75", "4.85"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("buildingCode IN ?1"), any(Object[].class)))
                    .thenReturn(List.of(building));
            panacheMock.when(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)))
                    .thenReturn(List.of(store));

            Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("B1"), new int[]{0, 0}, new ArrayList<>());

            assertSame(building, ctx.get("B1"));
            assertSame(store, ctx.get("STORE_S1"));
        }
    }

    // --------------------------------------------------
    // prepareContextForLine (1-by-1 fallback context)
    // --------------------------------------------------

    @Test
    void prepareContextForLine_buildingFound_cachesBuildingAndStore() {
        Building building = building("B1", 7L);
        Store store = store("S1", 3L);
        PanacheQuery<Building> buildingQuery = queryReturning(building);
        PanacheQuery<Store> storeQuery = queryReturning(store);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                    .thenReturn(storeQuery);

            Map<String, Object> ctx = resource.prepareContextForLine(
                    line("B1", "S1", "STORE", "1 Main St", "", "75000", "Lyon", "FR", "45.75", "4.85"));

            assertSame(building, ctx.get("B1"));
            assertSame(store, ctx.get("STORE_S1"));
        }
    }

    @Test
    void prepareContextForLine_nullStoreCode_skipsStoreLookup() {
        Building building = building("B1", 7L);
        PanacheQuery<Building> buildingQuery = queryReturning(building);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);

            // Only one column: no store code
            Map<String, Object> ctx = resource.prepareContextForLine(line("B1"));

            assertSame(building, ctx.get("B1"));
            assertNull(ctx.get("STORE_null"));
            assertEquals(1, ctx.size());
        }
    }

    // --------------------------------------------------
    // processLineLogic
    // --------------------------------------------------

    @Test
    void processLineLogic_newWarehouseBuilding_createsAndCounts() {
        Store store = store("S1", 3L);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("STORE_S1", store);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(eq(3L))).thenReturn(store);

            resource.processLineLogic(
                    line("B1", "S1", "WAREHOUSE", "1 Main St", "", "75000", "Lyon", "FR", "45.75", "4.85"), ctx, counters);

            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            Building created = (Building) ctx.get("B1");
            assertNotNull(created);
            assertInstanceOf(WarehouseBuilding.class, created);
            assertEquals("B1", created.buildingCode);
            assertTrue(store.buildings.contains(created));
            assertNotNull(created.address);
            assertNotNull(created.gpsPosition);
            assertEquals(45.75, created.gpsPosition.latitude);
        }
    }

    @Test
    void processLineLogic_existingBuildingWithGps_keepsGpsInstance() {
        Building existing = building("B1", 7L);
        GPSPosition gps = new GPSPosition(1.0, 2.0);
        existing.gpsPosition = gps;
        Store store = store("S1", 3L);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("B1", existing);
        ctx.put("STORE_S1", store);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(eq(7L))).thenReturn(existing);

            resource.processLineLogic(
                    line("B1", "S1", "STORE", "2 Main St", "", "75000", "Lyon", "FR", "45.75", "4.85"), ctx, counters);

            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            // The existing GPSPosition instance is reused, not replaced
            assertSame(gps, existing.gpsPosition);
            assertEquals(45.75, existing.gpsPosition.latitude);
            assertEquals("2 Main St", existing.address.streetLine1);
        }
    }

    @Test
    void processLineLogic_missingStore_throwsIAE() {
        Map<String, Object> ctx = new HashMap<>();
        int[] counters = {0, 0};

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(
                        line("B1", "UNKNOWN", "STORE", "1 Main St", "", "75000", "Lyon", "FR", "45.75", "4.85"), ctx, counters));

        assertTrue(ex.getMessage().contains("Store with code 'UNKNOWN' not found"));
        assertEquals(0, counters[0]);
    }
}
