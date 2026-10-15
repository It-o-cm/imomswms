package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * Entity representing the Buffer (waiting area) capacities of a Building.
 * <p>
 * Defines the maximum number of Totes (bacs) that can be staged in the buffer area
 * of a specific building, broken down by temperature zone.
 * <p>
 * Also defines safety limits (weight) and operational status.
 * <p>
 * This class extends {@link BaseEntity}.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "buffers",
        indexes = {
                @Index(name = "idx_buffer_building", columnList = "building_id"),
                @Index(name = "idx_buffer_status", columnList = "is_active")
        }
)
@Cacheable
public class Buffer extends BaseEntity {

    // --------------------------------------------------
    // Inner Enum
    // --------------------------------------------------

    /**
     * Enumeration defining the primary purpose of the Buffer.
     */
    public enum BufferPurpose {
        /**
         * Buffer used for staging orders ready for customer pickup (Drive) or shipment.
         */
        STAGING,
        /**
         * Buffer used as a workspace for pickers/packers to accumulate items during processing.
         */
        PROCESSING,
        /**
         * Buffer used for unloading goods from trucks before they are moved to shelves.
         */
        RECEIPT
    }

    // --------------------------------------------------
    // Relationship
    // --------------------------------------------------

    /**
     * The Building (Store or Warehouse) to which this Buffer configuration applies.
     * <p>
     * A Building has exactly one Buffer configuration (1-to-1).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "building_id", nullable = false, foreignKey = @ForeignKey(name = "fk_buffer_building"))
    @NotNull(message = "Building is mandatory")
    public Building building;

    // --------------------------------------------------
    // Operational Details
    // --------------------------------------------------

    /**
     * The specific purpose of this buffer area.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20)
    @NotNull(message = "Purpose is mandatory")
    public BufferPurpose purpose = BufferPurpose.STAGING;

    /**
     * Human-readable code to identity the buffer.
     * <p>
     * Example: "North Dock", "Quai 4", "Drive Zone 1".
     */
    @Column(name = "buffer_code", length = 100)
    public String bufferCode;

    /**
     * Human-readable description of the physical location.
     * <p>
     * Example: "North Dock", "Quai 4", "Drive Zone 1".
     */
    @Column(name = "location_description", length = 100)
    public String locationDescription;

    /**
     * Flag indicating if the buffer is currently operational.
     * <p>
     * If false, the buffer is considered unavailable (e.g., under maintenance, blocked),
     * and no totes should be assigned to it regardless of capacity.
     */
    @Column(name = "is_active", nullable = false)
    public boolean active = true;

    // --------------------------------------------------
    // Capacities (Max Totes)
    // --------------------------------------------------

    /**
     * Maximum capacity for the Ambient (Dry) zone.
     */
    @Column(name = "ambient_capacity", nullable = false)
    @Min(value = 0, message = "Capacity must be positive")
    public int ambientCapacity;

    /**
     * Maximum capacity for the Chilled (Fresh) zone.
     */
    @Column(name = "chilled_capacity", nullable = false)
    @Min(value = 0, message = "Capacity must be positive")
    public int chilledCapacity;

    /**
     * Maximum capacity for the Frozen zone.
     */
    @Column(name = "frozen_capacity", nullable = false)
    @Min(value = 0, message = "Capacity must be positive")
    public int frozenCapacity;

    // --------------------------------------------------
    // Safety Limits
    // --------------------------------------------------

    /**
     * Maximum weight capacity for this buffer area (Kilograms).
     * <p>
     * Used to ensure structural integrity or equipment limits (e.g., lift tables) are not exceeded.
     * Null if there is no specific weight limit.
     */
    @Column(name = "max_weight_kg")
    public Double maxWeightKg;

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds the Buffer configuration for a specific Building.
     *
     * @param building The building entity.
     * @return The Buffer entity or null if not found.
     */
    public static Buffer findByBuilding(Building building) {
        return find("building", building).firstResult();
    }

    /**
     * Finds all active buffers.
     *
     * @return List of active buffers.
     */
    public static List<Buffer> findActive() {
        return list("active", true);
    }

    /**
     * Finds a Buffer entity based on its unique buffer code.
     *
     * @param bufferCode The unique code of the buffer.
     * @return The Buffer entity matching the provided code, or null if no match is found.
     */
    public static Buffer findByCode(String bufferCode) {
        return find("bufferCode = ?1", bufferCode).firstResult();
    }

    // --------------------------------------------------
    // Helper
    // --------------------------------------------------

    /**
     * Helper method to get the maximum capacity for a specific temperature zone.
     *
     * @param zone The temperature zone.
     * @return The max capacity (int).
     */
    public int getCapacityForZone(TemperatureZone zone) {
        // If buffer is inactive, capacity is 0
        if (!this.active) return 0;

        switch (zone) {
            case AMBIENT:
                return this.ambientCapacity;
            case CHILLED:
                return this.chilledCapacity;
            case FROZEN:
                return this.frozenCapacity;
            default:
                return 0; // Or handle HOUSEHOLD if needed
        }
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum based on the buffer's key attributes.
     * <p>
     * Covers exactly the fields imported by the Buffer CSV: buffer code, building link,
     * purpose, location description, active flag and capacities.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        // Uses the building's natural key (buildingCode), not the surrogate id:
        // building is a lazy @ManyToOne, and reading .id via field access on a
        // Hibernate proxy returns null, which would corrupt the checksum.
        return Objects.hash(
                bufferCode,
                building != null ? building.buildingCode : null,
                purpose,
                locationDescription,
                active,
                ambientCapacity,
                chilledCapacity,
                frozenCapacity,
                maxWeightKg
        );
    }
}