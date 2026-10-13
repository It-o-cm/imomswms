package com.intermarche.backoffice.domain.auth;

import com.intermarche.backoffice.domain.store.Store;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Interface defining the contract for an entity capable of authentication.
 * <p>
 * This interface is implemented by entities representing internal users
 * (e.g., {@link com.intermarche.backoffice.domain.store.Employee}) and
 * administrative system accounts. It is not intended for end Customers.
 */
public interface Account {

    /**
     * Returns the unique principal identifier used for login.
     * <p>
     * For employees, this is typically the badge ID.
     * For administrative accounts, this is typically the username.
     *
     * @return the principal identifier.
     */
    String getUsername();

    /**
     * Returns the hashed password associated with the account.
     *
     * @return the hashed password string.
     */
    String getPassword();

    /**
     * Returns the set of roles assigned to the account.
     * <p>
     * Roles determine the permissions granted to the account holder.
     *
     * @return a set of role strings.
     */
    Set<String> getRoles();

    /**
     * Checks if the account is currently active and allowed to authenticate.
     *
     * @return true if the account is enabled, false otherwise.
     */
    boolean isEnabled();

    /**
     * Returns the unique technical ID of the account holder.
     * <p>
     * This is used as the "sub" (subject) claim in the JWT.
     *
     * @return the database ID as a string.
     */
    String getSubjectId();

    /**
     * Returns a display name for the account holder.
     * <p>
     * Used for informational purposes in logs or UI displays.
     *
     * @return the full name or display name.
     */
    String getFullName();

    /**
     * Returns the email address associated with the account.
     *
     * @return the email address.
     */
    String getEmail();

    /**
     * Returns the store associated with this account.
     * <p>
     * If the account is not attached to a specific store (e.g., System Administrator),
     * this method returns null.
     *
     * @return the associated {@link Store} or null.
     */
    Store getStore();

    /**
     * Sets the hashed password for the account.
     *
     * @param password the new hashed password.
     */
    void setPassword(String password);

    /**
     * Returns a string representation of the account for logging purposes.
     * <p>
     * This method provides a human-readable summary of the account details.
     *
     * @return a descriptive string of the account.
     */
    String present();

    /**
     * Returns the password reset token associated with the account.
     *
     * @return the reset token, or null if no reset is in progress.
     */
    String getPasswordResetToken();

    /**
     * Sets the password reset token for the account.
     *
     * @param token the reset token.
     */
    void setPasswordResetToken(String token);

    /**
     * Returns the expiration timestamp of the password reset token.
     *
     * @return the expiration date and time, or null.
     */
    LocalDateTime getPasswordResetTokenExpiry();

    /**
     * Sets the expiration timestamp for the password reset token.
     *
     * @param expiry the expiration date and time.
     */
    void setPasswordResetTokenExpiry(LocalDateTime expiry);
}