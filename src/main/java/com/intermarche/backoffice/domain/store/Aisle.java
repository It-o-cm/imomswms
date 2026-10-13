package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;

/**
 * Entity representing an Aisle (Allée) in a Store or Warehouse.
 * <p>
 * This entity defines the topology of the building for navigation purposes.
 * It links aisles together to form a graph for shortest path calculations,
 * without requiring precise spatial coordinates (GPS).
 * <p>
 * An aisle has two extremities (Head/Tail) which can be connected to other aisles.
 * <p>
 * This class extends {@link BaseEntity}.
 */
@Entity
@Table(name = "aisles",
        indexes = {
                @Index(name = "idx_aisle_code", columnList = "code", unique = true),
                @Index(name = "idx_aisle_building", columnList = "building_id")
        }
)
@Cacheable
public class Aisle extends BaseEntity {

    // --------------------------------------------------
    // Identification
    // --------------------------------------------------

    /**
     * The unique code for the Aisle.
     * <p>
     * Example: "A01", "B05", "FROZ-01".
     * This corresponds loosely to the 'aisle' field in {@link BaseLocation}.
     */
    @Column(name = "code", unique = true, nullable = false, length = 20)
    @NotBlank(message = "Aisle code is mandatory")
    public String code;

    // --------------------------------------------------
    // Physical Characteristics
    // --------------------------------------------------

    /**
     * The physical length of the Aisle in meters.
     * <p>
     * Used as the "weight" (cost) in graph traversal algorithms (e.g., Dijkstra).
     * If a picker enters at the Head and exits at the Tail, the cost is the full length.
     */
    @Column(name = "length_meters", nullable = false)
    @NotNull(message = "Length is mandatory")
    @DecimalMin(value = "0.1", message = "Length must be positive")
    public Double lengthMeters;

    /**
     * The type of Aisle (Linear, Circular, Island).
     * Can influence pathfinding rules (e.g., one-way circulation).
     */
    @Column(name = "aisle_type", length = 20)
    public String aisleType;

    // --------------------------------------------------
    // Topology / Connections (The "Extremities")
    // --------------------------------------------------

    /**
     * The code of the Aisle (or specific Node) connected to the START of this Aisle (Head).
     * <p>
     * Represents the extremity at position 0 (usually the start of the picking flow).
     * If null, this extremity is a dead-end or the start of the graph.
     */
    @Column(name = "connected_to_head", length = 50)
    public String connectedToHead;

    /**
     * The code of the Aisle (or specific Node) connected to the END of this Aisle (Tail).
     * <p>
     * Represents the extremity at the end (position Length).
     * If null, this extremity is a dead-end.
     */
    @Column(name = "connected_to_tail", length = 50)
    public String connectedToTail;

    // --------------------------------------------------
    // Link to Building
    // --------------------------------------------------

    /**
     * The Building containing this Aisle.
     * <p>
     * Topology is scoped to a single building.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "building_id", nullable = false)
    @NotNull(message = "Building is mandatory")
    public Building building;

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds an Aisle by its code within a specific building.
     *
     * @param building The building context.
     * @param code    The aisle code.
     * @return The Aisle entity or null.
     */
    public static Aisle findByCode(Building building, String code) {
        return find("building = ?1 and code = ?2", building, code).firstResult();
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    @Override
    public int getChecksum() {
        return Objects.hash(
                code,
                lengthMeters,
                aisleType,
                connectedToHead,
                connectedToTail,
                building != null ? building.id : null
        );
    }
}