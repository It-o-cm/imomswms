package com.intermarche.e2e.imports;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.Configuration;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreGroup;
import com.intermarche.backoffice.imports.ConfigurationCsvResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Test class for {@link ConfigurationCsvResource}.
 * <p>
 * Tests the CSV import endpoint for Configurations, covering creation, updates,
 * checksum-based change detection, store/group linking, the 1-by-1 fallback
 * mechanism and error handling.
 * <p>
 * CSV format (6 columns): name|type|priority|specification|storeCodes|groupCodes
 */
@QuarkusTest
public class ConfigurationCsvResourceTest {

    /**
     * The TransactionManager for manual transaction control in tests.
     */
    @Inject
    TransactionManager tm;

    /**
     * The resource under test, injected to exercise protected methods directly.
     */
    @Inject
    ConfigurationCsvResource resource;

    /**
     * Cleans the database before each test to ensure isolation.
     */
    @BeforeEach
    void setUp() {
        CsvImportTestHelper.cleanDatabase(tm);
    }

    /**
     * Cleans the database after each test so that no committed row leaks into the next test class.
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
     * Creates a StoreGroup fixture with the given code.
     *
     * @param code The group code.
     */
    private void createStoreGroup(String code) {
        CsvImportTestHelper.withTransaction(tm, () -> {
            StoreGroup g = new StoreGroup();
            g.code = code;
            g.name = "Group " + code;
            g.persist();
            return null;
        });
    }

    /**
     * Tests the successful import of a new configuration with store and group links.
     * <p>
     * Verifies that the configuration is created with correct attributes and links.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewConfigurationSuccess() {
        createStore("S_CF");
        createStoreGroup("G_CF");
        String csvContent = "name|type|priority|specification|storeCodes|groupCodes\n" +
                "CFG_MAIN|FULFILLMENT|10|{\"fulfiller\":\"default\"}|S_CF|G_CF";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/configurations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        Configuration config = Configuration.find("name", "CFG_MAIN").firstResult();
        assertNotNull(config);
        assertEquals("FULFILLMENT", config.type);
        assertEquals(10, config.priority);
        assertTrue(config.specification.contains("fulfiller"));
        assertEquals(1, config.stores.size());
        assertEquals("S_CF", config.stores.iterator().next().code);
        assertEquals(1, config.storeGroups.size());
        assertEquals("G_CF", config.storeGroups.iterator().next().code);
    }

    /**
     * Tests that re-importing an identical CSV does not trigger any update.
     * <p>
     * Validates the checksum consistency between the entity and the importer:
     * the checksum covers exactly the CSV-imported fields (name, type,
     * specification, priority, storeCodes, groupCodes).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testReimportIdenticalCsv_NoUpdate() {
        createStore("S_CF");
        createStoreGroup("G_CF");
        String csvContent = "name|type|priority|specification|storeCodes|groupCodes\n" +
                "CFG_MAIN|FULFILLMENT|10|{\"fulfiller\":\"default\"}|S_CF|G_CF";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/configurations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/configurations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));
    }

    /**
     * Tests the update of an existing configuration when the priority changes.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingConfiguration_ChangedPriority() {
        given()
                .body("name|type|priority|specification|storeCodes|groupCodes\n" +
                        "CFG_PRIO|FULFILLMENT|10|{\"a\":1}||")
                .contentType(ContentType.TEXT)
                .when()
                .post("/configurations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body("name|type|priority|specification|storeCodes|groupCodes\n" +
                        "CFG_PRIO|FULFILLMENT|20|{\"a\":1}||")
                .contentType(ContentType.TEXT)
                .when()
                .post("/configurations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        Configuration updated = Configuration.find("name", "CFG_PRIO").firstResult();
        assertNotNull(updated);
        assertEquals(20, updated.priority);
    }

    /**
     * Tests that re-importing with different store codes relinks the stores.
     * <p>
     * The storeCodes column is part of the checksum, so changing it must be
     * detected as an update, and the old link must be replaced.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testRelinkStores() {
        createStore("S_CF");
        createStore("S_CF2");
        given()
                .body("name|type|priority|specification|storeCodes|groupCodes\n" +
                        "CFG_REL|FULFILLMENT|10|{\"a\":1}|S_CF|")
                .contentType(ContentType.TEXT)
                .when()
                .post("/configurations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body("name|type|priority|specification|storeCodes|groupCodes\n" +
                        "CFG_REL|FULFILLMENT|10|{\"a\":1}|S_CF2|")
                .contentType(ContentType.TEXT)
                .when()
                .post("/configurations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        Configuration config = Configuration.find("name", "CFG_REL").firstResult();
        assertNotNull(config);
        assertEquals(1, config.stores.size());
        assertEquals("S_CF2", config.stores.iterator().next().code);
    }

    /**
     * Tests that unknown store/group codes are skipped with a warning only.
     * <p>
     * The configuration must still be created, without any error reported,
     * and without any link.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportConfiguration_UnknownStoreAndGroup_WarnOnly() {
        String csvContent = "name|type|priority|specification|storeCodes|groupCodes\n" +
                "CFG_WARN|FULFILLMENT|10|{\"a\":1}|S_UNKNOWN|G_UNKNOWN";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/configurations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        Configuration config = Configuration.find("name", "CFG_WARN").firstResult();
        assertNotNull(config);
        assertTrue(config.stores.isEmpty());
        assertTrue(config.storeGroups.isEmpty());
    }

    /**
     * Tests the error handling when the specification is not valid JSON.
     * <p>
     * The specification column is a JSON column: invalid JSON must roll the line
     * back and report an error.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportConfiguration_InvalidJsonSpecification_Error() {
        String csvContent = "name|type|priority|specification|storeCodes|groupCodes\n" +
                "CFG_BAD|FULFILLMENT|10|not-json||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/configurations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("CFG_BAD"));

        assertNull(Configuration.find("name", "CFG_BAD").firstResult());
    }

    /**
     * Tests the 1-by-1 fallback mechanism with a mix of valid and invalid lines.
     * <p>
     * The invalid line (bad JSON) rolls the chunk back; the fallback must still
     * create the valid line.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportConfigurations_FallbackMixedLines() {
        String csvContent = "name|type|priority|specification|storeCodes|groupCodes\n" +
                "CFG_OK|FULFILLMENT|10|{\"a\":1}||\n" +
                "CFG_BAD|FULFILLMENT|10|not-json||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/configurations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("CFG_BAD"));

        assertNotNull(Configuration.find("name", "CFG_OK").firstResult());
        assertNull(Configuration.find("name", "CFG_BAD").firstResult());
    }

    /**
     * Tests the chunk processing guard when the parsed line list is empty.
     */
    @Test
    void testProcessChunkWithFallback_EmptyList() {
        Map<String, Object> result = resource.processChunkWithFallback(List.of(), Set.of(), new int[2], new ArrayList<>());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Tests the processing logic when the input CSV contains no data lines (only header).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportConfigurations_EmptyInput() {
        String csvContent = "name|type|priority|specification|storeCodes|groupCodes\n";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/configurations/import")
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
    void testImportConfigurations_TooFewColumns() {
        String csvContent = "name|type|priority|specification|storeCodes|groupCodes\n" +
                "CFG_MAIN|FULFILLMENT|10|{\"a\":1}|S_CF";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/configurations/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("not enough columns"));

        assertNull(Configuration.find("name", "CFG_MAIN").firstResult());
    }

    /**
     * Tests the security configuration to ensure access is denied for non-admin users.
     */
    @Test
    void testSecurity_AccessDeniedForNonAdmin() {
        String csvContent = "name|type|priority|specification|storeCodes|groupCodes\n" +
                "CFG_MAIN|FULFILLMENT|10|{\"a\":1}||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/configurations/import")
                .then()
                .statusCode(401);
    }
}
