package com.intermarche.e2e.imports.store;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.domain.store.WarehouseShelfLocation;
import com.intermarche.backoffice.imports.store.LocationCsvResource;
import com.intermarche.e2e.imports.CsvImportTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link LocationCsvResource}.
 * <p>
 * Tests the CSV import endpoint for Locations (SHELF and BIN), covering creation,
 * updates, checksum optimization, duplicate detection on re-import, the 1-by-1
 * fallback mechanism and error handling.
 */
@QuarkusTest
public class LocationCsvResourceTest {

    /**
     * The TransactionManager for manual transaction control in tests.
     */
    @Inject
    TransactionManager tm;

    /**
     * Cleans the database before each test to ensure isolation.
     */
    @BeforeEach
    void setUp() {
        CsvImportTestHelper.cleanDatabase(tm);
    }

    /**
     * Cleans the database after each test so that no committed row leaks into
     * the next test class.
     */
    @AfterEach
    void tearDown() {
        CsvImportTestHelper.cleanDatabase(tm);
    }

    /**
     * Creates a Store with an attached Building and a Product fixture.
     *
     * @param storeCode    The store code.
     * @param buildingCode The building code.
     * @param ean          The product EAN.
     */
    private void createBuildingWithProduct(String storeCode, String buildingCode, String ean) {
        CsvImportTestHelper.withTransaction(tm, () -> {
            // The store/building fixture is created only once: this helper is called
            // several times per test (once per product EAN) and Store.code is unique.
            Store s = Store.findByCode(storeCode);
            if (s == null) {
                s = new Store();
                s.code = storeCode;
                s.name = "Store " + storeCode;
                s.address = new Adresse();
                s.address.city = "Paris";
                s.persist();
                Building b = new StoreBuilding();
                b.buildingCode = buildingCode;
                s.buildings.add(b);
            }
            Product p = new Product();
            p.ean = ean;
            p.name = "Product " + ean;
            p.productType = ProductType.UNIT;
            p.temperatureZone = TemperatureZone.AMBIENT;
            p.persist();
            return null;
        });
    }

    /**
     * Tests the successful import of a SHELF and a BIN location.
     * <p>
     * Verifies the concrete subclass, the physical coordinates, the type-specific
     * fields and the generated location code.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewLocationsSuccess() {
        createBuildingWithProduct("S_LC", "BLD_LC", "1234567890123");
        createBuildingWithProduct("S_LC", "BLD_LC", "1234567890124");
        String csvContent = "buildingCode|productEan|type|area|aisle|unit|elevation|position|facingsOrMaxWeight|departmentOrMaxPallets\n" +
                "BLD_LC|1234567890123|SHELF|EPICERIE|A05|B|2|05|3|FRUITS\n" +
                "BLD_LC|1234567890124|BIN|COLD|A|R12|1|12|250.5|4";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/locations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        Building building = Building.findByCode("BLD_LC");
        assertNotNull(building);
        assertEquals(2, building.locations.size());

        StoreShelfLocation shelf = null;
        WarehouseShelfLocation bin = null;
        for (ShelfLocation loc : building.locations) {
            if (loc instanceof StoreShelfLocation s) shelf = s;
            if (loc instanceof WarehouseShelfLocation w) bin = w;
        }
        assertNotNull(shelf);
        assertEquals("EPICERIE", shelf.area);
        assertEquals("A05", shelf.aisle);
        assertEquals("B", shelf.unit);
        assertEquals("2", shelf.elevation);
        assertEquals("05", shelf.position);
        assertEquals(3, shelf.facings);
        assertEquals("FRUITS", shelf.department);
        assertEquals("EPICERIE-A05-B-2-05", shelf.locationCode);
        assertEquals("1234567890123", shelf.product.ean);

        assertNotNull(bin);
        assertEquals("COLD", bin.area);
        assertEquals("A", bin.aisle);
        assertEquals("R12", bin.unit);
        assertEquals("1", bin.elevation);
        assertEquals("12", bin.position);
        assertEquals(250.5, bin.maxWeightKg, 0.0001);
        assertEquals(4, bin.maxPallets);
        assertEquals("COLD-A-R12-1-12", bin.locationCode);
        assertEquals("1234567890124", bin.product.ean);
    }

    /**
     * Tests that re-importing an identical CSV neither creates duplicates nor updates.
     * <p>
     * Validates the location lookup cache (keyed by the CSV type token) and the
     * checksum consistency between the entity and the importer.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testReimportIdenticalCsv_NoUpdateNoDuplicate() {
        createBuildingWithProduct("S_LC", "BLD_LC", "1234567890123");
        String csvContent = "buildingCode|productEan|type|area|aisle|unit|elevation|position|facingsOrMaxWeight|departmentOrMaxPallets\n" +
                "BLD_LC|1234567890123|SHELF|EPICERIE|A05|B|2|05|3|FRUITS";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/locations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/locations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        Building building = Building.findByCode("BLD_LC");
        assertNotNull(building);
        assertEquals(1, building.locations.size());
    }

    /**
     * Tests the update of an existing location when the incoming data differs.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingLocation_ChangedFacings() {
        createBuildingWithProduct("S_LC", "BLD_LC", "1234567890123");
        given()
                .body("buildingCode|productEan|type|area|aisle|unit|elevation|position|facingsOrMaxWeight|departmentOrMaxPallets\nBLD_LC|1234567890123|SHELF|EPICERIE|A05|B|2|05|3|FRUITS")
                .contentType(ContentType.TEXT)
                .when()
                .post("/locations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body("buildingCode|productEan|type|area|aisle|unit|elevation|position|facingsOrMaxWeight|departmentOrMaxPallets\nBLD_LC|1234567890123|SHELF|EPICERIE|A05|B|2|05|5|FRUITS")
                .contentType(ContentType.TEXT)
                .when()
                .post("/locations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        Building building = Building.findByCode("BLD_LC");
        assertNotNull(building);
        assertEquals(1, building.locations.size());
        StoreShelfLocation shelf = (StoreShelfLocation) building.locations.iterator().next();
        assertEquals(5, shelf.facings);
    }

    /**
     * Tests the error handling when the location type is unknown.
     * <p>
     * The line must be reported as an error and nothing must be created
     * (the line must not be silently counted as created).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportLocation_UnknownType_Error() {
        createBuildingWithProduct("S_LC", "BLD_LC", "1234567890123");
        String csvContent = "buildingCode|productEan|type|area|aisle|unit|elevation|position|facingsOrMaxWeight|departmentOrMaxPallets\n" +
                "BLD_LC|1234567890123|PALLET|EPICERIE|A05|B|2|05|3|FRUITS";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/locations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Unknown location type: PALLET"));

        Building building = Building.findByCode("BLD_LC");
        assertNotNull(building);
        assertTrue(building.locations.isEmpty());
    }

    /**
     * Tests the 1-by-1 fallback mechanism with a mix of valid and invalid lines.
     * <p>
     * This validates that the fallback context contains the building, the product
     * and the location lookup cache.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportLocations_FallbackMixedLines() {
        createBuildingWithProduct("S_LC", "BLD_LC", "1234567890123");
        String csvContent = "buildingCode|productEan|type|area|aisle|unit|elevation|position|facingsOrMaxWeight|departmentOrMaxPallets\n" +
                "BLD_LC|1234567890123|SHELF|EPICERIE|A05|B|2|05|3|FRUITS\n" +
                "BLD_LC|1234567890123|PALLET|EPICERIE|A05|B|2|05|3|FRUITS";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/locations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"errors\""))
                .body(containsString("Unknown location type: PALLET"));

        Building building = Building.findByCode("BLD_LC");
        assertNotNull(building);
        assertEquals(1, building.locations.size());
    }

    /**
     * Tests that a line referencing a missing building is skipped silently.
     * <p>
     * The importer logs a warning and ignores the line: no counter, no error.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportLocation_MissingBuilding_SkippedSilently() {
        createBuildingWithProduct("S_LC", "BLD_LC", "1234567890123");
        String csvContent = "buildingCode|productEan|type|area|aisle|unit|elevation|position|facingsOrMaxWeight|departmentOrMaxPallets\n" +
                "BLD_NOPE|1234567890123|SHELF|EPICERIE|A05|B|2|05|3|FRUITS";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/locations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));
    }

    /**
     * Tests that a line referencing a missing product is skipped silently.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportLocation_MissingProduct_SkippedSilently() {
        createBuildingWithProduct("S_LC", "BLD_LC", "1234567890123");
        String csvContent = "buildingCode|productEan|type|area|aisle|unit|elevation|position|facingsOrMaxWeight|departmentOrMaxPallets\n" +
                "BLD_LC|9999999999999|SHELF|EPICERIE|A05|B|2|05|3|FRUITS";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/locations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        Building building = Building.findByCode("BLD_LC");
        assertNotNull(building);
        assertTrue(building.locations.isEmpty());
    }

    /**
     * Tests the processing logic when the input CSV contains no data lines (only header).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportLocations_EmptyInput() {
        String csvContent = "buildingCode|productEan|type|area|aisle|unit|elevation|position|facingsOrMaxWeight|departmentOrMaxPallets\n";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/locations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));
    }

    /**
     * Tests the security configuration to ensure access is denied for non-admin users.
     */
    @Test
    void testSecurity_AccessDeniedForNonAdmin() {
        String csvContent = "buildingCode|productEan|type|area|aisle|unit|elevation|position|facingsOrMaxWeight|departmentOrMaxPallets\n" +
                "BLD_LC|1234567890123|SHELF|EPICERIE|A05|B|2|05|3|FRUITS";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/locations/import")
                .then()
                .statusCode(401);
    }
}
