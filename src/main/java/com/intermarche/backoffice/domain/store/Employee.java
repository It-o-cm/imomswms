package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.auth.Account;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Entity representing an employee working in a store.
 * <p>
 * This class handles authentication (badge ID/PIN), authorization (role),
 * and attachment to a specific {@link Store}.
 * <p>
 * It implements {@link Account} to participate in the authentication system,
 * allowing JWT generation based on employee credentials.
 */
@Entity
@Table(name = "employees",
        indexes = {
                @Index(name = "idx_employee_badge", columnList = "badge_id", unique = true),
                @Index(name = "idx_employee_store", columnList = "store_id")
        }
)
@Cacheable
public class Employee extends BaseEntity implements Account {

    /**
     * Enumeration representing the role of an employee within the supermarket.
     */
    public enum EmployeeRole {
        /**
         * Administrator with full access to the system.
         */
        ADMIN,
        /**
         * Store manager with read-only access to reports and basic management capabilities.
         */
        MANAGER,
        /**
         * Employee responsible for picking orders for the Drive/E-commerce.
         */
        PICKER,
        /**
         * Cashier operating the Point of Sale (POS) system.
         */
        CASHIER
    }

    // --------------------------------------------------
    // Identification
    // --------------------------------------------------

    /**
     * The employee's first name.
     */
    @Column(name = "first_name", nullable = false)
    @NotBlank(message = "First name is mandatory")
    public String firstName;

    /**
     * The employee's last name.
     */
    @Column(name = "last_name", nullable = false)
    @NotBlank(message = "Last name is mandatory")
    public String lastName;

    /**
     * The hashed password (PIN code) of the employee.
     * <p>
     * Note: This should store the hash (e.g., BCrypt), never the plain text PIN.
     */
    @Column(nullable = false)
    @NotBlank(message = "Password is mandatory")
    @Size(min = 60) // Typical size for BCrypt hash
    public String password;

    /**
     * The email address of the employee.
     */
    @Column(name = "email", unique = true, nullable = false)
    @Email(message = "Email should be valid")
    @NotBlank(message = "Email is mandatory")
    public String email;

    // --------------------------------------------------
    // Role & Store Attachment
    // --------------------------------------------------

    /**
     * The role assigned to the employee.
     * Determines permissions for accessing Backoffice, Mobile Apps, etc.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @NotNull(message = "Role is mandatory")
    public EmployeeRole role;

    /**
     * The store to which this employee is assigned.
     * <p>
     * An employee belongs to exactly one store.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false, foreignKey = @ForeignKey(name = "fk_employee_store"))
    @NotNull(message = "Store attachment is mandatory")
    public Store store;

    // --------------------------------------------------
    // Operational Data
    // --------------------------------------------------

    /**
     * The unique identifier of the physical RFID badge.
     * <p>
     * This is the primary login credential.
     */
    @Column(name = "badge_id", unique = true, length = 20)
    @NotBlank(message = "Badge ID is mandatory")
    public String badgeId;

    // --------------------------------------------------
    // Password Reset
    // --------------------------------------------------

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

    // --------------------------------------------------
    // Status
    // --------------------------------------------------

    /**
     * Flag indicating if the employee is currently active.
     * <p>
     * If false, the employee cannot log in to any system.
     */
    @Column(name = "is_active", nullable = false)
    public boolean active = true;

    // --------------------------------------------------
    // Implementation of Account Interface
    // --------------------------------------------------

    /**
     * Returns the badge ID as the username for authentication.
     *
     * @return the badge ID.
     */
    @Override
    public String getUsername() {
        return badgeId;
    }

    /**
     * Returns the hashed password for authentication verification.
     *
     * @return the password hash.
     */
    @Override
    public String getPassword() {
        return password;
    }

    /**
     * Returns a set containing the single role assigned to the employee.
     *
     * @return a set with the role name.
     */
    @Override
    public Set<String> getRoles() {
        if (role == null) {
            return Set.of();
        }
        return Set.of(role.name());
    }

    /**
     * Checks if the employee account is active.
     *
     * @return true if active, false otherwise.
     */
    @Override
    public boolean isEnabled() {
        return active;
    }

    /**
     * Returns the database ID as the subject for the JWT.
     *
     * @return the ID as a string.
     */
    @Override
    public String getSubjectId() {
        return String.valueOf(id);
    }

    /**
     * Returns the full name of the employee.
     *
     * @return "FirstName LastName".
     */
    @Override
    public String getFullName() {
        return firstName + " " + lastName;
    }

    // --------------------------------------------------
    // Implementation of new Account methods
    // --------------------------------------------------

    /**
     * Returns the email address of the employee.
     *
     * @return the email address.
     */
    @Override
    public String getEmail() {
        return this.email;
    }

    /**
     * Returns the store associated with this employee.
     *
     * @return the {@link Store} entity.
     */
    @Override
    public Store getStore() {
        return this.store;
    }

    /**
     * Sets the password for the employee.
     *
     * @param password the new password to be set
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
        String storeName = (store != null) ? store.name : "No Store";
        return String.format("Employee[%s] %s (%s) - %s", this.badgeId, this.getFullName(), this.email, storeName);
    }

    /**
     * Returns the password reset token associated with this account.
     *
     * @return the reset token, or null if not set.
     */
    @Override
    public String getPasswordResetToken() {
        return this.passwordResetToken;
    }

    /**
     * Sets the password reset token for this account.
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
     * @return the expiration timestamp, or null if not set.
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

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds an employee by their physical badge ID.
     *
     * @param badgeId The badge ID to search for.
     * @return The Employee or null if not found.
     */
    public static Employee findByBadgeId(String badgeId) {
        return find("SELECT e FROM Employee e LEFT JOIN FETCH e.store WHERE e.badgeId = ?1", badgeId)
                .firstResult();
    }

    /**
     * Finds an active employee by their physical badge ID.
     * Combines badge lookup with active status check.
     *
     * @param badgeId The badge ID to search for.
     * @return The active Employee or null.
     */
    public static Employee findActiveByBadgeId(String badgeId) {
        return find("badgeId = ?1 and active = true", badgeId).firstResult();
    }

    /**
     * Finds all employees for a specific store.
     *
     * @param store The store entity.
     * @return List of employees belonging to the store.
     */
    public static List<Employee> findByStore(Store store) {
        return list("store", store);
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum based on the entity's current state.
     * <p>
     * Includes the password reset fields in the calculation to detect changes.
     *
     * @return the calculated checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(firstName, lastName, password, role, store.id, badgeId, active, email, passwordResetToken, passwordResetTokenExpiry);
    }
}