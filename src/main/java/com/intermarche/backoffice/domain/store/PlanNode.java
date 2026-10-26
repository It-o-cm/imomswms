package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * Entity representing a topological Node of a store plan.
 * <p>
 * A node is a positioned point (x/y coordinates in meters, origin at the center
 * of the building) that acts as an extremity for aisles: entrance, intersection
 * or dead-end. Nodes carry the geometric layer of the topology: the logical
 * graph between aisles remains defined by {@link Aisle#connectedToHead} and
 * {@link Aisle#connectedToTail}.
 * <p>
 * This class extends {@link BaseEntity}.
 */
@Entity
@Table(name = "plan_nodes",
        indexes = {
                @Index(name = "idx_plan_node_code", columnList = "code", unique = true),
                @Index(name = "idx_plan_node_building", columnList = "building_id")
        }
)
@Cacheable
public class PlanNode extends BaseEntity {

    // --------------------------------------------------
    // Identification
    // --------------------------------------------------

    /**
     * The unique code of the node (e.g., "ENTRANCE", "HUB_CENTRAL").
     */
    @Column(name = "code", unique = true, nullable = false, length = 50)
    @NotBlank(message = "Node code is mandatory")
    public String code;

    /**
     * The display label of the node (e.g., "Entrée", "Hub Central").
     */
    @Column(name = "label", length = 100)
    public String label;

    /**
     * The type of the node (ENTRANCE, INTERSECTION, DEADEND).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false, length = 20)
    @NotNull(message = "Node type is mandatory")
    public PlanNodeType nodeType;

    // --------------------------------------------------
    // Geometry (plan coordinates, meters, origin = building center)
    // --------------------------------------------------

    /**
     * The X coordinate of the node on the store plan, in meters.
     */
    @Column(name = "pos_x", nullable = false)
    @NotNull(message = "X coordinate is mandatory")
    public Double x;

    /**
     * The Y coordinate of the node on the store plan, in meters.
     */
    @Column(name = "pos_y", nullable = false)
    @NotNull(message = "Y coordinate is mandatory")
    public Double y;

    // --------------------------------------------------
    // Link to Building
    // --------------------------------------------------

    /**
     * The Building containing this node.
     * <p>
     * The plan is scoped to a single building.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "building_id", nullable = false)
    @NotNull(message = "Building is mandatory")
    public Building building;

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a node by its code within a specific building.
     *
     * @param building The building context.
     * @param code     The node code.
     * @return The PlanNode entity or null.
     */
    public static PlanNode findByCode(Building building, String code) {
        return find("building = ?1 and code = ?2", building, code).firstResult();
    }

    /**
     * Lists all nodes of a building, ordered by code.
     *
     * @param building The building context.
     * @return The list of nodes (never null).
     */
    public static List<PlanNode> listByBuilding(Building building) {
        return find("building = ?1 order by code", building).list();
    }

    /**
     * Deletes all nodes of a building.
     *
     * @param building The building context.
     * @return The number of deleted nodes.
     */
    public static long deleteByBuilding(Building building) {
        return delete("building = ?1", building);
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    @Override
    public int getChecksum() {
        // Uses the building's natural key (buildingCode), not the surrogate id:
        // building is a lazy @ManyToOne, and reading .id via field access on a
        // Hibernate proxy returns null, which would corrupt the checksum.
        return Objects.hash(
                code,
                label,
                nodeType,
                x,
                y,
                building != null ? building.buildingCode : null
        );
    }
}
