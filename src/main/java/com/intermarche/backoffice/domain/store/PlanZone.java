package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Entity representing a geometric Zone of a store plan.
 * <p>
 * A zone is a named, colored polygonal area of the building (e.g., "Rayon Frais",
 * "Surgelés"). The polygon is defined by an ordered list of
 * {@link PlanZoneVertex} (minimum 3 vertices).
 * <p>
 * A zone can optionally be linked to a {@link TemperatureZone} to reflect the
 * storage temperature of the area.
 * <p>
 * This class extends {@link BaseEntity}.
 */
@Entity
@Table(name = "plan_zones",
        indexes = {
                @Index(name = "idx_plan_zone_code", columnList = "code", unique = true),
                @Index(name = "idx_plan_zone_building", columnList = "building_id")
        }
)
@Cacheable
public class PlanZone extends BaseEntity {

    // --------------------------------------------------
    // Identification
    // --------------------------------------------------

    /**
     * The unique code of the zone (e.g., "FRESH_AREA").
     */
    @Column(name = "code", unique = true, nullable = false, length = 50)
    @NotBlank(message = "Zone code is mandatory")
    public String code;

    /**
     * The display label of the zone (e.g., "Rayon Frais").
     */
    @Column(name = "label", length = 100)
    public String label;

    /**
     * The display color of the zone (hex format, e.g., "#0891b2").
     */
    @Column(name = "color", length = 20)
    public String color;

    /**
     * The temperature zone associated with this area (nullable).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "temperature_zone", length = 20)
    public TemperatureZone temperatureZone;

    // --------------------------------------------------
    // Geometry
    // --------------------------------------------------

    /**
     * The ordered vertices of the zone polygon (minimum 3).
     * <p>
     * Managed by the parent Zone. Cascades delete operations.
     */
    @OneToMany(mappedBy = "zone", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @BatchSize(size = 50)
    public List<PlanZoneVertex> vertices = new ArrayList<>();

    /**
     * The horizontal offset of the zone label from the polygon centroid, in meters.
     */
    @Column(name = "label_offset_x")
    public Double labelOffsetX;

    /**
     * The vertical offset of the zone label from the polygon centroid, in meters.
     */
    @Column(name = "label_offset_y")
    public Double labelOffsetY;

    // --------------------------------------------------
    // Link to Building
    // --------------------------------------------------

    /**
     * The Building containing this zone.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "building_id", nullable = false)
    @NotNull(message = "Building is mandatory")
    public Building building;

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a zone by its code within a specific building.
     *
     * @param building The building context.
     * @param code     The zone code.
     * @return The PlanZone entity or null.
     */
    public static PlanZone findByCode(Building building, String code) {
        return find("building = ?1 and code = ?2", building, code).firstResult();
    }

    /**
     * Lists all zones of a building, ordered by code.
     *
     * @param building The building context.
     * @return The list of zones (never null).
     */
    public static List<PlanZone> listByBuilding(Building building) {
        return find("building = ?1 order by code", building).list();
    }

    /**
     * Deletes all zones of a building.
     *
     * @param building The building context.
     * @return The number of deleted zones.
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
        int verticesHash = 0;
        if (vertices != null) {
            for (PlanZoneVertex v : vertices) {
                verticesHash = 31 * verticesHash + v.getChecksum();
            }
        }
        return Objects.hash(
                code,
                label,
                color,
                temperatureZone,
                verticesHash,
                labelOffsetX,
                labelOffsetY,
                building != null ? building.buildingCode : null
        );
    }
}
