package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.order.Product;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ShelfLocation} (abstract) via the concrete {@link StoreShelfLocation}.
 * Pure unit tests — no database, no Quarkus.
 */
class ShelfLocationTest {

    // --------------------------------------------------
    // getAisle / getArea
    // --------------------------------------------------

    @Test
    void getAisle_returnsAisleField() {
        StoreShelfLocation location = new StoreShelfLocation();
        location.aisle = "A05";
        assertEquals("A05", location.getAisle());
    }

    @Test
    void getArea_returnsAreaField() {
        StoreShelfLocation location = new StoreShelfLocation();
        location.area = "EPICERIE";
        assertEquals("EPICERIE", location.getArea());
    }

    @Test
    void getAisleAndArea_nullByDefault() {
        StoreShelfLocation location = new StoreShelfLocation();
        assertNull(location.getAisle());
        assertNull(location.getArea());
    }

    // --------------------------------------------------
    // onCreate — location code generation
    // --------------------------------------------------

    @Test
    void onCreate_allFields_generatesFullCode() {
        StoreShelfLocation location = createLocation("EPICERIE", "A05", "B", "2", "05");
        location.onCreate();
        assertEquals("EPICERIE-A05-B-2-05", location.locationCode);
    }

    @Test
    void onCreate_nullFields_generatesPlaceholders() {
        StoreShelfLocation location = new StoreShelfLocation();
        location.onCreate();
        assertEquals("??-??-??-??-??", location.locationCode);
    }

    @Test
    void onCreate_partialFields_generatesMixedCode() {
        StoreShelfLocation location = createLocation("EPICERIE", null, "B", null, "05");
        location.onCreate();
        assertEquals("EPICERIE-??-B-??-05", location.locationCode);
    }

    @Test
    void onCreate_blankArea_generatesPlaceholder() {
        StoreShelfLocation location = createLocation("  ", "A05", "B", "2", "05");
        location.onCreate();
        assertEquals("??-A05-B-2-05", location.locationCode);
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_changesWithArea() {
        StoreShelfLocation location = createLocation("EPICERIE", "A05", "B", "2", "05");
        int checksum1 = location.getChecksum();

        location.area = "FRAIS";
        int checksum2 = location.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_changesWithPosition() {
        StoreShelfLocation location = createLocation("EPICERIE", "A05", "B", "2", "05");
        int checksum1 = location.getChecksum();

        location.position = "06";
        int checksum2 = location.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_sameFields_sameValue() {
        StoreShelfLocation location1 = createLocation("EPICERIE", "A05", "B", "2", "05");
        StoreShelfLocation location2 = createLocation("EPICERIE", "A05", "B", "2", "05");

        assertEquals(location1.getChecksum(), location2.getChecksum());
    }

    // --------------------------------------------------
    // BuildingAndLocation record
    // --------------------------------------------------

    @Test
    void buildingAndLocation_accessors() {
        StoreBuilding building = new StoreBuilding();
        StoreShelfLocation location = new StoreShelfLocation();

        ShelfLocation.BuildingAndLocation pair = new ShelfLocation.BuildingAndLocation(building, location);

        assertSame(building, pair.building());
        assertSame(location, pair.location());
    }

    @Test
    void buildingAndLocation_nullValues() {
        ShelfLocation.BuildingAndLocation pair = new ShelfLocation.BuildingAndLocation(null, null);
        assertNull(pair.building());
        assertNull(pair.location());
    }

    @Test
    void buildingAndLocation_valueEquality() {
        StoreBuilding building = new StoreBuilding();
        StoreShelfLocation location = new StoreShelfLocation();

        ShelfLocation.BuildingAndLocation pair1 = new ShelfLocation.BuildingAndLocation(building, location);
        ShelfLocation.BuildingAndLocation pair2 = new ShelfLocation.BuildingAndLocation(building, location);

        assertEquals(pair1, pair2);
        assertEquals(pair1.hashCode(), pair2.hashCode());
    }

    private StoreShelfLocation createLocation(String area, String aisle, String unit, String elevation, String position) {
        StoreShelfLocation location = new StoreShelfLocation();
        location.area = area;
        location.aisle = aisle;
        location.unit = unit;
        location.elevation = elevation;
        location.position = position;
        return location;
    }

    // --------------------------------------------------
    // findByProductId
    // --------------------------------------------------

    @Test
    void findByProductId_returnsQueryList() {
        StoreShelfLocation location = createLocation("EPICERIE", "A05", "B", "2", "05");
        @SuppressWarnings("unchecked")
        PanacheQuery<ShelfLocation> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(location));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("product.id"), any(Object[].class)))
                    .thenReturn(query);

            List<ShelfLocation> result = ShelfLocation.findByProductId(42L);

            assertEquals(1, result.size());
            assertSame(location, result.get(0));
        }
    }

    // --------------------------------------------------
    // findClosestForProduct — guards
    // --------------------------------------------------

    @Test
    void findClosestForProduct_nullStore_returnsEmpty() {
        Product product = productWithId(42L);
        assertTrue(ShelfLocation.findClosestForProduct(null, product, null, "EPICERIE", "A05").isEmpty());
    }

    @Test
    void findClosestForProduct_storeWithoutId_returnsEmpty() {
        Store store = new Store();
        store.code = "S001";
        Product product = productWithId(42L);
        assertTrue(ShelfLocation.findClosestForProduct(store, product, null, "EPICERIE", "A05").isEmpty());
    }

    @Test
    void findClosestForProduct_nullProduct_returnsEmpty() {
        Store store = storeWithId(1L);
        assertTrue(ShelfLocation.findClosestForProduct(store, null, null, "EPICERIE", "A05").isEmpty());
    }

    @Test
    void findClosestForProduct_productWithoutId_returnsEmpty() {
        Store store = storeWithId(1L);
        Product product = new Product();
        assertTrue(ShelfLocation.findClosestForProduct(store, product, null, "EPICERIE", "A05").isEmpty());
    }

    @Test
    void findClosestForProduct_noCandidates_returnsEmpty() {
        Store store = storeWithId(1L);
        Product product = productWithId(42L);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            mockCandidatesQuery(panacheMock, List.of());

            assertTrue(ShelfLocation.findClosestForProduct(store, product, null, "EPICERIE", "A05").isEmpty());
        }
    }

    // --------------------------------------------------
    // findClosestForProduct — fallback levels
    // --------------------------------------------------

    @Test
    void findClosestForProduct_level3_exactBuildingAreaAisleMatch() {
        Store store = storeWithId(1L);
        Product product = productWithId(42L);
        StoreBuilding building = buildingWithId(10L);
        StoreShelfLocation match = createLocation("EPICERIE", "A05", "B", "2", "05");
        StoreShelfLocation other = createLocation("EPICERIE", "A06", "B", "2", "05");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            mockCandidatesQuery(panacheMock, List.of(
                    new ShelfLocation.BuildingAndLocation(building, match),
                    new ShelfLocation.BuildingAndLocation(building, other)));

            List<ShelfLocation> result = ShelfLocation.findClosestForProduct(store, product, building, "EPICERIE", "A05");

            assertEquals(1, result.size());
            assertSame(match, result.get(0));
        }
    }

    @Test
    void findClosestForProduct_level3NoMatch_fallsBackToLevel2_buildingAndArea() {
        Store store = storeWithId(1L);
        Product product = productWithId(42L);
        StoreBuilding building = buildingWithId(10L);
        StoreShelfLocation match = createLocation("EPICERIE", "A06", "B", "2", "05");
        StoreShelfLocation other = createLocation("FRAIS", "A05", "B", "2", "05");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            mockCandidatesQuery(panacheMock, List.of(
                    new ShelfLocation.BuildingAndLocation(building, match),
                    new ShelfLocation.BuildingAndLocation(building, other)));

            List<ShelfLocation> result = ShelfLocation.findClosestForProduct(store, product, building, "EPICERIE", "A05");

            assertEquals(1, result.size());
            assertSame(match, result.get(0));
        }
    }

    @Test
    void findClosestForProduct_level2NoMatch_fallsBackToLevel1_buildingOnly() {
        Store store = storeWithId(1L);
        Product product = productWithId(42L);
        StoreBuilding building = buildingWithId(10L);
        StoreShelfLocation match = createLocation("FRAIS", "A06", "B", "2", "05");
        StoreBuilding otherBuilding = buildingWithId(20L);
        StoreShelfLocation other = createLocation("EPICERIE", "A05", "B", "2", "05");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            mockCandidatesQuery(panacheMock, List.of(
                    new ShelfLocation.BuildingAndLocation(building, match),
                    new ShelfLocation.BuildingAndLocation(otherBuilding, other)));

            List<ShelfLocation> result = ShelfLocation.findClosestForProduct(store, product, building, "EPICERIE", "A05");

            assertEquals(1, result.size());
            assertSame(match, result.get(0));
        }
    }

    @Test
    void findClosestForProduct_level1NoMatch_fallsBackToLevel0_allCandidates() {
        Store store = storeWithId(1L);
        Product product = productWithId(42L);
        StoreBuilding building = buildingWithId(10L);
        StoreBuilding otherBuilding = buildingWithId(20L);
        StoreShelfLocation loc1 = createLocation("FRAIS", "A06", "B", "2", "05");
        StoreShelfLocation loc2 = createLocation("EPICERIE", "A05", "B", "2", "05");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            mockCandidatesQuery(panacheMock, List.of(
                    new ShelfLocation.BuildingAndLocation(otherBuilding, loc1),
                    new ShelfLocation.BuildingAndLocation(otherBuilding, loc2)));

            List<ShelfLocation> result = ShelfLocation.findClosestForProduct(store, product, building, "EPICERIE", "A05");

            assertEquals(2, result.size());
            assertTrue(result.contains(loc1));
            assertTrue(result.contains(loc2));
        }
    }

    @Test
    void findClosestForProduct_nullBuilding_returnsAllCandidates() {
        Store store = storeWithId(1L);
        Product product = productWithId(42L);
        StoreShelfLocation loc1 = createLocation("FRAIS", "A06", "B", "2", "05");
        StoreShelfLocation loc2 = createLocation("EPICERIE", "A05", "B", "2", "05");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            mockCandidatesQuery(panacheMock, List.of(
                    new ShelfLocation.BuildingAndLocation(buildingWithId(10L), loc1),
                    new ShelfLocation.BuildingAndLocation(buildingWithId(20L), loc2)));

            List<ShelfLocation> result = ShelfLocation.findClosestForProduct(store, product, null, "EPICERIE", "A05");

            assertEquals(2, result.size());
        }
    }

    @Test
    void findClosestForProduct_blankAreaAndAisle_skipsToBuildingOnly() {
        Store store = storeWithId(1L);
        Product product = productWithId(42L);
        StoreBuilding building = buildingWithId(10L);
        StoreShelfLocation match = createLocation("FRAIS", "A06", "B", "2", "05");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            mockCandidatesQuery(panacheMock, List.of(
                    new ShelfLocation.BuildingAndLocation(building, match)));

            List<ShelfLocation> result = ShelfLocation.findClosestForProduct(store, product, building, "  ", " ");

            assertEquals(1, result.size());
            assertSame(match, result.get(0));
        }
    }

    @Test
    void findClosestForProduct_blankAisle_fallsBackToLevel2() {
        Store store = storeWithId(1L);
        Product product = productWithId(42L);
        StoreBuilding building = buildingWithId(10L);
        StoreShelfLocation match = createLocation("EPICERIE", "A06", "B", "2", "05");
        StoreShelfLocation other = createLocation("FRAIS", "A05", "B", "2", "05");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            mockCandidatesQuery(panacheMock, List.of(
                    new ShelfLocation.BuildingAndLocation(building, match),
                    new ShelfLocation.BuildingAndLocation(building, other)));

            List<ShelfLocation> result = ShelfLocation.findClosestForProduct(store, product, building, "EPICERIE", "");

            assertEquals(1, result.size());
            assertSame(match, result.get(0));
        }
    }

    @Test
    void findClosestForProduct_nullArea_skipsToBuildingOnly() {
        Store store = storeWithId(1L);
        Product product = productWithId(42L);
        StoreBuilding building = buildingWithId(10L);
        StoreShelfLocation match = createLocation("EPICERIE", "A05", "B", "2", "05");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            mockCandidatesQuery(panacheMock, List.of(
                    new ShelfLocation.BuildingAndLocation(building, match)));

            List<ShelfLocation> result = ShelfLocation.findClosestForProduct(store, product, building, null, "A05");

            assertEquals(1, result.size());
            assertSame(match, result.get(0));
        }
    }

    @Test
    void findClosestForProduct_nullAisle_fallsBackToLevel2() {
        Store store = storeWithId(1L);
        Product product = productWithId(42L);
        StoreBuilding building = buildingWithId(10L);
        StoreShelfLocation match = createLocation("EPICERIE", "A06", "B", "2", "05");
        StoreShelfLocation other = createLocation("FRAIS", "A05", "B", "2", "05");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            mockCandidatesQuery(panacheMock, List.of(
                    new ShelfLocation.BuildingAndLocation(building, match),
                    new ShelfLocation.BuildingAndLocation(building, other)));

            List<ShelfLocation> result = ShelfLocation.findClosestForProduct(store, product, building, "EPICERIE", null);

            assertEquals(1, result.size());
            assertSame(match, result.get(0));
        }
    }

    @Test
    void findClosestForProduct_buildingWithoutId_returnsAllCandidates() {
        Store store = storeWithId(1L);
        Product product = productWithId(42L);
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = "BLD-NO-ID";
        StoreShelfLocation loc1 = createLocation("FRAIS", "A06", "B", "2", "05");
        StoreShelfLocation loc2 = createLocation("EPICERIE", "A05", "B", "2", "05");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            mockCandidatesQuery(panacheMock, List.of(
                    new ShelfLocation.BuildingAndLocation(buildingWithId(10L), loc1),
                    new ShelfLocation.BuildingAndLocation(buildingWithId(20L), loc2)));

            List<ShelfLocation> result = ShelfLocation.findClosestForProduct(store, product, building, "EPICERIE", "A05");

            assertEquals(2, result.size());
        }
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private static Product productWithId(Long id) {
        Product product = new Product();
        product.id = id;
        return product;
    }

    private static Store storeWithId(Long id) {
        Store store = new Store();
        store.id = id;
        store.code = "S001";
        return store;
    }

    private static StoreBuilding buildingWithId(Long id) {
        StoreBuilding building = new StoreBuilding();
        building.id = id;
        building.buildingCode = "BLD-" + id;
        return building;
    }

    @SuppressWarnings("unchecked")
    private static void mockCandidatesQuery(MockedStatic<PanacheEntityBase> panacheMock,
                                            List<ShelfLocation.BuildingAndLocation> candidates) {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<ShelfLocation.BuildingAndLocation> query = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(ShelfLocation.BuildingAndLocation.class))).thenReturn(query);
        when(query.setParameter(anyInt(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(candidates);
        panacheMock.when(PanacheEntityBase::getEntityManager).thenReturn(entityManager);
    }
}
