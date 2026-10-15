package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.domain.auth.Administrative;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuthenticationResource}.
 * The {@link AuthenticationService} and {@link JsonWebToken} are mocked; tests assert
 * the HTTP status codes and response entities of the auth endpoints, plus the DTO mapping.
 */
class AuthenticationResourceTest {

    private AuthenticationService authenticationService;
    private JsonWebToken jwt;
    private AuthenticationResource resource;

    @BeforeEach
    void setUp() {
        authenticationService = mock(AuthenticationService.class);
        jwt = mock(JsonWebToken.class);
        resource = new AuthenticationResource();
        resource.authenticationService = authenticationService;
        resource.jwt = jwt;
    }

    private AuthenticationResource.LoginRequest loginRequest(String username, String password) {
        var request = new AuthenticationResource.LoginRequest();
        request.username = username;
        request.password = password;
        return request;
    }

    private Employee employeeAccount() {
        Store store = new Store();
        store.id = 1L;
        store.code = "STORE-1";
        store.name = "Store One";

        Employee employee = new Employee();
        employee.id = 10L;
        employee.badgeId = "BADGE-1";
        employee.firstName = "John";
        employee.lastName = "Doe";
        employee.email = "john.doe@test.com";
        employee.role = Employee.EmployeeRole.MANAGER;
        employee.store = store;
        employee.active = true;
        return employee;
    }

    // --------------------------------------------------
    // login
    // --------------------------------------------------

    @Test
    void login_nullUsername_returns400() {
        Response response = resource.login(loginRequest(null, "pass"));

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("Username and Password are required"));
    }

    @Test
    void login_nullPassword_returns400() {
        Response response = resource.login(loginRequest("user", null));

        assertEquals(400, response.getStatus());
    }

    @Test
    void login_nullRequest_returns400() {
        Response response = resource.login(null);

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("Username and Password are required"));
        verifyNoInteractions(authenticationService);
    }

    @Test
    void login_success_returns200WithTokenResponse() {
        when(authenticationService.authenticate("user", "pass")).thenReturn("token-123");

        Response response = resource.login(loginRequest("user", "pass"));

        assertEquals(200, response.getStatus());
        var tokenResponse = (AuthenticationResource.TokenResponse) response.getEntity();
        assertEquals("token-123", tokenResponse.token);
        assertEquals("Bearer", tokenResponse.type);
        assertEquals(28800L, tokenResponse.expiresIn);
    }

    @Test
    void login_securityException_returns401() {
        when(authenticationService.authenticate("user", "wrong"))
                .thenThrow(new SecurityException("Bad credentials"));

        Response response = resource.login(loginRequest("user", "wrong"));

        assertEquals(401, response.getStatus());
        assertTrue(response.getEntity().toString().contains("Invalid credentials"));
    }

    @Test
    void login_unexpectedException_returns500() {
        when(authenticationService.authenticate(anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));

        Response response = resource.login(loginRequest("user", "pass"));

        assertEquals(500, response.getStatus());
    }

    // --------------------------------------------------
    // getAccount
    // --------------------------------------------------

    @Test
    void getAccount_notAdminNotSelf_returns403() {
        when(jwt.getClaim("upn")).thenReturn("other-user");
        when(jwt.getGroups()).thenReturn(Set.of("MANAGER"));

        Response response = resource.getAccount("target-user");

        assertEquals(403, response.getStatus());
        assertTrue(response.getEntity().toString().contains("Access denied"));
        verifyNoInteractions(authenticationService);
    }

    @Test
    void getAccount_admin_canAccessAnyAccount() {
        when(jwt.getClaim("upn")).thenReturn("admin-user");
        when(jwt.getGroups()).thenReturn(Set.of("ADMIN"));
        when(authenticationService.findAccountByUsername("target-user")).thenReturn(employeeAccount());

        Response response = resource.getAccount("target-user");

        assertEquals(200, response.getStatus());
        var profile = (AuthenticationResource.AccountPublicProfile) response.getEntity();
        assertEquals("BADGE-1", profile.username);
    }

    @Test
    void getAccount_self_canAccessOwnAccount() {
        when(jwt.getClaim("upn")).thenReturn("BADGE-1");
        when(jwt.getGroups()).thenReturn(Set.of("PICKER"));
        when(authenticationService.findAccountByUsername("BADGE-1")).thenReturn(employeeAccount());

        Response response = resource.getAccount("BADGE-1");

        assertEquals(200, response.getStatus());
    }

    @Test
    void getAccount_unknownAccount_returns404() {
        when(jwt.getClaim("upn")).thenReturn("admin-user");
        when(jwt.getGroups()).thenReturn(Set.of("ADMIN"));
        when(authenticationService.findAccountByUsername("ghost")).thenReturn(null);

        Response response = resource.getAccount("ghost");

        assertEquals(404, response.getStatus());
        assertTrue(response.getEntity().toString().contains("Account not found"));
    }

    @Test
    void getAccount_nullUpnClaim_fallsBackToSubject() {
        when(jwt.getClaim("upn")).thenReturn(null);
        when(jwt.getSubject()).thenReturn("BADGE-1");
        when(jwt.getGroups()).thenReturn(Set.of());
        when(authenticationService.findAccountByUsername("BADGE-1")).thenReturn(employeeAccount());

        Response response = resource.getAccount("BADGE-1");

        assertEquals(200, response.getStatus());
        verify(jwt).getSubject();
    }

    @Test
    void getAccount_nullGroups_notAdmin() {
        when(jwt.getClaim("upn")).thenReturn("other-user");
        when(jwt.getGroups()).thenReturn(null);

        Response response = resource.getAccount("target-user");

        assertEquals(403, response.getStatus());
    }

    // --------------------------------------------------
    // requestPasswordReset
    // --------------------------------------------------

    @Test
    void requestPasswordReset_success_returns200() {
        doNothing().when(authenticationService).initiatePasswordReset("user");

        var request = new AuthenticationResource.ResetRequest();
        request.username = "user";
        Response response = resource.requestPasswordReset(request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getEntity().toString().contains("reset email has been sent"));
        verify(authenticationService).initiatePasswordReset("user");
    }

    @Test
    void requestPasswordReset_nullRequest_returns400() {
        Response response = resource.requestPasswordReset(null);

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("Username is required"));
        verifyNoInteractions(authenticationService);
    }

    @Test
    void requestPasswordReset_nullUsername_returns400() {
        var request = new AuthenticationResource.ResetRequest();
        request.username = null;
        Response response = resource.requestPasswordReset(request);

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("Username is required"));
        verifyNoInteractions(authenticationService);
    }

    @Test
    void requestPasswordReset_serviceThrowsIAE_stillReturns200() {
        doThrow(new IllegalArgumentException("unknown user"))
                .when(authenticationService).initiatePasswordReset("ghost");

        var request = new AuthenticationResource.ResetRequest();
        request.username = "ghost";
        Response response = resource.requestPasswordReset(request);

        // The endpoint must not reveal whether the account exists
        assertEquals(200, response.getStatus());
    }

    // --------------------------------------------------
    // performPasswordReset
    // --------------------------------------------------

    @Test
    void performPasswordReset_success_returns200WithToken() {
        when(authenticationService.resetPassword("token-abc", "newPass1!")).thenReturn("jwt-token");

        var request = new AuthenticationResource.NewPasswordRequest();
        request.newPassword = "newPass1!";
        Response response = resource.performPasswordReset("token-abc", request);

        assertEquals(200, response.getStatus());
        var tokenResponse = (AuthenticationResource.TokenResponse) response.getEntity();
        assertEquals("jwt-token", tokenResponse.token);
        assertEquals(28800L, tokenResponse.expiresIn);
    }

    @Test
    void performPasswordReset_nullRequest_returns400() {
        Response response = resource.performPasswordReset("token-abc", null);

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("New password is required"));
        verifyNoInteractions(authenticationService);
    }

    @Test
    void performPasswordReset_nullNewPassword_returns400() {
        var request = new AuthenticationResource.NewPasswordRequest();
        request.newPassword = null;
        Response response = resource.performPasswordReset("token-abc", request);

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("New password is required"));
        verifyNoInteractions(authenticationService);
    }

    @Test
    void performPasswordReset_securityException_returns403() {
        when(authenticationService.resetPassword("bad-token", "newPass1!"))
                .thenThrow(new SecurityException("Invalid or expired token"));

        var request = new AuthenticationResource.NewPasswordRequest();
        request.newPassword = "newPass1!";
        Response response = resource.performPasswordReset("bad-token", request);

        assertEquals(403, response.getStatus());
        assertTrue(response.getEntity().toString().contains("Invalid or expired token"));
    }

    // --------------------------------------------------
    // DTOs
    // --------------------------------------------------

    @Test
    void tokenResponse_constructor_setsFields() {
        var tokenResponse = new AuthenticationResource.TokenResponse("abc", 3600L);

        assertEquals("abc", tokenResponse.token);
        assertEquals("Bearer", tokenResponse.type);
        assertEquals(3600L, tokenResponse.expiresIn);
    }

    @Test
    void accountPublicProfile_employeeWithStore_mapsAllFields() {
        Employee employee = employeeAccount();

        var profile = new AuthenticationResource.AccountPublicProfile(employee);

        assertEquals("BADGE-1", profile.username);
        assertEquals("John Doe", profile.fullName);
        assertEquals("john.doe@test.com", profile.email);
        assertEquals(Set.of("MANAGER"), profile.roles);
        assertTrue(profile.enabled);
        assertNotNull(profile.store);
        assertEquals(1L, profile.store.id);
        assertEquals("STORE-1", profile.store.code);
        assertEquals("Store One", profile.store.name);
    }

    @Test
    void accountPublicProfile_administrativeWithoutStore_storeIsNull() {
        Administrative admin = new Administrative();
        admin.id = 20L;
        admin.username = "admin";
        admin.fullName = "Super Admin";
        admin.email = "admin@test.com";
        admin.roles = Set.of("ADMIN", "SERVICE_CALLER");
        admin.enabled = false;

        var profile = new AuthenticationResource.AccountPublicProfile(admin);

        assertEquals("admin", profile.username);
        assertEquals("Super Admin", profile.fullName);
        assertEquals("admin@test.com", profile.email);
        assertEquals(Set.of("ADMIN", "SERVICE_CALLER"), profile.roles);
        assertFalse(profile.enabled);
        assertNull(profile.store);
    }

    @Test
    void storeInfo_constructor_mapsFields() {
        Store store = new Store();
        store.id = 5L;
        store.code = "S5";
        store.name = "Store Five";

        var info = new AuthenticationResource.StoreInfo(store);

        assertEquals(5L, info.id);
        assertEquals("S5", info.code);
        assertEquals("Store Five", info.name);
    }
}
