package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.Tote;
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
 * Unit tests for {@link ToteCsvResource}.
 * Focuses on the edge branches of chunk context building, the 1-by-1 fallback
 * context and the tote type parsing of {@code populateTote}/{@code computeChecksum}.
 */
class ToteCsvResourceTest {

    private final ToteCsvResource resource = new ToteCsvResource();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private static ImporterCsvResource.LineData line(String... parts) {
        return new ImporterCsvResource.LineData(2, parts.length > 0 ? parts[0] : null, parts);
    }

    private static Store store(String code, Long id) {
        Store store = new Store();
        store.id = id;
        store.code = code;
        return store;
    }

    private static Tote tote(String code, Long id) {
        Tote tote = new Tote();
        tote.id = id;
        tote.code = code;
        tote.checksum = -999; // never matches an incoming checksum: forces the update path
        return tote;
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
    void processChunkWithFallback_nullStoreCode_skipsStoreFetch() {
        // Only one column: safeGet(parts, 1) is out of bounds -> null
        List<ImporterCsvResource.LineData> lines = List.of(line("T1"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)))
                    .thenReturn(List.of());

            Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("T1"), new int[]{0, 0}, new ArrayList<>());

            // No store code in any line: the store fetch is skipped entirely
            panacheMock.verify(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)), times(1));
            assertTrue(ctx.isEmpty());
        }
    }

    @Test
    void processChunkWithFallback_withStoreCode_fetchesTotesAndStores() {
        Store store = store("S1", 3L);
        Tote tote = tote("T1", 100L);
        List<ImporterCsvResource.LineData> lines = List.of(line("T1", "S1", "25.5", "STANDARD"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)))
                    .thenReturn(List.of(tote), List.of(store));

            Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("T1"), new int[]{0, 0}, new ArrayList<>());

            assertSame(tote, ctx.get("T1"));
            assertSame(store, ctx.get("STORE_S1"));
        }
    }

    // --------------------------------------------------
    // prepareContextForLine (1-by-1 fallback context)
    // --------------------------------------------------

    @Test
    void prepareContextForLine_toteFound_cachesToteAndStore() {
        Tote tote = tote("T1", 100L);
        Store store = store("S1", 3L);
        PanacheQuery<Tote> toteQuery = queryReturning(tote);
        PanacheQuery<Store> storeQuery = queryReturning(store);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                    .thenReturn(toteQuery, storeQuery);

            Map<String, Object> ctx = resource.prepareContextForLine(line("T1", "S1", "25.5", "STANDARD"));

            assertSame(tote, ctx.get("T1"));
            assertSame(store, ctx.get("STORE_S1"));
        }
    }

    @Test
    void prepareContextForLine_nullStoreCode_skipsStoreLookup() {
        Tote tote = tote("T1", 100L);
        PanacheQuery<Tote> toteQuery = queryReturning(tote);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                    .thenReturn(toteQuery);

            // Only one column: no store code
            Map<String, Object> ctx = resource.prepareContextForLine(line("T1"));

            assertSame(tote, ctx.get("T1"));
            assertEquals(1, ctx.size());
        }
    }

    // --------------------------------------------------
    // processLineLogic - tote type parsing
    // --------------------------------------------------

    @Test
    void processLineLogic_nullType_keepsToteType() {
        Store store = store("S1", 3L);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("STORE_S1", store);
        int[] counters = {0, 0};

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheTestSupport.mockArcSession(arcMock);

            // No type column: parts[3] is out of bounds -> null
            resource.processLineLogic(line("T1", "S1", "25.5"), ctx, counters);

            assertEquals(1, counters[0]);
            Tote created = (Tote) ctx.get("T1");
            assertNotNull(created);
            assertNull(created.toteType);
            assertSame(store, created.store);
        }
    }

    @Test
    void processLineLogic_blankType_keepsToteType() {
        Store store = store("S1", 3L);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("STORE_S1", store);
        int[] counters = {0, 0};

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheTestSupport.mockArcSession(arcMock);

            resource.processLineLogic(line("T1", "S1", "25.5", "   "), ctx, counters);

            assertEquals(1, counters[0]);
            assertNull(((Tote) ctx.get("T1")).toteType);
        }
    }

    @Test
    void processLineLogic_unknownType_throwsIAE() {
        Store store = store("S1", 3L);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("STORE_S1", store);
        int[] counters = {0, 0};

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheTestSupport.mockArcSession(arcMock);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> resource.processLineLogic(line("T1", "S1", "25.5", "FOO"), ctx, counters));

            assertTrue(ex.getMessage().contains("Unknown ToteType 'FOO'"));
            assertEquals(0, counters[0]);
        }
    }

    // --------------------------------------------------
    // processLineLogic - update path edge branches
    // --------------------------------------------------

    @Test
    void processLineLogic_existingToteNullType_computesChecksumWithoutType() {
        Store store = store("S1", 3L);
        Tote existing = tote("T1", 100L);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("STORE_S1", store);
        ctx.put("T1", existing);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(eq(100L))).thenReturn(existing);

            // No type column: computeChecksum must skip the enum parsing
            resource.processLineLogic(line("T1", "S1", "25.5"), ctx, counters);

            assertEquals(1, counters[1]);
            assertNull(existing.toteType);
        }
    }

    @Test
    void processLineLogic_existingToteInvalidType_coversChecksumCatchThenThrows() {
        Store store = store("S1", 3L);
        Tote existing = tote("T1", 100L);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("STORE_S1", store);
        ctx.put("T1", existing);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(eq(100L))).thenReturn(existing);

            // computeChecksum swallows the invalid enum, then populateTote rejects it
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> resource.processLineLogic(line("T1", "S1", "25.5", "FOO"), ctx, counters));

            assertTrue(ex.getMessage().contains("Unknown ToteType 'FOO'"));
        }
    }
}
