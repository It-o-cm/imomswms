package com.intermarche.backoffice.graphql;

import com.intermarche.backoffice.domain.Configuration;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreGroup;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConfigurationResource}.
 * Covers the identity accessor, the store/group linking branches of the create mutation
 * (including the group-not-found warning) and the update mutation branches.
 */
class ConfigurationResourceTest {

    private MockedStatic<PanacheEntityBase> panacheMock;
    private MockedStatic<Arc> arcMock;

    @BeforeEach
    void setUp() {
        panacheMock = mockStatic(PanacheEntityBase.class);
        arcMock = mockStatic(Arc.class);
        PanacheTestSupport.mockArcSession(arcMock);
    }

    @AfterEach
    void tearDown() {
        panacheMock.close();
        arcMock.close();
    }

    private ConfigurationResource newResource() {
        ConfigurationResource resource = new ConfigurationResource();
        resource.identity = mock(SecurityIdentity.class);
        when(resource.identity.hasRole("ADMIN")).thenReturn(true);
        resource.ruleSchemas=mock(com.intermarche.backoffice.rules.RuleSchemaRegistry.class);
        when(resource.ruleSchemas.normalize(any(),any())).thenAnswer(i -> i.getArgument(1));
        return resource;
    }

    private Store store(Long id) {
        Store store = new Store();
        store.id = id;
        store.code = "STORE-" + id;
        return store;
    }

    private StoreGroup group(Long id) {
        StoreGroup group = new StoreGroup();
        group.id = id;
        return group;
    }

    @Test
    void getIdentity_returnsInjectedIdentity() {
        ConfigurationResource resource = new ConfigurationResource();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        resource.identity = identity;

        assertSame(identity, resource.getIdentity());
    }

    @Test
    void createConfiguration_groupNotFound_skipsGroupWithWarning() throws Exception {
        StoreGroup group = group(1L);
        panacheMock.when(() -> PanacheEntityBase.findById(any()))
                .thenAnswer(invocation -> {
                    Object id = invocation.getArgument(0);
                    if (Long.valueOf(1L).equals(id)) return group;
                    return null;
                });

        ConfigurationResource.ConfigurationRecord input = new ConfigurationResource.ConfigurationRecord();
        input.name = "conf";
        input.type = "OPENING_HOURS";
        input.storeGroupIds = List.of(1L, 2L);

        Configuration result = newResource().createConfiguration(input);

        assertEquals("conf", result.name);
        assertEquals(1, result.storeGroups.size());
        assertTrue(result.storeGroups.contains(group));
    }

    @Test
    void createConfiguration_withStoresAndGroups_linksThem() throws Exception {
        Store store = store(1L);
        StoreGroup group = group(2L);
        panacheMock.when(() -> PanacheEntityBase.findById(any()))
                .thenAnswer(invocation -> {
                    Object id = invocation.getArgument(0);
                    if (Long.valueOf(1L).equals(id)) return store;
                    if (Long.valueOf(2L).equals(id)) return group;
                    return null;
                });

        ConfigurationResource.ConfigurationRecord input = new ConfigurationResource.ConfigurationRecord();
        input.name = "conf";
        input.type = "OPENING_HOURS";
        input.priority = 3;
        input.storeIds = List.of(1L);
        input.storeGroupIds = List.of(2L);

        Configuration result = newResource().createConfiguration(input);

        assertEquals(3, result.priority);
        assertTrue(result.stores.contains(store));
        assertTrue(result.storeGroups.contains(group));
    }

    @Test
    void updateConfiguration_appliesPriorityStoresAndGroups() throws Exception {
        Configuration config = new Configuration();
        config.id = 7L;
        config.name = "old";
        config.priority = 0;
        Store store = store(1L);
        StoreGroup group = group(2L);
        panacheMock.when(() -> PanacheEntityBase.findById(any()))
                .thenAnswer(invocation -> {
                    Object id = invocation.getArgument(0);
                    if (Long.valueOf(7L).equals(id)) return config;
                    if (Long.valueOf(1L).equals(id)) return store;
                    if (Long.valueOf(2L).equals(id)) return group;
                    return null;
                });

        ConfigurationResource.ConfigurationRecord input = new ConfigurationResource.ConfigurationRecord();
        input.name = "new";
        input.priority = 5;
        input.storeIds = List.of(1L);
        input.storeGroupIds = List.of(2L);

        Configuration result = newResource().updateConfiguration(7L, input);

        assertSame(config, result);
        assertEquals("new", config.name);
        assertEquals(5, config.priority);
        assertTrue(config.stores.contains(store));
        assertTrue(config.storeGroups.contains(group));
    }

    @Test
    void updateConfiguration_unknownStoreAndGroupIds_skipsThem() throws Exception {
        Configuration config = new Configuration();
        config.id = 7L;
        config.name = "old";
        panacheMock.when(() -> PanacheEntityBase.findById(any()))
                .thenAnswer(invocation -> {
                    Object id = invocation.getArgument(0);
                    if (Long.valueOf(7L).equals(id)) return config;
                    return null;
                });

        ConfigurationResource.ConfigurationRecord input = new ConfigurationResource.ConfigurationRecord();
        input.storeIds = List.of(99L);
        input.storeGroupIds = List.of(98L);

        Configuration result = newResource().updateConfiguration(7L, input);

        assertSame(config, result);
        assertTrue(config.stores.isEmpty());
        assertTrue(config.storeGroups.isEmpty());
    }

    @Test
    void updateConfiguration_nullLinks_keepsExistingLinks() throws Exception {
        Configuration config = new Configuration();
        config.id = 7L;
        config.name = "old";
        Store existingStore = store(1L);
        config.stores.add(existingStore);
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(config);

        ConfigurationResource.ConfigurationRecord input = new ConfigurationResource.ConfigurationRecord();
        input.storeIds = null;
        input.storeGroupIds = null;

        Configuration result = newResource().updateConfiguration(7L, input);

        assertSame(config, result);
        assertTrue(config.stores.contains(existingStore));
        assertTrue(config.storeGroups.isEmpty());
    }
}
