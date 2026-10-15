package com.intermarche.backoffice.imports;

import com.intermarche.backoffice.domain.Configuration;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreGroup;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConfigurationCsvResource}.
 * Focuses on the store/group code resolution branches of {@code processLineLogic}
 * (missing, blank and populated code columns). The e2e suite covers the happy path;
 * these tests pin the defensive branches without a database.
 */
class ConfigurationCsvResourceTest {

    private final ConfigurationCsvResource resource = new ConfigurationCsvResource();

    private static ImporterCsvResource.LineData line(String... parts) {
        return new ImporterCsvResource.LineData(1, parts[0], parts);
    }

    private static Store store(String code) {
        Store store = new Store();
        store.code = code;
        return store;
    }

    private static StoreGroup group(String code) {
        StoreGroup group = new StoreGroup();
        group.code = code;
        return group;
    }

    @Test
    void processLineLogic_missingCodeColumns_skipsResolution() {
        EntityManager em = mock(EntityManager.class);
        Map<String, Object> entityMap = new HashMap<>();
        int[] counters = {0, 0};

        try (MockedStatic<Panache> panacheMock = mockStatic(Panache.class);
             MockedStatic<Store> storeMock = mockStatic(Store.class);
             MockedStatic<StoreGroup> groupMock = mockStatic(StoreGroup.class)) {
            panacheMock.when(Panache::getEntityManager).thenReturn(em);

            // Only 4 columns: StoreCodes and GroupCodes are absent (null)
            resource.processLineLogic(line("CFG-1", "FULFILLMENT", "10", "{}"), entityMap, counters);

            storeMock.verify(() -> Store.findByCode(any()), never());
            groupMock.verify(() -> StoreGroup.findByCode(any()), never());
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            verify(em).persist(any(Configuration.class));
        }
    }

    @Test
    void processLineLogic_blankCodeColumns_skipsResolution() {
        EntityManager em = mock(EntityManager.class);
        Map<String, Object> entityMap = new HashMap<>();
        int[] counters = {0, 0};

        try (MockedStatic<Panache> panacheMock = mockStatic(Panache.class);
             MockedStatic<Store> storeMock = mockStatic(Store.class);
             MockedStatic<StoreGroup> groupMock = mockStatic(StoreGroup.class)) {
            panacheMock.when(Panache::getEntityManager).thenReturn(em);

            resource.processLineLogic(line("CFG-1", "FULFILLMENT", "10", "{}", "  ", ""), entityMap, counters);

            storeMock.verify(() -> Store.findByCode(any()), never());
            groupMock.verify(() -> StoreGroup.findByCode(any()), never());
            assertEquals(1, counters[0]);
            verify(em).persist(any(Configuration.class));
        }
    }

    @Test
    void processLineLogic_withCodes_resolvesKnownAndSkipsUnknown() {
        EntityManager em = mock(EntityManager.class);
        Map<String, Object> entityMap = new HashMap<>();
        int[] counters = {0, 0};
        Store knownStore = store("S1");
        StoreGroup knownGroup = group("G1");

        try (MockedStatic<Panache> panacheMock = mockStatic(Panache.class);
             MockedStatic<Store> storeMock = mockStatic(Store.class);
             MockedStatic<StoreGroup> groupMock = mockStatic(StoreGroup.class)) {
            panacheMock.when(Panache::getEntityManager).thenReturn(em);
            storeMock.when(() -> Store.findByCode("S1")).thenReturn(knownStore);
            storeMock.when(() -> Store.findByCode("S2")).thenReturn(null);
            groupMock.when(() -> StoreGroup.findByCode("G1")).thenReturn(knownGroup);
            groupMock.when(() -> StoreGroup.findByCode("G2")).thenReturn(null);

            resource.processLineLogic(line("CFG-1", "FULFILLMENT", "10", "{}", "S1,S2", "G1,G2"), entityMap, counters);

            assertEquals(1, counters[0]);
            verify(em).persist(argThat(config ->
                    config instanceof Configuration c
                            && c.stores.size() == 1 && c.stores.contains(knownStore)
                            && c.storeGroups.size() == 1 && c.storeGroups.contains(knownGroup)));
        }
    }
}
