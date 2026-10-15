package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.imports.ImporterCsvResource;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
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
 * Unit tests for {@link BufferCsvResource}.
 * Focuses on the edge branches of chunk context building, the 1-by-1 fallback
 * lookup/context and the purpose parsing of {@code populateBuffer}/{@code computeChecksum}.
 */
class BufferCsvResourceTest {

    private final BufferCsvResource resource = new BufferCsvResource();

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

    private static Buffer buffer(String code, Long id) {
        Buffer buffer = new Buffer();
        buffer.id = id;
        buffer.bufferCode = code;
        buffer.checksum = -999; // never matches an incoming checksum: forces the update path
        return buffer;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Buffer> bufferMap(Map<String, Object> ctx) {
        return (Map<String, Buffer>) ctx.get("BUFFERS");
    }

    @SuppressWarnings("unchecked")
    private static <T> PanacheQuery<T> queryReturning(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    private static Map<String, Object> contextWithBuffers(Map<String, Buffer> buffers) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("BUFFERS", buffers);
        return ctx;
    }

    // --------------------------------------------------
    // processChunkWithFallback
    // --------------------------------------------------

    @Test
    void processChunkWithFallback_nullBuildingCode_skipsBuildingFetch() {
        // Only one column: safeGet(parts, 1) is out of bounds -> null
        List<ImporterCsvResource.LineData> lines = List.of(line("BUF1"));

        Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("BUF1"), new int[]{0, 0}, new ArrayList<>());

        @SuppressWarnings("unchecked")
        Map<String, Building> buildings = (Map<String, Building>) ctx.get("BUILDINGS");
        @SuppressWarnings("unchecked")
        Map<String, Buffer> buffers = (Map<String, Buffer>) ctx.get("BUFFERS");
        assertTrue(buildings.isEmpty());
        assertTrue(buffers.isEmpty());
    }

    @Test
    void processChunkWithFallback_withBuildingCode_fetchesBuildingsAndBuffers() {
        Building building = building("B1", 7L);
        Buffer buffer = buffer("BUF1", 100L);
        List<ImporterCsvResource.LineData> lines = List.of(
                line("BUF1", "B1", "STAGING", "10", "5", "2", "50.5", "Dock 1", "true"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("buildingCode IN ?1"), any(Object[].class)))
                    .thenReturn(List.of(building));
            panacheMock.when(() -> PanacheEntityBase.list(eq("building.id IN ?1"), any(Object[].class)))
                    .thenReturn(List.of(buffer));

            Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("BUF1"), new int[]{0, 0}, new ArrayList<>());

            @SuppressWarnings("unchecked")
            Map<String, Building> buildings = (Map<String, Building>) ctx.get("BUILDINGS");
            assertSame(building, buildings.get("B1"));
            assertSame(buffer, bufferMap(ctx).get("BUF1"));
        }
    }

    // --------------------------------------------------
    // findEntityForLine (1-by-1 fallback lookup)
    // --------------------------------------------------

    @Test
    void findEntityForLine_buildingNotFound_returnsNull() {
        PanacheQuery<Building> buildingQuery = queryReturning(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);

            assertNull(resource.findEntityForLine(line("BUF1", "B1", "STAGING")));
        }
    }

    @Test
    void findEntityForLine_buildingFound_returnsBuffer() {
        Building building = building("B1", 7L);
        Buffer buffer = buffer("BUF1", 100L);
        PanacheQuery<Building> buildingQuery = queryReturning(building);
        PanacheQuery<Buffer> bufferQuery = queryReturning(buffer);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("building"), any(Object[].class)))
                    .thenReturn(bufferQuery);

            assertSame(buffer, resource.findEntityForLine(line("BUF1", "B1", "STAGING")));
        }
    }

    // --------------------------------------------------
    // prepareContextForLine (1-by-1 fallback context)
    // --------------------------------------------------

    @Test
    void prepareContextForLine_buildingFoundWithBuffer_cachesBuffer() {
        Building building = building("B1", 7L);
        Buffer buffer = buffer("BUF1", 100L);
        PanacheQuery<Building> buildingQuery = queryReturning(building);
        PanacheQuery<Buffer> bufferQuery = queryReturning(buffer);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("building"), any(Object[].class)))
                    .thenReturn(bufferQuery);

            Map<String, Object> ctx = resource.prepareContextForLine(line("BUF1", "B1", "STAGING"));

            assertSame(buffer, bufferMap(ctx).get("BUF1"));
        }
    }

    @Test
    void prepareContextForLine_buildingNotFound_noBuffersKey() {
        PanacheQuery<Building> buildingQuery = queryReturning(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);

            Map<String, Object> ctx = resource.prepareContextForLine(line("BUF1", "B1", "STAGING"));

            assertNull(ctx.get("BUFFERS"));
        }
    }

    // --------------------------------------------------
    // processLineLogic - purpose parsing
    // --------------------------------------------------

    @Test
    void processLineLogic_unknownPurpose_throwsIAE() {
        Building building = building("B1", 7L);
        PanacheQuery<Building> buildingQuery = queryReturning(building);
        Map<String, Object> ctx = contextWithBuffers(new HashMap<>());
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> resource.processLineLogic(line("BUF1", "B1", "FOO", "10", "5", "2", "50.5", "Dock 1", "true"), ctx, counters));

            assertTrue(ex.getMessage().contains("Unknown BufferPurpose 'FOO'"));
            assertEquals(0, counters[0]);
        }
    }

    @Test
    void processLineLogic_nullPurpose_keepsDefault() {
        Building building = building("B1", 7L);
        PanacheQuery<Building> buildingQuery = queryReturning(building);
        Map<String, Object> ctx = contextWithBuffers(new HashMap<>());
        int[] counters = {0, 0};

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);

            // Null purpose column: parts[2] is null
            resource.processLineLogic(line("BUF1", "B1", null, "10", "5", "2", "50.5", "Dock 1", "true"), ctx, counters);

            assertEquals(1, counters[0]);
            Buffer created = bufferMap(ctx).get("BUF1");
            assertNotNull(created);
            assertEquals(Buffer.BufferPurpose.STAGING, created.purpose);
        }
    }

    @Test
    void processLineLogic_blankPurpose_keepsDefault() {
        Building building = building("B1", 7L);
        PanacheQuery<Building> buildingQuery = queryReturning(building);
        Map<String, Object> ctx = contextWithBuffers(new HashMap<>());
        int[] counters = {0, 0};

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);

            resource.processLineLogic(line("BUF1", "B1", "   ", "10", "5", "2", "50.5", "Dock 1", "true"), ctx, counters);

            assertEquals(1, counters[0]);
            assertEquals(Buffer.BufferPurpose.STAGING, bufferMap(ctx).get("BUF1").purpose);
        }
    }

    // --------------------------------------------------
    // processLineLogic - update path edge branches
    // --------------------------------------------------

    @Test
    void processLineLogic_existingBufferNullCode_updatesWithNullCode() {
        Building building = building("B1", 7L);
        Buffer existing = buffer("BUF1", 100L);
        PanacheQuery<Building> buildingQuery = queryReturning(building);
        Map<String, Buffer> buffers = new HashMap<>();
        buffers.put(null, existing); // data.code is null: parts[0] == null
        Map<String, Object> ctx = contextWithBuffers(buffers);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);
            panacheMock.when(() -> PanacheEntityBase.findById(eq(100L))).thenReturn(existing);

            resource.processLineLogic(line(null, "B1", "STAGING", "10", "5", "2", "50.5", "Dock 1", "true"), ctx, counters);

            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertNull(existing.bufferCode);
        }
    }

    @Test
    void processLineLogic_existingBufferNullPurpose_computesChecksumWithoutPurpose() {
        Building building = building("B1", 7L);
        Buffer existing = buffer("BUF1", 100L);
        PanacheQuery<Building> buildingQuery = queryReturning(building);
        Map<String, Buffer> buffers = new HashMap<>();
        buffers.put("BUF1", existing);
        Map<String, Object> ctx = contextWithBuffers(buffers);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);
            panacheMock.when(() -> PanacheEntityBase.findById(eq(100L))).thenReturn(existing);

            // Null purpose column: computeChecksum must skip the enum parsing
            resource.processLineLogic(line("BUF1", "B1", null, "10", "5", "2", "50.5", "Dock 1", "true"), ctx, counters);

            assertEquals(1, counters[1]);
            assertEquals(Buffer.BufferPurpose.STAGING, existing.purpose);
        }
    }

    @Test
    void processLineLogic_existingBufferInvalidPurpose_coversChecksumCatchThenThrows() {
        Building building = building("B1", 7L);
        Buffer existing = buffer("BUF1", 100L);
        PanacheQuery<Building> buildingQuery = queryReturning(building);
        Map<String, Buffer> buffers = new HashMap<>();
        buffers.put("BUF1", existing);
        Map<String, Object> ctx = contextWithBuffers(buffers);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class)))
                    .thenReturn(buildingQuery);
            panacheMock.when(() -> PanacheEntityBase.findById(eq(100L))).thenReturn(existing);

            // computeChecksum swallows the invalid enum, then populateBuffer rejects it
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> resource.processLineLogic(line("BUF1", "B1", "FOO", "10", "5", "2", "50.5", "Dock 1", "true"), ctx, counters));

            assertTrue(ex.getMessage().contains("Unknown BufferPurpose 'FOO'"));
        }
    }
}
