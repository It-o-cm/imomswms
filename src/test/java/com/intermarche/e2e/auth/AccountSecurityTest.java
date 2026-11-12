package com.intermarche.e2e.auth;

import com.intermarche.backoffice.auth.AuthenticationService;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end checks of the account protections over HTTP: generic login errors, login throttling, revocation of the
 * sessions of a disabled account or after a password change, the password reset flow and the endpoints that require
 * a token.
 */
@QuarkusTest
public class AccountSecurityTest {

    @Inject
    MockMailbox mailbox;

    /**
     * Creates an active picker with a unique badge and the PIN 1234, in its own committed store.
     *
     * @return the badge
     */
    private static String picker() {
        String badge = "SEC-" + UUID.randomUUID().toString().substring(0, 8);
        QuarkusTransaction.requiringNew().run(() -> {
            Store store = DomainUtils.createAndPersistStore("S" + badge, 48.0, 2.0);
            Employee employee = new Employee();
            employee.firstName = "Sec";
            employee.lastName = badge;
            employee.badgeId = badge;
            employee.email = badge.toLowerCase() + "@test.invalid";
            employee.password = BCrypt.hashpw("1234", BCrypt.gensalt(4));
            employee.role = Employee.EmployeeRole.PICKER;
            employee.store = store;
            employee.active = true;
            employee.persist();
        });
        return badge;
    }

    /**
     * Logs in.
     *
     * @param username the username
     * @param password the password
     * @return the HTTP status
     */
    private static int login(String username, String password) {
        return given().contentType(ContentType.JSON).body(Map.of("username", username, "password", password))
                .post("/auth/login").then().extract().statusCode();
    }

    /**
     * Logs in and returns the token.
     *
     * @param username the username
     * @param password the password
     * @return the token
     */
    private static String token(String username, String password) {
        return given().contentType(ContentType.JSON).body(Map.of("username", username, "password", password))
                .post("/auth/login").then().statusCode(200).extract().path("token");
    }

    /**
     * Sends a trivial GraphQL query.
     *
     * @param token the bearer token, or null for an anonymous call
     * @return the HTTP status
     */
    private static int graphql(String token) {
        var request = given().contentType(ContentType.JSON).body(Map.of("query", "{ __typename }"));
        if (token != null) request = request.auth().oauth2(token);
        return request.post("/graphql").then().extract().statusCode();
    }

    /**
     * An unknown account and a wrong password get the same answer.
     */
    @Test
    void loginErrorsDoNotTellWhetherTheAccountExists() {
        String badge = picker();
        String unknown = given().contentType(ContentType.JSON).body(Map.of("username", "NOBODY-" + badge, "password", "x"))
                .post("/auth/login").then().statusCode(401).extract().asString();
        String wrong = given().contentType(ContentType.JSON).body(Map.of("username", badge, "password", "x"))
                .post("/auth/login").then().statusCode(401).extract().asString();
        assertEquals(unknown, wrong);
        assertEquals("{\"error\": \"Identifiants invalides\"}", wrong);
    }

    /**
     * After five failures, even the right password is refused for a while.
     */
    @Test
    void repeatedFailuresBlockTheUsername() {
        String badge = picker();
        for (int attempt = 0; attempt < 5; attempt++) assertEquals(401, login(badge, "0000"));
        assertEquals(429, login(badge, "1234"));
    }

    /**
     * A success forgets the earlier failures of the username.
     */
    @Test
    void successForgetsEarlierFailures() {
        String badge = picker();
        for (int attempt = 0; attempt < 4; attempt++) assertEquals(401, login(badge, "0000"));
        assertEquals(200, login(badge, "1234"));
        for (int attempt = 0; attempt < 4; attempt++) assertEquals(401, login(badge, "0000"));
        assertEquals(200, login(badge, "1234"));
    }

    /**
     * GraphQL, schema included, and OpenAPI require a token.
     */
    @Test
    void graphqlAndOpenApiRequireAToken() {
        String token = token(picker(), "1234");
        assertEquals(401, graphql(null));
        assertEquals(200, graphql(token));
        given().get("/graphql/schema.graphql").then().statusCode(401);
        given().get("/q/openapi").then().statusCode(401);
        given().auth().oauth2(token).get("/q/openapi").then().statusCode(200);
    }

    /**
     * The token of an account disabled after login stops working at once.
     */
    @Test
    void disablingAnAccountEndsItsSessions() {
        String badge = picker();
        String token = token(badge, "1234");
        assertEquals(200, graphql(token));

        QuarkusTransaction.requiringNew().run(() -> Employee.findByBadgeId(badge).active = false);

        assertEquals(401, graphql(token));
    }

    /**
     * A token issued before the last password change stops working.
     */
    @Test
    void aPasswordChangeEndsTheEarlierSessions() {
        String badge = picker();
        String token = token(badge, "1234");
        assertEquals(200, graphql(token));

        QuarkusTransaction.requiringNew().run(() -> Employee.findByBadgeId(badge).credentialsChangedAt = java.time.Instant.now().getEpochSecond() + 5);

        assertEquals(401, graphql(token));
    }

    /**
     * The reset link works once, with a password meeting the policy; the database only holds the digest of the token,
     * and unknown accounts get the same answer as known ones.
     */
    @Test
    void passwordResetFlow() {
        String badge = picker();
        String email = badge.toLowerCase() + "@test.invalid";
        mailbox.clear();
        String answer = given().contentType(ContentType.JSON).body(Map.of("username", badge))
                .post("/auth/password-reset/request").then().statusCode(200).extract().asString();
        String unknown = given().contentType(ContentType.JSON).body(Map.of("username", "NOBODY-" + badge))
                .post("/auth/password-reset/request").then().statusCode(200).extract().asString();
        assertEquals(answer, unknown);

        Matcher link = Pattern.compile("/reset-password/([0-9a-f-]{36})").matcher(mailbox.getMailsSentTo(email).get(0).getHtml());
        assertTrue(link.find());
        String resetToken = link.group(1);
        String stored = QuarkusTransaction.requiringNew().call(() -> Employee.findByBadgeId(badge).passwordResetToken);
        assertEquals(AuthenticationService.digest(resetToken), stored);

        given().contentType(ContentType.JSON).body(Map.of("newPassword", "12"))
                .post("/auth/password-reset/" + resetToken).then().statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of("newPassword", "5678"))
                .post("/auth/password-reset/" + stored).then().statusCode(403).body("error", equalTo("Lien invalide ou expiré"));
        given().contentType(ContentType.JSON).body(Map.of("newPassword", "5678"))
                .post("/auth/password-reset/" + resetToken).then().statusCode(200).body("token", notNullValue());
        given().contentType(ContentType.JSON).body(Map.of("newPassword", "9999"))
                .post("/auth/password-reset/" + resetToken).then().statusCode(403);

        assertEquals(401, login(badge, "1234"));
        assertEquals(200, login(badge, "5678"));
    }

    /**
     * Past three requests for one username, reset mails stop while the answer stays the same.
     */
    @Test
    void resetRequestsAreThrottledSilently() {
        String badge = picker();
        String email = badge.toLowerCase() + "@test.invalid";
        mailbox.clear();
        for (int request = 0; request < 5; request++) {
            given().contentType(ContentType.JSON).body(Map.of("username", badge))
                    .post("/auth/password-reset/request").then().statusCode(200);
        }
        assertEquals(3, mailbox.getMailsSentTo(email).size());
    }
}
