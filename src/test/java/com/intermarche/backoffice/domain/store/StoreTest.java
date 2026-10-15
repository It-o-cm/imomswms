package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.Adresse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Store} business logic.
 * Pure unit tests — no database, no Quarkus.
 */
class StoreTest {

    private Store createStore() {
        Store store = new Store();
        store.code = "FR-LYON-001";
        store.name = "SCA Lyon Centre";
        return store;
    }

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaultConstructor_buildingsInitializedEmpty() {
        Store store = new Store();
        assertNotNull(store.buildings);
        assertTrue(store.buildings.isEmpty());
    }

    // --------------------------------------------------
    // getStoreBuilding
    // --------------------------------------------------

    @Test
    void getStoreBuilding_noBuildings_returnsNull() {
        Store store = createStore();
        assertNull(store.getStoreBuilding());
    }

    @Test
    void getStoreBuilding_singleStoreBuilding_returnsIt() {
        Store store = createStore();
        StoreBuilding shop = new StoreBuilding();
        shop.buildingCode = "LYON-01";
        store.buildings.add(shop);

        assertSame(shop, store.getStoreBuilding());
    }

    @Test
    void getStoreBuilding_onlyWarehouseBuilding_returnsNull() {
        Store store = createStore();
        store.buildings.add(new WarehouseBuilding());

        assertNull(store.getStoreBuilding());
    }

    @Test
    void getStoreBuilding_multipleStoreBuildings_throwsIllegalState() {
        Store store = createStore();
        store.buildings.add(new StoreBuilding());
        store.buildings.add(new StoreBuilding());

        IllegalStateException exception = assertThrows(IllegalStateException.class, store::getStoreBuilding);
        assertTrue(exception.getMessage().contains("FR-LYON-001"));
    }

    @Test
    void getStoreBuilding_mixedBuildings_returnsStoreBuilding() {
        Store store = createStore();
        StoreBuilding shop = new StoreBuilding();
        store.buildings.add(new WarehouseBuilding());
        store.buildings.add(shop);

        assertSame(shop, store.getStoreBuilding());
    }

    // --------------------------------------------------
    // findBuildingByCode
    // --------------------------------------------------

    @Test
    void findBuildingByCode_nullCode_returnsNull() {
        Store store = createStore();
        assertNull(store.findBuildingByCode(null));
    }

    @Test
    void findBuildingByCode_blankCode_returnsNull() {
        Store store = createStore();
        assertNull(store.findBuildingByCode("   "));
    }

    @Test
    void findBuildingByCode_matchingStoreBuildingCode_returnsBuilding() {
        Store store = createStore();
        StoreBuilding shop = new StoreBuilding();
        shop.buildingCode = "LYON-01";
        store.buildings.add(shop);

        assertSame(shop, store.findBuildingByCode("LYON-01"));
    }

    @Test
    void findBuildingByCode_matchingWarehouseCode_returnsBuilding() {
        Store store = createStore();
        WarehouseBuilding warehouse = new WarehouseBuilding();
        warehouse.buildingCode = "LYON-WH";
        store.buildings.add(warehouse);

        assertSame(warehouse, store.findBuildingByCode("LYON-WH"));
    }

    @Test
    void findBuildingByCode_unknownCode_returnsNull() {
        Store store = createStore();
        store.buildings.add(new StoreBuilding());

        assertNull(store.findBuildingByCode("UNKNOWN"));
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_changesWithCode() {
        Store store = createStore();
        int checksum1 = store.getChecksum();

        store.code = "FR-PARIS-002";
        int checksum2 = store.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_changesWithName() {
        Store store = createStore();
        int checksum1 = store.getChecksum();

        store.name = "Other Name";
        int checksum2 = store.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_changesWithAddress() {
        Store store = createStore();
        int checksum1 = store.getChecksum();

        store.address = new Adresse("1 rue de la Paix", null, "75001", "Paris", "France", null, null);
        int checksum2 = store.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_ignoresBuildings() {
        Store store = createStore();
        int checksum1 = store.getChecksum();

        store.buildings.add(new StoreBuilding());
        int checksum2 = store.getChecksum();

        assertEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_nullAddress_noException() {
        Store store = createStore();
        store.address = null;
        assertDoesNotThrow(store::getChecksum);
    }

    @Test
    void getChecksum_sameFields_sameValue() {
        Store store1 = createStore();
        Store store2 = createStore();

        assertEquals(store1.getChecksum(), store2.getChecksum());
    }
}
