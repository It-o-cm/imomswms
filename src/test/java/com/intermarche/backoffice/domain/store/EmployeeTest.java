package com.intermarche.backoffice.domain.store;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Employee} business logic and {@link com.intermarche.backoffice.domain.auth.Account} contract.
 * Pure unit tests — no database, no Quarkus.
 */
class EmployeeTest {

    private Employee createEmployee() {
        Employee employee = new Employee();
        employee.badgeId = "AS10";
        employee.firstName = "John";
        employee.lastName = "Doe";
        employee.email = "AS10@test.com";
        employee.password = "hashed-password";
        employee.role = Employee.EmployeeRole.PICKER;
        Store store = new Store();
        store.id = 1L;
        store.code = "S001";
        store.name = "Test Store";
        employee.store = store;
        return employee;
    }

    // --------------------------------------------------
    // EmployeeRole enum
    // --------------------------------------------------

    @Test
    void employeeRole_hasFourValues() {
        assertEquals(4, Employee.EmployeeRole.values().length);
    }

    @Test
    void employeeRole_valueOf() {
        assertEquals(Employee.EmployeeRole.ADMIN, Employee.EmployeeRole.valueOf("ADMIN"));
        assertEquals(Employee.EmployeeRole.MANAGER, Employee.EmployeeRole.valueOf("MANAGER"));
        assertEquals(Employee.EmployeeRole.PICKER, Employee.EmployeeRole.valueOf("PICKER"));
        assertEquals(Employee.EmployeeRole.CASHIER, Employee.EmployeeRole.valueOf("CASHIER"));
    }

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaultConstructor_activeIsTrue() {
        assertTrue(new Employee().active);
    }

    // --------------------------------------------------
    // Account contract
    // --------------------------------------------------

    @Test
    void getUsername_returnsBadgeId() {
        Employee employee = createEmployee();
        assertEquals("AS10", employee.getUsername());
    }

    @Test
    void getUsername_nullBadgeId_returnsNull() {
        assertNull(new Employee().getUsername());
    }

    @Test
    void getPassword_returnsPassword() {
        Employee employee = createEmployee();
        assertEquals("hashed-password", employee.getPassword());
    }

    @Test
    void getRoles_roleSet_returnsSingleRoleSet() {
        Employee employee = createEmployee();
        assertEquals(Set.of("PICKER"), employee.getRoles());
    }

    @Test
    void getRoles_nullRole_returnsEmptySet() {
        Employee employee = new Employee();
        employee.role = null;
        assertTrue(employee.getRoles().isEmpty());
    }

    @Test
    void isEnabled_activeTrue_returnsTrue() {
        assertTrue(createEmployee().isEnabled());
    }

    @Test
    void isEnabled_activeFalse_returnsFalse() {
        Employee employee = createEmployee();
        employee.active = false;
        assertFalse(employee.isEnabled());
    }

    @Test
    void getSubjectId_returnsIdAsString() {
        Employee employee = createEmployee();
        employee.id = 42L;
        assertEquals("42", employee.getSubjectId());
    }

    @Test
    void getSubjectId_nullId_returnsNullLiteral() {
        assertEquals("null", new Employee().getSubjectId());
    }

    @Test
    void getFullName_returnsFirstAndLastName() {
        assertEquals("John Doe", createEmployee().getFullName());
    }

    @Test
    void getEmail_returnsEmail() {
        assertEquals("AS10@test.com", createEmployee().getEmail());
    }

    @Test
    void getStore_returnsStore() {
        Employee employee = createEmployee();
        assertSame(employee.store, employee.getStore());
    }

    @Test
    void setPassword_updatesPassword() {
        Employee employee = createEmployee();
        employee.setPassword("new-hash");
        assertEquals("new-hash", employee.password);
        assertEquals("new-hash", employee.getPassword());
    }

    @Test
    void present_withStore_containsBadgeNameEmailAndStoreName() {
        String present = createEmployee().present();
        assertTrue(present.contains("AS10"));
        assertTrue(present.contains("John Doe"));
        assertTrue(present.contains("AS10@test.com"));
        assertTrue(present.contains("Test Store"));
    }

    @Test
    void present_withoutStore_showsNoStore() {
        Employee employee = createEmployee();
        employee.store = null;
        assertTrue(employee.present().contains("No Store"));
    }

    @Test
    void passwordResetToken_getAndSet() {
        Employee employee = createEmployee();
        assertNull(employee.getPasswordResetToken());
        employee.setPasswordResetToken("token-123");
        assertEquals("token-123", employee.getPasswordResetToken());
        assertEquals("token-123", employee.passwordResetToken);
    }

    @Test
    void passwordResetTokenExpiry_getAndSet() {
        Employee employee = createEmployee();
        assertNull(employee.getPasswordResetTokenExpiry());
        LocalDateTime expiry = LocalDateTime.of(2026, 10, 15, 12, 0);
        employee.setPasswordResetTokenExpiry(expiry);
        assertEquals(expiry, employee.getPasswordResetTokenExpiry());
        assertEquals(expiry, employee.passwordResetTokenExpiry);
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_changesWithFirstName() {
        Employee employee = createEmployee();
        int checksum1 = employee.getChecksum();
        employee.firstName = "Jane";
        assertNotEquals(checksum1, employee.getChecksum());
    }

    @Test
    void getChecksum_changesWithLastName() {
        Employee employee = createEmployee();
        int checksum1 = employee.getChecksum();
        employee.lastName = "Smith";
        assertNotEquals(checksum1, employee.getChecksum());
    }

    @Test
    void getChecksum_changesWithEmail() {
        Employee employee = createEmployee();
        int checksum1 = employee.getChecksum();
        employee.email = "other@test.com";
        assertNotEquals(checksum1, employee.getChecksum());
    }

    @Test
    void getChecksum_changesWithRole() {
        Employee employee = createEmployee();
        int checksum1 = employee.getChecksum();
        employee.role = Employee.EmployeeRole.ADMIN;
        assertNotEquals(checksum1, employee.getChecksum());
    }

    @Test
    void getChecksum_changesWithBadgeId() {
        Employee employee = createEmployee();
        int checksum1 = employee.getChecksum();
        employee.badgeId = "AS99";
        assertNotEquals(checksum1, employee.getChecksum());
    }

    @Test
    void getChecksum_changesWithActive() {
        Employee employee = createEmployee();
        int checksum1 = employee.getChecksum();
        employee.active = false;
        assertNotEquals(checksum1, employee.getChecksum());
    }

    @Test
    void getChecksum_changesWithStoreCode() {
        Employee employee = createEmployee();
        int checksum1 = employee.getChecksum();
        employee.store.code = "S002";
        assertNotEquals(checksum1, employee.getChecksum());
    }

    @Test
    void getChecksum_ignoresPassword() {
        Employee employee = createEmployee();
        int checksum1 = employee.getChecksum();
        employee.password = "a-completely-different-hash";
        assertEquals(checksum1, employee.getChecksum());
    }

    @Test
    void getChecksum_ignoresPasswordResetFields() {
        Employee employee = createEmployee();
        int checksum1 = employee.getChecksum();
        employee.setPasswordResetToken("token");
        employee.setPasswordResetTokenExpiry(LocalDateTime.now());
        assertEquals(checksum1, employee.getChecksum());
    }

    @Test
    void getChecksum_sameFields_sameValue() {
        assertEquals(createEmployee().getChecksum(), createEmployee().getChecksum());
    }
}
