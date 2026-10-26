package com.intermarche.e2e.graphql;

import com.intermarche.backoffice.domain.Configuration;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreGroup;
import com.intermarche.backoffice.graphql.ConfigurationResource;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.util.List;
import java.util.NoSuchElementException;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ConfigurationResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class ConfigurationResourceTest {

    @Inject
    ConfigurationResource configurationResource;

    // --------------------------------------------------
    // Fixture Helpers
    // --------------------------------------------------

    private Store createStore(String code) {
        return DomainUtils.createAndPersistStore(code, 48.0, 2.0);
    }

    private Configuration persistConfiguration(String name, String type, int priority) {
        Configuration config = new Configuration();
        config.name = name;
        config.type = type;
        config.specification = "{\"key\":\"" + name + "\"}";
        config.priority = priority;
        config.persist();
        return config;
    }

    // --------------------------------------------------
    // Query Tests (Requires MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testConfigurationsByStore_Success() {
        Store store = createStore("STORE_CF1");
        Configuration configA = persistConfiguration("CFG_A", "PRICING", 1);
        Configuration configB = persistConfiguration("CFG_B", "FULFILLMENT", 2);
        configA.stores.add(store);
        configB.stores.add(store);
        configA.persist();
        configB.persist();

        List<Configuration> configs = configurationResource.configurationsByStore(store.id);
        assertEquals(2, configs.size());
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testConfigurationsByStore_NoLink_ReturnsEmpty() {
        Store store = createStore("STORE_CF2");
        persistConfiguration("CFG_C", "PRICING", 1);

        List<Configuration> configs = configurationResource.configurationsByStore(store.id);
        assertNotNull(configs);
        assertTrue(configs.isEmpty());
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testConfigurationsByStore_StoreNotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            configurationResource.configurationsByStore(9999L);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testConfigurationsForStore_DirectLink() {
        Store store = createStore("STORE_CF3");
        Configuration config = persistConfiguration("CFG_D", "PRICING", 1);
        config.stores.add(store);
        config.persist();
        persistConfiguration("CFG_E", "FULFILLMENT", 1); // different type: must be filtered out

        List<Configuration> configs = configurationResource.configurationsForStore(store.id, "PRICING");
        assertEquals(1, configs.size());
        assertEquals("CFG_D", configs.get(0).name);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testConfigurationsForStore_ViaStoreGroup() {
        Store store = createStore("STORE_CF4");
        StoreGroup group = DomainUtils.createAndPersistStoreGroup("GRP_CF1");
        group.stores.add(store);
        group.persist();
        Configuration config = persistConfiguration("CFG_F", "PRICING", 1);
        config.storeGroups.add(group);
        config.persist();

        List<Configuration> configs = configurationResource.configurationsForStore(store.id, "PRICING");
        assertEquals(1, configs.size(), "Configuration linked via a store group must apply to the store");
        assertEquals("CFG_F", configs.get(0).name);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testConfigurationsForStore_StoreNotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            configurationResource.configurationsForStore(9999L, "PRICING");
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testEffectiveConfigurationForStore_HighestPriority() {
        Store store = createStore("STORE_CF5");
        Configuration low = persistConfiguration("CFG_LOW", "PRICING", 1);
        Configuration high = persistConfiguration("CFG_HIGH", "PRICING", 5);
        low.stores.add(store);
        high.stores.add(store);
        low.persist();
        high.persist();

        Configuration effective = configurationResource.effectiveConfigurationForStore(store.id, "PRICING");
        assertNotNull(effective);
        assertEquals("CFG_HIGH", effective.name, "The configuration with the highest priority must win");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testEffectiveConfigurationForStore_Tie_Throws() {
        Store store = createStore("STORE_CF6");
        Configuration configA = persistConfiguration("CFG_TIE_A", "PRICING", 5);
        Configuration configB = persistConfiguration("CFG_TIE_B", "PRICING", 5);
        configA.stores.add(store);
        configB.stores.add(store);
        configA.persist();
        configB.persist();

        assertThrows(IllegalStateException.class, () -> {
            configurationResource.effectiveConfigurationForStore(store.id, "PRICING");
        }, "A tie on the highest priority must be rejected");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testEffectiveConfigurationForStore_None_Throws() {
        Store store = createStore("STORE_CF7");

        assertThrows(IllegalStateException.class, () -> {
            configurationResource.effectiveConfigurationForStore(store.id, "PRICING");
        }, "No applicable configuration must raise an IllegalStateException");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testEffectiveConfigurationForStore_StoreNotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            configurationResource.effectiveConfigurationForStore(9999L, "PRICING");
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Create (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateConfiguration_Success() throws GraphQLException {
        Store store = createStore("STORE_CF8");
        StoreGroup group = DomainUtils.createAndPersistStoreGroup("GRP_CF2");

        ConfigurationResource.ConfigurationRecord input = new ConfigurationResource.ConfigurationRecord();
        input.name = "CFG_NEW";
        input.type = "PRICING";
        input.priority = 3;
        input.specification = "{\"k\":1}";
        input.storeIds = List.of(store.id);
        input.storeGroupIds = List.of(group.id);

        Configuration created = configurationResource.createConfiguration(input);

        assertNotNull(created);
        assertNotNull(created.id);
        assertEquals("CFG_NEW", created.name);
        assertEquals("PRICING", created.type);
        assertEquals(3, created.priority);
        assertEquals("{\"k\":1}", created.specification);
        assertEquals(1, created.stores.size());
        assertEquals(store.id, created.stores.iterator().next().id);
        assertEquals(1, created.storeGroups.size());
        assertEquals(group.id, created.storeGroups.iterator().next().id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateConfiguration_DefaultPriority() throws GraphQLException {
        ConfigurationResource.ConfigurationRecord input = new ConfigurationResource.ConfigurationRecord();
        input.name = "CFG_NO_PRIO";
        input.type = "PRICING";
        input.specification = "{}";
        input.priority = null; // must default to 0

        Configuration created = configurationResource.createConfiguration(input);

        assertNotNull(created.id);
        assertEquals(0, created.priority, "Priority must default to 0 when not provided");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateConfiguration_UnknownStoreId_Ignored() throws GraphQLException {
        ConfigurationResource.ConfigurationRecord input = new ConfigurationResource.ConfigurationRecord();
        input.name = "CFG_GHOST_STORE";
        input.type = "PRICING";
        input.specification = "{}";
        input.storeIds = List.of(9999L); // unknown: logged and skipped, no error

        Configuration created = configurationResource.createConfiguration(input);

        assertNotNull(created.id);
        assertTrue(created.stores.isEmpty(), "Unknown store IDs must be ignored");
    }

    // --------------------------------------------------
    // Mutation Tests: Update (Requires ADMIN or MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateConfiguration_Success() throws GraphQLException {
        Store storeA = createStore("STORE_CF9");
        Store storeB = createStore("STORE_CF9_BIS");
        StoreGroup group = DomainUtils.createAndPersistStoreGroup("GRP_CF3");
        Configuration existing = persistConfiguration("CFG_OLD", "PRICING", 1);
        existing.stores.add(storeA);
        existing.persist();

        ConfigurationResource.ConfigurationRecord input = new ConfigurationResource.ConfigurationRecord();
        input.name = "CFG_UPDATED";
        input.type = "FULFILLMENT";
        input.priority = 9;
        input.specification = "{\"updated\":true}";
        input.storeIds = List.of(storeB.id); // replacement strategy
        input.storeGroupIds = List.of(group.id);

        Configuration updated = configurationResource.updateConfiguration(existing.id, input);

        assertEquals("CFG_UPDATED", updated.name);
        assertEquals("FULFILLMENT", updated.type);
        assertEquals(9, updated.priority);
        assertEquals("{\"updated\":true}", updated.specification);
        assertEquals(1, updated.stores.size(), "Store links must be replaced, not appended");
        assertEquals(storeB.id, updated.stores.iterator().next().id);
        assertEquals(1, updated.storeGroups.size());
        assertEquals(group.id, updated.storeGroups.iterator().next().id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateConfiguration_ManagerRole_Allowed() throws GraphQLException {
        Configuration existing = persistConfiguration("CFG_MGR", "PRICING", 1);

        ConfigurationResource.ConfigurationRecord input = new ConfigurationResource.ConfigurationRecord();
        input.priority = 2;

        Configuration updated = configurationResource.updateConfiguration(existing.id, input);
        assertEquals(2, updated.priority);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateConfiguration_NotFound() {
        ConfigurationResource.ConfigurationRecord input = new ConfigurationResource.ConfigurationRecord();
        input.name = "Ghost";

        assertThrows(NoSuchElementException.class, () -> {
            configurationResource.updateConfiguration(9999L, input);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteConfiguration_Success() throws GraphQLException {
        Configuration existing = persistConfiguration("CFG_DEL", "PRICING", 1);

        boolean result = configurationResource.deleteConfiguration(existing.id);
        assertTrue(result, "Delete should return true");
        assertNull(Configuration.findById(existing.id));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteConfiguration_NotFound() throws GraphQLException {
        boolean result = configurationResource.deleteConfiguration(9999L);
        assertFalse(result, "Delete should return false for non-existent ID");
    }

    // --------------------------------------------------
    // Record Tests
    // --------------------------------------------------

    @Test
    void testConfigurationRecord_ToString() {
        ConfigurationResource.ConfigurationRecord record = new ConfigurationResource.ConfigurationRecord();
        record.name = "CFG_REC";
        record.type = "PRICING";
        record.priority = 7;
        record.specification = "{}";

        assertEquals("ConfigurationRecord [name=CFG_REC, type=PRICING, priority=7, specification={}]",
                record.toString());
    }
}
