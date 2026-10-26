package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.*;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Abstract base class for a Physical Building.
 * <p>
 * Represents a physical structure owned by a {@link Store}.
 * Uses SINGLE_TABLE inheritance strategy to map {@link StoreBuilding} and {@link WarehouseBuilding}
 * to the same database table.
 * <p>
 * Contains the full physical definition: Postal Address and GPS Location.
 * Owns a list of {@link BaseLocation}s.
 */
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "type", discriminatorType = DiscriminatorType.STRING)
@Table(name = "buildings")
public abstract class Building extends BaseEntity {
    /** Concrete business kind, including when GraphQL exposes the base Building type. */
    public String getBuildingType(){return WarehouseBuilding.class.isAssignableFrom(org.hibernate.Hibernate.getClass(this))?"WarehouseBuilding":"StoreBuilding";}


    /**
     * Physical postal address of the building.
     * <p>
     * Includes street, city, and postal code.
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "streetLine1", column = @Column(name = "address_street_line1")),
            @AttributeOverride(name = "streetLine2", column = @Column(name = "address_street_line2")),
            @AttributeOverride(name = "postalCode", column = @Column(name = "address_postal_code")),
            @AttributeOverride(name = "city", column = @Column(name = "address_city")),
            @AttributeOverride(name = "country", column = @Column(name = "address_country"))
    })
    public Adresse address;

    /**
     * Physical GPS location coordinates.
     * <p>
     * Used for navigation systems, maps, and logistics routing.
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "latitude", column = @Column(name = "gps_latitude")),
            @AttributeOverride(name = "longitude", column = @Column(name = "gps_longitude"))
    })
    public GPSPosition gpsPosition;

    /**
     * The specific code for this building (e.g., "LYON-01" for a shop, "LYON-WH" for warehouse).
     * <p>
     * Distinct from the business entity code.
     */
    @Column(name = "building_code", length = 50, nullable = false)
    public String buildingCode;

    /**
     * Background image of the building's store plan (PNG encoded in base64), or null if none.
     * <p>
     * Seeded by the demo data initializer; not part of the CSV import, so it is
     * deliberately excluded from {@link #getChecksum()}.
     */
    @Column(name = "plan_image", columnDefinition = "text")
    public String planImage;

    /**
     * World X coordinate (in meters) of the top-left corner of the plan background image.
     * <p>
     * Only meaningful when {@link #planImage} is set.
     */
    @Column(name = "plan_image_x")
    public double planImageX;

    /**
     * World Y coordinate (in meters) of the top-left corner of the plan background image.
     * <p>
     * Only meaningful when {@link #planImage} is set.
     */
    @Column(name = "plan_image_y")
    public double planImageY;

    /**
     * Scale factor applied to the plan background image (1 = natural size).
     * <p>
     * Only meaningful when {@link #planImage} is set. Not part of the CSV import,
     * so it is deliberately excluded from {@link #getChecksum()}.
     */
    @Column(name = "plan_image_scale")
    public double planImageScale = 1;

    /**
     * Rotation of the plan background image, in degrees (around its center).
     * <p>
     * Only meaningful when {@link #planImage} is set. Not part of the CSV import,
     * so it is deliberately excluded from {@link #getChecksum()}.
     */
    @Column(name = "plan_image_rotation")
    public double planImageRotation = 0;

    /**
     * Opacity of the plan background image (0 = transparent, 1 = opaque).
     * <p>
     * Only meaningful when {@link #planImage} is set. Not part of the CSV import,
     * so it is deliberately excluded from {@link #getChecksum()}.
     */
    @Column(name = "plan_image_opacity")
    public double planImageOpacity = 1;

    /**
     * The list of locations (Shelves or Bins) within this building.
     * <p>
     * Managed by the parent Building. Cascades delete operations.
     * <p>
     * <b>Performance Note:</b> Annotated with {@link BatchSize} to optimize loading.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "building_id")
    @BatchSize(size = 50)
    public Set<ShelfLocation> locations = new HashSet<>();

    /**
     * Transient map to optimize location lookups by Product ID during processing.
     * <p>
     * This map is not persisted in the database. It is initialized lazily when
     * {@link #findLocationByProductId(Long)} is first called.
     */
    @Transient
    private Map<Long, ShelfLocation> locationIndexMap;

    /**
     * Finds a building by its unique building code.
     *
     * @param buildingCode The unique code associated with the building.
     * @return The {@link Building} identified by the given code, or null if no building is found.
     */
    public static Building findByCode(String buildingCode) {
        return find("buildingCode", buildingCode).firstResult();
    }

    /**
     * Retrieves a location within this building that stores the specified product.
     * <p>
     * Initializes an internal lookup map on first access for $O(1)$ performance.
     *
     * @param productId The ID of the product to locate.
     * @return The {@link BaseLocation} associated with the product, or null if not found.
     */
    public ShelfLocation findLocationByProductId(Long productId) {
        if (this.locationIndexMap == null) {
            initializeLocationIndexMap();
        }
        return this.locationIndexMap.get(productId);
    }

    /**
     * Initializes the transient map indexing locations by product ID.
     * <p>
     * If multiple locations exist for the same product, the last one iterated will be kept
     * (order is not guaranteed in a Set).
     */
    private void initializeLocationIndexMap() {
        this.locationIndexMap = new HashMap<>();
        if (this.locations != null) {
            for (ShelfLocation location : this.locations) {
                if (location.product != null && location.product.id != null) {
                    this.locationIndexMap.put(location.product.id, location);
                }
            }
        }
    }

    /**
     * Calculates a checksum representing the current state of the building.
     * <p>
     * Covers exactly the fields imported by the Building CSV: the building code and the
     * checksums of the embedded objects (address, GPS position). The set of locations is
     * managed by the Location CSV import and is deliberately excluded.
     *
     * @return An integer hash representing the building's state.
     */
    @Override
    public int getChecksum() {
        int addressHash = (address == null) ? 0 : address.getChecksum();
        int locationHash = (gpsPosition == null) ? 0 : gpsPosition.getChecksum();

        return Objects.hash(
                buildingCode,
                addressHash,
                locationHash
        );
    }

    /**
     * Finds the store associated with a specific building ID.
     *
     * @param buildingId The unique identifier of the building for which to find the associated store.
     *                   If null, the method will return null.
     * @return The {@link Store} entity associated with the provided building ID,
     *         or null if no store is found or if the building ID is null.
     */
    public static Store findStoreByBuildingId(Long buildingId) {
        if (buildingId == null) return null;
        return find("SELECT s FROM Store s JOIN s.buildings b WHERE b.id = ?1", buildingId).firstResult();
    }

}