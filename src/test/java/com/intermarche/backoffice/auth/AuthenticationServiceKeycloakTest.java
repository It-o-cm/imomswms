package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.domain.auth.Administrative;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.mailer.Mailer;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuthenticationService} PROD profile (Keycloak HTTP paths).
 * <p>
 * A stub Keycloak server is started on an ephemeral localhost port using the JDK built-in
 * {@link HttpServer}. The service under test uses its real {@code java.net.http.HttpClient}
 * to call it, so the full HTTP request/response cycle (success and error branches) is
 * exercised. Pure unit tests — no database, no Quarkus, no network beyond localhost.
 */
class AuthenticationServiceKeycloakTest {

    private AuthenticationService service;
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    /**
     * Canned HTTP response returned by the stub Keycloak server.
     */
    private static final class StubResponse {
        final int status;
        final String body;

        StubResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /**
     * Routing logic of the stub Keycloak server: maps an incoming request to a canned response.
     */
    @FunctionalInterface
    private interface KeycloakStub {
        StubResponse respond(HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void setUp() {
        service = new AuthenticationService();
        service.profile = "prod";
        service.clientId = "backoffice";
        service.clientSecret = "secret";
        service.frontendUrl = "http://frontend";
        service.mailer = mock(Mailer.class);
        service.tokenService = mock(TokenService.class);
        service.jwt = mock(JsonWebToken.class);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * Starts a stub Keycloak server on an ephemeral localhost port and points the service at it.
     * Every request is recorded as {@code "METHOD path"} in {@link #requests}.
     */
    private void startKeycloak(KeycloakStub handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            StubResponse resp = handler.respond(exchange);
            byte[] bytes = resp.body.getBytes(StandardCharsets.UTF_8);
            if (resp.status == 204 || bytes.length == 0) {
                exchange.sendResponseHeaders(resp.status, -1);
            } else {
                exchange.sendResponseHeaders(resp.status, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        service.authServerUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @SuppressWarnings("unchecked")
    private static <T> PanacheQuery<T> mockQuery(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    private static StubResponse json(int status, String body) {
        return new StubResponse(status, body);
    }

    private static StubResponse noContent() {
        return new StubResponse(204, "");
    }

    private static boolean isTokenRequest(HttpExchange exchange) {
        return "POST".equals(exchange.getRequestMethod())
                && exchange.getRequestURI().getPath().endsWith("/protocol/openid-connect/token");
    }

    private static boolean isUsersLookup(HttpExchange exchange) {
        return "GET".equals(exchange.getRequestMethod())
                && exchange.getRequestURI().getPath().startsWith("/admin/realms/intermarche/users");
    }

    // --------------------------------------------------
    // authenticate (prod)
    // --------------------------------------------------

    @Test
    void authenticate_prod_keycloakReturnsToken_returnsToken() throws Exception {
        startKeycloak(exchange -> json(200, "{\"access_token\":\"kc-token-123\",\"expires_in\":300}"));

        String token = service.authenticate("user", "password");

        assertEquals("kc-token-123", token);
        assertTrue(requests.contains("POST /protocol/openid-connect/token"));
    }

    @Test
    void authenticate_prod_keycloakRejectsCredentials_throwsSecurityException() throws Exception {
        startKeycloak(exchange -> json(401, "{\"error\":\"invalid_grant\"}"));

        // The "Keycloak rejected credentials" SecurityException is caught by the generic
        // catch (Exception) and rethrown as "Authentication service unavailable"
        SecurityException exception = assertThrows(SecurityException.class,
                () -> service.authenticate("user", "wrong-password"));
        assertEquals("Authentication service unavailable", exception.getMessage());
    }

    // --------------------------------------------------
    // initiatePasswordReset (prod)
    // --------------------------------------------------

    @Test
    void initiatePasswordReset_prod_userFound_triggersActionsEmail() throws Exception {
        startKeycloak(exchange -> {
            if (isTokenRequest(exchange)) {
                return json(200, "{\"access_token\":\"admin-tok\"}");
            }
            if (isUsersLookup(exchange)) {
                return json(200, "[{\"id\":\"kc-user-1\"}]");
            }
            if ("PUT".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getPath().endsWith("/execute-actions-email")) {
                return noContent();
            }
            return json(404, "");
        });

        service.initiatePasswordReset("user");

        assertTrue(requests.contains("POST /protocol/openid-connect/token"));
        assertTrue(requests.contains("GET /admin/realms/intermarche/users"));
        assertTrue(requests.contains("PUT /admin/realms/intermarche/users/kc-user-1/execute-actions-email"));
    }

    @Test
    void initiatePasswordReset_prod_userNotFoundInKeycloak_logsAndReturns() throws Exception {
        startKeycloak(exchange -> {
            if (isTokenRequest(exchange)) {
                return json(200, "{\"access_token\":\"admin-tok\"}");
            }
            if (isUsersLookup(exchange)) {
                return json(200, "[]");
            }
            return json(404, "");
        });

        // User not found in Keycloak: silent no-op, no exception
        service.initiatePasswordReset("ghost");

        assertTrue(requests.contains("GET /admin/realms/intermarche/users"));
        assertFalse(requests.stream().anyMatch(r -> r.startsWith("PUT")));
    }

    @Test
    void initiatePasswordReset_prod_usersResponseWithoutId_logsAndReturns() throws Exception {
        startKeycloak(exchange -> {
            if (isTokenRequest(exchange)) {
                return json(200, "{\"access_token\":\"admin-tok\"}");
            }
            if (isUsersLookup(exchange)) {
                // 200 but no "id" field in the JSON body
                return json(200, "{\"foo\":\"bar\"}");
            }
            return json(404, "");
        });

        service.initiatePasswordReset("user");

        assertFalse(requests.stream().anyMatch(r -> r.startsWith("PUT")));
    }

    @Test
    void initiatePasswordReset_prod_usersRequestFails_logsAndReturns() throws Exception {
        startKeycloak(exchange -> {
            if (isTokenRequest(exchange)) {
                return json(200, "{\"access_token\":\"admin-tok\"}");
            }
            if (isUsersLookup(exchange)) {
                return json(500, "");
            }
            return json(404, "");
        });

        service.initiatePasswordReset("user");

        assertFalse(requests.stream().anyMatch(r -> r.startsWith("PUT")));
    }

    @Test
    void initiatePasswordReset_prod_adminTokenRequestFails_throwsRuntimeException() throws Exception {
        startKeycloak(exchange -> json(500, ""));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.initiatePasswordReset("user"));
        assertEquals("Failed to initiate password reset", exception.getMessage());
    }

    @Test
    void initiatePasswordReset_prod_noAdminTokenInResponse_throwsRuntimeException() throws Exception {
        startKeycloak(exchange -> json(200, "{\"error\":\"no token here\"}"));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.initiatePasswordReset("user"));
        assertEquals("Failed to initiate password reset", exception.getMessage());
    }

    @Test
    void initiatePasswordReset_prod_executeActionsEmailFails_throwsRuntimeException() throws Exception {
        startKeycloak(exchange -> {
            if (isTokenRequest(exchange)) {
                return json(200, "{\"access_token\":\"admin-tok\"}");
            }
            if (isUsersLookup(exchange)) {
                return json(200, "[{\"id\":\"kc-user-1\"}]");
            }
            if ("PUT".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getPath().endsWith("/execute-actions-email")) {
                return json(500, "");
            }
            return json(404, "");
        });

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.initiatePasswordReset("user"));
        assertEquals("Failed to initiate password reset", exception.getMessage());
    }

    // --------------------------------------------------
    // resetPassword (prod)
    // --------------------------------------------------

    @Test
    void resetPassword_prod_success_updatesKeycloakAndReturnsToken() throws Exception {
        Administrative admin = new Administrative();
        admin.username = "root";
        admin.setPasswordResetToken("valid-token");
        admin.setPasswordResetTokenExpiry(LocalDateTime.now().plusMinutes(10));

        startKeycloak(exchange -> {
            if (isTokenRequest(exchange)) {
                // Called twice: once for the admin token, once to authenticate after the reset
                return json(200, "{\"access_token\":\"final-jwt\"}");
            }
            if (isUsersLookup(exchange)) {
                return json(200, "[{\"id\":\"kc-user-1\"}]");
            }
            if ("PUT".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getPath().endsWith("/reset-password")) {
                return noContent();
            }
            return json(404, "");
        });

        PanacheQuery<Administrative> adminQuery = mockQuery(admin);
        PanacheQuery<Employee> employeeQuery = mockQuery(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("passwordResetToken"), any(Object[].class)))
                    .thenReturn(adminQuery, employeeQuery);
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            String token = service.resetPassword("valid-token", "newPassword1!");

            assertEquals("final-jwt", token);
            // The local reset token is cleared and the account persisted
            assertNull(admin.getPasswordResetToken());
            assertNull(admin.getPasswordResetTokenExpiry());
            verify(session).persist(admin);
        }

        assertTrue(requests.contains("PUT /admin/realms/intermarche/users/kc-user-1/reset-password"));
        // The token endpoint is called twice: getAdminToken + authenticateProd
        assertEquals(2, requests.stream().filter(r -> r.equals("POST /protocol/openid-connect/token")).count());
    }

    @Test
    void resetPassword_prod_passwordWithQuotes_escapesPasswordInJson() throws Exception {
        Administrative admin = new Administrative();
        admin.username = "root";
        admin.setPasswordResetToken("valid-token");
        admin.setPasswordResetTokenExpiry(LocalDateTime.now().plusMinutes(10));

        AtomicReference<String> capturedBody = new AtomicReference<>();
        startKeycloak(exchange -> {
            if (isTokenRequest(exchange)) {
                return json(200, "{\"access_token\":\"final-jwt\"}");
            }
            if (isUsersLookup(exchange)) {
                return json(200, "[{\"id\":\"kc-user-1\"}]");
            }
            if ("PUT".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getPath().endsWith("/reset-password")) {
                capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                return noContent();
            }
            return json(404, "");
        });

        PanacheQuery<Administrative> adminQuery = mockQuery(admin);
        PanacheQuery<Employee> employeeQuery = mockQuery(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("passwordResetToken"), any(Object[].class)))
                    .thenReturn(adminQuery, employeeQuery);
            PanacheTestSupport.mockArcSession(arcMock);

            String token = service.resetPassword("valid-token", "pass\"word");

            assertEquals("final-jwt", token);
        }

        // Double quotes in the password are escaped in the JSON payload sent to Keycloak
        assertNotNull(capturedBody.get());
        assertTrue(capturedBody.get().contains("pass\\\"word"));
    }

    @Test
    void resetPassword_prod_userNotFoundInKeycloak_throwsSecurityException() throws Exception {
        Administrative admin = new Administrative();
        admin.username = "root";
        admin.setPasswordResetToken("valid-token");
        admin.setPasswordResetTokenExpiry(LocalDateTime.now().plusMinutes(10));

        startKeycloak(exchange -> {
            if (isTokenRequest(exchange)) {
                return json(200, "{\"access_token\":\"admin-tok\"}");
            }
            if (isUsersLookup(exchange)) {
                return json(200, "[]");
            }
            return json(404, "");
        });

        PanacheQuery<Administrative> adminQuery = mockQuery(admin);
        PanacheQuery<Employee> employeeQuery = mockQuery(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("passwordResetToken"), any(Object[].class)))
                    .thenReturn(adminQuery, employeeQuery);

            // "User not found in Keycloak" is caught by the generic catch and rethrown
            SecurityException exception = assertThrows(SecurityException.class,
                    () -> service.resetPassword("valid-token", "newPassword1!"));
            assertEquals("Failed to reset password", exception.getMessage());
        }
    }

    @Test
    void resetPassword_prod_updatePasswordFails_throwsSecurityException() throws Exception {
        Administrative admin = new Administrative();
        admin.username = "root";
        admin.setPasswordResetToken("valid-token");
        admin.setPasswordResetTokenExpiry(LocalDateTime.now().plusMinutes(10));

        startKeycloak(exchange -> {
            if (isTokenRequest(exchange)) {
                return json(200, "{\"access_token\":\"admin-tok\"}");
            }
            if (isUsersLookup(exchange)) {
                return json(200, "[{\"id\":\"kc-user-1\"}]");
            }
            if ("PUT".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getPath().endsWith("/reset-password")) {
                return json(500, "");
            }
            return json(404, "");
        });

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
}
