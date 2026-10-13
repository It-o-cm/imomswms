package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Entity representing a Tote (plastic bin) used in logistics.
 * <p>
 * This class focuses on the physical characteristics of the tote:
 * a business identifier (code), its volume, its type, and its ownership.
 * <p>
 * This class extends {@link BaseEntity} to inherit ID, versioning,
 * and audit fields.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "totes",
        indexes = {
                @Index(name = "idx_tote_code", columnList = "code", unique = true),
                @Index(name = "idx_tote_store", columnList = "store_id"),
                @Index(name = "idx_tote_type", columnList = "tote_type")
        }
)
@Cacheable
public class Tote extends BaseEntity {

    // --------------------------------------------------
    // Inner Enum
    // --------------------------------------------------

    /**
     * Enumeration representing the type of Tote.
     * <p>
     * Determines the thermal properties and handling requirements.
     */
    public enum ToteType {
        /**
         * Standard plastic bin.
         * <p>
         * Used for dry goods (Ambient) and household items.
         * Not insulated.
         */
        STANDARD,

        /**
         * Insulated plastic bin (Cooler).
         * <p>
         * Used for Chilled or Frozen goods.
         * Features thick walls to maintain temperature, often used with ice packs.
         */
        INSULATED
    }

    // --------------------------------------------------
    // Identification
    // --------------------------------------------------

    /**
     * The unique business identifier for the Tote.
     * <p>
     * This code is usually physically printed on the RFID tag or the bin itself.
     * Example: "TOTE-01", "ISO-BLUE-45L".
     */
    @Column(name = "code", unique = true, nullable = false, length = 20)
    @NotBlank(message = "Tote code is mandatory")
    public String code;

    // --------------------------------------------------
    // Physical Characteristics
    // --------------------------------------------------

    /**
     * The volume capacity of the Tote.
     * <p>
     * Stored in Liters (L) with 3 decimal places (milliliter precision).
     */
    @Column(name = "volume", nullable = false, precision = 10, scale = 3)
    @NotNull(message = "Volume is mandatory")
    @Positive(message = "Volume must be positive")
    public BigDecimal volume;

    /**
     * The type of the Tote (Standard vs Insulated).
     * <p>
     * Determines how the tote should be used in the picking process.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "tote_type", nullable = false, length = 20)
    @NotNull(message = "Tote type is mandatory")
    public ToteType toteType;

    // --------------------------------------------------
    // Relationship
    // --------------------------------------------------

    /**
     * The Store that owns or manages this Tote.
     * <p>
     * A Tote is typically assigned to a specific physical store location.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false, foreignKey = @ForeignKey(name = "fk_tote_store"))
    @NotNull(message = "Store attachment is mandatory")
    public Store store;

    // --------------------------------------------------
    // Business Logic
    // --------------------------------------------------

    /**
     * Validates if the requested usage (TemperatureZone) is allowed for this Tote type.
     * <p>
     * STANDARD totes are for AMBIENT and HOUSEHOLD.
     * INSULATED totes are for CHILLED and FROZEN.
     *
     * @param usage The requested TemperatureZone.
     * @return true if allowed, false otherwise.
     */
    public boolean isUsageAllowed(TemperatureZone usage) {
        if (this.toteType == ToteType.STANDARD) {
            return usage == TemperatureZone.AMBIENT || usage == TemperatureZone.HOUSEHOLD;
        } else if (this.toteType == ToteType.INSULATED) {
            return usage == TemperatureZone.CHILLED || usage == TemperatureZone.FROZEN;
        }
        return false;
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum based on the tote's key attributes.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, volume, toteType, store.id);
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a Tote by its unique business code.
     *
     * @param code The tote code.
     * @return The Tote entity or null if not found.
     */
    public static Tote findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * Finds all Totes assigned to a specific Store.
     *
     * @param store The store entity.
     * @return A list of Totes belonging to the store.
     */
    public static List<Tote> findByStore(Store store) {
        return list("store", store);
    }

    /**
     * Finds all Totes assigned to a Store identified by its business code.
     * <p>
     * Uses a join query to filter by the store's code directly.
     *
     * @param storeCode The unique code of the store.
     * @return A list of Totes belonging to the store.
     */
    public static List<Tote> findByStoreCode(String storeCode) {
        return list("store.code", storeCode);
    }

}