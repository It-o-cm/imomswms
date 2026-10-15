package com.intermarche.e2e.imports.store;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.Tote;
import com.intermarche.backoffice.imports.store.ToteCsvResource;
import com.intermarche.e2e.imports.CsvImportTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import java.math.BigDecimal;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link ToteCsvResource}.
 * <p>
 * Tests the CSV import endpoint for Totes, covering creation, updates, checksum
 * optimization, the 1-by-1 fallback mechanism and error handling.
 */
@QuarkusTest
public class ToteCsvResourceTest {

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
     * Cleans the database after each test so that no committed row (tote, store,
     * handle or event) leaks into the next test class.
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
     * Tests the successful import of new totes with valid data.
     * <p>
     * Verifies that totes are created with correct attributes and store attachment.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewTotesSuccess() {
        createStore("S_TT");
        String csvContent = "code|storeCode|volume|toteType\n" +
                "TT1|S_TT|45.5|STANDARD\n" +
                "TT2|S_TT|30|INSULATED";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/totes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        Tote t1 = Tote.findByCode("TT1");
        assertNotNull(t1);
        assertEquals(0, new BigDecimal("45.5").compareTo(t1.volume));
        assertEquals(Tote.ToteType.STANDARD, t1.toteType);
        assertEquals("S_TT", t1.store.code);

        Tote t2 = Tote.findByCode("TT2");
        assertNotNull(t2);
        assertEquals(0, new BigDecimal("30").compareTo(t2.volume));
        assertEquals(Tote.ToteType.INSULATED, t2.toteType);
    }

    /**
     * Tests that re-importing an identical CSV does not trigger any update.
     * <p>
     * Validates the checksum consistency between the entity and the importer.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testReimportIdenticalCsv_NoUpdate() {
        createStore("S_TT");
        String csvContent = "code|storeCode|volume|toteType\n" +
                "TT1|S_TT|45.5|STANDARD";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/totes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/totes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));
    }

    /**
     * Tests the update of an existing tote when the incoming data differs.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingTote_ChangedVolume() {
        createStore("S_TT");
        given()
                .body("code|storeCode|volume|toteType\nTT1|S_TT|45.5|STANDARD")
                .contentType(ContentType.TEXT)
                .when()
                .post("/totes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body("code|storeCode|volume|toteType\nTT1|S_TT|50|STANDARD")
                .contentType(ContentType.TEXT)
                .when()
                .post("/totes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        Tote updated = Tote.findByCode("TT1");
        assertNotNull(updated);
        assertEquals(0, new BigDecimal("50").compareTo(updated.volume));
    }

    /**
     * Tests the error handling when the referenced store does not exist.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportTote_UnknownStore_Error() {
        String csvContent = "code|storeCode|volume|toteType\n" +
                "TT_X|S_UNKNOWN|45.5|STANDARD";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/totes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Store with code 'S_UNKNOWN' not found"));

        assertNull(Tote.findByCode("TT_X"));
    }

    /**
     * Tests the error handling when the tote type is not a valid enum value.
     * <p>
     * The unknown type is ignored during population, leaving a null mandatory field,
     * which triggers a validation error and rolls the line back.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportTote_InvalidToteType_Error() {
        createStore("S_TT");
        String csvContent = "code|storeCode|volume|toteType\n" +
                "TT_BAD|S_TT|45.5|INVALID_TYPE";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/totes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("TT_BAD"));

        assertNull(Tote.findByCode("TT_BAD"));
    }

    /**
     * Tests the 1-by-1 fallback mechanism with a mix of valid and invalid lines.
     * <p>
     * The invalid line (unknown store) rolls the chunk back; the fallback must
     * still create the valid line. This validates that the fallback context
     * contains the referenced store.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportTotes_FallbackMixedLines() {
        createStore("S_TT");
        String csvContent = "code|storeCode|volume|toteType\n" +
                "TT_OK|S_TT|45.5|STANDARD\n" +
                "TT_BAD|S_UNKNOWN|45.5|STANDARD";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/totes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("S_UNKNOWN"));

        assertNotNull(Tote.findByCode("TT_OK"));
        assertNull(Tote.findByCode("TT_BAD"));
    }

    /**
     * Tests the processing logic when the input CSV contains no data lines (only header).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportTotes_EmptyInput() {
        String csvContent = "code|storeCode|volume|toteType\n";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/totes/import")
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
    void testImportTotes_TooFewColumns() {
        String csvContent = "code|storeCode|volume|toteType\n" +
                "TT1|S_TT|45.5";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/totes/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("not enough columns"));

        assertNull(Tote.findByCode("TT1"));
    }

    /**
     * Tests the security configuration to ensure access is denied for non-admin users.
     */
    @Test
    void testSecurity_AccessDeniedForNonAdmin() {
        String csvContent = "code|storeCode|volume|toteType\n" +
                "TT1|S_TT|45.5|STANDARD";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/totes/import")
                .then()
                .statusCode(401);
    }
}
