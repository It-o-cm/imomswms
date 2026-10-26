package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.GPSPosition;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Building} business logic.
 * Focuses on {@link Building#findLocationByProductId(Long)} (transient index map)
 * and checksum sensitivity. Uses concrete subclasses {@link StoreBuilding}
 * and {@link WarehouseBuilding}.
 */
public class BuildingTest {

    // --------------------------------------------------
    // findLocationByProductId
    // --------------------------------------------------

    @Test
    void findLocationByProductId_shouldReturnLocation_whenProductIsIndexed() {
        StoreBuilding building = new StoreBuilding();
        StoreShelfLocation loc = createLocation(100L);
        building.locations = new HashSet<>(Set.of(loc));

        assertSame(loc, building.findLocationByProductId(100L));
    }

    @Test
    void findLocationByProductId_shouldReturnNull_whenProductNotIndexed() {
        StoreBuilding building = new StoreBuilding();
        building.locations = new HashSet<>(Set.of(createLocation(100L)));

        assertNull(building.findLocationByProductId(999L));
    }

    @Test
    void findLocationByProductId_shouldReturnNull_whenLocationsIsNull() {
        StoreBuilding building = new StoreBuilding();
        building.locations = null;

        assertNull(building.findLocationByProductId(100L));
    }

    @Test
    void findLocationByProductId_shouldIgnoreLocationsWithoutProduct() {
        StoreBuilding building = new StoreBuilding();
        StoreShelfLocation noProduct = new StoreShelfLocation();
        noProduct.product = null;
        StoreShelfLocation noProductId = new StoreShelfLocation();
        noProductId.product = new com.intermarche.backoffice.domain.order.Product();
        noProductId.product.id = null;
        building.locations = new HashSet<>(Set.of(noProduct, noProductId));

        assertNull(building.findLocationByProductId(100L));
    }

    @Test
    void findLocationByProductId_shouldReuseIndexOnSecondCall() {
        StoreBuilding building = new StoreBuilding();
        StoreShelfLocation loc = createLocation(100L);
        building.locations = new HashSet<>(Set.of(loc));

        assertSame(loc, building.findLocationByProductId(100L));
        // Second call must use the cached index and still return the location
        assertSame(loc, building.findLocationByProductId(100L));
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_shouldChangeWhenBuildingCodeChanges() {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = "BLD-01";
        int checksum1 = building.getChecksum();

        building.buildingCode = "BLD-02";
        assertNotEquals(checksum1, building.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenAddressChanges() {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = "BLD-01";
        building.address = new Adresse();
        building.address.city = "Lyon";
        int checksum1 = building.getChecksum();

        building.address.city = "Paris";
        assertNotEquals(checksum1, building.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenGpsPositionChanges() {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = "BLD-01";
        building.gpsPosition = new GPSPosition(45.0, 4.0);
        int checksum1 = building.getChecksum();

        building.gpsPosition = new GPSPosition(45.0, 5.0);
        assertNotEquals(checksum1, building.getChecksum());
    }

    @Test
    void getChecksum_shouldNotFailWhenAddressAndGpsAreNull() {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = "BLD-01";
        building.address = null;
        building.gpsPosition = null;
        assertDoesNotThrow(building::getChecksum);
    }

    @Test
    void getChecksum_shouldBeIdenticalForStoreAndWarehouseWithSameFields() {
        StoreBuilding storeBuilding = new StoreBuilding();
        storeBuilding.buildingCode = "BLD-01";
        storeBuilding.address = new Adresse();
        storeBuilding.address.city = "Lyon";
        storeBuilding.gpsPosition = new GPSPosition(45.0, 4.0);

        WarehouseBuilding warehouseBuilding = new WarehouseBuilding();
        warehouseBuilding.buildingCode = "BLD-01";
        warehouseBuilding.address = new Adresse();
        warehouseBuilding.address.city = "Lyon";
        warehouseBuilding.gpsPosition = new GPSPosition(45.0, 4.0);

        // Both subclasses delegate to Building.getChecksum()
        assertEquals(storeBuilding.getChecksum(), warehouseBuilding.getChecksum());
    }

    // --------------------------------------------------
    // Plan image (fond de plan)
    // --------------------------------------------------

    @Test
    void planImageFields_shouldDefaultToNullAndZero() {
        StoreBuilding building = new StoreBuilding();

        assertNull(building.planImage);
        assertEquals(0.0, building.planImageX);
        assertEquals(0.0, building.planImageY);
    }

    @Test
    void getChecksum_shouldNotChangeWhenPlanImageChanges() {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = "BLD-01";
        int checksum1 = building.getChecksum();

        // planImage is seeded, not CSV-imported: it must stay out of the checksum
        building.planImage = "aW1hZ2U=";
        building.planImageX = -283.0;
        building.planImageY = -238.0;
        assertEquals(checksum1, building.getChecksum());
    }

    private StoreShelfLocation createLocation(Long productId) {
        StoreShelfLocation loc = new StoreShelfLocation();
        com.intermarche.backoffice.domain.order.Product product = new com.intermarche.backoffice.domain.order.Product();
        product.id = productId;
        loc.product = product;
        return loc;
    }
}
