package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.domain.auth.Account;
import io.quarkus.arc.profile.IfBuildProfile;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.Claims;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Service responsible for generating JWT tokens for development purposes.
 * <p>
 * This service is only active when the application is running in the 'dev' profile.
 * In production, token generation is handled by Keycloak.
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class TokenService {

    @ConfigProperty(name = "mp.jwt.verify.issuer")
    String issuer;

    // 1. Ajoutez cette injection pour récupérer l'audience définie dans les properties
    @ConfigProperty(name = "mp.jwt.verify.audiences")
    String audience;

    public String generateToken(Account account) {
        Set<String> groups = new HashSet<>(account.getRoles());

        return Jwt
            .issuer(issuer)
            .subject(account.getSubjectId())
            .upn(account.getUsername())
            .groups(groups)
            // 2. Ajoutez cette ligne pour mettre l'audience dans le token
            .audience(audience)
            .claim(Claims.email.name(), account.getUsername())
            .claim("name", account.getFullName())
            .expiresIn(Duration.ofHours(8))
            .sign();
    }
}