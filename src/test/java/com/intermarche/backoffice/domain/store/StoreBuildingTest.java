package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.GPSPosition;
import com.intermarche.backoffice.domain.order.Product;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StoreBuilding}.
 * Pure unit tests — no database, no Quarkus.
 */
class StoreBuildingTest {

    @Test
    void isABuilding() {
        assertTrue(new StoreBuilding() instanceof Building);
    }

    @Test
    void defaultConstructor_locationsInitializedEmpty() {
        StoreBuilding building = new StoreBuilding();
        assertNotNull(building.locations);
        assertTrue(building.locations.isEmpty());
    }

    @Test
    void getChecksum_sameFields_sameValue() {
        StoreBuilding building1 = createBuilding("LYON-01");
        StoreBuilding building2 = createBuilding("LYON-01");

        assertEquals(building1.getChecksum(), building2.getChecksum());
    }

    @Test
    void getChecksum_changesWithBuildingCode() {
        StoreBuilding building = createBuilding("LYON-01");
        int checksum1 = building.getChecksum();

        building.buildingCode = "LYON-02";
        int checksum2 = building.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_changesWithAddress() {
        StoreBuilding building = createBuilding("LYON-01");
        int checksum1 = building.getChecksum();

        building.address = new Adresse("1 rue de la Paix", null, "75001", "Paris", "France", null, null);
        int checksum2 = building.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_changesWithGpsPosition() {
        StoreBuilding building = createBuilding("LYON-01");
        int checksum1 = building.getChecksum();

        building.gpsPosition = new GPSPosition(45.76, 4.83);
        int checksum2 = building.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_nullAddressAndGps_noException() {
        StoreBuilding building = createBuilding("LYON-01");
        building.address = null;
        building.gpsPosition = null;
        assertDoesNotThrow(building::getChecksum);
    }

    @Test
    void findLocationByProductId_findsLocation() {
        StoreBuilding building = createBuilding("LYON-01");
        Product product = new Product();
        product.id = 7L;
        StoreShelfLocation location = new StoreShelfLocation();
        location.product = product;
        building.locations.add(location);

        assertSame(location, building.findLocationByProductId(7L));
        assertNull(building.findLocationByProductId(8L));
    }

    private StoreBuilding createBuilding(String code) {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = code;
        return building;
    }
}
