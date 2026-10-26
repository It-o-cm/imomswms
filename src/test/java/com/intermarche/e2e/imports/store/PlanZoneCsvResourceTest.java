package com.intermarche.e2e.imports.store;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.PlanZone;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.imports.store.PlanZoneCsvResource;
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
 * Test class for {@link PlanZoneCsvResource}.
 * <p>
 * Tests the CSV import endpoint for Plan Zones, covering creation, updates, checksum
 * optimization, the 1-by-1 fallback mechanism and error handling.
 */
@QuarkusTest
public class PlanZoneCsvResourceTest {

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
     * Creates a Store with an attached Building fixture.
     *
     * @param storeCode    The store code.
     * @param buildingCode The building code.
     */
    private void createBuilding(String storeCode, String buildingCode) {
        CsvImportTestHelper.withTransaction(tm, () -> {
            Store s = new Store();
            s.code = storeCode;
            s.name = "Store " + storeCode;
            s.address = new Adresse();
            s.address.city = "Paris";
            s.persist();
            Building b = new StoreBuilding();
            b.buildingCode = buildingCode;
            s.buildings.add(b);
            return null;
        });
    }

    /**
     * Tests the successful import of new plan zones with valid data.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewZonesSuccess() {
        createBuilding("S_PZ", "BLD_PZ");
        String csvContent = "code|buildingCode|label|color|temperatureZone|vertices|offsetX|offsetY\n" +
                "FRESH_AREA|BLD_PZ|Rayon Frais|#0891b2|CHILLED|-293,47;-188,47;-183,120;-298,120|0|0\n" +
                "FROZEN_AREA|BLD_PZ|Surgelés|#0284c7|FROZEN|252,47;357,47;362,120;247,120|1.5|-2.5";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-zones/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        PlanZone fresh = PlanZone.find("code", "FRESH_AREA").firstResult();
        assertNotNull(fresh);
        assertEquals("Rayon Frais", fresh.label);
        assertEquals("#0891b2", fresh.color);
        assertEquals(TemperatureZone.CHILLED, fresh.temperatureZone);
        assertEquals(4, fresh.vertices.size());
        assertEquals(0, fresh.vertices.get(0).position);
        assertEquals(-293.0, fresh.vertices.get(0).x, 0.0001);
        assertEquals(47.0, fresh.vertices.get(0).y, 0.0001);
        assertEquals(3, fresh.vertices.get(3).position);
        assertEquals(-298.0, fresh.vertices.get(3).x, 0.0001);
        assertEquals(0.0, fresh.labelOffsetX, 0.0001);
        assertEquals(0.0, fresh.labelOffsetY, 0.0001);
        assertEquals("BLD_PZ", fresh.building.buildingCode);

        PlanZone frozen = PlanZone.find("code", "FROZEN_AREA").firstResult();
        assertNotNull(frozen);
        assertEquals(TemperatureZone.FROZEN, frozen.temperatureZone);
        assertEquals(1.5, frozen.labelOffsetX, 0.0001);
        assertEquals(-2.5, frozen.labelOffsetY, 0.0001);
    }

    /**
     * Tests that re-importing an identical CSV does not trigger any update.
     * <p>
     * Validates the checksum consistency between the entity and the importer.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testReimportIdenticalCsv_NoUpdate() {
        createBuilding("S_PZ", "BLD_PZ");
        String csvContent = "code|buildingCode|label|color|temperatureZone|vertices|offsetX|offsetY\n" +
                "FRESH_AREA|BLD_PZ|Rayon Frais|#0891b2|CHILLED|-293,47;-188,47;-183,120;-298,120|0|0";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-zones/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-zones/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));
    }

    /**
     * Tests the update of an existing plan zone when the incoming data differs.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingZone_ChangedColor() {
        createBuilding("S_PZ", "BLD_PZ");
        given()
                .body("code|buildingCode|label|color|temperatureZone|vertices|offsetX|offsetY\nFRESH_AREA|BLD_PZ|Rayon Frais|#0891b2|CHILLED|0,0;10,0;10,10|0|0")
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-zones/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body("code|buildingCode|label|color|temperatureZone|vertices|offsetX|offsetY\nFRESH_AREA|BLD_PZ|Rayon Frais|#ff0000|CHILLED|0,0;10,0;10,10|0|0")
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-zones/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        PlanZone updated = PlanZone.find("code", "FRESH_AREA").firstResult();
        assertNotNull(updated);
        assertEquals("#ff0000", updated.color);
    }

    /**
     * Tests the update of an existing plan zone replaces its polygon.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingZone_ReplacesPolygon() {
        createBuilding("S_PZ", "BLD_PZ");
        given()
                .body("code|buildingCode|label|color|temperatureZone|vertices|offsetX|offsetY\nFRESH_AREA|BLD_PZ|Rayon Frais|#0891b2|CHILLED|0,0;10,0;10,10|0|0")
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-zones/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body("code|buildingCode|label|color|temperatureZone|vertices|offsetX|offsetY\nFRESH_AREA|BLD_PZ|Rayon Frais|#0891b2|CHILLED|0,0;20,0;20,20;0,20|0|0")
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-zones/import")
                .then()
                .statusCode(200)
                .body(containsString("\"updatedCount\":1"));

        PlanZone updated = PlanZone.find("code", "FRESH_AREA").firstResult();
        assertNotNull(updated);
        assertEquals(4, updated.vertices.size());
        assertEquals(20.0, updated.vertices.get(1).x, 0.0001);
    }

    /**
     * Tests the error handling when the referenced building does not exist.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportZone_UnknownBuilding_Error() {
        String csvContent = "code|buildingCode|label|color|temperatureZone|vertices|offsetX|offsetY\n" +
                "Z_X|BLD_UNKNOWN|Zone|#fff||0,0;10,0;10,10|0|0";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-zones/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Building with code 'BLD_UNKNOWN' not found"));

        assertNull(PlanZone.find("code", "Z_X").firstResult());
    }

    /**
     * Tests the error handling when the polygon has fewer than 3 vertices.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportZone_TooFewVertices_Error() {
        createBuilding("S_PZ", "BLD_PZ");
        String csvContent = "code|buildingCode|label|color|temperatureZone|vertices|offsetX|offsetY\n" +
                "Z_BAD|BLD_PZ|Zone|#fff||0,0;10,0|0|0";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-zones/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("at least 3 vertices"));

        assertNull(PlanZone.find("code", "Z_BAD").firstResult());
    }

    /**
     * Tests the 1-by-1 fallback mechanism with a mix of valid and invalid lines.
     * <p>
     * This validates that the fallback context contains the referenced building.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportZones_FallbackMixedLines() {
        createBuilding("S_PZ", "BLD_PZ");
        String csvContent = "code|buildingCode|label|color|temperatureZone|vertices|offsetX|offsetY\n" +
                "Z_OK|BLD_PZ|Zone|#fff||0,0;10,0;10,10|0|0\n" +
                "Z_BAD|BLD_UNKNOWN|Zone|#fff||0,0;10,0;10,10|0|0";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-zones/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"errors\""))
                .body(containsString("BLD_UNKNOWN"));

        assertNotNull(PlanZone.find("code", "Z_OK").firstResult());
        assertNull(PlanZone.find("code", "Z_BAD").firstResult());
    }

    /**
     * Tests the processing logic when the input CSV contains no data lines (only header).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportZones_EmptyInput() {
        String csvContent = "code|buildingCode|label|color|temperatureZone|vertices|offsetX|offsetY\n";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-zones/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));
    }

    /**
     * Tests that a line with fewer columns than expected is ignored with an error.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportZones_TooFewColumns() {
        String csvContent = "code|buildingCode|label|color|temperatureZone|vertices|offsetX|offsetY\n" +
                "Z1|BLD_PZ|Zone|#fff";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-zones/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("not enough columns"));
    }

    /**
     * Tests the security configuration to ensure access is denied for non-admin users.
     */
    @Test
    void testSecurity_AccessDeniedForNonAdmin() {
        String csvContent = "code|buildingCode|label|color|temperatureZone|vertices|offsetX|offsetY\n" +
                "Z1|BLD_PZ|Zone|#fff||0,0;10,0;10,10|0|0";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-zones/import")
                .then()
                .statusCode(401);
    }
}
