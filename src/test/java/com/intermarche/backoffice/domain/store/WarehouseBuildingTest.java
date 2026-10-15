package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.GPSPosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WarehouseBuilding}.
 * Pure unit tests — no database, no Quarkus.
 */
class WarehouseBuildingTest {

    @Test
    void isABuilding() {
        assertTrue(new WarehouseBuilding() instanceof Building);
    }

    @Test
    void defaultConstructor_locationsInitializedEmpty() {
        WarehouseBuilding building = new WarehouseBuilding();
        assertNotNull(building.locations);
        assertTrue(building.locations.isEmpty());
    }

    @Test
    void getChecksum_delegatesToSuper_sameAsStoreBuildingWithSameFields() {
        WarehouseBuilding warehouse = new WarehouseBuilding();
        warehouse.buildingCode = "LYON-WH";
        warehouse.address = new Adresse("1 quai du Rhône", null, "69001", "Lyon", "France", null, null);
        warehouse.gpsPosition = new GPSPosition(45.76, 4.83);

        StoreBuilding shop = new StoreBuilding();
        shop.buildingCode = "LYON-WH";
        shop.address = new Adresse("1 quai du Rhône", null, "69001", "Lyon", "France", null, null);
        shop.gpsPosition = new GPSPosition(45.76, 4.83);

        assertEquals(shop.getChecksum(), warehouse.getChecksum());
    }

    @Test
    void getChecksum_changesWithBuildingCode() {
        WarehouseBuilding building = new WarehouseBuilding();
        building.buildingCode = "LYON-WH";
        int checksum1 = building.getChecksum();

        building.buildingCode = "LYON-WH2";
        assertNotEquals(checksum1, building.getChecksum());
    }

    @Test
    void getChecksum_nullAddressAndGps_noException() {
        WarehouseBuilding building = new WarehouseBuilding();
        building.buildingCode = "LYON-WH";
        building.address = null;
        building.gpsPosition = null;
        assertDoesNotThrow(building::getChecksum);
    }
}
