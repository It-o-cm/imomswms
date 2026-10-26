package com.intermarche.e2e.imports.store;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.PlanNode;
import com.intermarche.backoffice.domain.store.PlanNodeType;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.imports.store.PlanNodeCsvResource;
import com.intermarche.e2e.imports.CsvImportTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import org.hamcrest.Matchers;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link PlanNodeCsvResource}.
 * <p>
 * Tests the CSV import endpoint for Plan Nodes, covering creation, updates, checksum
 * optimization, the 1-by-1 fallback mechanism and error handling.
 */
@QuarkusTest
public class PlanNodeCsvResourceTest {

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
     * Tests the successful import of new plan nodes with valid data.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewNodesSuccess() {
        createBuilding("S_PN", "BLD_PN");
        String csvContent = "code|buildingCode|label|nodeType|x|y\n" +
                "ENTRANCE|BLD_PN|Entrée|ENTRANCE|-83|242\n" +
                "HUB|BLD_PN|Hub Central|INTERSECTION|-83|82";

        given()
                // Explicit UTF-8 bytes: the importer reads the stream as UTF-8
                .body(csvContent.getBytes(StandardCharsets.UTF_8))
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-nodes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        PlanNode entrance = PlanNode.find("code", "ENTRANCE").firstResult();
        assertNotNull(entrance);
        assertEquals("Entrée", entrance.label);
        assertEquals(PlanNodeType.ENTRANCE, entrance.nodeType);
        assertEquals(-83.0, entrance.x, 0.0001);
        assertEquals(242.0, entrance.y, 0.0001);
        assertEquals("BLD_PN", entrance.building.buildingCode);

        PlanNode hub = PlanNode.find("code", "HUB").firstResult();
        assertNotNull(hub);
        assertEquals(PlanNodeType.INTERSECTION, hub.nodeType);
    }

    /**
     * Tests that re-importing an identical CSV does not trigger any update.
     * <p>
     * Validates the checksum consistency between the entity and the importer.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testReimportIdenticalCsv_NoUpdate() {
        createBuilding("S_PN", "BLD_PN");
        String csvContent = "code|buildingCode|label|nodeType|x|y\n" +
                "ENTRANCE|BLD_PN|Entrée|ENTRANCE|-83|242";

        given()
                // Explicit UTF-8 bytes: the importer reads the stream as UTF-8
                .body(csvContent.getBytes(StandardCharsets.UTF_8))
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-nodes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body(csvContent.getBytes(StandardCharsets.UTF_8))
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-nodes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));
    }

    /**
     * Tests the update of an existing plan node when the incoming data differs.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingNode_ChangedLabel() {
        createBuilding("S_PN", "BLD_PN");
        given()
                // Explicit UTF-8 bytes: the importer reads the stream as UTF-8
                .body("code|buildingCode|label|nodeType|x|y\nENTRANCE|BLD_PN|Entrée|ENTRANCE|-83|242".getBytes(StandardCharsets.UTF_8))
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-nodes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body("code|buildingCode|label|nodeType|x|y\nENTRANCE|BLD_PN|Entrée Principale|ENTRANCE|-83|242".getBytes(StandardCharsets.UTF_8))
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-nodes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        PlanNode updated = PlanNode.find("code", "ENTRANCE").firstResult();
        assertNotNull(updated);
        assertEquals("Entrée Principale", updated.label);
    }

    /**
     * Tests the error handling when the referenced building does not exist.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNode_UnknownBuilding_Error() {
        String csvContent = "code|buildingCode|label|nodeType|x|y\n" +
                "N_X|BLD_UNKNOWN|Node|DEADEND|0|0";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-nodes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Building with code 'BLD_UNKNOWN' not found"));

        assertNull(PlanNode.find("code", "N_X").firstResult());
    }

    /**
     * Tests the error handling when the node type is not a valid enum value.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNode_InvalidNodeType_Error() {
        createBuilding("S_PN", "BLD_PN");
        String csvContent = "code|buildingCode|label|nodeType|x|y\n" +
                "N_BAD|BLD_PN|Node|UNKNOWN|0|0";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-nodes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""));

        assertNull(PlanNode.find("code", "N_BAD").firstResult());
    }

    /**
     * Tests the 1-by-1 fallback mechanism with a mix of valid and invalid lines.
     * <p>
     * This validates that the fallback context contains the referenced building.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNodes_FallbackMixedLines() {
        createBuilding("S_PN", "BLD_PN");
        String csvContent = "code|buildingCode|label|nodeType|x|y\n" +
                "N_OK|BLD_PN|Node|DEADEND|0|0\n" +
                "N_BAD|BLD_UNKNOWN|Node|DEADEND|0|0";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-nodes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"errors\""))
                .body(containsString("BLD_UNKNOWN"));

        assertNotNull(PlanNode.find("code", "N_OK").firstResult());
        assertNull(PlanNode.find("code", "N_BAD").firstResult());
    }

    /**
     * Tests the processing logic when the input CSV contains no data lines (only header).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNodes_EmptyInput() {
        String csvContent = "code|buildingCode|label|nodeType|x|y\n";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-nodes/import")
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
    void testImportNodes_TooFewColumns() {
        String csvContent = "code|buildingCode|label|nodeType|x|y\n" +
                "N1|BLD_PN|Node|DEADEND";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-nodes/import")
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
        String csvContent = "code|buildingCode|label|nodeType|x|y\n" +
                "N1|BLD_PN|Node|DEADEND|0|0";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/plan-nodes/import")
                .then()
                .statusCode(401);
    }
}
