package com.intermarche.e2e.domain;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.Configuration;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreGroup;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link Configuration}.
 * Tests configuration resolution by store, priority handling, and applicability checks.
 */
@QuarkusTest
public class ConfigurationTest {

    @Inject
    EntityManager em;

    private Store createStore(String code) {
        Store store = new Store();
        store.code = code;
        store.name = "Store " + code;
        store.address = new Adresse();
        store.address.streetLine1 = "1 rue de Test";
        store.address.city = "Paris";
        store.address.postalCode = "75001";
        store.address.country = "France";
        store.persist();
        return store;
    }

    private StoreGroup createStoreGroup(String code) {
        StoreGroup group = new StoreGroup();
        group.code = code;
        group.name = code;
        group.stores = new HashSet<>();
        group.storeGroups = new HashSet<>();
        group.persist();
        return group;
    }

    private Configuration createConfig(String name, String type, int priority, String spec) {
        Configuration config = new Configuration();
        config.name = name;
        config.type = type;
        config.priority = priority;
        config.specification = spec;
        config.stores = new HashSet<>();
        config.storeGroups = new HashSet<>();
        config.persist();
        return config;
    }

    // --------------------------------------------------
    // findByType Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void findByType_shouldReturnMatchingConfigurations() {
        createConfig("Config-A", "FULFILLMENT", 1, "{}");
        createConfig("Config-B", "FULFILLMENT", 2, "{}");
        createConfig("Config-C", "PRICING", 1, "{}");

        List<Configuration> results = Configuration.findByType("FULFILLMENT");

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(c -> "FULFILLMENT".equals(c.type)));
    }

    @Test
    @TestTransaction
    void findByType_shouldReturnEmptyList_whenNoMatch() {
        createConfig("Config-A", "FULFILLMENT", 1, "{}");

        List<Configuration> results = Configuration.findByType("NONEXISTENT");

        assertTrue(results.isEmpty());
    }

    // --------------------------------------------------
    // findConfigurationsForStore Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void findConfigurationsForStore_shouldReturnDirectlyLinkedConfigs() {
        Store store = createStore("CFG-S1");
        Configuration config = createConfig("Direct-Config", "FULFILLMENT", 1, "{}");
        config.stores.add(store);
        config.persist();

        List<Configuration> results = Configuration.findConfigurationsForStore(store, "FULFILLMENT");

        assertEquals(1, results.size());
        assertEquals("Direct-Config", results.get(0).name);
    }

    @Test
    @TestTransaction
    void findConfigurationsForStore_shouldReturnGroupLinkedConfigs() {
        Store store = createStore("CFG-S2");
        StoreGroup group = createStoreGroup("CFG-G1");
        group.stores.add(store);
        group.persist();

        Configuration config = createConfig("Group-Config", "FULFILLMENT", 1, "{}");
        config.storeGroups.add(group);
        config.persist();

        List<Configuration> results = Configuration.findConfigurationsForStore(store, "FULFILLMENT");

        assertEquals(1, results.size());
        assertEquals("Group-Config", results.get(0).name);
    }

    @Test
    @TestTransaction
    void findConfigurationsForStore_shouldReturnEmptyList_whenStoreIsNull() {
        List<Configuration> results = Configuration.findConfigurationsForStore(null, "FULFILLMENT");
        assertTrue(results.isEmpty());
    }

    @Test
    @TestTransaction
    void findConfigurationsForStore_shouldReturnEmptyList_whenTypeIsNull() {
        Store store = createStore("CFG-S3");
        List<Configuration> results = Configuration.findConfigurationsForStore(store, null);
        assertTrue(results.isEmpty());
    }

    @Test
    @TestTransaction
    void findConfigurationsForStore_shouldFilterByType() {
        Store store = createStore("CFG-S4");
        Configuration config1 = createConfig("Fulfill-Config", "FULFILLMENT", 1, "{}");
        config1.stores.add(store);
        config1.persist();

        Configuration config2 = createConfig("Pricing-Config", "PRICING", 1, "{}");
        config2.stores.add(store);
        config2.persist();

        List<Configuration> results = Configuration.findConfigurationsForStore(store, "FULFILLMENT");

        assertEquals(1, results.size());
        assertEquals("Fulfill-Config", results.get(0).name);
    }

    // --------------------------------------------------
    // findHighestPriorityForStore Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void findHighestPriorityForStore_shouldReturnHighestPriorityConfig() {
        Store store = createStore("CFG-S5");
        Configuration low = createConfig("Low-Priority", "FULFILLMENT", 1, "{}");
        low.stores.add(store);
        low.persist();

        Configuration high = createConfig("High-Priority", "FULFILLMENT", 10, "{}");
        high.stores.add(store);
        high.persist();

        Configuration result = Configuration.findHighestPriorityForStore(store, "FULFILLMENT");

        assertNotNull(result);
        assertEquals("High-Priority", result.name);
        assertEquals(10, result.priority);
    }

    @Test
    @TestTransaction
    void findHighestPriorityForStore_shouldThrowException_whenNoConfigFound() {
        Store store = createStore("CFG-S6");

        assertThrows(IllegalStateException.class, () -> {
            Configuration.findHighestPriorityForStore(store, "NONEXISTENT_TYPE");
        });
    }

    @Test
    @TestTransaction
    void findHighestPriorityForStore_shouldThrowException_whenPriorityTie() {
        Store store = createStore("CFG-S7");
        Configuration config1 = createConfig("Tie-1", "FULFILLMENT", 5, "{}");
        config1.stores.add(store);
        config1.persist();

        Configuration config2 = createConfig("Tie-2", "FULFILLMENT", 5, "{}");
        config2.stores.add(store);
        config2.persist();

        assertThrows(IllegalStateException.class, () -> {
            Configuration.findHighestPriorityForStore(store, "FULFILLMENT");
        });
    }

    // --------------------------------------------------
    // appliesTo Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void appliesTo_shouldReturnTrue_whenStoreDirectlyLinked() {
        Store store = createStore("CFG-S8");
        Configuration config = createConfig("Applies-Config", "FULFILLMENT", 1, "{}");
        config.stores.add(store);
        config.persist();

        assertTrue(config.appliesTo(store));
    }

    @Test
    @TestTransaction
    void appliesTo_shouldReturnTrue_whenStoreInLinkedGroup() {
        Store store = createStore("CFG-S9");
        StoreGroup group = createStoreGroup("CFG-G2");
        group.stores.add(store);
        group.persist();

        Configuration config = createConfig("Group-Applies-Config", "FULFILLMENT", 1, "{}");
        config.storeGroups.add(group);
        config.persist();

        assertTrue(config.appliesTo(store));
    }

    @Test
    @TestTransaction
    void appliesTo_shouldReturnFalse_whenStoreNotLinked() {
        Store store1 = createStore("CFG-S10A");
        Store store2 = createStore("CFG-S10B");
        Configuration config = createConfig("Not-Applies-Config", "FULFILLMENT", 1, "{}");
        config.stores.add(store1);
        config.persist();

        assertFalse(config.appliesTo(store2));
    }

    // --------------------------------------------------
    // getSpecificationFromJSON Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void getSpecificationFromJSON_shouldParseValidJson() {
        Configuration config = createConfig("JSON-Config", "FULFILLMENT", 1, "{\"key\":\"value\"}");

        var json = config.getSpecificationFromJSON();

        assertNotNull(json);
        assertEquals("value", json.get("key").asText());
    }

    @Test
    @TestTransaction
    void getSpecificationFromJSON_shouldThrowException_whenInvalidJson() {
        Configuration config = createConfig("Bad-JSON-Config", "FULFILLMENT", 1, "not valid json {{{");

        assertThrows(RuntimeException.class, config::getSpecificationFromJSON);
    }
}
