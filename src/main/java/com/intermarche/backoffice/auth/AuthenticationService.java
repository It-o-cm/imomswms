package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.domain.auth.Account;
import com.intermarche.backoffice.domain.auth.Administrative;
import com.intermarche.backoffice.domain.store.Employee;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unified Service responsible for authentication and password management.
 * <p>
 * This service is active in both Dev and Prod profiles.
 * It internally routes operations to the database (Dev) or to Keycloak (Prod).
 */
@ApplicationScoped
public class AuthenticationService {

    /**
     * Logger instance for this service.
     */
    private static final Logger LOGGER = Logger.getLogger(AuthenticationService.class);

    /**
     * The validity duration of a password reset token in minutes.
     */
    private static final long TOKEN_VALIDITY_MINUTES = 15;

    /**
     * The current JSON Web Token representing the authenticated caller.
     */
    @Inject
    JsonWebToken jwt;

    /**
     * The active application profile (dev, prod, etc.).
     */
    @Inject
    @ConfigProperty(name = "quarkus.profile")
    String profile;

    /**
     * The base URL of the frontend application.
     */
    @Inject
    @ConfigProperty(name = "app.frontend.url")
    String frontendUrl;

    /**
     * The base URL of the Keycloak authentication server.
     */
    @Inject
    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    String authServerUrl;

    /**
     * The OAuth2 client ID for Keycloak communication.
     */
    @Inject
    @ConfigProperty(name = "quarkus.oidc.client-id")
    String clientId;

    /**
     * The OAuth2 client secret for Keycloak communication.
     */
    @Inject
    @ConfigProperty(name = "quarkus.oidc.credentials.secret")
    String clientSecret;

    /**
     * The Quarkus Mailer for sending emails.
     */
    @Inject
    Mailer mailer;

    /**
     * The service responsible for generating JWT tokens (Dev mode only).
     */
    @Inject
    TokenService tokenService;

    // --------------------------------------------------
    // Authentication
    // --------------------------------------------------

    /**
     * Authenticates a user and returns a JWT token.
     * <p>
     * Routes to Database (Dev) or Keycloak (Prod).
     *
     * @param username the username or badge ID.
     * @param password the plain text password.
     * @return the JWT access token.
     * @throws SecurityException if authentication fails.
     */
    public String authenticate(String username, String password) {
        if (username == null || password == null) {
            throw new SecurityException("Invalid credentials");
        }
        if ("dev".equals(profile)) {
            return authenticateDev(username, password);
        } else {
            return authenticateProd(username, password);
        }
    }

    /**
     * Retrieves the {@link Account} entity for the currently authenticated user.
     * <p>
     * This method extracts the principal name (username or badge ID) from the
     * current JWT context and performs a database lookup to return the full
     * account entity.
     *
     * @return The {@link Account} entity (Administrative or Employee) of the connected user.
     * @throws IllegalStateException if no user is authenticated or the account cannot be found in the database.
     */
    public Account getConnectedAccount() {
        // 'jwt.getName()' returns the 'upn' claim (User Principal Name),
        // which corresponds to the username or badgeId.
        String identifier = jwt.getName();
        if (identifier == null) {
            throw new IllegalStateException("No authenticated user found in the security context");
        }
        Account account = findAccountByUsername(identifier);
        if (account == null) {
            // This suggests a data integrity issue: the user has a valid token
            // but no longer exists in the local database.
            LOGGER.errorf("Authenticated user '%s' not found in database", identifier);
            throw new IllegalStateException("Authenticated user not found in database");
        }
        return account;
    }

    /**
     * Finds an account in the local database.
     * <p>
     * Available in both profiles (used for profile data retrieval).
     *
     * @param username the username.
     * @return the Account or null.
     */
    public Account findAccountByUsername(String username) {
        Administrative account = Administrative.find("username", username).firstResult();
        if (account != null) {
            return account;
        }
        return Employee.find("badgeId", username).firstResult();
    }

    // --------------------------------------------------
    // Password Reset - Initiation
    // --------------------------------------------------

    /**
     * Initiates the password reset process for a user.
     * <p>
     * Routes to Database (Dev) or Keycloak Admin API (Prod).
     *
     * @param username the username or badge ID.
     */
    @Transactional
    public void initiatePasswordReset(String username) {
        if ("dev".equals(profile)) {
            initiateResetDev(username);
        } else {
            initiateResetProd(username);
        }
    }

    // --------------------------------------------------
    // Password Reset - Execution
    // --------------------------------------------------

    /**
     * Resets the password using a valid token and authenticates the user.
     * <p>
     * This method validates the provided reset token, updates the user's password,
     * and immediately generates a new JWT token to allow automatic login.
     * <p>
     * Routes to Database (Dev) or Keycloak Admin API (Prod).
     *
     * @param token       the reset token.
     * @param newPassword the new plain text password.
     * @return the JWT access token for the authenticated session.
     * @throws IllegalArgumentException if the new password is null.
     * @throws SecurityException if the token is invalid or expired.
     */
    @Transactional
    public String resetPassword(String token, String newPassword) {
        if (newPassword == null) {
            throw new IllegalArgumentException("New password cannot be null");
        }
        if ("dev".equals(profile)) {
            return resetPasswordDev(token, newPassword);
        } else {
            return resetPasswordProd(token, newPassword);
        }
    }

    // --------------------------------------------------
    // DEV Logic (Database)
    // --------------------------------------------------

    /**
     * Authenticates a user against the local database.
     *
     * @param username the username.
     * @param password the password.
     * @return the generated JWT token.
     * @throws SecurityException if credentials are invalid.
     */
    private String authenticateDev(String username, String password) {
        Account account = findAccountByUsername(username);
        if (account == null || !account.isEnabled()) {
            throw new SecurityException("Invalid credentials: " + ((account==null) ? null : account.isEnabled()));
        }
        if (!org.mindrot.jbcrypt.BCrypt.checkpw(password, account.getPassword())) {
            throw new SecurityException("Invalid credentials: wrong password");
        }
        return tokenService.generateToken(account);
    }

    /**
     * Initiates password reset in the local database.
     *
     * @param username the username.
     */
    private void initiateResetDev(String username) {
        Account account = findAccountByUsername(username);
        if (account == null) {
            LOGGER.infof("Reset requested for unknown user %s", username);
            return;
        }
        String token = UUID.randomUUID().toString();
        LocalDateTime expiry = LocalDateTime.now().plusMinutes(TOKEN_VALIDITY_MINUTES);
        account.setPasswordResetToken(token);
        account.setPasswordResetTokenExpiry(expiry);
        persistAccount(account);
        sendResetEmail(account, token);
    }

    /**
     * Resets the password in the local database and returns an authentication token.
     *
     * @param token       the token.
     * @param newPassword the new password.
     * @return the JWT token for the newly authenticated user.
     * @throws SecurityException if token is invalid.
     */
    private String resetPasswordDev(String token, String newPassword) {
        Account account = findAccountByToken(token);
        if (account == null || account.getPasswordResetTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new SecurityException("Invalid or expired token");
        }
        account.setPassword(org.mindrot.jbcrypt.BCrypt.hashpw(newPassword, org.mindrot.jbcrypt.BCrypt.gensalt()));
        account.setPasswordResetToken(null);
        account.setPasswordResetTokenExpiry(null);
        persistAccount(account);
        // Automatically authenticate the user
        return tokenService.generateToken(account);
    }

    // --------------------------------------------------
    // PROD Logic (Keycloak)
    // --------------------------------------------------

    /**
     * Authenticates a user by proxying the request to Keycloak.
     *
     * @param username the username.
     * @param password the password.
     * @return the Keycloak access token.
     * @throws SecurityException if Keycloak rejects credentials.
     */
    private String authenticateProd(String username, String password) {
        try {
            String keycloakUrl = authServerUrl + "/protocol/openid-connect/token";
            String formData = String.format(
                    "grant_type=password&client_id=%s&client_secret=%s&username=%s&password=%s",
                    URLEncoder.encode(clientId, StandardCharsets.UTF_8),
                    URLEncoder.encode(clientSecret, StandardCharsets.UTF_8),
                    URLEncoder.encode(username, StandardCharsets.UTF_8),
                    URLEncoder.encode(password, StandardCharsets.UTF_8)
            );
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(keycloakUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formData))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return extractTokenFromJson(response.body());
            } else {
                throw new SecurityException("Keycloak rejected credentials");
            }
        } catch (Exception e) {
            LOGGER.error("Error communicating with Keycloak", e);
            throw new SecurityException("Authentication service unavailable");
        }
    }

    /**
     * Initiates password reset via Keycloak Admin API.
     *
     * @param username the username.
     */
    private void initiateResetProd(String username) {
        try {
            String adminToken = getAdminToken();
            String userId = findUserIdByKeycloak(adminToken, username);
            if (userId == null) {
                LOGGER.warnf("User %s not found in Keycloak for password reset", username);
                return;
            }
            executeActionsEmail(adminToken, userId);
        } catch (Exception e) {
            LOGGER.error("Failed to initiate reset via Keycloak", e);
            throw new RuntimeException("Failed to initiate password reset");
        }
    }

    /**
     * Resets the password via Keycloak Admin API and returns an authentication token.
     * <p>
     * This method assumes the token was validated locally first.
     *
     * @param token       the local reset token.
     * @param newPassword the new password.
     * @return the JWT token for the authenticated session.
     * @throws SecurityException if the operation fails.
     */
    private String resetPasswordProd(String token, String newPassword) {
        Account account = findAccountByToken(token);
        if (account == null) throw new SecurityException("Invalid token");
        try {
            String adminToken = getAdminToken();
            String userId = findUserIdByKeycloak(adminToken, account.getUsername());
            if (userId == null) throw new SecurityException("User not found in Keycloak");
            updateKeycloakPassword(adminToken, userId, newPassword);
            account.setPasswordResetToken(null);
            account.setPasswordResetTokenExpiry(null);
            persistAccount(account);
            // Authenticate the user with the new password to get a token
            return authenticateProd(account.getUsername(), newPassword);
        } catch (Exception e) {
            LOGGER.error("Failed to reset password in Keycloak", e);
            throw new SecurityException("Failed to reset password");
        }
    }

    // --------------------------------------------------
    // Keycloak Admin API Helpers
    // --------------------------------------------------

    /**
     * Retrieves an admin access token from Keycloak using client credentials.
     *
     * @return the admin access token string.
     * @throws Exception if the request fails.
     */
    private String getAdminToken() throws Exception {
        String url = authServerUrl + "/protocol/openid-connect/token";
        String formData = String.format(
                "grant_type=client_credentials&client_id=%s&client_secret=%s",
                URLEncoder.encode(clientId, StandardCharsets.UTF_8),
                URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
        );
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formData))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new RuntimeException("Failed to get admin token");
        Pattern pattern = Pattern.compile("\"access_token\":\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(response.body());
        if (matcher.find()) return matcher.group(1);
        throw new RuntimeException("No admin token");
    }

    /**
     * Finds a Keycloak user ID by username using the Admin API.
     *
     * @param adminToken the admin access token.
     * @param username   the username to search for.
     * @return the user ID, or null if not found.
     * @throws Exception if the request fails.
     */
    private String findUserIdByKeycloak(String adminToken, String username) throws Exception {
        String url = authServerUrl + "/admin/realms/intermarche/users?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8);
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + adminToken)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200 && !response.body().equals("[]")) {
            Pattern pattern = Pattern.compile("\"id\":\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(response.body());
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }

    /**
     * Triggers a password reset email via Keycloak.
     *
     * @param adminToken the admin access token.
     * @param userId     the Keycloak user ID.
     * @throws Exception if the request fails.
     */
    private void executeActionsEmail(String adminToken, String userId) throws Exception {
        String url = authServerUrl + "/admin/realms/intermarche/users/" + userId + "/execute-actions-email";
        String json = "[\"UPDATE_PASSWORD\"]";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 204) {
            throw new RuntimeException("Failed to execute actions email");
        }
    }

    /**
     * Updates a user's password directly in Keycloak via Admin API.
     *
     * @param adminToken  the admin access token.
     * @param userId      the Keycloak user ID.
     * @param newPassword the new password.
     * @throws Exception if the request fails.
     */
    private void updateKeycloakPassword(String adminToken, String userId, String newPassword) throws Exception {
        String url = authServerUrl + "/admin/realms/intermarche/users/" + userId + "/reset-password";
        String json = String.format("{\"type\":\"password\",\"value\":\"%s\",\"temporary\":false}", newPassword.replace("\"", "\\\""));
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 204) {
            throw new RuntimeException("Failed to update password in Keycloak");
        }
    }

    // --------------------------------------------------
    // Common Helpers
    // --------------------------------------------------

    /**
     * Sends a password reset email to the user.
     *
     * @param account the account entity.
     * @param token   the reset token.
     */
    private void sendResetEmail(Account account, String token) {
        String link = String.format("%s/reset-password/%s", frontendUrl, token);
        String htmlContent = "<p>Hello " + account.getFullName() + ",</p>"
                + "<p>Click the link below to reset your password:</p>"
                + "<p><a href=\"" + link + "\">Reset Password</a></p>";
        mailer.send(Mail.withHtml(account.getEmail(), "Password Reset Request", htmlContent));
        LOGGER.infof("Reset email sent to %s", account.getEmail());
    }

    /**
     * Finds an account by the reset token.
     *
     * @param token the reset token.
     * @return the Account or null.
     */
    private Account findAccountByToken(String token) {
        Administrative admin = Administrative.find("passwordResetToken", token).firstResult();
        if (admin != null) return admin;
        return Employee.find("passwordResetToken", token).firstResult();
    }

    /**
     * Persists changes to the database based on the concrete entity type.
     *
     * @param account the account to persist.
     */
    private void persistAccount(Account account) {
        // Account has exactly two implementations: Administrative and Employee
        if (account instanceof Administrative) ((Administrative) account).persist();
        else ((Employee) account).persist();
    }

    /**
     * Extracts the access token from a JSON response.
     *
     * @param json the JSON string.
     * @return the token string.
     */
    private String extractTokenFromJson(String json) {
        Pattern pattern = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new RuntimeException("Token not found in response");
    }
}