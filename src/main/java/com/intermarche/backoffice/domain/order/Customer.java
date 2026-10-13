package com.intermarche.backoffice.domain.order;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;

/**
 * Entity representing a Customer.
 * <p>
 * Stores identification, contact details, and loyalty information.
 * Uses the standard {@link Adresse} embeddable for the main address.
 * <p>
 * Extends {@link BaseEntity} for auditing, ID generation, and checksum capabilities.
 */
@Entity
@Table(name = "customers",
        indexes = {
                @Index(name = "idx_customer_code", columnList = "customer_id", unique = true),
                @Index(name = "idx_customer_email", columnList = "email", unique = true),
                @Index(name = "idx_customer_loyalty", columnList = "loyalty_card_id")
        }
)
@Cacheable
public class Customer extends BaseEntity {

    // --------------------------------------------------
    // Identification
    // --------------------------------------------------

    /**
     * The unique business identifier for the customer (e.g., "CUS-998877").
     * This is distinct from the technical database ID and is used for external lookups.
     */
    @Column(name = "customer_id", unique = true, nullable = false, length = 50)
    @NotBlank(message = "Customer ID is mandatory")
    public String customerId;

    /**
     * The loyalty card number associated with the customer.
     * Optional field, used to link transactions to loyalty programs.
     */
    @Column(name = "loyalty_card_id", length = 50)
    public String loyaltyCardId;

    // --------------------------------------------------
    // Personal Details
    // --------------------------------------------------

    /**
     * The first name of the customer.
     */
    @Column(name = "first_name", nullable = false, length = 100)
    @NotBlank(message = "First name is mandatory")
    public String firstName;

    /**
     * The last name of the customer.
     */
    @Column(name = "last_name", nullable = false, length = 100)
    @NotBlank(message = "Last name is mandatory")
    public String lastName;

    /**
     * The email address of the customer.
     * Used for communication and order confirmations.
     */
    @Column(name = "email", nullable = false)
    @NotBlank(message = "Email is mandatory")
    public String email;

    /**
     * The phone number of the customer.
     * Used for delivery coordination or contact.
     */
    @Column(name = "phone", length = 20)
    public String phone;

    // --------------------------------------------------
    // Address
    // --------------------------------------------------

    /**
     * The main postal address of the customer.
     * Mapped as an {@link Embeddable} to reuse the standard address structure.
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "streetLine1", column = @Column(name = "address_street_line1")),
            @AttributeOverride(name = "streetLine2", column = @Column(name = "address_street_line2")),
            @AttributeOverride(name = "postalCode", column = @Column(name = "address_postal_code")),
            @AttributeOverride(name = "city", column = @Column(name = "address_city")),
            @AttributeOverride(name = "country", column = @Column(name = "address_country")),
            @AttributeOverride(name = "latitude", column = @Column(name = "address_latitude")),
            @AttributeOverride(name = "longitude", column = @Column(name = "address_longitude"))
    })
    public Adresse address;

    // --------------------------------------------------
    // Data Payload
    // --------------------------------------------------

    /**
     * Raw JSON representation of the customer data.
     * <p>
     * Preserves fields that are not explicitly mapped to columns
     * (e.g., delivery instructions, specific preferences, secondary contacts).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "specification", columnDefinition = "json")
    public String specification;

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a customer by their unique business identifier.
     *
     * @param customerId The business customer ID to search for.
     * @return The Customer instance or null if not found.
     */
    public static Customer findByCustomerId(String customerId) {
        return find("customerId", customerId).firstResult();
    }

    /**
     * Finds a customer by their loyalty card ID.
     *
     * @param loyaltyCardId The loyalty card number.
     * @return The Customer instance or null if not found.
     */
    public static Customer findByLoyaltyCardId(String loyaltyCardId) {
        return find("loyaltyCardId", loyaltyCardId).firstResult();
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum based on the customer's key attributes.
     *
     * @return An integer hash representing the customer's state.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(customerId, firstName, lastName, email, phone, loyaltyCardId, address, specification);
    }
}