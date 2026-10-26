package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;

/**
 * Entity representing a Waypoint (intermediate point) of an {@link Aisle}.
 * <p>
 * Waypoints define the geometric path of an aisle between its head and tail
 * nodes. For a STRAIGHT aisle they are intermediate points the path must go
 * through; for a CURVED aisle, the first two waypoints act as the control
 * points of a cubic Bézier curve.
 * <p>
 * Waypoints are ordered by their {@link #position} index.
 * <p>
 * This class extends {@link BaseEntity}.
 */
@Entity
@Table(name = "aisle_waypoints",
        indexes = {
                @Index(name = "idx_aisle_waypoint_aisle", columnList = "aisle_id")
        }
)
@Cacheable
public class AisleWaypoint extends BaseEntity {

    /**
     * The aisle owning this waypoint.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "aisle_id", nullable = false)
    @NotNull(message = "Aisle is mandatory")
    public Aisle aisle;

    /**
     * The ordinal position of the waypoint along the aisle path (0-based).
     */
    @Column(name = "position", nullable = false)
    @NotNull(message = "Position is mandatory")
    public Integer position;

    /**
     * The X coordinate of the waypoint on the store plan, in meters.
     */
    @Column(name = "pos_x", nullable = false)
    @NotNull(message = "X coordinate is mandatory")
    public Double x;

    /**
     * The Y coordinate of the waypoint on the store plan, in meters.
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
