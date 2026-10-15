package com.intermarche.e2e.domain.store;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.persistence.PersistenceException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link Employee}.
 * Covers persistence, unique constraints, Panache queries,
 * Account interface implementation, and getChecksum sensitivity.
 */
@QuarkusTest
public class EmployeeTest {

    // --------------------------------------------------
    // Fixture Helpers
    // --------------------------------------------------

    private Store createStore(String prefix) {
        Store store = new Store();
        store.code = prefix + "-S";
        store.name = "Store " + prefix;
        store.address = new Adresse();
        store.address.streetLine1 = "1 rue de Test";
        store.address.city = "Lyon";
        store.address.postalCode = "69001";
        store.address.country = "France";
        store.persist();
        return store;
    }

    private Employee createEmployee(Store store, String prefix) {
        Employee emp = new Employee();
        emp.firstName = "John";
        emp.lastName = "Doe";
        emp.password = BCrypt.hashpw("1234", BCrypt.gensalt());
        emp.email = prefix + "@test.com";
        emp.role = Employee.EmployeeRole.PICKER;
        emp.store = store;
        emp.badgeId = prefix + "-BADGE";
        emp.persist();
        return emp;
    }

    // --------------------------------------------------
    // Persistence Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void persist_shouldSucceed_withValidData() {
        Store store = createStore("EM01");
        Employee emp = createEmployee(store, "EM01");

        assertNotNull(emp.id);
        assertTrue(emp.active);
        assertEquals(store.id, emp.store.id);
        assertTrue(emp.password.startsWith("$2"));
        assertEquals(60, emp.password.length());
    }

    @Test
    @TestTransaction
    void persist_shouldFail_whenBadgeIdDuplicated() {
        Store store = createStore("EM02");
        createEmployee(store, "EM02");

        Employee duplicate = new Employee();
        duplicate.firstName = "Jane";
        duplicate.lastName = "Smith";
        duplicate.password = BCrypt.hashpw("1234", BCrypt.gensalt());
        duplicate.email = "EM02-other@test.com";
        duplicate.role = Employee.EmployeeRole.CASHIER;
        duplicate.store = store;
        duplicate.badgeId = "EM02-BADGE"; // same badge

        assertThrows(PersistenceException.class, () -> {
            duplicate.persist();
            Panache.getEntityManager().flush();
        });
    }

    @Test
    @TestTransaction
    void persist_shouldFail_whenEmailDuplicated() {
        Store store = createStore("EM03");
        createEmployee(store, "EM03");

        Employee duplicate = new Employee();
        duplicate.firstName = "Jane";
        duplicate.lastName = "Smith";
        duplicate.password = BCrypt.hashpw("1234", BCrypt.gensalt());
        duplicate.email = "EM03@test.com"; // same email
        duplicate.role = Employee.EmployeeRole.CASHIER;
        duplicate.store = store;
        duplicate.badgeId = "EM03-BADGE-2";

        assertThrows(PersistenceException.class, () -> {
            duplicate.persist();
            Panache.getEntityManager().flush();
        });
    }

    // --------------------------------------------------
    // Query Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void findByBadgeId_shouldReturnEmployee() {
        Store store = createStore("EM04");
        Employee emp = createEmployee(store, "EM04");

        Employee found = Employee.findByBadgeId("EM04-BADGE");

        assertNotNull(found);
        assertEquals(emp.id, found.id);
        assertNotNull(found.store);
        assertEquals(store.id, found.store.id);
    }

    @Test
    @TestTransaction
    void findByBadgeId_shouldReturnNull_whenNotFound() {
        assertNull(Employee.findByBadgeId("NO-SUCH-BADGE"));
    }

    @Test
    @TestTransaction
    void findActiveByBadgeId_shouldReturnEmployee_whenActive() {
        Store store = createStore("EM05");
        Employee emp = createEmployee(store, "EM05");
        emp.active = true;

        Employee found = Employee.findActiveByBadgeId("EM05-BADGE");

        assertNotNull(found);
        assertEquals(emp.id, found.id);
    }

    @Test
    @TestTransaction
    void findActiveByBadgeId_shouldReturnNull_whenInactive() {
        Store store = createStore("EM06");
        Employee emp = createEmployee(store, "EM06");
        emp.active = false;

        assertNull(Employee.findActiveByBadgeId("EM06-BADGE"));
    }

    @Test
    @TestTransaction
    void findByStore_shouldReturnOnlyStoreEmployees() {
        Store store1 = createStore("EM07A");
        Store store2 = createStore("EM07B");
        createEmployee(store1, "EM07A");
        createEmployee(store1, "EM07A2");
        createEmployee(store2, "EM07B");

        List<Employee> store1Employees = Employee.findByStore(store1);

        assertEquals(2, store1Employees.size());
        assertTrue(store1Employees.stream().allMatch(e -> e.store.id.equals(store1.id)));
    }

    // --------------------------------------------------
    // Account Interface Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void accountMethods_shouldExposeExpectedValues() {
        Store store = createStore("EM08");
        Employee emp = createEmployee(store, "EM08");
        emp.role = Employee.EmployeeRole.MANAGER;

        assertEquals("EM08-BADGE", emp.getUsername());
        assertEquals(emp.password, emp.getPassword());
        assertEquals(Set.of("MANAGER"), emp.getRoles());
        assertTrue(emp.isEnabled());
        assertEquals(String.valueOf(emp.id), emp.getSubjectId());
        assertEquals("John Doe", emp.getFullName());
        assertEquals("EM08@test.com", emp.getEmail());
        assertEquals(store.id, emp.getStore().id);
    }

    @Test
    @TestTransaction
    void getRoles_shouldReturnEmptySet_whenRoleIsNull() {
        Employee emp = new Employee();
        emp.role = null;

        assertTrue(emp.getRoles().isEmpty());
    }

    @Test
    @TestTransaction
    void isEnabled_shouldReflectActiveFlag() {
        Store store = createStore("EM09");
        Employee emp = createEmployee(store, "EM09");

        assertTrue(emp.isEnabled());
        emp.active = false;
        assertFalse(emp.isEnabled());
    }

    @Test
    @TestTransaction
    void present_shouldContainBadgeNameAndStore() {
        Store store = createStore("EM10");
        Employee emp = createEmployee(store, "EM10");

        String presentation = emp.present();

        assertTrue(presentation.contains("EM10-BADGE"));
        assertTrue(presentation.contains("John Doe"));
        assertTrue(presentation.contains("EM10@test.com"));
        assertTrue(presentation.contains("Store EM10"));
    }

    @Test
    @TestTransaction
    void passwordResetToken_shouldBeSettable() {
        Store store = createStore("EM11");
        Employee emp = createEmployee(store, "EM11");

        assertNull(emp.getPasswordResetToken());
        assertNull(emp.getPasswordResetTokenExpiry());

        LocalDateTime expiry = LocalDateTime.now().plusHours(1);
        emp.setPasswordResetToken("token-123");
        emp.setPasswordResetTokenExpiry(expiry);

        assertEquals("token-123", emp.getPasswordResetToken());
        assertEquals(expiry, emp.getPasswordResetTokenExpiry());
    }

    // --------------------------------------------------
    // Checksum Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenActiveChanges() {
        Store store = createStore("EM12");
        Employee emp = createEmployee(store, "EM12");

        int checksum1 = emp.getChecksum();
        emp.active = false;
        int checksum2 = emp.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenRoleChanges() {
        Store store = createStore("EM13");
        Employee emp = createEmployee(store, "EM13");

        int checksum1 = emp.getChecksum();
        emp.role = Employee.EmployeeRole.ADMIN;
        int checksum2 = emp.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    @TestTransaction
    void getChecksum_shouldNotChangeWhenPasswordResetTokenChanges() {
        // The checksum covers exactly the fields imported by the Employee CSV.
        // The password reset token is not CSV-imported, so it must not affect the checksum.
        Store store = createStore("EM14");
        Employee emp = createEmployee(store, "EM14");

        int checksum1 = emp.getChecksum();
        emp.setPasswordResetToken("token-xyz");
        int checksum2 = emp.getChecksum();

        assertEquals(checksum1, checksum2);
    }

    @Test
    @TestTransaction
    void getChecksum_shouldNotChangeWhenPasswordChanges() {
        // The password is stored as a salted BCrypt hash: it changes on every re-hash,
        // so it is deliberately excluded from the checksum (see EmployeeCsvResource).
        Store store = createStore("EM15");
        Employee emp = createEmployee(store, "EM15");

        int checksum1 = emp.getChecksum();
        emp.password = "$2a$10$anotherHashValueThatIsDifferentFromTheOriginalOne0123456789";
        int checksum2 = emp.getChecksum();

        assertEquals(checksum1, checksum2);
    }

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenCsvFieldChanges() {
        Store store = createStore("EM16");
        Employee emp = createEmployee(store, "EM16");

        int checksum1 = emp.getChecksum();
        emp.firstName = "Jane";
        int checksum2 = emp.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }
}
