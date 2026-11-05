package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.domain.auth.Account;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Set;

/**
 * Unified REST Resource for authentication and account management.
 * <p>
 * Handles login, profile retrieval, and password reset operations.
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthenticationResource {

    /**
     * Constant representing 8 hours in seconds.
     */
    private static final int EIGHT_HOURS_IN_SECONDS = 28800;

    /**
     * The unified authentication service.
     */
    @Inject
    AuthenticationService authenticationService;

    /**
     * The current JSON Web Token representing the authenticated caller.
     */
    @Inject
    JsonWebToken jwt;

    /**
     * Limits failed logins and password reset requests.
     */
    @Inject
    LoginThrottle throttle;

    /**
     * The generic answer to a refused login: it never tells whether the account exists, is disabled or the password
     * is wrong.
     */
    static final String INVALID_CREDENTIALS = "{\"error\": \"Identifiants invalides\"}";

    /**
     * Endpoint to authenticate a user and receive a JWT token.
     *
     * @param request the login request containing username and password.
     * @param http    the HTTP request, used for the client address; may be null outside a server.
     * @return the response containing the JWT token, 401 with a generic message, or 429 after too many failures.
     */
    @POST
    @Path("/login")
    public Response login(LoginRequest request, @Context HttpServerRequest http) {
        if (request == null || request.username == null || request.password == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Username and Password are required\"}")
                    .build();
        }
        String address = clientAddress(http);
        if (throttle.loginBlocked(request.username, address)) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity("{\"error\": \"Trop de tentatives, réessayez dans quelques minutes\"}")
                    .build();
        }
        try {
            String token = authenticationService.authenticate(request.username, request.password);
            throttle.loginSucceeded(request.username);
            return Response.ok(new TokenResponse(token, EIGHT_HOURS_IN_SECONDS)).build();
        } catch (SecurityException e) {
            throttle.loginFailed(request.username, address);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(INVALID_CREDENTIALS)
                    .build();
        } catch (Exception e) {
            return Response.serverError().build();
        }
    }

    /**
     * Retrieves the account information for a specific username.
     * <p>
     * Access is restricted to Admin or the account owner.
     *
     * @param username the username of the account to retrieve.
     * @return the account information.
     */
    @GET
    @Path("/accounts/{username}")
    public Response getAccount(@PathParam("username") String username) {
        String callerPrincipal = jwt.getClaim("upn");
        if (callerPrincipal == null) {
            callerPrincipal = jwt.getSubject();
        }

        Set<String> callerRoles = jwt.getGroups();

        boolean isAdmin = callerRoles != null && callerRoles.contains("ADMIN");
        boolean isSelf = username.equals(callerPrincipal);

        if (!isAdmin && !isSelf) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"error\": \"Access denied\"}")
                    .build();
        }

        Account account = authenticationService.findAccountByUsername(username);

        if (account == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Account not found\"}")
                    .build();
        }

        return Response.ok(new AccountPublicProfile(account)).build();
    }

    /**
     * Requests a password reset for a specific user.
     *
     * @param request the request containing the username.
     * @param http    the HTTP request, used for the client address; may be null outside a server.
     * @return 200 OK response, whether the account exists, the mail was sent or the request was throttled.
     */
    @POST
    @Path("/password-reset/request")
    public Response requestPasswordReset(ResetRequest request, @Context HttpServerRequest http) {
        if (request == null || request.username == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Username is required\"}")
                    .build();
        }
        // A throttled request is answered like any other, so that it reveals nothing
        if (throttle.resetAllowed(request.username, clientAddress(http))) {
            try {
                authenticationService.initiatePasswordReset(request.username);
            } catch (RuntimeException e) {
                // Do not reveal specific details to the client
            }
        }
        return Response.ok("{\"message\": \"If the account exists, a reset email has been sent.\"}").build();
    }

    /**
     * Performs the password reset using the token from the URI.
     * <p>
     * Validates the reset token, updates the password, and returns a new JWT token
     * to authenticate the user immediately (Auto-Login).
     *
     * @param token   the reset token extracted from the URI path.
     * @param request the request containing the new password.
     * @return 200 OK with a {@link TokenResponse} on success, 400 when the new password does not meet the policy,
     *         403 Forbidden with a generic message on an invalid or expired token.
     */
    @POST
    @Path("/password-reset/{token}")
    public Response performPasswordReset(@PathParam("token") String token, NewPasswordRequest request) {
        if (request == null || request.newPassword == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"New password is required\"}")
                    .build();
        }
        try {
            String jwtToken = authenticationService.resetPassword(token, request.newPassword);
            return Response.ok(new TokenResponse(jwtToken, EIGHT_HOURS_IN_SECONDS)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}")
                    .build();
        } catch (SecurityException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"error\": \"Lien invalide ou expiré\"}")
                    .build();
        }
    }

    /**
     * Reads the address of the client.
     *
     * @param http the HTTP request, possibly null
     * @return the remote host, or null when unknown
     */
    static String clientAddress(HttpServerRequest http) {
        return http == null || http.remoteAddress() == null ? null : http.remoteAddress().host();
    }

    // --------------------------------------------------
    // DTOs
    // --------------------------------------------------

    /**
     * Data Transfer Object for the login request.
     */
    public static class LoginRequest {
        /**
         * The username or badge ID.
         */
        @NotBlank
        public String username;

        /**
         * The password.
         */
        @NotBlank
        public String password;
    }

    /**
     * Data Transfer Object for the token response.
     */
    public static class TokenResponse {
        /**
         * The JWT access token.
         */
        public String token;

        /**
         * The token type.
         */
        public String type = "Bearer";

        /**
         * The expiration time in seconds.
         */
        public long expiresIn;

        /**
         * Constructs a TokenResponse.
         *
         * @param token     the access token.
         * @param expiresIn the expiration duration.
         */
        public TokenResponse(String token, long expiresIn) {
            this.token = token;
            this.expiresIn = expiresIn;
        }
    }

    /**
     * Data Transfer Object for the password reset request.
     */
    public static class ResetRequest {
        /**
         * The username or badge ID of the user.
         */
        @NotBlank
        public String username;
    }

    /**
     * Data Transfer Object for setting the new password.
     */
    public static class NewPasswordRequest {
        /**
         * The new password.
         */
        @NotBlank
        public String newPassword;
    }

    /**
     * Data Transfer Object for Account information.
     */
    public static class AccountPublicProfile {
        /**
         * The username.
         */
        public String username;

        /**
         * The full name.
         */
        public String fullName;

        /**
         * The email address.
         */
        public String email;

        /**
         * The set of roles.
         */
        public Set<String> roles;

        /**
         * The active status.
         */
        public boolean enabled;

        /**
         * The store information.
         */
        public StoreInfo store;

        /**
         * Constructs a profile from an Account entity.
         *
         * @param account the source account.
         */
        public AccountPublicProfile(Account account) {
            this.username = account.getUsername();
            this.fullName = account.getFullName();
            this.email = account.getEmail();
            this.roles = account.getRoles();
            this.enabled = account.isEnabled();

            if (account.getStore() != null) {
                this.store = new StoreInfo(account.getStore());
            }
        }
    }

    /**
     * Lightweight Data Transfer Object for Store information.
     */
    public static class StoreInfo {
        /**
         * The store ID.
         */
        public Long id;

        /**
         * The store code.
         */
        public String code;

        /**
         * The store name.
         */
        public String name;

        /**
         * Constructs StoreInfo from a Store entity.
         *
         * @param store the source store.
         */
        public StoreInfo(Store store) {
            this.id = store.id;
            this.code = store.code;
            this.name = store.name;
        }
    }
}