package com.intermarche.e2e.domain.auth;

import com.intermarche.backoffice.domain.auth.Account;
import com.intermarche.backoffice.domain.auth.Administrative;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.persistence.PersistenceException;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link Administrative}.
 * Covers persistence, unique constraints, Account interface implementation,
 * and getChecksum sensitivity.
 */
@QuarkusTest
public class AdministrativeTest {

    // --------------------------------------------------
    // Fixture Helpers
    // --------------------------------------------------

    private Administrative createAdmin(String prefix) {
        Administrative admin = new Administrative();
        admin.username = prefix + "-admin";
        admin.password = BCrypt.hashpw("secret", BCrypt.gensalt());
        admin.fullName = "Admin " + prefix;
        admin.email = prefix + "-admin@test.com";
        admin.roles = Set.of("ADMIN");
        admin.enabled = true;
        admin.persist();
        return admin;
    }

    // --------------------------------------------------
    // Persistence Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void persist_shouldSucceed_withValidData() {
        Administrative admin = createAdmin("AD01");

        assertNotNull(admin.id);
        assertTrue(admin.enabled);
        assertEquals(Set.of("ADMIN"), admin.roles);
    }

    @Test
    @TestTransaction
    void persist_shouldFail_whenUsernameDuplicated() {
        createAdmin("AD02");

        Administrative duplicate = new Administrative();
        duplicate.username = "AD02-admin"; // same username
        duplicate.password = BCrypt.hashpw("secret", BCrypt.gensalt());
        duplicate.fullName = "Other Admin";
        duplicate.email = "AD02-other@test.com";

        assertThrows(PersistenceException.class, () -> {
            duplicate.persist();
            Panache.getEntityManager().flush();
        });
    }

    @Test
    @TestTransaction
    void persist_shouldFail_whenEmailDuplicated() {
        createAdmin("AD03");

        Administrative duplicate = new Administrative();
        duplicate.username = "AD03-admin-2";
        duplicate.password = BCrypt.hashpw("secret", BCrypt.gensalt());
        duplicate.fullName = "Other Admin";
        duplicate.email = "AD03-admin@test.com"; // same email

        assertThrows(PersistenceException.class, () -> {
            duplicate.persist();
            Panache.getEntityManager().flush();
        });
    }

    // --------------------------------------------------
    // Account Interface Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void accountMethods_shouldExposeExpectedValues() {
        Administrative admin = createAdmin("AD04");

        assertEquals("AD04-admin", admin.getUsername());
        assertEquals(admin.password, admin.getPassword());
        assertEquals(Set.of("ADMIN"), admin.getRoles());
        assertTrue(admin.isEnabled());
        assertEquals(String.valueOf(admin.id), admin.getSubjectId());
        assertEquals("Admin AD04", admin.getFullName());
        assertEquals("AD04-admin@test.com", admin.getEmail());
    }

    @Test
    @TestTransaction
    void getStore_shouldAlwaysReturnNull() {
        Administrative admin = createAdmin("AD05");

        assertNull(admin.getStore());
    }

    @Test
    @TestTransaction
    void present_shouldContainUsernameAndFullName() {
        Administrative admin = createAdmin("AD06");

        String presentation = admin.present();

        assertTrue(presentation.contains("AD06-admin"));
        assertTrue(presentation.contains("Admin AD06"));
        assertTrue(presentation.contains("AD06-admin@test.com"));
    }

    @Test
    @TestTransaction
    void passwordResetToken_shouldBeSettable() {
        Administrative admin = createAdmin("AD07");

        assertNull(admin.getPasswordResetToken());
        assertNull(admin.getPasswordResetTokenExpiry());

        LocalDateTime expiry = LocalDateTime.now().plusHours(1);
        admin.setPasswordResetToken("token-abc");
        admin.setPasswordResetTokenExpiry(expiry);

        assertEquals("token-abc", admin.getPasswordResetToken());
        assertEquals(expiry, admin.getPasswordResetTokenExpiry());
    }

    @Test
    @TestTransaction
    void setPassword_shouldUpdatePassword() {
        Administrative admin = createAdmin("AD08");
        String oldHash = admin.getPassword();

        String newHash = BCrypt.hashpw("new-secret", BCrypt.gensalt());
        admin.setPassword(newHash);

        assertEquals(newHash, admin.getPassword());
        assertNotEquals(oldHash, admin.getPassword());
    }

    // --------------------------------------------------
    // Checksum Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenEnabledChanges() {
        Administrative admin = createAdmin("AD09");

        int checksum1 = admin.getChecksum();
        admin.enabled = false;
        int checksum2 = admin.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenRolesChange() {
        Administrative admin = createAdmin("AD10");

        int checksum1 = admin.getChecksum();
        admin.roles = Set.of("ADMIN", "SUPPORT");
        int checksum2 = admin.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenUsernameChanges() {
        Administrative admin = createAdmin("AD11");

        int checksum1 = admin.getChecksum();
        admin.username = "AD11-renamed";
        int checksum2 = admin.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    // --------------------------------------------------
    // Query Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void findById_shouldReturnAdmin() {
        Administrative admin = createAdmin("AD12");

        Administrative found = Administrative.findById(admin.id);

        assertNotNull(found);
        assertEquals("AD12-admin", found.username);
    }

    @Test
    @TestTransaction
    void findById_shouldReturnNull_whenNotFound() {
        assertNull(Administrative.findById(999999L));
    }
}
