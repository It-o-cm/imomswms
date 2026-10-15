package com.intermarche.e2e.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.auth.TokenService;
import com.intermarche.backoffice.domain.auth.Administrative;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link TokenService}.
 * <p>
 * Verifies that the generated JWT carries the expected claims for the given account
 * (issuer, subject, upn, groups, audience, email, name) and an 8-hour expiration.
 */
@QuarkusTest
@TestTransaction
public class TokenServiceTest {

    @Inject
    TokenService tokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generateToken_shouldProduceSignedJwtWithAccountClaims() throws Exception {
        Administrative account = new Administrative();
        account.username = "tok-user";
        account.password = BCrypt.hashpw("secret", BCrypt.gensalt());
        account.fullName = "Token User";
        account.email = "tok-user@test.com";
        account.roles = Set.of("ADMIN", "PICKER");
        account.persist();

        String token = tokenService.generateToken(account);

        assertNotNull(token);
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "A JWT must have header, payload and signature");
        assertFalse(parts[2].isEmpty(), "The token must be signed");

        JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertEquals("RS256", header.get("alg").asText());

        JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals("https://intermarche-dev.com", payload.get("iss").asText());
        assertEquals(String.valueOf(account.id), payload.get("sub").asText());
        assertEquals("tok-user", payload.get("upn").asText());
        assertEquals("backoffice-api", payload.get("aud").asText());
        assertEquals("tok-user", payload.get("email").asText());
        assertEquals("Token User", payload.get("name").asText());

        Set<String> groups = new HashSet<>();
        payload.get("groups").forEach(group -> groups.add(group.asText()));
        assertEquals(Set.of("ADMIN", "PICKER"), groups);

        long iat = payload.get("iat").asLong();
        long exp = payload.get("exp").asLong();
        assertEquals(8 * 3600, exp - iat, "The token must expire 8 hours after its issue time");
    }

    @Test
    void generateToken_nullAccount_throwsNPE() {
        // account.getRoles() est déréférencé sans garde
        assertThrows(NullPointerException.class, () -> tokenService.generateToken(null));
    }
}
