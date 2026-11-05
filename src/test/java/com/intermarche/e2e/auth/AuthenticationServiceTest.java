package com.intermarche.e2e.auth;

import com.intermarche.backoffice.auth.AuthenticationService;
import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.auth.Account;
import com.intermarche.backoffice.domain.auth.Administrative;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link AuthenticationService}.
 * Covers account lookup, database authentication (dev path),
 * and the password reset flow (initiate + reset with token).
 */
@QuarkusTest
public class AuthenticationServiceTest {

    @Inject
    AuthenticationService authenticationService;

    @Inject
    io.quarkus.mailer.MockMailbox mailbox;

    /**
     * Reads the reset token sent by mail to an address: the database only holds its digest.
     *
     * @param address the recipient
     * @return the token of the last mail
     */
    private String mailedToken(String address) {
        var mails = mailbox.getMailsSentTo(address);
        assertFalse(mails.isEmpty(), "A reset mail must be sent");
        java.util.regex.Matcher link = java.util.regex.Pattern.compile("/reset-password/([0-9a-f-]{36})").matcher(mails.get(mails.size() - 1).getHtml());
        assertTrue(link.find());
        return link.group(1);
    }

    // --------------------------------------------------
    // Fixture Helpers
    // --------------------------------------------------

    /**
     * Builds store.
     *
     * @param prefix the prefix
     * @return the built value
     */
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

    /**
     * Builds employee.
     *
     * @param store the store
     * @param prefix the prefix
     * @param rawPassword the raw password
     * @return the built value
     */
    private Employee createEmployee(Store store, String prefix, String rawPassword) {
        Employee emp = new Employee();
        emp.firstName = "John";
        emp.lastName = "Doe";
        emp.password = BCrypt.hashpw(rawPassword, BCrypt.gensalt());
        emp.email = prefix + "@test.com";
        emp.role = Employee.EmployeeRole.PICKER;
        emp.store = store;
        emp.badgeId = prefix + "-BADGE";
        emp.persist();
        return emp;
    }

    /**
     * Builds admin.
     *
     * @param prefix the prefix
     * @param rawPassword the raw password
     * @return the built value
     */
    private Administrative createAdmin(String prefix, String rawPassword) {
        Administrative admin = new Administrative();
        admin.username = prefix + "-admin";
        admin.password = BCrypt.hashpw(rawPassword, BCrypt.gensalt());
        admin.fullName = "Admin " + prefix;
        admin.email = prefix + "-admin@test.com";
        admin.roles = java.util.Set.of("ADMIN");
        admin.persist();
        return admin;
    }

    // --------------------------------------------------
    // findAccountByUsername Tests
    // --------------------------------------------------

    /**
     * Checks find account by username: should return administrative.
     */
    @Test
    @TestTransaction
    void findAccountByUsername_shouldReturnAdministrative() {
        createAdmin("AS01", "secret");

        Account account = authenticationService.findAccountByUsername("AS01-admin");

        assertNotNull(account);
        assertInstanceOf(Administrative.class, account);
        assertEquals("AS01-admin", account.getUsername());
    }

    /**
     * Checks find account by username: should return employee by badge id.
     */
    @Test
    @TestTransaction
    void findAccountByUsername_shouldReturnEmployeeByBadgeId() {
        Store store = createStore("AS02");
        createEmployee(store, "AS02", "1234");

        Account account = authenticationService.findAccountByUsername("AS02-BADGE");

        assertNotNull(account);
        assertInstanceOf(Employee.class, account);
        assertEquals("AS02-BADGE", account.getUsername());
    }

    /**
     * Checks find account by username: should return null, when unknown.
     */
    @Test
    @TestTransaction
    void findAccountByUsername_shouldReturnNull_whenUnknown() {
        assertNull(authenticationService.findAccountByUsername("NO-SUCH-USER"));
    }

    /**
     * Checks find account by username: should prefer administrative over employee.
     */
    @Test
    @TestTransaction
    void findAccountByUsername_shouldPreferAdministrativeOverEmployee() {
        Store store = createStore("AS04");
        createAdmin("AS04", "secret");
        // Employee with the same identifier as the admin username
        Employee emp = new Employee();
        emp.firstName = "John";
        emp.lastName = "Doe";
        emp.password = BCrypt.hashpw("1234", BCrypt.gensalt());
        emp.email = "AS04-emp@test.com";
        emp.role = Employee.EmployeeRole.PICKER;
        emp.store = store;
        emp.badgeId = "AS04-admin";
        emp.persist();

        Account account = authenticationService.findAccountByUsername("AS04-admin");

        assertNotNull(account);
        assertInstanceOf(Administrative.class, account);
    }

    // --------------------------------------------------
    // authenticate Tests (database path)
    // --------------------------------------------------

    /**
     * Checks authenticate: should return jwt, for valid employee credentials.
     */
    @Test
    @TestTransaction
    void authenticate_shouldReturnJwt_forValidEmployeeCredentials() {
        Store store = createStore("AS05");
        createEmployee(store, "AS05", "1234");

        String token = authenticationService.authenticate("AS05-BADGE", "1234");

        assertNotNull(token);
        assertFalse(token.isBlank());
        // A JWT is composed of 3 dot-separated parts
        assertEquals(3, token.split("\\.").length);
    }

    /**
     * Checks authenticate: should return jwt, for valid admin credentials.
     */
    @Test
    @TestTransaction
    void authenticate_shouldReturnJwt_forValidAdminCredentials() {
        createAdmin("AS06", "secret");

        String token = authenticationService.authenticate("AS06-admin", "secret");

        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    /**
     * Checks authenticate: should throw, for wrong password.
     */
    @Test
    @TestTransaction
    void authenticate_shouldThrow_forWrongPassword() {
        Store store = createStore("AS07");
        createEmployee(store, "AS07", "1234");

        assertThrows(SecurityException.class,
                () -> authenticationService.authenticate("AS07-BADGE", "wrong"));
    }

    /**
     * Checks authenticate: should throw, for unknown user.
     */
    @Test
    @TestTransaction
    void authenticate_shouldThrow_forUnknownUser() {
        assertThrows(SecurityException.class,
                () -> authenticationService.authenticate("NO-SUCH-USER", "1234"));
    }

    /**
     * Checks authenticate: should throw, for disabled account.
     */
    @Test
    @TestTransaction
    void authenticate_shouldThrow_forDisabledAccount() {
        Store store = createStore("AS09");
        Employee emp = createEmployee(store, "AS09", "1234");
        emp.active = false;

        assertThrows(SecurityException.class,
                () -> authenticationService.authenticate("AS09-BADGE", "1234"));
    }

    // --------------------------------------------------
    // initiatePasswordReset Tests
    // --------------------------------------------------

    /**
     * Checks initiate password reset: should set token and expiry.
     */
    @Test
    @TestTransaction
    void initiatePasswordReset_shouldSetTokenAndExpiry() {
        Store store = createStore("AS10");
        Employee emp = createEmployee(store, "AS10", "1234");

        mailbox.clear();
        authenticationService.initiatePasswordReset("AS10-BADGE");

        assertNotNull(emp.getPasswordResetToken());
        assertFalse(emp.getPasswordResetToken().isBlank());
        // Only the digest of the mailed token is stored
        String mailed = mailedToken("AS10@test.com");
        assertNotEquals(mailed, emp.getPasswordResetToken());
        assertEquals(AuthenticationService.digest(mailed), emp.getPasswordResetToken());
        assertNotNull(emp.getPasswordResetTokenExpiry());
        assertTrue(emp.getPasswordResetTokenExpiry().isAfter(LocalDateTime.now()));
    }

    /**
     * Checks initiate password reset: should do nothing, for unknown user.
     */
    @Test
    @TestTransaction
    void initiatePasswordReset_shouldDoNothing_forUnknownUser() {
        assertDoesNotThrow(() -> authenticationService.initiatePasswordReset("NO-SUCH-USER"));
    }

    // --------------------------------------------------
    // resetPassword Tests
    // --------------------------------------------------

    /**
     * Checks reset password: should update password clear token and return jwt.
     */
    @Test
    @TestTransaction
    void resetPassword_shouldUpdatePasswordClearTokenAndReturnJwt() {
        Store store = createStore("AS11");
        Employee emp = createEmployee(store, "AS11", "old-pin");
        mailbox.clear();
        authenticationService.initiatePasswordReset("AS11-BADGE");
        String token = mailedToken("AS11@test.com");
        assertThrows(SecurityException.class, () -> authenticationService.resetPassword(emp.getPasswordResetToken(), "new-pin"),
                "The stored digest must not work as a token");

        String jwt = authenticationService.resetPassword(token, "new-pin");

        assertNotNull(jwt);
        assertFalse(jwt.isBlank());
        // Token is consumed
        assertNull(emp.getPasswordResetToken());
        assertNull(emp.getPasswordResetTokenExpiry());
        // New password works, old one does not
        assertDoesNotThrow(() -> authenticationService.authenticate("AS11-BADGE", "new-pin"));
        assertThrows(SecurityException.class,
                () -> authenticationService.authenticate("AS11-BADGE", "old-pin"));
        // Sessions opened before the change are revoked
        assertNotNull(emp.getCredentialsChangedAt());
    }

    /**
     * Checks reset password: should throw, for invalid token.
     */
    @Test
    @TestTransaction
    void resetPassword_shouldThrow_forInvalidToken() {
        assertThrows(SecurityException.class,
                () -> authenticationService.resetPassword("not-a-real-token", "new-pin"));
    }

    /**
     * Checks reset password: should throw, for expired token.
     */
    @Test
    @TestTransaction
    void resetPassword_shouldThrow_forExpiredToken() {
        Store store = createStore("AS13");
        Employee emp = createEmployee(store, "AS13", "1234");
        emp.setPasswordResetToken(AuthenticationService.digest("expired-token"));
        emp.setPasswordResetTokenExpiry(LocalDateTime.now().minusMinutes(1));

        assertThrows(SecurityException.class,
                () -> authenticationService.resetPassword("expired-token", "new-pin"));
    }

    /**
     * Checks reset password: should work for administrative account.
     */
    @Test
    @TestTransaction
    void resetPassword_shouldWorkForAdministrativeAccount() {
        Administrative admin = createAdmin("AS14", "old-secret");
        mailbox.clear();
        authenticationService.initiatePasswordReset("AS14-admin");
        String token = mailedToken("AS14-admin@test.com");

        String jwt = authenticationService.resetPassword(token, "new-secret");

        assertNotNull(jwt);
        assertTrue(BCrypt.checkpw("new-secret", admin.getPassword()));
        assertDoesNotThrow(() -> authenticationService.authenticate("AS14-admin", "new-secret"));
    }

    /**
     * Checks reset password: should refuse apassword outside the policy, and keep the token.
     */
    @Test
    @TestTransaction
    void resetPassword_shouldRefuseAPasswordOutsideThePolicy_andKeepTheToken() {
        Store store = createStore("AS15");
        Employee emp = createEmployee(store, "AS15", "1234");
        mailbox.clear();
        authenticationService.initiatePasswordReset("AS15-BADGE");
        String token = mailedToken("AS15@test.com");

        assertThrows(IllegalArgumentException.class, () -> authenticationService.resetPassword(token, ""));
        assertThrows(IllegalArgumentException.class, () -> authenticationService.resetPassword(token, "12"));

        assertNotNull(emp.getPasswordResetToken());
        assertDoesNotThrow(() -> authenticationService.authenticate("AS15-BADGE", "1234"));
    }
}
