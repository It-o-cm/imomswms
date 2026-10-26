package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;
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
    // Geometry (store plan layer)
    // --------------------------------------------------

    /**
     * The flow type of the aisle on the store plan (LINEAR, DIAGONAL, CIRCULAR).
     * <p>
     * Drives the visual rendering of the aisle (color, dash pattern).
     */
    @Column(name = "flow_type", length = 20)
    public String flowType;

    /**
     * The shape type of the aisle on the store plan (STRAIGHT, CURVED).
     * <p>
     * A CURVED aisle is rendered as a cubic Bézier curve using its first two
     * waypoints as control points.
     */
    @Column(name = "shape_type", length = 20)
    public String shapeType;

    /**
     * The topological node at the START of this aisle (Head), if any.
     * <p>
     * Complements {@link #connectedToHead}: the latter references another aisle
     * code for the logical graph, while this field references the positioned
     * node for the geometric plan.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "head_node_id")
    public PlanNode headNode;

    /**
     * The topological node at the END of this aisle (Tail), if any.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tail_node_id")
    public PlanNode tailNode;

    /**
     * The ordered waypoints (intermediate points) of the aisle on the store plan.
     * <p>
     * Managed by the parent Aisle. Cascades delete operations.
     */
    @OneToMany(mappedBy = "aisle", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @BatchSize(size = 50)
    public List<AisleWaypoint> waypoints = new ArrayList<>();

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

    /**
     * Lists all aisles of a building, ordered by code.
     *
     * @param building The building context.
     * @return The list of aisles (never null).
     */
    public static List<Aisle> listByBuilding(Building building) {
        return find("building = ?1 order by code", building).list();
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    @Override
    public int getChecksum() {
        // Uses the building's natural key (buildingCode), not the surrogate id:
        // building is a lazy @ManyToOne, and reading .id via field access on a
        // Hibernate proxy returns null, which would corrupt the checksum.
        // headNode/tailNode are resolved by their natural key (code) for the same reason.
        int waypointsHash = 0;
        if (waypoints != null) {
            for (AisleWaypoint w : waypoints) {
                waypointsHash = 31 * waypointsHash + w.getChecksum();
            }
        }
        return Objects.hash(
                code,
                lengthMeters,
                aisleType,
                connectedToHead,
                connectedToTail,
                flowType,
                shapeType,
                headNode != null ? headNode.code : null,
                tailNode != null ? tailNode.code : null,
                waypointsHash,
                building != null ? building.buildingCode : null
        );
    }
}