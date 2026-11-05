package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.domain.auth.Account;
import com.intermarche.backoffice.domain.auth.Administrative;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuthenticationService} (dev profile logic).
 * Pure unit tests — no database, no Quarkus.
 * Panache static methods are intercepted via {@code mockStatic(PanacheEntityBase.class)}
 * because the static finders are declared on PanacheEntityBase.
 */
class AuthenticationServiceTest {

    private AuthenticationService service;
    private JsonWebToken jwtMock;
    private TokenService tokenServiceMock;

    /**
     * Builds the service in the dev profile with mocked token collaborators.
     */
    @BeforeEach
    void setUp() {
        service = new AuthenticationService();
        jwtMock = mock(JsonWebToken.class);
        tokenServiceMock = mock(TokenService.class);
        service.jwt = jwtMock;
        service.tokenService = tokenServiceMock;
        service.profile = "dev";
    }

    /**
     * Builds a Panache query whose first result is given.
     *
     * @param result the first result
     * @param <T>    the entity type
     * @return the mocked query
     */
    @SuppressWarnings("unchecked")
    private static <T> PanacheQuery<T> mockQuery(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * Builds a picker of store S001.
     *
     * @param badgeId the badge
     * @param active  whether the account is active
     * @return the employee
     */
    private Employee createEmployee(String badgeId, boolean active) {
        Employee employee = new Employee();
        employee.badgeId = badgeId;
        employee.firstName = "John";
        employee.lastName = "Doe";
        employee.email = badgeId + "@test.com";
        employee.role = Employee.EmployeeRole.PICKER;
        employee.active = active;
        Store store = new Store();
        store.id = 1L;
        store.code = "S001";
        store.name = "Test Store";
        employee.store = store;
        return employee;
    }

    // --------------------------------------------------
    // findAccountByUsername
    // --------------------------------------------------

    /**
     * Checks find account by username: admin found, returns admin.
     */
    @Test
    void findAccountByUsername_adminFound_returnsAdmin() {
        Administrative admin = new Administrative();
        admin.username = "root";
        // Mocks must be created BEFORE the static stubbing: mockQuery() uses when(),
        // which would clash with an in-progress static stubbing (UnfinishedStubbing).
        PanacheQuery<Administrative> adminQuery = mockQuery(admin);
        PanacheQuery<Employee> employeeQuery = mockQuery(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("username"), any(Object[].class)))
                    .thenReturn(adminQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("badgeId"), any(Object[].class)))
                    .thenReturn(employeeQuery);

            Account result = service.findAccountByUsername("root");

            assertSame(admin, result);
        }
    }

    /**
     * Checks find account by username: no admin, falls back to employee.
     */
    @Test
    void findAccountByUsername_noAdmin_fallsBackToEmployee() {
        Employee employee = createEmployee("AS10", true);
        PanacheQuery<Administrative> adminQuery = mockQuery(null);
        PanacheQuery<Employee> employeeQuery = mockQuery(employee);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("username"), any(Object[].class)))
                    .thenReturn(adminQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("badgeId"), any(Object[].class)))
                    .thenReturn(employeeQuery);

            Account result = service.findAccountByUsername("AS10");

            assertSame(employee, result);
        }
    }

    /**
     * Checks find account by username: nothing found, returns null.
     */
    @Test
    void findAccountByUsername_nothingFound_returnsNull() {
        PanacheQuery<Administrative> adminQuery = mockQuery(null);
        PanacheQuery<Employee> employeeQuery = mockQuery(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("username"), any(Object[].class)))
                    .thenReturn(adminQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("badgeId"), any(Object[].class)))
                    .thenReturn(employeeQuery);

            assertNull(service.findAccountByUsername("NOBODY"));
        }
    }

    // --------------------------------------------------
    // authenticate (dev)
    // --------------------------------------------------

    /**
     * Checks authenticate: valid credentials, returns token.
     */
    @Test
    void authenticate_validCredentials_returnsToken() {
        Employee employee = createEmployee("AS10", true);
        employee.password = org.mindrot.jbcrypt.BCrypt.hashpw("1234", org.mindrot.jbcrypt.BCrypt.gensalt());

        AuthenticationService spyService = spy(service);
        doReturn(employee).when(spyService).findAccountByUsername("AS10");
        when(tokenServiceMock.generateToken(employee)).thenReturn("jwt-token");

        String token = spyService.authenticate("AS10", "1234");

        assertEquals("jwt-token", token);
    }

    /**
     * Checks authenticate: null username, throws security exception.
     */
    @Test
    void authenticate_nullUsername_throwsSecurityException() {
        SecurityException exception = assertThrows(SecurityException.class,
                () -> service.authenticate(null, "1234"));
        assertEquals("Invalid credentials", exception.getMessage());
    }

    /**
     * Checks authenticate: null password, throws security exception.
     */
    @Test
    void authenticate_nullPassword_throwsSecurityException() {
        SecurityException exception = assertThrows(SecurityException.class,
                () -> service.authenticate("AS10", null));
        assertEquals("Invalid credentials", exception.getMessage());
    }

    /**
     * Checks authenticate: unknown user, throws security exception.
     */
    @Test
    void authenticate_unknownUser_throwsSecurityException() {
        AuthenticationService spyService = spy(service);
        doReturn(null).when(spyService).findAccountByUsername("GHOST");

        SecurityException exception = assertThrows(SecurityException.class,
                () -> spyService.authenticate("GHOST", "1234"));
        assertEquals("Invalid credentials", exception.getMessage());
    }

    /**
     * Checks authenticate: disabled account, throws security exception.
     */
    @Test
    void authenticate_disabledAccount_throwsSecurityException() {
        Employee employee = createEmployee("AS10", false);
        employee.password = org.mindrot.jbcrypt.BCrypt.hashpw("1234", org.mindrot.jbcrypt.BCrypt.gensalt());

        AuthenticationService spyService = spy(service);
        doReturn(employee).when(spyService).findAccountByUsername("AS10");

        SecurityException exception = assertThrows(SecurityException.class,
                () -> spyService.authenticate("AS10", "1234"));
        assertEquals("Invalid credentials", exception.getMessage());
    }

    /**
     * Checks authenticate: wrong password, throws security exception.
     */
    @Test
    void authenticate_wrongPassword_throwsSecurityException() {
        Employee employee = createEmployee("AS10", true);
        employee.password = org.mindrot.jbcrypt.BCrypt.hashpw("1234", org.mindrot.jbcrypt.BCrypt.gensalt());

        AuthenticationService spyService = spy(service);
        doReturn(employee).when(spyService).findAccountByUsername("AS10");

        SecurityException exception = assertThrows(SecurityException.class,
                () -> spyService.authenticate("AS10", "wrong-password"));
        assertEquals("Invalid credentials", exception.getMessage());
    }

    // --------------------------------------------------
    // getConnectedAccount
    // --------------------------------------------------

    /**
     * Checks get connected account: known user, returns account.
     */
    @Test
    void getConnectedAccount_knownUser_returnsAccount() {
        Employee employee = createEmployee("AS10", true);
        when(jwtMock.getName()).thenReturn("AS10");

        AuthenticationService spyService = spy(service);
        doReturn(employee).when(spyService).findAccountByUsername("AS10");

        Account account = spyService.getConnectedAccount();

        assertSame(employee, account);
    }

    /**
     * Checks get connected account: no jwt name, throws illegal state.
     */
    @Test
    void getConnectedAccount_noJwtName_throwsIllegalState() {
        when(jwtMock.getName()).thenReturn(null);

        assertThrows(IllegalStateException.class, service::getConnectedAccount);
    }

    /**
     * Checks get connected account: unknown user, throws illegal state.
     */
    @Test
    void getConnectedAccount_unknownUser_throwsIllegalState() {
        when(jwtMock.getName()).thenReturn("GHOST");

        AuthenticationService spyService = spy(service);
        doReturn(null).when(spyService).findAccountByUsername("GHOST");

        IllegalStateException exception = assertThrows(IllegalStateException.class, spyService::getConnectedAccount);
        assertTrue(exception.getMessage().contains("not found"));
    }

    // --------------------------------------------------
    // PROD profile (Keycloak) — error paths
    // --------------------------------------------------

    /**
     * Switches the service to the Keycloak (prod) profile, pointing at an unreachable server.
     */
    private void configureProdProfile() {
        service.profile = "prod";
        // Port 1 is never open: the HTTP call fails fast with a connection error
        service.authServerUrl = "http://127.0.0.1:1";
        service.clientId = "backoffice";
        service.clientSecret = "secret";
        service.frontendUrl = "http://frontend";
        service.mailer = mock(Mailer.class);
    }

    /**
     * Checks authenticate: prod profile, unreachable keycloak, throws security exception.
     */
    @Test
    void authenticate_prodProfile_unreachableKeycloak_throwsSecurityException() {
        configureProdProfile();

        SecurityException exception = assertThrows(SecurityException.class,
                () -> service.authenticate("user", "password"));
        assertEquals("Authentication service unavailable", exception.getMessage());
    }

    /**
     * Checks initiate password reset: prod profile, unreachable keycloak, throws runtime exception.
     */
    @Test
    void initiatePasswordReset_prodProfile_unreachableKeycloak_throwsRuntimeException() {
        configureProdProfile();

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.initiatePasswordReset("user"));
        assertEquals("Failed to initiate password reset", exception.getMessage());
    }

    /**
     * Checks reset password: prod profile, unknown token, throws security exception.
     */
    @Test
    void resetPassword_prodProfile_unknownToken_throwsSecurityException() {
        configureProdProfile();
        PanacheQuery<Administrative> adminQuery = mockQuery(null);
        PanacheQuery<Employee> employeeQuery = mockQuery(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("passwordResetToken"), any(Object[].class)))
                    .thenReturn(adminQuery, employeeQuery);

            SecurityException exception = assertThrows(SecurityException.class,
                    () -> service.resetPassword("unknown-token", "newPassword1!"));
            assertEquals("Invalid or expired token", exception.getMessage());
        }
    }

    /**
     * Checks reset password: prod profile, unreachable keycloak, throws security exception.
     */
    @Test
    void resetPassword_prodProfile_unreachableKeycloak_throwsSecurityException() {
        configureProdProfile();
        Administrative admin = new Administrative();
        admin.username = "root";
        admin.setPasswordResetTokenExpiry(LocalDateTime.now().plusMinutes(10));
        PanacheQuery<Administrative> adminQuery = mockQuery(admin);
        PanacheQuery<Employee> employeeQuery = mockQuery(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("passwordResetToken"), any(Object[].class)))
                    .thenReturn(adminQuery, employeeQuery);

            SecurityException exception = assertThrows(SecurityException.class,
                    () -> service.resetPassword("valid-token", "newPassword1!"));
            assertEquals("Failed to reset password", exception.getMessage());
        }
    }

    // --------------------------------------------------
    // extractTokenFromJson (private, tested via reflection)
    // --------------------------------------------------

    /**
     * Checks extract token from json: valid json, returns token.
     */
    @Test
    void extractTokenFromJson_validJson_returnsToken() throws Exception {
        Method method = AuthenticationService.class.getDeclaredMethod("extractTokenFromJson", String.class);
        method.setAccessible(true);

        String token = (String) method.invoke(service, "{\"access_token\":\"abc.def.ghi\",\"expires_in\":300}");

        assertEquals("abc.def.ghi", token);
    }

    /**
     * Checks extract token from json: missing token, throws runtime exception.
     */
    @Test
    void extractTokenFromJson_missingToken_throwsRuntimeException() throws Exception {
        Method method = AuthenticationService.class.getDeclaredMethod("extractTokenFromJson", String.class);
        method.setAccessible(true);

        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                () -> method.invoke(service, "{\"error\":\"invalid_grant\"}"));
        assertInstanceOf(RuntimeException.class, exception.getCause());
        assertTrue(exception.getCause().getMessage().contains("Token not found"));
    }

    // --------------------------------------------------
    // initiatePasswordReset (dev) — success paths
    // --------------------------------------------------

    /**
     * Checks initiate password reset: dev profile, employee, sends email and persists.
     */
    @Test
    void initiatePasswordReset_devProfile_employee_sendsEmailAndPersists() {
        Employee employee = createEmployee("AS10", true);
        service.mailer = mock(Mailer.class);
        service.frontendUrl = "http://frontend";
        PanacheQuery<Administrative> adminQuery = mockQuery(null);
        PanacheQuery<Employee> employeeQuery = mockQuery(employee);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("username"), any(Object[].class)))
                    .thenReturn(adminQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("badgeId"), any(Object[].class)))
                    .thenReturn(employeeQuery);
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            service.initiatePasswordReset("AS10");

            // A reset token with a ~15 minute expiry is generated and stored
            assertNotNull(employee.getPasswordResetToken());
            assertNotNull(employee.getPasswordResetTokenExpiry());
            assertTrue(employee.getPasswordResetTokenExpiry().isAfter(LocalDateTime.now().plusMinutes(14)));
            assertTrue(employee.getPasswordResetTokenExpiry().isBefore(LocalDateTime.now().plusMinutes(16)));
            // The employee is persisted (Employee branch of persistAccount)
            verify(session).persist(employee);
            // The reset email is sent with the reset link
            ArgumentCaptor<Mail> mailCaptor = ArgumentCaptor.forClass(Mail.class);
            verify(service.mailer).send(mailCaptor.capture());
            Mail mail = mailCaptor.getValue();
            assertEquals(List.of("AS10@test.com"), mail.getTo());
            assertEquals("Password Reset Request", mail.getSubject());
            // The mail carries the token; only its digest is stored
            java.util.regex.Matcher link = java.util.regex.Pattern.compile("http://frontend/reset-password/([0-9a-f-]{36})").matcher(mail.getHtml());
            assertTrue(link.find());
            assertNotEquals(link.group(1), employee.getPasswordResetToken());
            assertEquals(AuthenticationService.digest(link.group(1)), employee.getPasswordResetToken());
            assertTrue(mail.getHtml().contains("John Doe"));
        }
    }

    /**
     * Checks initiate password reset: dev profile, admin, persists admin.
     */
    @Test
    void initiatePasswordReset_devProfile_admin_persistsAdmin() {
        Administrative admin = new Administrative();
        admin.username = "root";
        admin.fullName = "Root Admin";
        admin.email = "root@test.com";
        service.mailer = mock(Mailer.class);
        service.frontendUrl = "http://frontend";
        PanacheQuery<Administrative> adminQuery = mockQuery(admin);
        PanacheQuery<Employee> employeeQuery = mockQuery(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("username"), any(Object[].class)))
                    .thenReturn(adminQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("badgeId"), any(Object[].class)))
                    .thenReturn(employeeQuery);
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            service.initiatePasswordReset("root");

            assertNotNull(admin.getPasswordResetToken());
            assertNotNull(admin.getPasswordResetTokenExpiry());
            // The admin is persisted (Administrative branch of persistAccount)
            verify(session).persist(admin);
            verify(service.mailer).send(any(Mail.class));
        }
    }

    /**
     * Checks initiate password reset: dev profile, unknown user, logs and returns.
     */
    @Test
    void initiatePasswordReset_devProfile_unknownUser_logsAndReturns() {
        service.mailer = mock(Mailer.class);
        PanacheQuery<Administrative> adminQuery = mockQuery(null);
        PanacheQuery<Employee> employeeQuery = mockQuery(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("username"), any(Object[].class)))
                    .thenReturn(adminQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("badgeId"), any(Object[].class)))
                    .thenReturn(employeeQuery);

            // Unknown user: silent no-op, no email sent
            service.initiatePasswordReset("GHOST");

            verify(service.mailer, never()).send(any(Mail.class));
        }
    }

    // --------------------------------------------------
    // resetPassword (dev) — success and error paths
    // --------------------------------------------------

    /**
     * Checks reset password: null new password, throws illegal argument exception.
     */
    @Test
    void resetPassword_nullNewPassword_throwsIllegalArgumentException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.resetPassword("any-token", null));
        assertTrue(exception.getMessage().contains("de 4 à 100 caractères"));
    }

    /**
     * Checks reset password: dev profile, valid token, returns new token.
     */
    @Test
    void resetPassword_devProfile_validToken_returnsNewToken() {
        Employee employee = createEmployee("AS10", true);
        employee.setPasswordResetToken(AuthenticationService.digest("reset-token"));
        employee.setPasswordResetTokenExpiry(LocalDateTime.now().plusMinutes(10));
        when(tokenServiceMock.generateToken(employee)).thenReturn("new-jwt");
        PanacheQuery<Administrative> adminQuery = mockQuery(null);
        PanacheQuery<Employee> employeeQuery = mockQuery(employee);
        long before = java.time.Instant.now().getEpochSecond();

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            // The lookup is made with the digest of the token, never with the token itself
            panacheMock.when(() -> PanacheEntityBase.find("passwordResetToken", AuthenticationService.digest("reset-token")))
                    .thenReturn(adminQuery, employeeQuery);
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            String token = service.resetPassword("reset-token", "newPassword1!");

            assertEquals("new-jwt", token);
            // The password is rehashed and the reset token is cleared
            assertTrue(org.mindrot.jbcrypt.BCrypt.checkpw("newPassword1!", employee.getPassword()));
            assertNull(employee.getPasswordResetToken());
            assertNull(employee.getPasswordResetTokenExpiry());
            // Sessions opened before the change are revoked
            assertTrue(employee.getCredentialsChangedAt() >= before);
            verify(session).persist(employee);
        }
    }

    /**
     * Checks reset password: dev profile, admin account, persists admin.
     */
    @Test
    void resetPassword_devProfile_adminAccount_persistsAdmin() {
        Administrative admin = new Administrative();
        admin.username = "root";
        admin.setPasswordResetToken("reset-token");
        admin.setPasswordResetTokenExpiry(LocalDateTime.now().plusMinutes(10));
        when(tokenServiceMock.generateToken(admin)).thenReturn("admin-jwt");
        PanacheQuery<Administrative> adminQuery = mockQuery(admin);
        PanacheQuery<Employee> employeeQuery = mockQuery(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("passwordResetToken"), any(Object[].class)))
                    .thenReturn(adminQuery, employeeQuery);
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            String token = service.resetPassword("reset-token", "newPassword1!");

            assertEquals("admin-jwt", token);
            assertNull(admin.getPasswordResetToken());
            assertNull(admin.getPasswordResetTokenExpiry());
            verify(session).persist(admin);
        }
    }

    /**
     * Checks reset password: dev profile, expired token, throws security exception.
     */
    @Test
    void resetPassword_devProfile_expiredToken_throwsSecurityException() {
        Employee employee = createEmployee("AS10", true);
        employee.setPasswordResetToken("expired-token");
        employee.setPasswordResetTokenExpiry(LocalDateTime.now().minusMinutes(1));
        PanacheQuery<Administrative> adminQuery = mockQuery(null);
        PanacheQuery<Employee> employeeQuery = mockQuery(employee);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("passwordResetToken"), any(Object[].class)))
                    .thenReturn(adminQuery, employeeQuery);

            SecurityException exception = assertThrows(SecurityException.class,
                    () -> service.resetPassword("expired-token", "newPassword1!"));
            assertEquals("Invalid or expired token", exception.getMessage());
        }
    }

    /**
     * Checks reset password: dev profile, unknown token, throws security exception.
     */
    @Test
    void resetPassword_devProfile_unknownToken_throwsSecurityException() {
        PanacheQuery<Administrative> adminQuery = mockQuery(null);
        PanacheQuery<Employee> employeeQuery = mockQuery(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("passwordResetToken"), any(Object[].class)))
                    .thenReturn(adminQuery, employeeQuery);

            SecurityException exception = assertThrows(SecurityException.class,
                    () -> service.resetPassword("unknown-token", "newPassword1!"));
            assertEquals("Invalid or expired token", exception.getMessage());
        }
    }

    /**
     * Checks reset password: dev profile, token without expiry, throws security exception.
     */
    @Test
    void resetPassword_devProfile_tokenWithoutExpiry_throwsSecurityException() {
        Employee employee = createEmployee("AS10", true);
        employee.setPasswordResetToken(AuthenticationService.digest("no-expiry"));
        PanacheQuery<Administrative> adminQuery = mockQuery(null);
        PanacheQuery<Employee> employeeQuery = mockQuery(employee);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("passwordResetToken"), any(Object[].class)))
                    .thenReturn(adminQuery, employeeQuery);

            SecurityException exception = assertThrows(SecurityException.class,
                    () -> service.resetPassword("no-expiry", "newPassword1!"));
            assertEquals("Invalid or expired token", exception.getMessage());
        }
    }

    /**
     * Checks reset password: prod profile, token without expiry, throws security exception.
     */
    @Test
    void resetPassword_prodProfile_tokenWithoutExpiry_throwsSecurityException() {
        configureProdProfile();
        Administrative admin = new Administrative();
        admin.username = "root";
        PanacheQuery<Administrative> adminQuery = mockQuery(admin);
        PanacheQuery<Employee> employeeQuery = mockQuery(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("passwordResetToken"), any(Object[].class)))
                    .thenReturn(adminQuery, employeeQuery);

            SecurityException exception = assertThrows(SecurityException.class,
                    () -> service.resetPassword("valid-token", "newPassword1!"));
            assertEquals("Invalid or expired token", exception.getMessage());
        }
    }

    /**
     * Checks reset password: prod profile, expired token, throws security exception.
     */
    @Test
    void resetPassword_prodProfile_expiredToken_throwsSecurityException() {
        configureProdProfile();
        Administrative admin = new Administrative();
        admin.username = "root";
        admin.setPasswordResetTokenExpiry(LocalDateTime.now().minusMinutes(1));
        PanacheQuery<Administrative> adminQuery = mockQuery(admin);
        PanacheQuery<Employee> employeeQuery = mockQuery(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("passwordResetToken"), any(Object[].class)))
                    .thenReturn(adminQuery, employeeQuery);

            SecurityException exception = assertThrows(SecurityException.class,
                    () -> service.resetPassword("valid-token", "newPassword1!"));
            assertEquals("Invalid or expired token", exception.getMessage());
        }
    }

    /**
     * Checks reset password: missing or blank token, never queries the database.
     */
    @Test
    void resetPassword_missingOrBlankToken_neverQueriesTheDatabase() {
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            assertThrows(SecurityException.class, () -> service.resetPassword(null, "newPassword1!"));
            assertThrows(SecurityException.class, () -> service.resetPassword("  ", "newPassword1!"));
            panacheMock.verifyNoInteractions();
        }
    }

    /**
     * Checks initiate password reset: dev profile, escapes the name in the mail.
     */
    @Test
    void initiatePasswordReset_devProfile_escapesTheNameInTheMail() {
        Employee employee = createEmployee("AS10", true);
        employee.firstName = "<img src=x onerror=alert(1)>";
        service.mailer = mock(Mailer.class);
        service.frontendUrl = "http://frontend";
        PanacheQuery<Administrative> adminQuery = mockQuery(null);
        PanacheQuery<Employee> employeeQuery = mockQuery(employee);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("username"), any(Object[].class)))
                    .thenReturn(adminQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("badgeId"), any(Object[].class)))
                    .thenReturn(employeeQuery);
            PanacheTestSupport.mockArcSession(arcMock);

            service.initiatePasswordReset("AS10");

            ArgumentCaptor<Mail> mailCaptor = ArgumentCaptor.forClass(Mail.class);
            verify(service.mailer).send(mailCaptor.capture());
            assertFalse(mailCaptor.getValue().getHtml().contains("<img"));
            assertTrue(mailCaptor.getValue().getHtml().contains("&lt;img src=x onerror=alert(1)&gt;"));
        }
    }

    /**
     * Checks check password policy: covers each rule.
     */
    @Test
    void checkPasswordPolicy_coversEachRule() {
        assertThrows(IllegalArgumentException.class, () -> AuthenticationService.checkPasswordPolicy(null));
        assertThrows(IllegalArgumentException.class, () -> AuthenticationService.checkPasswordPolicy("      "));
        assertThrows(IllegalArgumentException.class, () -> AuthenticationService.checkPasswordPolicy("abc"));
        assertThrows(IllegalArgumentException.class, () -> AuthenticationService.checkPasswordPolicy("x".repeat(101)));
        assertDoesNotThrow(() -> AuthenticationService.checkPasswordPolicy("1234"));
        assertDoesNotThrow(() -> AuthenticationService.checkPasswordPolicy("x".repeat(100)));
    }

    /**
     * Checks digest: is the hexadecimal sha 256.
     */
    @Test
    void digest_isTheHexadecimalSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AuthenticationService.digest("abc"));
    }

    /**
     * Checks escape html: escapes every special character.
     */
    @Test
    void escapeHtml_escapesEverySpecialCharacter() {
        assertEquals("", AuthenticationService.escapeHtml(null));
        assertEquals("Jean Dupont", AuthenticationService.escapeHtml("Jean Dupont"));
        assertEquals("&lt;a&gt; &amp; &quot;b&quot; &#39;c&#39;", AuthenticationService.escapeHtml("<a> & \"b\" 'c'"));
    }
}
