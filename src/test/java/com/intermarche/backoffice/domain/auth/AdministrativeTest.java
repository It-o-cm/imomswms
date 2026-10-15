package com.intermarche.backoffice.domain.auth;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Administrative} and its {@link Account} contract.
 * Pure unit tests — no database, no Quarkus.
 */
class AdministrativeTest {

    private Administrative createAdmin() {
        Administrative admin = new Administrative();
        admin.username = "root";
        admin.password = "hashed-password";
        admin.fullName = "Root Admin";
        admin.email = "root@test.com";
        admin.roles = Set.of("ADMIN");
        return admin;
    }

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaultConstructor_enabledIsTrue() {
        assertTrue(new Administrative().enabled);
    }

    @Test
    void defaultConstructor_rolesInitializedEmpty() {
        Administrative admin = new Administrative();
        assertNotNull(admin.roles);
        assertTrue(admin.roles.isEmpty());
    }

    // --------------------------------------------------
    // Account contract
    // --------------------------------------------------

    @Test
    void getUsername_returnsUsername() {
        assertEquals("root", createAdmin().getUsername());
    }

    @Test
    void getPassword_returnsPassword() {
        assertEquals("hashed-password", createAdmin().getPassword());
    }

    @Test
    void getRoles_returnsRoles() {
        assertEquals(Set.of("ADMIN"), createAdmin().getRoles());
    }

    @Test
    void isEnabled_enabledTrue_returnsTrue() {
        assertTrue(createAdmin().isEnabled());
    }

    @Test
    void isEnabled_enabledFalse_returnsFalse() {
        Administrative admin = createAdmin();
        admin.enabled = false;
        assertFalse(admin.isEnabled());
    }

    @Test
    void getSubjectId_returnsIdAsString() {
        Administrative admin = createAdmin();
        admin.id = 42L;
        assertEquals("42", admin.getSubjectId());
    }

    @Test
    void getSubjectId_nullId_returnsNullLiteral() {
        assertEquals("null", new Administrative().getSubjectId());
    }

    @Test
    void getFullName_returnsFullName() {
        assertEquals("Root Admin", createAdmin().getFullName());
    }

    @Test
    void getEmail_returnsEmail() {
        assertEquals("root@test.com", createAdmin().getEmail());
    }

    @Test
    void getStore_returnsNull() {
        assertNull(createAdmin().getStore());
    }

    @Test
    void setPassword_updatesPassword() {
        Administrative admin = createAdmin();
        admin.setPassword("new-hash");
        assertEquals("new-hash", admin.password);
        assertEquals("new-hash", admin.getPassword());
    }

    @Test
    void present_containsUsernameFullNameAndEmail() {
        String present = createAdmin().present();
        assertTrue(present.contains("root"));
        assertTrue(present.contains("Root Admin"));
        assertTrue(present.contains("root@test.com"));
    }

    @Test
    void passwordResetToken_getAndSet() {
        Administrative admin = createAdmin();
        assertNull(admin.getPasswordResetToken());
        admin.setPasswordResetToken("token-123");
        assertEquals("token-123", admin.getPasswordResetToken());
        assertEquals("token-123", admin.passwordResetToken);
    }

    @Test
    void passwordResetTokenExpiry_getAndSet() {
        Administrative admin = createAdmin();
        assertNull(admin.getPasswordResetTokenExpiry());
        LocalDateTime expiry = LocalDateTime.of(2026, 10, 15, 12, 0);
        admin.setPasswordResetTokenExpiry(expiry);
        assertEquals(expiry, admin.getPasswordResetTokenExpiry());
        assertEquals(expiry, admin.passwordResetTokenExpiry);
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_changesWithUsername() {
        Administrative admin = createAdmin();
        int checksum1 = admin.getChecksum();
        admin.username = "other";
        assertNotEquals(checksum1, admin.getChecksum());
    }

    @Test
    void getChecksum_changesWithFullName() {
        Administrative admin = createAdmin();
        int checksum1 = admin.getChecksum();
        admin.fullName = "Other Name";
        assertNotEquals(checksum1, admin.getChecksum());
    }

    @Test
    void getChecksum_changesWithEmail() {
        Administrative admin = createAdmin();
        int checksum1 = admin.getChecksum();
        admin.email = "other@test.com";
        assertNotEquals(checksum1, admin.getChecksum());
    }

    @Test
    void getChecksum_changesWithEnabled() {
        Administrative admin = createAdmin();
        int checksum1 = admin.getChecksum();
        admin.enabled = false;
        assertNotEquals(checksum1, admin.getChecksum());
    }

    @Test
    void getChecksum_changesWithPasswordResetToken() {
        Administrative admin = createAdmin();
        int checksum1 = admin.getChecksum();
        admin.setPasswordResetToken("token");
        assertNotEquals(checksum1, admin.getChecksum());
    }

    @Test
    void getChecksum_sameFields_sameValue() {
        assertEquals(createAdmin().getChecksum(), createAdmin().getChecksum());
    }
}
