package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.store.WarehouseShelfLocation;
import com.intermarche.backoffice.imports.ImporterCsvResource;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.TransactionManager;
import jakarta.ws.rs.core.Response;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LocationCsvResource}.
 * <p>
 * Covers line processing (creation, update, skip on missing parents / type mismatch /
 * unchanged checksum), the 1-by-1 fallback context and lookup, chunk context building
 * (buildings, products, location cache) and the full import flow including the staged
 * fallback algorithm.
 */
class LocationCsvResourceTest {

    private static final String CACHE_KEY = "LOCATION_CACHE";

    private final LocationCsvResource resource = new LocationCsvResource();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private static ImporterCsvResource.LineData line(String... parts) {
        return new ImporterCsvResource.LineData(2, parts[0], parts);
    }

    private static Building building(String code, Long id) {
        StoreBuilding building = new StoreBuilding();
        building.id = id;
        building.buildingCode = code;
        return building;
    }

    private static Product product(String ean, Long id) {
        Product product = new Product();
        product.id = id;
        product.ean = ean;
        return product;
    }

    private static StoreShelfLocation shelf(Long id, Product product, String area, String aisle,
                                            String unit, String elevation, String position,
                                            Integer facings, String department) {
        StoreShelfLocation loc = new StoreShelfLocation();
        loc.id = id;
        loc.product = product;
        loc.area = area;
        loc.aisle = aisle;
        loc.unit = unit;
        loc.elevation = elevation;
        loc.position = position;
        loc.facings = facings;
        loc.department = department;
        loc.checksum = loc.getChecksum();
        return loc;
    }

    private static WarehouseShelfLocation bin(Long id, Product product, String area, String aisle,
                                              String unit, String elevation, String position,
                                              Double maxWeightKg, Integer maxPallets) {
        WarehouseShelfLocation loc = new WarehouseShelfLocation();
        loc.id = id;
        loc.product = product;
        loc.area = area;
        loc.aisle = aisle;
        loc.unit = unit;
        loc.elevation = elevation;
        loc.position = position;
        loc.maxWeightKg = maxWeightKg;
        loc.maxPallets = maxPallets;
        loc.checksum = loc.getChecksum();
        return loc;
    }

    private static Map<String, Object> contextWith(Building building, Product product) {
        Map<String, Object> ctx = new HashMap<>();
        if (building != null) {
            ctx.put("BLD_" + building.buildingCode, building);
        }
        if (product != null) {
            ctx.put("PRD_" + product.ean, product);
        }
        ctx.put(CACHE_KEY, new HashMap<String, ShelfLocation>());
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private static <T> PanacheQuery<T> queryReturning(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ShelfLocation> locationCache(Map<String, Object> ctx) {
        return (Map<String, ShelfLocation>) ctx.get(CACHE_KEY);
    }

    // --------------------------------------------------
    // processLineLogic - missing parents
    // --------------------------------------------------

    @Test
    void processLineLogic_missingBuilding_skipsLine() {
        Product product = product("EAN-1", 9L);
        Map<String, Object> ctx = contextWith(null, product);
        int[] counters = {0, 0};

        resource.processLineLogic(line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "3", "Grocery"), ctx, counters);

        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
        assertTrue(locationCache(ctx).isEmpty());
    }

    @Test
    void processLineLogic_missingProduct_skipsLine() {
        Building building = building("B1", 7L);
        Map<String, Object> ctx = contextWith(building, null);
        int[] counters = {0, 0};

        resource.processLineLogic(line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "3", "Grocery"), ctx, counters);

        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
        assertTrue(building.locations.isEmpty());
    }

    // --------------------------------------------------
    // processLineLogic - creation
    // --------------------------------------------------

    @Test
    void processLineLogic_newShelfLocation_createsAndCounts() {
        Building building = building("B1", 7L);
        Product product = product("EAN-1", 9L);
        Map<String, Object> ctx = contextWith(building, product);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(eq(7L))).thenReturn(building);

            resource.processLineLogic(line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "3", "Grocery"), ctx, counters);

            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            assertEquals(1, building.locations.size());

            ShelfLocation created = building.locations.iterator().next();
            assertInstanceOf(StoreShelfLocation.class, created);
            assertSame(product, created.product);
            assertEquals("Fruits", created.area);
            assertEquals("A05", created.aisle);
            assertEquals("B", created.unit);
            assertEquals("2", created.elevation);
            assertEquals("05", created.position);
            assertEquals(3, ((StoreShelfLocation) created).facings);
            assertEquals("Grocery", ((StoreShelfLocation) created).department);
            // locationCode generated by onCreate: AREA-AISLE-UNIT-ELEVATION-POSITION
            assertEquals("Fruits-A05-B-2-05", created.locationCode);
            // The new location is cached under the generated key
            assertSame(created, locationCache(ctx).get("B1_EAN-1_SHELF"));
        }
    }

    @Test
    void processLineLogic_newBinLocationCaseInsensitive_createsAndCounts() {
        Building building = building("B1", 7L);
        Product product = product("EAN-1", 9L);
        Map<String, Object> ctx = contextWith(building, product);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(eq(7L))).thenReturn(building);

            // Lowercase type token: creation is case-insensitive
            resource.processLineLogic(line("B1", "EAN-1", "bin", "COLD", "A", "R12", "3", "12", "25.5", "4"), ctx, counters);

            assertEquals(1, counters[0]);
            ShelfLocation created = building.locations.iterator().next();
            assertInstanceOf(WarehouseShelfLocation.class, created);
            assertEquals(25.5, ((WarehouseShelfLocation) created).maxWeightKg);
            assertEquals(4, ((WarehouseShelfLocation) created).maxPallets);
            assertEquals("COLD-A-R12-3-12", created.locationCode);
        }
    }

    @Test
    void processLineLogic_unknownType_throwsIAE() {
        Building building = building("B1", 7L);
        Product product = product("EAN-1", 9L);
        Map<String, Object> ctx = contextWith(building, product);
        int[] counters = {0, 0};

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(line("B1", "EAN-1", "PALLET", "Fruits", "A05", "B", "2", "05", "3", "Grocery"), ctx, counters));

        assertTrue(ex.getMessage().contains("Unknown location type: PALLET"));
        assertEquals(0, counters[0]);
    }

    @Test
    void processLineLogic_newShelfWithBlankFacings_usesDefault() {
        Building building = building("B1", 7L);
        Product product = product("EAN-1", 9L);
        Map<String, Object> ctx = contextWith(building, product);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(eq(7L))).thenReturn(building);

            // Empty facings column -> default value 1
            resource.processLineLogic(line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "", "Grocery"), ctx, counters);

            ShelfLocation created = building.locations.iterator().next();
            assertEquals(1, ((StoreShelfLocation) created).facings);
        }
    }

    @Test
    void processLineLogic_newShelfWithInvalidFacings_usesDefault() {
        Building building = building("B1", 7L);
        Product product = product("EAN-1", 9L);
        Map<String, Object> ctx = contextWith(building, product);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(eq(7L))).thenReturn(building);

            // Non-numeric facings column -> default value 1
            resource.processLineLogic(line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "abc", "Grocery"), ctx, counters);

            ShelfLocation created = building.locations.iterator().next();
            assertEquals(1, ((StoreShelfLocation) created).facings);
        }
    }

    // --------------------------------------------------
    // processLineLogic - existing locations
    // --------------------------------------------------

    @Test
    void processLineLogic_existingShelfSameChecksum_skipsUpdate() {
        Building building = building("B1", 7L);
        Product product = product("EAN-1", 9L);
        StoreShelfLocation existing = shelf(100L, product, "Fruits", "A05", "B", "2", "05", 3, "Grocery");
        Map<String, Object> ctx = contextWith(building, product);
        locationCache(ctx).put("B1_EAN-1_SHELF", existing);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(eq(100L))).thenReturn(existing);

            // Same data as the existing location: checksum matches, nothing to do
            resource.processLineLogic(line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "3", "Grocery"), ctx, counters);

            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            assertEquals("Grocery", existing.department);
        }
    }

    @Test
    void processLineLogic_existingShelfDifferentChecksum_updatesAndCounts() {
        Building building = building("B1", 7L);
        Product product = product("EAN-1", 9L);
        StoreShelfLocation existing = shelf(100L, product, "Fruits", "A05", "B", "2", "05", 3, "Grocery");
        Map<String, Object> ctx = contextWith(building, product);
        locationCache(ctx).put("B1_EAN-1_SHELF", existing);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(eq(100L))).thenReturn(existing);

            // Department changed: checksum differs, the location is updated
            resource.processLineLogic(line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "3", "Bakery"), ctx, counters);

            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("Bakery", existing.department);
        }
    }

    @Test
    void processLineLogic_existingLocationTypeMismatch_skipsLine() {
        Building building = building("B1", 7L);
        Product product = product("EAN-1", 9L);
        // A SHELF location exists but the CSV line declares a BIN
        StoreShelfLocation existing = shelf(100L, product, "Fruits", "A05", "B", "2", "05", 3, "Grocery");
        Map<String, Object> ctx = contextWith(building, product);
        locationCache(ctx).put("B1_EAN-1_BIN", existing);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(eq(100L))).thenReturn(existing);

            resource.processLineLogic(line("B1", "EAN-1", "BIN", "Fruits", "A05", "B", "2", "05", "3", "Grocery"), ctx, counters);

            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            // The existing location is left untouched
            assertEquals("Grocery", existing.department);
        }
    }

    @Test
    void processLineLogic_cachedLocationRefetchNull_createsNewLocation() {
        Building building = building("B1", 7L);
        Product product = product("EAN-1", 9L);
        StoreShelfLocation phantom = shelf(100L, product, "Fruits", "A05", "B", "2", "05", 3, "Grocery");
        Map<String, Object> ctx = contextWith(building, product);
        locationCache(ctx).put("B1_EAN-1_SHELF", phantom);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            // The cached entity is a phantom: re-fetch returns null
            panacheMock.when(() -> PanacheEntityBase.findById(eq(100L))).thenReturn(null);
            panacheMock.when(() -> PanacheEntityBase.findById(eq(7L))).thenReturn(building);

            resource.processLineLogic(line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "3", "Grocery"), ctx, counters);

            assertEquals(1, counters[0]);
            assertEquals(1, building.locations.size());
            ShelfLocation created = building.locations.iterator().next();
            assertNotSame(phantom, created);
            assertSame(created, locationCache(ctx).get("B1_EAN-1_SHELF"));
        }
    }

    @Test
    void processLineLogic_existingBinDifferentChecksum_updatesAndCounts() {
        Building building = building("B1", 7L);
        Product product = product("EAN-1", 9L);
        WarehouseShelfLocation existing = bin(100L, product, "COLD", "A", "R12", "3", "12", 25.5, 4);
        Map<String, Object> ctx = contextWith(building, product);
        locationCache(ctx).put("B1_EAN-1_BIN", existing);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(eq(100L))).thenReturn(existing);

            // Max weight changed: the BIN checksum differs, the location is updated
            resource.processLineLogic(line("B1", "EAN-1", "BIN", "COLD", "A", "R12", "3", "12", "30.0", "4"), ctx, counters);

            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals(30.0, existing.maxWeightKg);
        }
    }

    @Test
    void processLineLogic_existingShelfUnknownType_usesBaseHashAndUpdates() {
        Building building = building("B1", 7L);
        Product product = product("EAN-1", 9L);
        StoreShelfLocation existing = shelf(100L, product, "Fruits", "A05", "B", "2", "05", 3, "Grocery");
        Map<String, Object> ctx = contextWith(building, product);
        locationCache(ctx).put("B1_EAN-1_PALLET", existing);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(eq(100L))).thenReturn(existing);

            // Unknown type: the incoming checksum is the base hash only, which never
            // matches the full entity checksum (facings/department included) -> update
            resource.processLineLogic(line("B1", "EAN-1", "PALLET", "Fruits", "A05", "B", "2", "05", "3", "Grocery"), ctx, counters);

            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
        }
    }

    // --------------------------------------------------
    // prepareContextForLine (1-by-1 fallback context)
    // --------------------------------------------------

    @Test
    void prepareContextForLine_buildingAndProductFound_buildsCache() {
        Product product = product("EAN-1", 9L);
        Building building = building("B1", 7L);
        StoreShelfLocation shelf = shelf(100L, product, "Fruits", "A05", "B", "2", "05", 3, "Grocery");
        StoreShelfLocation noProduct = shelf(101L, null, "Fruits", "A05", "B", "2", "05", 3, "Grocery");
        building.locations.add(shelf);
        building.locations.add(noProduct);

        PanacheQuery<Building> buildingQuery = queryReturning(building);
        PanacheQuery<Product> productQuery = queryReturning(product);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class))).thenReturn(buildingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("ean"), any(Object[].class))).thenReturn(productQuery);

            Map<String, Object> ctx = resource.prepareContextForLine(line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "3", "Grocery"));

            assertSame(building, ctx.get("BLD_B1"));
            assertSame(product, ctx.get("PRD_EAN-1"));
            Map<String, ShelfLocation> cache = locationCache(ctx);
            // Only the location with a product is cached
            assertEquals(1, cache.size());
            assertSame(shelf, cache.get("B1_EAN-1_SHELF"));
        }
    }

    @Test
    void prepareContextForLine_buildingNotFound_contextWithoutBuilding() {
        Product product = product("EAN-1", 9L);

        PanacheQuery<Building> buildingQuery = queryReturning(null);
        PanacheQuery<Product> productQuery = queryReturning(product);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class))).thenReturn(buildingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("ean"), any(Object[].class))).thenReturn(productQuery);

            Map<String, Object> ctx = resource.prepareContextForLine(line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "3", "Grocery"));

            assertNull(ctx.get("BLD_B1"));
            assertNull(ctx.get(CACHE_KEY));
            assertSame(product, ctx.get("PRD_EAN-1"));
        }
    }

    @Test
    void prepareContextForLine_buildingWithNullLocations_emptyCache() {
        Product product = product("EAN-1", 9L);
        Building building = building("B1", 7L);
        building.locations = null;

        PanacheQuery<Building> buildingQuery = queryReturning(building);
        PanacheQuery<Product> productQuery = queryReturning(product);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class))).thenReturn(buildingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("ean"), any(Object[].class))).thenReturn(productQuery);

            Map<String, Object> ctx = resource.prepareContextForLine(line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "3", "Grocery"));

            assertSame(building, ctx.get("BLD_B1"));
            assertTrue(locationCache(ctx).isEmpty());
        }
    }

    @Test
    void prepareContextForLine_productNotFound_noProductKey() {
        Building building = building("B1", 7L);

        PanacheQuery<Building> buildingQuery = queryReturning(building);
        PanacheQuery<Product> productQuery = queryReturning(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class))).thenReturn(buildingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("ean"), any(Object[].class))).thenReturn(productQuery);

            Map<String, Object> ctx = resource.prepareContextForLine(line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "3", "Grocery"));

            assertSame(building, ctx.get("BLD_B1"));
            assertNull(ctx.get("PRD_EAN-1"));
            assertTrue(locationCache(ctx).isEmpty());
        }
    }

    // --------------------------------------------------
    // findEntityForLine (1-by-1 fallback lookup)
    // --------------------------------------------------

    @Test
    void findEntityForLine_buildingNull_returnsNull() {
        Product product = product("EAN-1", 9L);

        PanacheQuery<Building> buildingQuery = queryReturning(null);
        PanacheQuery<Product> productQuery = queryReturning(product);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class))).thenReturn(buildingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("ean"), any(Object[].class))).thenReturn(productQuery);

            assertNull(resource.findEntityForLine(line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "3", "Grocery")));
        }
    }

    @Test
    void findEntityForLine_productNull_returnsNull() {
        Building building = building("B1", 7L);

        PanacheQuery<Building> buildingQuery = queryReturning(building);
        PanacheQuery<Product> productQuery = queryReturning(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class))).thenReturn(buildingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("ean"), any(Object[].class))).thenReturn(productQuery);

            assertNull(resource.findEntityForLine(line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "3", "Grocery")));
        }
    }

    @Test
    void findEntityForLine_matchingLocation_returnsIt() {
        Product product = product("EAN-1", 9L);
        Building building = building("B1", 7L);
        StoreShelfLocation shelf = shelf(100L, product, "Fruits", "A05", "B", "2", "05", 3, "Grocery");
        building.locations.add(shelf);

        PanacheQuery<Building> buildingQuery = queryReturning(building);
        PanacheQuery<Product> productQuery = queryReturning(product);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class))).thenReturn(buildingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("ean"), any(Object[].class))).thenReturn(productQuery);

            Object found = resource.findEntityForLine(line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "3", "Grocery"));

            assertSame(shelf, found);
        }
    }

    @Test
    void findEntityForLine_productIdMismatch_returnsNull() {
        Product product = product("EAN-1", 9L);
        Product other = product("EAN-2", 10L);
        Building building = building("B1", 7L);
        building.locations.add(shelf(100L, other, "Fruits", "A05", "B", "2", "05", 3, "Grocery"));

        PanacheQuery<Building> buildingQuery = queryReturning(building);
        PanacheQuery<Product> productQuery = queryReturning(product);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class))).thenReturn(buildingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("ean"), any(Object[].class))).thenReturn(productQuery);

            assertNull(resource.findEntityForLine(line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "3", "Grocery")));
        }
    }

    @Test
    void findEntityForLine_typeMismatch_returnsNull() {
        Product product = product("EAN-1", 9L);
        Building building = building("B1", 7L);
        // A SHELF location exists but the CSV line declares a BIN
        building.locations.add(shelf(100L, product, "Fruits", "A05", "B", "2", "05", 3, "Grocery"));

        PanacheQuery<Building> buildingQuery = queryReturning(building);
        PanacheQuery<Product> productQuery = queryReturning(product);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class))).thenReturn(buildingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("ean"), any(Object[].class))).thenReturn(productQuery);

            assertNull(resource.findEntityForLine(line("B1", "EAN-1", "BIN", "Fruits", "A05", "B", "2", "05", "3", "Grocery")));
        }
    }

    // --------------------------------------------------
    // processChunkWithFallback (chunk context)
    // --------------------------------------------------

    @Test
    void processChunkWithFallback_buildsFullContext() {
        Product product1 = product("EAN-1", 9L);
        Product product2 = product("EAN-2", 10L);
        Building building1 = building("B1", 7L);
        Building building2 = building("B2", 8L);
        building2.locations = null;
        StoreShelfLocation shelf = shelf(100L, product1, "Fruits", "A05", "B", "2", "05", 3, "Grocery");
        WarehouseShelfLocation bin = bin(101L, product2, "COLD", "A", "R12", "3", "12", 25.5, 4);
        StoreShelfLocation noProduct = shelf(102L, null, "Fruits", "A05", "B", "2", "05", 3, "Grocery");
        building1.locations.add(shelf);
        building1.locations.add(bin);
        building1.locations.add(noProduct);

        EntityManager em = mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        TypedQuery<Building> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(Building.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(building1, building2));

        List<ImporterCsvResource.LineData> lines = List.of(
                line("B1", "EAN-1", "SHELF", "Fruits", "A05", "B", "2", "05", "3", "Grocery"),
                line("B2", "EAN-2", "BIN", "COLD", "A", "R12", "3", "12", "25.5", "4"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(PanacheEntityBase::getEntityManager).thenReturn(em);
            panacheMock.when(() -> PanacheEntityBase.list(eq("ean IN ?1"), any(Object[].class)))
                    .thenReturn(List.of(product1, product2));

            Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("B1", "B2"), new int[]{0, 0}, new ArrayList<>());

            assertSame(building1, ctx.get("BLD_B1"));
            assertSame(building2, ctx.get("BLD_B2"));
            assertSame(product1, ctx.get("PRD_EAN-1"));
            assertSame(product2, ctx.get("PRD_EAN-2"));
            Map<String, ShelfLocation> cache = locationCache(ctx);
            // Both type tokens are used; the location without product is skipped;
            // building2 has null locations and is skipped
            assertEquals(2, cache.size());
            assertSame(shelf, cache.get("B1_EAN-1_SHELF"));
            assertSame(bin, cache.get("B1_EAN-2_BIN"));
        }
    }

    @Test
    void processChunkWithFallback_emptyEans_skipsProductFetch() {
        Building building = building("B1", 7L);

        EntityManager em = mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        TypedQuery<Building> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(Building.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(building));

        // Line without an EAN column: no product to fetch
        List<ImporterCsvResource.LineData> lines = List.of(line("B1"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(PanacheEntityBase::getEntityManager).thenReturn(em);

            Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("B1"), new int[]{0, 0}, new ArrayList<>());

            panacheMock.verify(() -> PanacheEntityBase.list(anyString(), any(Object[].class)), never());
            assertSame(building, ctx.get("BLD_B1"));
            assertNull(ctx.get("PRD_EAN-1"));
            assertTrue(locationCache(ctx).isEmpty());
        }
    }

    // --------------------------------------------------
    // importLocations - full flow
    // --------------------------------------------------

    @Test
    void importLocations_validCsv_createsLocationAndReturnsSummary() throws Exception {
        Building building = building("B1", 7L);
        Product product = product("EAN-1", 9L);

        EntityManager em = mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        TypedQuery<Building> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(Building.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(building));

        String csv = "building|ean|type|area|aisle|unit|elevation|position|facings|department\n"
                + "B1|EAN-1|SHELF|Fruits|A05|B|2|05|3|Grocery\n";

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(PanacheEntityBase::getEntityManager).thenReturn(em);
            panacheMock.when(() -> PanacheEntityBase.findById(eq(7L))).thenReturn(building);
            panacheMock.when(() -> PanacheEntityBase.list(eq("ean IN ?1"), any(Object[].class))).thenReturn(List.of(product));

            TransactionManager tm = mock(TransactionManager.class);
            resource.tm = tm;

            Response response = resource.importLocations(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

            assertEquals(200, response.getStatus());
            String body = (String) response.getEntity();
            assertTrue(body.contains("\"createdCount\":1"), body);
            assertTrue(body.contains("\"updatedCount\":0"), body);
            assertFalse(body.contains("errors"), body);
            assertEquals(1, building.locations.size());
            verify(tm).commit();
            verify(session).clear();
        }
    }

    @Test
    void importLocations_failingLine_fallsBackToLineByLineAndReportsError() throws Exception {
        Building building = building("B1", 7L);
        Product product = product("EAN-1", 9L);

        EntityManager em = mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        TypedQuery<Building> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(Building.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(building));

        PanacheQuery<Building> buildingQuery = queryReturning(building);
        PanacheQuery<Product> productQuery = queryReturning(product);

        // Unknown location type: the line fails at every stage down to 1-by-1
        String csv = "building|ean|type|area|aisle|unit|elevation|position|facings|department\n"
                + "B1|EAN-1|PALLET|Fruits|A05|B|2|05|3|Grocery\n";

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(PanacheEntityBase::getEntityManager).thenReturn(em);
            panacheMock.when(() -> PanacheEntityBase.list(eq("ean IN ?1"), any(Object[].class))).thenReturn(List.of(product));
            panacheMock.when(() -> PanacheEntityBase.find(eq("buildingCode"), any(Object[].class))).thenReturn(buildingQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("ean"), any(Object[].class))).thenReturn(productQuery);

            TransactionManager tm = mock(TransactionManager.class);
            resource.tm = tm;

            Response response = resource.importLocations(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

            assertEquals(200, response.getStatus());
            String body = (String) response.getEntity();
            assertTrue(body.contains("\"createdCount\":0"), body);
            assertTrue(body.contains("Line 2 (B1): Unknown location type: PALLET"), body);
            // Staged fallback: 1000 -> 100 -> 10 -> 1-by-1, every attempt rolled back
            verify(tm, times(4)).rollback();
            verify(tm, never()).commit();
        }
    }
}
