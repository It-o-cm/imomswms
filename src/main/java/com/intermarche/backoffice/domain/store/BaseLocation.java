package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.order.Product;
import io.smallrye.graphql.api.Union;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.graphql.Interface;
import org.eclipse.microprofile.graphql.Type;

import java.util.List;
import java.util.Objects;

/**
 * Unified Entity representing the physical location of a Product within a Building.
 * <p>
 * This class replaces the need for separate StoreLocation and WarehouseLocation tables.
 * It uses a SINGLE_TABLE inheritance strategy where the 'type' column distinguishes between
 * 'SHELF' (Store) and 'BIN' (Warehouse) locations.
 * <p>
 * Linked to a {@link Building}, which can be a {@link StoreBuilding} or {@link WarehouseBuilding}.
 */
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "type", discriminatorType = DiscriminatorType.STRING)
@Table(name = "item_locations",
        indexes = {
                @Index(name = "idx_loc_building", columnList = "building_id"),
                @Index(name = "idx_loc_product", columnList = "product_id"),
                @Index(name = "idx_loc_code", columnList = "location_code"),
                @Index(name = "idx_loc_type", columnList = "type")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_item_loc", columnNames = {"building_id", "product_id", "type"})
        }
)
@Union
public abstract class BaseLocation extends BaseEntity implements Location {

    /**
     * The Product located at this position.
     */
    @ManyToOne
    @NotNull
    public Product product;

    /**
     * Generated human-readable code: AREA-AISLE-UNIT-ELEVATION-POSITION
     * <p>
     * Example Store: EPICERIE-A05-B-2-05
     * Example Warehouse: COLD-A-12-03-05
     */
    @Column(name = "location_code", length = 60)
    public String locationCode;

    public static class LocationSpecification {
        public final Store store;
        public final Building building;
        public final String area;
        public final String aisle;

        public LocationSpecification(Store store, Building building, String area, String aisle) {
            this.store = store;
            this.building = building;
            this.area = area;
            this.aisle = aisle;
        }
    }

    public static LocationSpecification findCommonSpecification(List<? extends BaseLocation> locations) {
        if (locations.isEmpty()) {
            return new LocationSpecification(null, null, null, null);
        }
        List<? extends Building> buildings = findBuildingsByLocations(locations);
        if (buildings.size() != 1) {
            return new LocationSpecification(null, null, null, null);
        }
        Building building = buildings.get(0);
        Store store = building==null ? null : Building.findStoreByBuildingId(building.id);
        String commonArea = null;
        String commonAisle = null;
        boolean allSameArea = true;
        boolean allSameAisle = true;
        for (BaseLocation loc : locations) {
            String currentArea = (loc instanceof ShelfLocation shelfLoc) ? shelfLoc.area : null;
            if (commonArea == null) commonArea = currentArea;
            else if (!Objects.equals(commonArea, currentArea)) allSameArea = false;

            String currentAisle = (loc instanceof ShelfLocation shelfLoc) ? shelfLoc.aisle : null;
            if (commonAisle == null) commonAisle = currentAisle;
            else if (!Objects.equals(commonAisle, currentAisle)) allSameAisle = false;

            if (!allSameArea && !allSameAisle) {
                return new LocationSpecification(store, null, null, null);
            }
        }
        if (allSameAisle && allSameArea) return new LocationSpecification(store, building, commonArea, commonAisle);
        if (allSameArea) return new LocationSpecification(store, building, commonArea, null);
        return new LocationSpecification(store, building, null, null);
    }

    /**
     * Retrieves the distinct list of Buildings that contain at least one of the specified locations.
     * <p>
     * This method handles the unidirectional relationship (Building -> ShelfLocation) by starting
     * the JPQL query from the Building entity and filtering via a JOIN on its locations collection.
     *
     * @param locations A list of {@link BaseLocation} (or subtypes like ShelfLocation).
     * @return A distinct list of {@link Building} entities. Returns an empty list if input is null/empty.
     */
    public static List<Building> findBuildingsByLocations(List<? extends BaseLocation> locations) {
        if (locations == null || locations.isEmpty()) {
            return List.of();
        }
        // 1. Extraire les IDs des localisations de manière sûre (ignore les nulls au cas où)
        List<Long> locationIds = locations.stream()
                .map(loc -> loc.id)
                .filter(Objects::nonNull)
                .toList();
        if (locationIds.isEmpty()) {
            return List.of();
        }
        String jpql = "SELECT DISTINCT b FROM Building b JOIN b.locations loc WHERE loc.id IN ?1";
        return find(jpql, locationIds).list();
    }

}