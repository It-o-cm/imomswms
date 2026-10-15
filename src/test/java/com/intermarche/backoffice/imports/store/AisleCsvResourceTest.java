package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.Building;
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
}
