package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;

/**
 * Entity representing a Vertex (corner) of a {@link PlanZone} polygon.
 * <p>
 * Vertices are ordered by their {@link #position} index to form the closed
 * polygon of the zone.
 * <p>
 * This class extends {@link BaseEntity}.
 */
@Entity
@Table(name = "plan_zone_vertices",
        indexes = {
                @Index(name = "idx_plan_zone_vertex_zone", columnList = "zone_id")
        }
)
@Cacheable
public class PlanZoneVertex extends BaseEntity {

    /**
     * The zone owning this vertex.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id", nullable = false)
    @NotNull(message = "Zone is mandatory")
    public PlanZone zone;

    /**
     * The ordinal position of the vertex in the polygon (0-based).
     */
    @Column(name = "position", nullable = false)
    @NotNull(message = "Position is mandatory")
    public Integer position;

    /**
     * The X coordinate of the vertex on the store plan, in meters.
     */
    @Column(name = "pos_x", nullable = false)
    @NotNull(message = "X coordinate is mandatory")
    public Double x;

    /**
     * The Y coordinate of the vertex on the store plan, in meters.
     */
    @Column(name = "pos_y", nullable = false)
    @NotNull(message = "Y coordinate is mandatory")
    public Double y;

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    @Override
    public int getChecksum() {
        return Objects.hash(position, x, y);
    }
}
