package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.*;

import java.util.List;
import java.util.Objects;

/**
 * Unified Entity representing the physical location of a Product within a Store system using shelves.
 * <p>
 * This class replaces the need for separate StoreLocation and WarehouseLocation tables.
 * It uses a SINGLE_TABLE inheritance strategy where the 'type' column distinguishes between
 * 'SHELF' (Store) and 'BIN' (Warehouse) locations.
 * <p>
 * Linked to a {@link Building}, which can be a {@link StoreBuilding} or {@link WarehouseBuilding}.
 */
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
public abstract class ShelfLocation extends BaseLocation {

    // --------------------------------------------------
    // Generic Address Components (Unified Hierarchy)
    // --------------------------------------------------

    /**
     * Level 1: The broad area.
     * <p>
     * In Store (StoreBuilding): Department (e.g., "FRUITS_ET_LEGUMES").
     * In Warehouse (WarehouseBuilding): Zone (e.g., "COLD", "RECEIPT").
     */
    @Column(name = "area", length = 50)
    public String area;

    /**
     * Level 2: The longitudinal corridor.
     * <p>
     * Common to both: Aisle (e.g., "A03", "05").
     */
    @Column(name = "aisle", length = 10)
    public String aisle;

    /**
     * Retrieves the aisle identifier associated with this shelf location.
     *
     * @return the aisle identifier as a String. If the aisle is not set, it may return null.
     */
    @Override
    public String getAisle() {
        return aisle;
    }

    /**
     * Retrieves the area identifier associated with this shelf location.
     *
     * @return the area identifier as a String. If the area is not set, it may return null.
     */
    @Override
    public String getArea() {
        return area;
    }

    /**
     * Level 3: The structural unit.
     * <p>
     * In Store: Bay/Gondole (e.g., "B").
     * In Warehouse: Rack (e.g., "R12").
     */
    @Column(name = "unit", length = 10)
    public String unit;

    /**
     * Level 4: The vertical height.
     * <p>
     * In Store: Shelf Level (e.g., "1" bottom, "2" eye-level).
     * In Warehouse: Level (e.g., "1" floor, "3" top).
     */
    @Column(name = "elevation", length = 10)
    public String elevation;

    /**
     * Level 5: The precise horizontal position.
     * <p>
     * In Store: Slot number (e.g., "5").
     * In Warehouse: Bin number (e.g., "12").
     */
    @Column(name = "position", length = 10)
    public String position;

    /**
     * Calculates a checksum based on the common physical components.
     * <p>
     * Subclasses should call this method via {@code super.getChecksum()} and
     * combine the result with their own specific fields.
     *
     * @return An integer hash representing the common state.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(area, aisle, unit, elevation, position);
    }

    // --------------------------------------------------
    // Lifecycle Callbacks
    // --------------------------------------------------

    @Override
    @PrePersist
    @PreUpdate
    public void onCreate() {
        super.onCreate();
        StringBuilder code = new StringBuilder();
        if (area != null && !area.isBlank()) code.append(area).append("-");
        else code.append("??-");
        if (aisle != null) code.append(aisle).append("-");
        else code.append("??-");
        if (unit != null) code.append(unit).append("-");
        else code.append("??-");
        if (elevation != null) code.append(elevation).append("-");
        else code.append("??-");
        if (position != null) code.append(position);
        else code.append("??");
        this.locationCode = code.toString();
    }


    /**
     * Retrieves a list of {@link ShelfLocation} associated with the specified product ID.
     *
     * @param productId the ID of the product whose shelf locations are to be retrieved
     * @return a list of {@link ShelfLocation} entities associated with the given product ID
     */
    public static List<ShelfLocation> findByProductId(Long productId) {
        return find("product.id", productId).list();
    }


    // --------------------------------------------------
    // INNER PROJECTION CLASS
    // --------------------------------------------------

    /**
         * Internal class representing a pair of a {@link ShelfLocation} and its parent {@link Building}.
         * <p>
         * Used as a JPQL projection target because {@link ShelfLocation} does not hold a direct
         * back-reference to {@link Building}.
         */
    public record BuildingAndLocation(Building building, ShelfLocation location) {
    }

    // --------------------------------------------------
    // QUERY & BUSINESS LOGIC
    // --------------------------------------------------

    /**
     * Retrieves the closest {@link ShelfLocation}s for a given product based on a hierarchical target (Building/Area/Aisle).
     * <p>
     * The method uses a single JPQL query strictly filtered by {@link Store} and {@link Product} to ensure
     * database-level performance. The hierarchical filtering is then applied in memory on the resulting small dataset.
     * <p>
     * <b>Fallback Strategy:</b>
     * <ul>
     *   <li>Level 3: Exact match (Building + Area + Aisle)</li>
     *   <li>Level 2: Drops Aisle (Building + Area)</li>
     *   <li>Level 1: Drops Area (Building only)</li>
     *   <li>Level 0: Fallback to any location in the Store</li>
     * </ul>
     *
     * @param store    The mandatory store context to limit the search scope.
     * @param product  The mandatory product to locate.
     * @param building The target building (can be null).
     * @param area     The target area (can be null, but logically requires a building).
     * @param aisle    The target aisle (can be null, but logically requires an area).
     * @return A list of matching ShelfLocations at the highest achieved precision level, or an empty list.
     */
    public static List<ShelfLocation> findClosestForProduct(Store store, Product product, Building building, String area, String aisle) {
        if (store == null || store.id == null || product == null || product.id == null) {
            return List.of();
        }
        String jpql = "SELECT new com.intermarche.backoffice.domain.store.ShelfLocation$BuildingAndLocation(b, loc) " +
                "FROM Store s JOIN s.buildings b JOIN b.locations loc " +
                "WHERE s.id = ?1 AND loc.product.id = ?2";
        List<BuildingAndLocation> candidates = getEntityManager().createQuery(jpql, BuildingAndLocation.class)
                .setParameter(1, store.id)
                .setParameter(2, product.id)
                .getResultList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        String targetArea = (area != null && !area.isBlank()) ? area : null;
        String targetAisle = (aisle != null && !aisle.isBlank()) ? aisle : null;
        if (building != null && building.id != null && targetArea != null && targetAisle != null) {
            List<ShelfLocation> matches = candidates.stream()
                    .filter(c -> building.id.equals(c.building.id)
                            && targetArea.equals(c.location.area)
                            && targetAisle.equals(c.location.aisle))
                    .map(c -> c.location)
                    .toList();
            if (!matches.isEmpty()) return matches;
        }
        if (building != null && building.id != null && targetArea != null) {
            List<ShelfLocation> matches = candidates.stream()
                    .filter(c -> building.id.equals(c.building.id)
                            && targetArea.equals(c.location.area))
                    .map(c -> c.location)
                    .toList();
            if (!matches.isEmpty()) return matches;
        }
        if (building != null && building.id != null) {
            List<ShelfLocation> matches = candidates.stream()
                    .filter(c -> building.id.equals(c.building.id))
                    .map(c -> c.location)
                    .toList();
            if (!matches.isEmpty()) return matches;
        }
        return candidates.stream().map(c -> c.location).toList();
    }


}