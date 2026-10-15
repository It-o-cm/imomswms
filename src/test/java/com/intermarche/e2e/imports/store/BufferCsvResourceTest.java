package com.intermarche.e2e.imports.store;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.imports.store.BufferCsvResource;
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
 * Test class for {@link BufferCsvResource}.
 * <p>
 * Tests the CSV import endpoint for Buffers, covering creation, updates, checksum
 * optimization, the 1-by-1 fallback mechanism and error handling.
 */
@QuarkusTest
public class BufferCsvResourceTest {

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
     * Tests the successful import of new buffers with valid data.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewBuffersSuccess() {
        createBuilding("S_BF", "BLD_BF");
        String csvContent = "bufferCode|buildingCode|purpose|ambient|chilled|frozen|maxWeightKg|locationDescription|active\n" +
                "BF1|BLD_BF|STAGING|10|5|2|250.5|Dock Nord|true\n" +
                "BF2|BLD_BF|RECEIPT|20|0|0|||false";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/buffers/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        Buffer b1 = Buffer.findByCode("BF1");
        assertNotNull(b1);
        assertEquals(Buffer.BufferPurpose.STAGING, b1.purpose);
        assertEquals(10, b1.ambientCapacity);
        assertEquals(5, b1.chilledCapacity);
        assertEquals(2, b1.frozenCapacity);
        assertEquals(250.5, b1.maxWeightKg, 0.0001);
        assertEquals("Dock Nord", b1.locationDescription);
        assertTrue(b1.active);
        assertEquals("BLD_BF", b1.building.buildingCode);

        Buffer b2 = Buffer.findByCode("BF2");
        assertNotNull(b2);
        assertEquals(Buffer.BufferPurpose.RECEIPT, b2.purpose);
        assertEquals(20, b2.ambientCapacity);
        assertNull(b2.maxWeightKg);
        assertNull(b2.locationDescription);
        assertFalse(b2.active);
    }

    /**
     * Tests that re-importing an identical CSV does not trigger any update.
     * <p>
     * Validates the checksum consistency between the entity and the importer.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testReimportIdenticalCsv_NoUpdate() {
        createBuilding("S_BF", "BLD_BF");
        String csvContent = "bufferCode|buildingCode|purpose|ambient|chilled|frozen|maxWeightKg|locationDescription|active\n" +
                "BF1|BLD_BF|STAGING|10|5|2|250.5|Dock Nord|true";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/buffers/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/buffers/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));
    }

    /**
     * Tests the update of an existing buffer when the incoming data differs.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingBuffer_ChangedCapacity() {
        createBuilding("S_BF", "BLD_BF");
        given()
                .body("bufferCode|buildingCode|purpose|ambient|chilled|frozen|maxWeightKg|locationDescription|active\nBF1|BLD_BF|STAGING|10|5|2|250.5|Dock Nord|true")
                .contentType(ContentType.TEXT)
                .when()
                .post("/buffers/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body("bufferCode|buildingCode|purpose|ambient|chilled|frozen|maxWeightKg|locationDescription|active\nBF1|BLD_BF|STAGING|20|5|2|250.5|Dock Nord|true")
                .contentType(ContentType.TEXT)
                .when()
                .post("/buffers/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        Buffer updated = Buffer.findByCode("BF1");
        assertNotNull(updated);
        assertEquals(20, updated.ambientCapacity);
    }

    /**
     * Tests the error handling when the referenced building does not exist.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportBuffer_UnknownBuilding_Error() {
        String csvContent = "bufferCode|buildingCode|purpose|ambient|chilled|frozen|maxWeightKg|locationDescription|active\n" +
                "BF_X|BLD_UNKNOWN|STAGING|10|5|2|250.5|Dock|true";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/buffers/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Building with code 'BLD_UNKNOWN' not found"));

        assertNull(Buffer.findByCode("BF_X"));
    }

    /**
     * Tests the 1-by-1 fallback mechanism with a mix of valid and invalid lines.
     * <p>
     * This validates that the fallback context contains the "BUFFERS" map
     * (without it, every fallback line would fail with a NullPointerException).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportBuffers_FallbackMixedLines() {
        createBuilding("S_BF", "BLD_BF");
        String csvContent = "bufferCode|buildingCode|purpose|ambient|chilled|frozen|maxWeightKg|locationDescription|active\n" +
                "BF_OK|BLD_BF|STAGING|10|5|2|250.5|Dock|true\n" +
                "BF_BAD|BLD_UNKNOWN|STAGING|10|5|2|250.5|Dock|true";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/buffers/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"errors\""))
                .body(containsString("BLD_UNKNOWN"));

        assertNotNull(Buffer.findByCode("BF_OK"));
        assertNull(Buffer.findByCode("BF_BAD"));
    }

    /**
     * Tests the processing logic when the input CSV contains no data lines (only header).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportBuffers_EmptyInput() {
        String csvContent = "bufferCode|buildingCode|purpose|ambient|chilled|frozen|maxWeightKg|locationDescription|active\n";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/buffers/import")
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
    void testImportBuffers_TooFewColumns() {
        String csvContent = "bufferCode|buildingCode|purpose|ambient|chilled|frozen|maxWeightKg|locationDescription|active\n" +
                "BF1|BLD_BF|STAGING|10|5|2";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/buffers/import")
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
        String csvContent = "bufferCode|buildingCode|purpose|ambient|chilled|frozen|maxWeightKg|locationDescription|active\n" +
                "BF1|BLD_BF|STAGING|10|5|2|250.5|Dock|true";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/buffers/import")
                .then()
                .statusCode(401);
    }
}
