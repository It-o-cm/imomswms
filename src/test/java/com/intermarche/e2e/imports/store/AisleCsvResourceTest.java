package com.intermarche.e2e.imports.store;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.imports.store.AisleCsvResource;
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
 * Test class for {@link AisleCsvResource}.
 * <p>
 * Tests the CSV import endpoint for Aisles, covering creation, updates, checksum
 * optimization, the 1-by-1 fallback mechanism and error handling.
 */
@QuarkusTest
public class AisleCsvResourceTest {

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
     * Tests the successful import of new aisles with valid data.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewAislesSuccess() {
        createBuilding("S_AI", "BLD_AI");
        String csvContent = "code|buildingCode|lengthMeters|aisleType|connectedToHead|connectedToTail\n" +
                "AI1|BLD_AI|12.5|LINEAR|A00|A02\n" +
                "AI2|BLD_AI|8|CIRCULAR||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/aisles/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        Aisle a1 = Aisle.find("code", "AI1").firstResult();
        assertNotNull(a1);
        assertEquals(12.5, a1.lengthMeters, 0.0001);
        assertEquals("LINEAR", a1.aisleType);
        assertEquals("A00", a1.connectedToHead);
        assertEquals("A02", a1.connectedToTail);
        assertEquals("BLD_AI", a1.building.buildingCode);

        Aisle a2 = Aisle.find("code", "AI2").firstResult();
        assertNotNull(a2);
        assertEquals(8.0, a2.lengthMeters, 0.0001);
        assertEquals("CIRCULAR", a2.aisleType);
        assertNull(a2.connectedToHead);
        assertNull(a2.connectedToTail);
    }

    /**
     * Tests that re-importing an identical CSV does not trigger any update.
     * <p>
     * Validates the checksum consistency between the entity and the importer.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testReimportIdenticalCsv_NoUpdate() {
        createBuilding("S_AI", "BLD_AI");
        String csvContent = "code|buildingCode|lengthMeters|aisleType|connectedToHead|connectedToTail\n" +
                "AI1|BLD_AI|12.5|LINEAR|A00|A02";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/aisles/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/aisles/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));
    }

    /**
     * Tests the update of an existing aisle when the incoming data differs.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingAisle_ChangedLength() {
        createBuilding("S_AI", "BLD_AI");
        given()
                .body("code|buildingCode|lengthMeters|aisleType|connectedToHead|connectedToTail\nAI1|BLD_AI|12.5|LINEAR|A00|A02")
                .contentType(ContentType.TEXT)
                .when()
                .post("/aisles/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body("code|buildingCode|lengthMeters|aisleType|connectedToHead|connectedToTail\nAI1|BLD_AI|20|LINEAR|A00|A02")
                .contentType(ContentType.TEXT)
                .when()
                .post("/aisles/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        Aisle updated = Aisle.find("code", "AI1").firstResult();
        assertNotNull(updated);
        assertEquals(20.0, updated.lengthMeters, 0.0001);
    }

    /**
     * Tests the error handling when the referenced building does not exist.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportAisle_UnknownBuilding_Error() {
        String csvContent = "code|buildingCode|lengthMeters|aisleType|connectedToHead|connectedToTail\n" +
                "AI_X|BLD_UNKNOWN|12.5|LINEAR||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/aisles/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Building with code 'BLD_UNKNOWN' not found"));

        assertNull(Aisle.find("code", "AI_X").firstResult());
    }

    /**
     * Tests the 1-by-1 fallback mechanism with a mix of valid and invalid lines.
     * <p>
     * This validates that the fallback context contains the referenced building.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportAisles_FallbackMixedLines() {
        createBuilding("S_AI", "BLD_AI");
        String csvContent = "code|buildingCode|lengthMeters|aisleType|connectedToHead|connectedToTail\n" +
                "AI_OK|BLD_AI|12.5|LINEAR||\n" +
                "AI_BAD|BLD_UNKNOWN|12.5|LINEAR||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/aisles/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"errors\""))
                .body(containsString("BLD_UNKNOWN"));

        assertNotNull(Aisle.find("code", "AI_OK").firstResult());
        assertNull(Aisle.find("code", "AI_BAD").firstResult());
    }

    /**
     * Tests the processing logic when the input CSV contains no data lines (only header).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportAisles_EmptyInput() {
        String csvContent = "code|buildingCode|lengthMeters|aisleType|connectedToHead|connectedToTail\n";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/aisles/import")
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
    void testImportAisles_TooFewColumns() {
        String csvContent = "code|buildingCode|lengthMeters|aisleType|connectedToHead|connectedToTail\n" +
                "AI1|BLD_AI|12.5";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/aisles/import")
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
        String csvContent = "code|buildingCode|lengthMeters|aisleType|connectedToHead|connectedToTail\n" +
                "AI1|BLD_AI|12.5|LINEAR||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/aisles/import")
                .then()
                .statusCode(401);
    }
}
