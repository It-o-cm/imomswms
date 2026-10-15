package com.intermarche.e2e.imports.store;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.imports.store.EmployeeCsvResource;
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
 * Test class for {@link EmployeeCsvResource}.
 * <p>
 * Tests the CSV import endpoint for Employees, covering creation, password hashing,
 * updates, checksum optimization, the 1-by-1 fallback mechanism and error handling.
 */
@QuarkusTest
public class EmployeeCsvResourceTest {

    /**
     * A pre-computed BCrypt hash used to verify that already-hashed passwords are kept as-is.
     */
    private static final String BCRYPT_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

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
     * Tests the successful import of new employees with valid data.
     * <p>
     * Verifies that plain-text passwords are hashed (BCrypt) on creation.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewEmployeesSuccess() {
        createStore("S_EM");
        String csvContent = "badgeId|storeCode|firstName|lastName|email|role|password|active\n" +
                "EM1|S_EM|Jean|Dupont|jean.dupont@example.com|PICKER|secret123|true\n" +
                "EM2|S_EM|Marie|Martin|marie.martin@example.com|MANAGER|pass456|false";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/employees/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        Employee e1 = Employee.findByBadgeId("EM1");
        assertNotNull(e1);
        assertEquals("Jean", e1.firstName);
        assertEquals("Dupont", e1.lastName);
        assertEquals("jean.dupont@example.com", e1.email);
        assertEquals(Employee.EmployeeRole.PICKER, e1.role);
        assertTrue(e1.active);
        assertEquals("S_EM", e1.store.code);
        // Plain text password must be hashed (BCrypt hashes start with "$2")
        assertNotEquals("secret123", e1.password);
        assertTrue(e1.password.startsWith("$2"));

        Employee e2 = Employee.findByBadgeId("EM2");
        assertNotNull(e2);
        assertEquals(Employee.EmployeeRole.MANAGER, e2.role);
        assertFalse(e2.active);
    }

    /**
     * Tests that an already-hashed password (BCrypt prefix) is kept as-is.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportEmployee_PasswordAlreadyHashed_Kept() {
        createStore("S_EM");
        String csvContent = "badgeId|storeCode|firstName|lastName|email|role|password|active\n" +
                "EM1|S_EM|Jean|Dupont|jean.dupont@example.com|PICKER|" + BCRYPT_HASH + "|true";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/employees/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(Matchers.not(containsString("\"errors\"")));

        Employee e = Employee.findByBadgeId("EM1");
        assertNotNull(e);
        assertEquals(BCRYPT_HASH, e.password);
    }

    /**
     * Tests that re-importing an identical CSV does not trigger any update.
     * <p>
     * Validates the checksum consistency between the entity and the importer.
     * The password is deliberately excluded from the checksum (salted hash).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testReimportIdenticalCsv_NoUpdate() {
        createStore("S_EM");
        String csvContent = "badgeId|storeCode|firstName|lastName|email|role|password|active\n" +
                "EM1|S_EM|Jean|Dupont|jean.dupont@example.com|PICKER|secret123|true";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/employees/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/employees/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));
    }

    /**
     * Tests the update of an existing employee when the incoming data differs.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingEmployee_ChangedRole() {
        createStore("S_EM");
        given()
                .body("badgeId|storeCode|firstName|lastName|email|role|password|active\nEM1|S_EM|Jean|Dupont|jean.dupont@example.com|PICKER|secret123|true")
                .contentType(ContentType.TEXT)
                .when()
                .post("/employees/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body("badgeId|storeCode|firstName|lastName|email|role|password|active\nEM1|S_EM|Jean|Dupont|jean.dupont@example.com|MANAGER|secret123|true")
                .contentType(ContentType.TEXT)
                .when()
                .post("/employees/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        Employee updated = Employee.findByBadgeId("EM1");
        assertNotNull(updated);
        assertEquals(Employee.EmployeeRole.MANAGER, updated.role);
    }

    /**
     * Tests the error handling when the referenced store does not exist.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportEmployee_UnknownStore_Error() {
        String csvContent = "badgeId|storeCode|firstName|lastName|email|role|password|active\n" +
                "EM_X|S_UNKNOWN|Jean|Dupont|jean.dupont@example.com|PICKER|secret123|true";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/employees/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Store with code 'S_UNKNOWN' not found"));

        assertNull(Employee.findByBadgeId("EM_X"));
    }

    /**
     * Tests the error handling when the role is not a valid enum value.
     * <p>
     * The unknown role is ignored during population, leaving a null mandatory field,
     * which triggers a validation error and rolls the line back.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportEmployee_InvalidRole_Error() {
        createStore("S_EM");
        String csvContent = "badgeId|storeCode|firstName|lastName|email|role|password|active\n" +
                "EM_BAD|S_EM|Jean|Dupont|bad@example.com|SUPERUSER|secret123|true";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/employees/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("EM_BAD"));

        assertNull(Employee.findByBadgeId("EM_BAD"));
    }

    /**
     * Tests the 1-by-1 fallback mechanism with a mix of valid and invalid lines.
     * <p>
     * This validates that the fallback context contains the referenced store.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportEmployees_FallbackMixedLines() {
        createStore("S_EM");
        String csvContent = "badgeId|storeCode|firstName|lastName|email|role|password|active\n" +
                "EM_OK|S_EM|Jean|Dupont|ok@example.com|PICKER|secret123|true\n" +
                "EM_BAD|S_UNKNOWN|Marie|Martin|bad@example.com|PICKER|secret123|true";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/employees/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"errors\""))
                .body(containsString("S_UNKNOWN"));

        assertNotNull(Employee.findByBadgeId("EM_OK"));
        assertNull(Employee.findByBadgeId("EM_BAD"));
    }

    /**
     * Tests the processing logic when the input CSV contains no data lines (only header).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportEmployees_EmptyInput() {
        String csvContent = "badgeId|storeCode|firstName|lastName|email|role|password|active\n";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/employees/import")
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
        String csvContent = "badgeId|storeCode|firstName|lastName|email|role|password|active\n" +
                "EM1|S_EM|Jean|Dupont|jean.dupont@example.com|PICKER|secret123|true";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/employees/import")
                .then()
                .statusCode(401);
    }
}
