package com.intermarche.backoffice.domain.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StoreGroup}.
 * Pure unit tests — no database, no Quarkus.
 */
class StoreGroupTest {

    private StoreGroup createGroup() {
        StoreGroup group = new StoreGroup();
        group.code = "AUvergne-Rhone-Alpes";
        group.name = "Auvergne-Rhône-Alpes";
        return group;
    }

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaultConstructor_storesInitializedEmpty() {
        StoreGroup group = new StoreGroup();
        assertNotNull(group.stores);
        assertTrue(group.stores.isEmpty());
    }

    @Test
    void defaultConstructor_storeGroupsInitializedEmpty() {
        StoreGroup group = new StoreGroup();
        assertNotNull(group.storeGroups);
        assertTrue(group.storeGroups.isEmpty());
    }

    // --------------------------------------------------
    // getChecksum — relations are excluded
    // --------------------------------------------------

    @Test
    void getChecksum_changesWithCode() {
        StoreGroup group = createGroup();
        int checksum1 = group.getChecksum();
        group.code = "IDF";
        assertNotEquals(checksum1, group.getChecksum());
    }

    @Test
    void getChecksum_changesWithName() {
        StoreGroup group = createGroup();
        int checksum1 = group.getChecksum();
        group.name = "Île-de-France";
        assertNotEquals(checksum1, group.getChecksum());
    }

    @Test
    void getChecksum_ignoresStores() {
        StoreGroup group = createGroup();
        int checksum1 = group.getChecksum();
        group.stores.add(new Store());
        assertEquals(checksum1, group.getChecksum());
    }

    @Test
    void getChecksum_ignoresStoreGroups() {
        StoreGroup group = createGroup();
        int checksum1 = group.getChecksum();
        group.storeGroups.add(new StoreGroup());
        assertEquals(checksum1, group.getChecksum());
    }

    @Test
    void getChecksum_sameFields_sameValue() {
        assertEquals(createGroup().getChecksum(), createGroup().getChecksum());
    }

    @Test
    void getChecksum_nullFields_noException() {
        StoreGroup group = new StoreGroup();
        assertDoesNotThrow(group::getChecksum);
    }
}
