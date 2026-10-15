package com.intermarche.backoffice.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreGroup;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Configuration}.
 * Pure unit tests — no database, no Quarkus.
 */
class ConfigurationTest {

    private Configuration createConfig() {
        Configuration config = new Configuration();
        config.name = "Default Pricing";
        config.type = "PRICING";
        config.specification = "{\"key\":\"value\"}";
        return config;
    }

    private Store createStore(String code) {
        Store store = new Store();
        store.code = code;
        store.name = "Store " + code;
        return store;
    }

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaultConstructor_priorityIsZero() {
        assertEquals(0, new Configuration().priority);
    }

    @Test
    void defaultConstructor_collectionsInitializedEmpty() {
        Configuration config = new Configuration();
        assertNotNull(config.stores);
        assertTrue(config.stores.isEmpty());
        assertNotNull(config.storeGroups);
        assertTrue(config.storeGroups.isEmpty());
    }

    // --------------------------------------------------
    // getSpecificationFromJSON
    // --------------------------------------------------

    @Test
    void getSpecificationFromJSON_validJson_returnsNode() {
        JsonNode node = createConfig().getSpecificationFromJSON();
        assertNotNull(node);
        assertEquals("value", node.get("key").asText());
    }

    @Test
    void getSpecificationFromJSON_invalidJson_throwsRuntimeException() {
        Configuration config = createConfig();
        config.specification = "{not valid json";

        RuntimeException exception = assertThrows(RuntimeException.class, config::getSpecificationFromJSON);
        assertTrue(exception.getMessage().contains("Default Pricing"));
    }

    // --------------------------------------------------
    // appliesTo
    // --------------------------------------------------

    @Test
    void appliesTo_directStoreLink_returnsTrue() {
        Configuration config = createConfig();
        Store store = createStore("S001");
        config.stores.add(store);

        assertTrue(config.appliesTo(store));
    }

    @Test
    void appliesTo_viaStoreGroup_returnsTrue() {
        Configuration config = createConfig();
        Store store = createStore("S001");
        StoreGroup group = new StoreGroup();
        group.code = "G1";
        group.stores.add(store);
        config.storeGroups.add(group);

        assertTrue(config.appliesTo(store));
    }

    @Test
    void appliesTo_noLink_returnsFalse() {
        Configuration config = createConfig();
        assertFalse(config.appliesTo(createStore("S001")));
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_changesWithName() {
        Configuration config = createConfig();
        int checksum1 = config.getChecksum();
        config.name = "Other name";
        assertNotEquals(checksum1, config.getChecksum());
    }

    @Test
    void getChecksum_changesWithType() {
        Configuration config = createConfig();
        int checksum1 = config.getChecksum();
        config.type = "FULFILLMENT";
        assertNotEquals(checksum1, config.getChecksum());
    }

    @Test
    void getChecksum_changesWithSpecification() {
        Configuration config = createConfig();
        int checksum1 = config.getChecksum();
        config.specification = "{\"key\":\"other\"}";
        assertNotEquals(checksum1, config.getChecksum());
    }

    @Test
    void getChecksum_changesWithPriority() {
        Configuration config = createConfig();
        int checksum1 = config.getChecksum();
        config.priority = 10;
        assertNotEquals(checksum1, config.getChecksum());
    }

    @Test
    void getChecksum_includesStoreCodes() {
        Configuration config = createConfig();
        int checksum1 = config.getChecksum();
        config.stores.add(createStore("S001"));
        assertNotEquals(checksum1, config.getChecksum());
    }

    @Test
    void getChecksum_includesGroupCodes() {
        Configuration config = createConfig();
        int checksum1 = config.getChecksum();
        StoreGroup group = new StoreGroup();
        group.code = "G1";
        config.storeGroups.add(group);
        assertNotEquals(checksum1, config.getChecksum());
    }

    @Test
    void getChecksum_storeCodesOrderIndependent() {
        Configuration config1 = createConfig();
        config1.stores.add(createStore("S001"));
        config1.stores.add(createStore("S002"));

        Configuration config2 = createConfig();
        config2.stores.add(createStore("S002"));
        config2.stores.add(createStore("S001"));

        assertEquals(config1.getChecksum(), config2.getChecksum());
    }

    @Test
    void getChecksum_sameFields_sameValue() {
        assertEquals(createConfig().getChecksum(), createConfig().getChecksum());
    }

    @Test
    void getChecksum_emptyRelations_noException() {
        assertDoesNotThrow(() -> createConfig().getChecksum());
    }

    // --------------------------------------------------
    // findConfigurationsForStore
    // --------------------------------------------------

    @Test
    void findConfigurationsForStore_nullStore_returnsEmpty() {
        assertTrue(Configuration.findConfigurationsForStore(null, "TYPE").isEmpty());
    }

    @Test
    void findConfigurationsForStore_storeWithoutId_returnsEmpty() {
        Store store = createStore("S001");
        assertTrue(Configuration.findConfigurationsForStore(store, "TYPE").isEmpty());
    }

    @Test
    void findConfigurationsForStore_nullType_returnsEmpty() {
        Store store = createStore("S001");
        store.id = 1L;
        assertTrue(Configuration.findConfigurationsForStore(store, null).isEmpty());
    }

    @Test
    void findConfigurationsForStore_noGroups_queriesDirectStoreLink() {
        Store store = createStore("S001");
        store.id = 1L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<StoreGroup> groupMock = mockStatic(StoreGroup.class)) {
            groupMock.when(() -> StoreGroup.findAllStoreGroups(any())).thenReturn(Set.of());
            panacheMock.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                    .thenReturn(List.of());

            List<Configuration> result = Configuration.findConfigurationsForStore(store, "TYPE");

            assertTrue(result.isEmpty());
            panacheMock.verify(() -> PanacheEntityBase.list(
                    eq("type = ?1 and ?2 member of stores"), eq("TYPE"), eq(store)));
        }
    }

    @Test
    void findConfigurationsForStore_withGroups_queriesStoreAndGroupLinks() {
        Store store = createStore("S001");
        store.id = 1L;
        StoreGroup group = new StoreGroup();
        group.id = 5L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<StoreGroup> groupMock = mockStatic(StoreGroup.class)) {
            groupMock.when(() -> StoreGroup.findAllStoreGroups(any())).thenReturn(Set.of(group));
            panacheMock.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                    .thenReturn(List.of());

            List<Configuration> result = Configuration.findConfigurationsForStore(store, "TYPE");

            assertTrue(result.isEmpty());
            panacheMock.verify(() -> PanacheEntityBase.list(
                    argThat(jpql -> jpql.contains("member of stores or id in")),
                    eq("TYPE"), eq(store), any()));
        }
    }

    // --------------------------------------------------
    // findHighestPriorityForStore
    // --------------------------------------------------

    @Test
    void findHighestPriorityForStore_nullStore_throwsIllegalState() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> Configuration.findHighestPriorityForStore(null, "TYPE"));
        assertTrue(exception.getMessage().contains("Store cannot be null"));
    }
}
