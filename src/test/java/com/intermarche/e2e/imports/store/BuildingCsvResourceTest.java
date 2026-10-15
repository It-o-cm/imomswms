package com.intermarche.e2e.imports.store;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.WarehouseBuilding;
import com.intermarche.backoffice.imports.store.BuildingCsvResource;
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
 * Test class for {@link BuildingCsvResource}.
 * <p>
 * Tests the CSV import endpoint for Buildings, covering creation (both building
 * types), updates, checksum optimization, type-mismatch handling, the 1-by-1
 * fallback mechanism and error handling.
 */
@QuarkusTest
public class BuildingCsvResourceTest {

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
     * Creates a Store fixture with the given code.
     *
     * @param code The store code.
     */
    private void createStore(String code) {
        CsvImportTestHelper.withTransaction(tm, () -> {
            Store s = new Store();
            s.code = code;
            s.name = "Store " + code;
            s.address = new Adresse();
            s.address.city = "Paris";
            s.persist();
            return null;
        });
    }

    /**
     * Tests the successful import of new buildings of both types (WAREHOUSE and STORE).
     * <p>
     * Verifies the concrete subclass, the embedded address/GPS and the store attachment.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewBuildingsSuccess() {
        createStore("S_BD");
        String csvContent = "buildingCode|storeCode|type|street1|street2|zip|city|country|lat|long\n" +
                "BD_WH|S_BD|WAREHOUSE|1 Rue de la Paix||75001|Paris|France|48.8566|2.3522\n" +
                "BD_ST|S_BD|STORE|10 Avenue des Champs||75008|Paris|France|48.8698|2.3075";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/buildings/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        Building wh = Building.findByCode("BD_WH");
        assertNotNull(wh);
        assertInstanceOf(WarehouseBuilding.class, wh);
        assertEquals("1 Rue de la Paix", wh.address.streetLine1);
        assertEquals("", wh.address.streetLine2);
        assertEquals("75001", wh.address.postalCode);
        assertEquals("Paris", wh.address.city);
        assertEquals("France", wh.address.country);
        assertEquals(48.8566, wh.gpsPosition.latitude, 0.0001);
        assertEquals(2.3522, wh.gpsPosition.longitude, 0.0001);

        Building st = Building.findByCode("BD_ST");
        assertNotNull(st);
        assertInstanceOf(StoreBuilding.class, st);
        assertEquals("10 Avenue des Champs", st.address.streetLine1);

        Store store = Store.findByCode("S_BD");
        assertNotNull(store);
        assertEquals(2, store.buildings.size());
    }

    /**
     * Tests that re-importing an identical CSV does not trigger any update.
     * <p>
     * Validates the checksum consistency between the entity and the importer.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testReimportIdenticalCsv_NoUpdate() {
        createStore("S_BD");
        String csvContent = "buildingCode|storeCode|type|street1|street2|zip|city|country|lat|long\n" +
                "BD_WH|S_BD|WAREHOUSE|1 Rue de la Paix||75001|Paris|France|48.8566|2.3522";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/buildings/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/buildings/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));
    }

    /**
     * Tests the update of an existing building when the incoming data differs.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingBuilding_ChangedCity() {
        createStore("S_BD");
        given()
                .body("buildingCode|storeCode|type|street1|street2|zip|city|country|lat|long\nBD_ST|S_BD|STORE|1 Rue||75001|Paris|France||")
                .contentType(ContentType.TEXT)
                .when()
                .post("/buildings/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body("buildingCode|storeCode|type|street1|street2|zip|city|country|lat|long\nBD_ST|S_BD|STORE|1 Rue||69001|Lyon|France||")
                .contentType(ContentType.TEXT)
                .when()
                .post("/buildings/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        Building updated = Building.findByCode("BD_ST");
        assertNotNull(updated);
        assertEquals("Lyon", updated.address.city);
        assertEquals("69001", updated.address.postalCode);
    }

    /**
     * Tests the error handling when the referenced store does not exist.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportBuilding_UnknownStore_Error() {
        String csvContent = "buildingCode|storeCode|type|street1|street2|zip|city|country|lat|long\n" +
                "BD_X|S_UNKNOWN|STORE|1 Rue||75001|Paris|France||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/buildings/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Store with code 'S_UNKNOWN' not found for building BD_X"));

        assertNull(Building.findByCode("BD_X"));
    }

    /**
     * Tests that a type mismatch on an existing building is skipped silently.
     * <p>
     * The line is neither counted as created nor as updated, and no error is
     * reported: the building keeps its original type.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportBuilding_TypeMismatch_SkippedSilently() {
        createStore("S_BD");
        given()
                .body("buildingCode|storeCode|type|street1|street2|zip|city|country|lat|long\nBD_ST|S_BD|STORE|1 Rue||75001|Paris|France||")
                .contentType(ContentType.TEXT)
                .when()
                .post("/buildings/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body("buildingCode|storeCode|type|street1|street2|zip|city|country|lat|long\nBD_ST|S_BD|WAREHOUSE|1 Rue||75001|Paris|France||")
                .contentType(ContentType.TEXT)
                .when()
                .post("/buildings/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        Building unchanged = Building.findByCode("BD_ST");
        assertNotNull(unchanged);
        assertInstanceOf(StoreBuilding.class, unchanged);
    }

    /**
     * Tests the 1-by-1 fallback mechanism with a mix of valid and invalid lines.
     * <p>
     * This validates that the fallback context contains the referenced store.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportBuildings_FallbackMixedLines() {
        createStore("S_BD");
        String csvContent = "buildingCode|storeCode|type|street1|street2|zip|city|country|lat|long\n" +
                "BD_OK|S_BD|STORE|1 Rue||75001|Paris|France||\n" +
                "BD_BAD|S_UNKNOWN|STORE|1 Rue||75001|Paris|France||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/buildings/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"errors\""))
                .body(containsString("S_UNKNOWN"));

        assertNotNull(Building.findByCode("BD_OK"));
        assertNull(Building.findByCode("BD_BAD"));
    }

    /**
     * Tests the import of a building with only the 3 mandatory columns.
     * <p>
     * Address and GPS fields are left null.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportBuilding_MinimalColumns() {
        createStore("S_BD");
        String csvContent = "buildingCode|storeCode|type|street1|street2|zip|city|country|lat|long\n" +
                "BD_MIN|S_BD|STORE";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/buildings/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(Matchers.not(containsString("\"errors\"")));

        Building minimal = Building.findByCode("BD_MIN");
        assertNotNull(minimal);
        assertInstanceOf(StoreBuilding.class, minimal);
        // With only the 3 mandatory columns, no address/GPS data is provided:
        // Hibernate reads back an all-null @Embedded as null.
        assertNull(minimal.address);
        assertNull(minimal.gpsPosition);
    }

    /**
     * Tests the processing logic when the input CSV contains no data lines (only header).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportBuildings_EmptyInput() {
        String csvContent = "buildingCode|storeCode|type|street1|street2|zip|city|country|lat|long\n";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/buildings/import")
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
        String csvContent = "buildingCode|storeCode|type|street1|street2|zip|city|country|lat|long\n" +
                "BD1|S_BD|STORE|1 Rue||75001|Paris|France||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/buildings/import")
                .then()
                .statusCode(401);
    }
}
