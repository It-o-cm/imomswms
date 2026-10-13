package com.intermarche.backoffice.domain.auth;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Entity representing an administrative account.
 * <p>
 * This entity implements {@link Account} to allow system administrators
 * or technical service accounts to authenticate alongside standard employees.
 * <p>
 * Administrative accounts are not associated with a specific physical store.
 */
@Entity
@Table(name = "administrative_accounts")
public class Administrative extends BaseEntity implements Account {

    /**
     * The unique username for the administrative account.
     * Used as the principal identifier for authentication.
     */
    @Column(name = "username", unique = true, nullable = false)
    public String username;

    /**
     * The hashed password for the account.
     * <p>
     * Stores the BCrypt hash of the raw password.
     */
    @Column(name = "password", nullable = false)
    public String password;

    /**
     * The display name of the administrator.
     */
    @Column(name = "full_name", nullable = false)
    public String fullName;

    /**
     * The email address of the administrator.
     * <p>
     * Must be unique across the system.
     */
    @Column(name = "email", unique = true, nullable = false)
    public String email;

    /**
     * The set of roles assigned to this administrative account.
     * <p>
     * Determines the permissions granted to the administrator.
     */
    @Column(name = "roles")
    public Set<String> roles = new HashSet<>();

    /**
     * Flag indicating if the account is active and allowed to authenticate.
     */
    @Column(name = "enabled", nullable = false)
    public boolean enabled = true;

    /**
     * The secure token used for password reset flows.
     * <p>
     * This token is generated when a user requests a password reset
     * and is sent via email.
     */
    @Column(name = "password_reset_token")
    public String passwordResetToken;

    /**
     * The expiration date and time of the reset token.
     * <p>
     * After this time, the token cannot be used to reset the password.
     */
    @Column(name = "password_reset_token_expiry")
    public LocalDateTime passwordResetTokenExpiry;

    /**
     * Returns the unique principal identifier used for login.
     *
     * @return the username.
     */
    @Override
    public String getUsername() {
        return this.username;
    }

    /**
     * Returns the hashed password associated with the account.
     *
     * @return the hashed password string.
     */
    @Override
    public String getPassword() {
        return this.password;
    }

    /**
     * Returns the set of roles assigned to the account.
     *
     * @return a set of role strings.
     */
    @Override
    public Set<String> getRoles() {
        return this.roles;
    }

    /**
     * Checks if the account is currently active and allowed to authenticate.
     *
     * @return true if the account is enabled, false otherwise.
     */
    @Override
    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * Returns the unique technical ID of the account holder.
     *
     * @return the database ID as a string.
     */
    @Override
    public String getSubjectId() {
        return String.valueOf(this.id);
    }

    /**
     * Returns a display name for the account holder.
     *
     * @return the full name.
     */
    @Override
    public String getFullName() {
        return this.fullName;
    }

    /**
     * Returns the email address of the administrator.
     *
     * @return the email address.
     */
    @Override
    public String getEmail() {
        return this.email;
    }

    /**
     * Returns the store associated with this account.
     *
     * @return null, as administrators are not attached to a store.
     */
    @Override
    public Store getStore() {
        return null;
    }

    /**
     * Sets the hashed password for the account.
     *
     * @param password the new hashed password.
     */
    @Override
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Returns a string representation of the account for logging.
     *
     * @return a descriptive string.
     */
    @Override
    public String present() {
        return String.format("Admin[%s] %s (%s)", this.username, this.fullName, this.email);
    }

    /**
     * Returns the password reset token associated with the account.
     *
     * @return the reset token, or null if not set.
     */
    @Override
    public String getPasswordResetToken() {
        return this.passwordResetToken;
    }

    /**
     * Sets the password reset token for the account.
     *
     * @param token the token to set.
     */
    @Override
    public void setPasswordResetToken(String token) {
        this.passwordResetToken = token;
    }

    /**
     * Returns the expiration date and time of the password reset token.
     *
     * @return the expiration timestamp, or null.
     */
    @Override
    public LocalDateTime getPasswordResetTokenExpiry() {
        return this.passwordResetTokenExpiry;
    }

    /**
     * Sets the expiration date and time for the password reset token.
     *
     * @param expiry the expiration timestamp to set.
     */
    @Override
    public void setPasswordResetTokenExpiry(LocalDateTime expiry) {
        this.passwordResetTokenExpiry = expiry;
    }

    /**
     * Calculates a checksum based on the account's key attributes.
     * <p>
     * Includes the password reset fields in the calculation to detect changes.
     *
     * @return the calculated checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(username, password, fullName, email, roles, enabled, passwordResetToken, passwordResetTokenExpiry);
    }
}