package com.intermarche.e2e.graphql.store;

import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.graphql.store.EmployeeResource;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.util.List;
import java.util.NoSuchElementException;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link EmployeeResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class EmployeeResourceTest {

    @Inject
    EmployeeResource employeeResource;

    /**
     * Creates and persists an employee directly via the entity.
     */
    private Employee persistEmployee(String badgeId, Store store) {
        Employee employee = new Employee();
        employee.firstName = "John";
        employee.lastName = "Doe";
        employee.badgeId = badgeId;
        employee.email = badgeId.toLowerCase() + "@test.com";
        employee.password = BCrypt.hashpw("secret", BCrypt.gensalt());
        employee.role = Employee.EmployeeRole.PICKER;
        employee.store = store;
        employee.persist();
        return employee;
    }

    // --------------------------------------------------
    // Query Tests (Requires MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testAllEmployees() {
        Store store = DomainUtils.createAndPersistStore("STORE_E1", 48.0, 2.0);
        persistEmployee("BADGE_E01", store);

        List<Employee> employees = employeeResource.allEmployees();
        assertNotNull(employees);
        assertFalse(employees.isEmpty(), "Should return at least one employee");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testEmployeeById_Success() {
        Store store = DomainUtils.createAndPersistStore("STORE_E2", 48.0, 2.0);
        Employee existing = persistEmployee("BADGE_E02", store);

        Employee found = employeeResource.employee(existing.id);
        assertNotNull(found);
        assertEquals("BADGE_E02", found.badgeId);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testEmployeeById_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            employeeResource.employee(9999L);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testEmployeeByBadgeId_Success() {
        Store store = DomainUtils.createAndPersistStore("STORE_E3", 48.0, 2.0);
        persistEmployee("BADGE_E03", store);

        Employee found = employeeResource.employeeByBadgeId("BADGE_E03");
        assertNotNull(found);
        assertEquals("BADGE_E03", found.badgeId);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testEmployeeByBadgeId_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            employeeResource.employeeByBadgeId("BADGE_99");
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Create (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateEmployee_AllFields_Success() throws GraphQLException {
        Store store = DomainUtils.createAndPersistStore("STORE_E4", 48.0, 2.0);

        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.firstName = "Jane";
        input.lastName = "Smith";
        input.password = "myPassword123";
        input.role = "manager"; // lowercase on purpose: must be uppercased
        input.storeId = store.id;
        input.badgeId = "BADGE_E10";
        input.email = "jane@test.com";
        input.active = false;

        Employee created = employeeResource.createEmployee(input);

        assertNotNull(created);
        assertNotNull(created.id);
        assertEquals("Jane", created.firstName);
        assertEquals("Smith", created.lastName);
        assertEquals("BADGE_E10", created.badgeId);
        assertEquals("jane@test.com", created.email);
        assertEquals(Employee.EmployeeRole.MANAGER, created.role);
        assertFalse(created.active, "Active flag should be set to false");
        assertNotNull(created.store);
        assertEquals(store.id, created.store.id);

        // Password must be hashed, not stored in clear
        assertNotNull(created.password);
        assertNotEquals("myPassword123", created.password);
        assertTrue(BCrypt.checkpw("myPassword123", created.password), "Password should be a valid BCrypt hash");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateEmployee_ActiveDefaultsToTrue() throws GraphQLException {
        Store store = DomainUtils.createAndPersistStore("STORE_E13", 48.0, 2.0);

        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.firstName = "Bob";
        input.lastName = "Martin";
        input.password = "pass";
        input.badgeId = "BADGE_E11";
        input.storeId = store.id; // mandatory
        // active is null: must default to true

        Employee created = employeeResource.createEmployee(input);

        assertTrue(created.active, "Active should default to true when null");
        assertEquals(store.id, created.store.id);
        assertNull(created.role);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateEmployee_NullStoreId() {
        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.firstName = "No";
        input.lastName = "Store";
        input.password = "pass";
        input.badgeId = "BADGE_E17";
        input.storeId = null; // mandatory

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            employeeResource.createEmployee(input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Store ID is mandatory"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateEmployee_DuplicateBadgeId() {
        Store store = DomainUtils.createAndPersistStore("STORE_E5", 48.0, 2.0);
        persistEmployee("BADGE_E12", store);

        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.firstName = "Dup";
        input.lastName = "Dup";
        input.password = "pass";
        input.badgeId = "BADGE_E12";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            employeeResource.createEmployee(input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateEmployee_NullPassword() {
        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.firstName = "No";
        input.lastName = "Pass";
        input.badgeId = "BADGE_E13";
        input.password = null;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            employeeResource.createEmployee(input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Password cannot be empty"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateEmployee_BlankPassword() {
        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.firstName = "Blank";
        input.lastName = "Pass";
        input.badgeId = "BADGE_E14";
        input.password = "   ";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            employeeResource.createEmployee(input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateEmployee_InvalidRole() {
        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.firstName = "Bad";
        input.lastName = "Role";
        input.password = "pass";
        input.badgeId = "BADGE_E15";
        input.role = "SUPERUSER";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            employeeResource.createEmployee(input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateEmployee_StoreNotFound() {
        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.firstName = "Ghost";
        input.lastName = "Store";
        input.password = "pass";
        input.badgeId = "BADGE_E16";
        input.storeId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            employeeResource.createEmployee(input);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Update (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateEmployee_AllFields_Success() throws GraphQLException {
        Store store = DomainUtils.createAndPersistStore("STORE_E6", 48.0, 2.0);
        Store otherStore = DomainUtils.createAndPersistStore("STORE_E7", 48.0, 2.0);
        Employee existing = persistEmployee("BADGE_E20", store);
        String oldHash = existing.password;

        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.firstName = "Updated";
        input.lastName = "Name";
        input.email = "updated@test.com";
        input.badgeId = "BADGE_E21";
        input.active = false;
        input.password = "newPassword";
        input.role = "ADMIN";
        input.storeId = otherStore.id;

        Employee updated = employeeResource.updateEmployee(existing.id, input);

        assertEquals("Updated", updated.firstName);
        assertEquals("Name", updated.lastName);
        assertEquals("updated@test.com", updated.email);
        assertEquals("BADGE_E21", updated.badgeId);
        assertFalse(updated.active);
        assertEquals(Employee.EmployeeRole.ADMIN, updated.role);
        assertEquals(otherStore.id, updated.store.id);

        // Password must be re-hashed
        assertNotEquals(oldHash, updated.password);
        assertTrue(BCrypt.checkpw("newPassword", updated.password));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateEmployee_SameBadgeId_NoConflict() throws GraphQLException {
        Store store = DomainUtils.createAndPersistStore("STORE_E8", 48.0, 2.0);
        Employee existing = persistEmployee("BADGE_E22", store);

        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.badgeId = "BADGE_E22"; // same badge: uniqueness check must be skipped
        input.firstName = "Renamed";

        Employee updated = employeeResource.updateEmployee(existing.id, input);
        assertEquals("BADGE_E22", updated.badgeId);
        assertEquals("Renamed", updated.firstName);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateEmployee_ConflictingBadgeId() {
        Store store = DomainUtils.createAndPersistStore("STORE_E9", 48.0, 2.0);
        Employee existing = persistEmployee("BADGE_E23", store);
        persistEmployee("BADGE_E24", store);

        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.badgeId = "BADGE_E24";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            employeeResource.updateEmployee(existing.id, input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateEmployee_NotFound() {
        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.firstName = "Ghost";

        assertThrows(NoSuchElementException.class, () -> {
            employeeResource.updateEmployee(9999L, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateEmployee_StoreNotFound() {
        Store store = DomainUtils.createAndPersistStore("STORE_E10", 48.0, 2.0);
        Employee existing = persistEmployee("BADGE_E25", store);

        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.storeId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            employeeResource.updateEmployee(existing.id, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateEmployee_PartialUpdate_KeepsPasswordAndFields() throws GraphQLException {
        Store store = DomainUtils.createAndPersistStore("STORE_E11", 48.0, 2.0);
        Employee existing = persistEmployee("BADGE_E26", store);
        String oldHash = existing.password;

        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.lastName = "OnlyLastName"; // only lastName changes

        Employee updated = employeeResource.updateEmployee(existing.id, input);
        assertEquals("OnlyLastName", updated.lastName);
        assertEquals("John", updated.firstName, "First name should remain unchanged");
        assertEquals("BADGE_E26", updated.badgeId, "Badge should remain unchanged");
        assertEquals(oldHash, updated.password, "Password should remain unchanged when not provided");
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteEmployee_Success() throws GraphQLException {
        Store store = DomainUtils.createAndPersistStore("STORE_E12", 48.0, 2.0);
        Employee existing = persistEmployee("BADGE_E27", store);

        boolean result = employeeResource.deleteEmployee(existing.id);
        assertTrue(result, "Delete should return true");
        assertNull(Employee.findById(existing.id));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteEmployee_NotFound() throws GraphQLException {
        boolean result = employeeResource.deleteEmployee(9999L);
        assertFalse(result, "Delete should return false for non-existent ID");
    }

    // --------------------------------------------------
    // Record Tests
    // --------------------------------------------------

    @Test
    void testEmployeeRecord_ToString() {
        EmployeeResource.EmployeeRecord record = new EmployeeResource.EmployeeRecord();
        record.firstName = "John";
        record.lastName = "Doe";
        record.badgeId = "BADGE_01";
        record.email = "john@test.com";
        record.role = "PICKER";
        record.storeId = 7L;
        record.active = true;

        assertEquals("EmployeeRecord [firstName=John, lastName=Doe, badgeId=BADGE_01, email=john@test.com, role=PICKER, storeId=7, active=true]",
                record.toString());
    }

    // --------------------------------------------------
    // Store scope of a manager
    // --------------------------------------------------

    /**
     * A manager only reads the employees of their store.
     */
    @Test
    @TestSecurity(user = "BADGE_ES_MGR", roles = {"MANAGER"})
    void managerOnlyReadsTheEmployeesOfTheirStore() {
        Store own = DomainUtils.createAndPersistStore("STORE_ES_OWN", 48.0, 2.0);
        Store other = DomainUtils.createAndPersistStore("STORE_ES_OTHER", 48.0, 2.0);
        Employee manager = persistEmployee("BADGE_ES_MGR", own);
        Employee colleague = persistEmployee("BADGE_ES_OWN", own);
        Employee foreign = persistEmployee("BADGE_ES_OTH", other);

        List<Long> ids = employeeResource.allEmployees().stream().map(e -> e.id).toList();
        assertTrue(ids.containsAll(List.of(manager.id, colleague.id)));
        assertFalse(ids.contains(foreign.id));
        assertEquals(colleague.id, employeeResource.employee(colleague.id).id);
        assertEquals(colleague.id, employeeResource.employeeByBadgeId("BADGE_ES_OWN").id);
        assertThrows(NoSuchElementException.class, () -> employeeResource.employee(foreign.id));
        assertThrows(NoSuchElementException.class, () -> employeeResource.employeeByBadgeId("BADGE_ES_OTH"));
    }
}
