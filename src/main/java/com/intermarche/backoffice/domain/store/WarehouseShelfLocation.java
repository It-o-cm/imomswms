package com.intermarche.backoffice.domain.store;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;

/**
 * Specific location for a Warehouse Bin.
 * <p>
 * Adds logistics-specific attributes like weight capacity and Pallet capacity.
 */
@Entity
@DiscriminatorValue("BIN")
public class WarehouseShelfLocation extends ShelfLocation {

    /**
     * Maximum weight this location can hold (kg).
     * Critical for warehouse safety and robot/AGV navigation.
     */
    @Column(name = "max_weight_kg")
    public Double maxWeightKg;

    /**
     * Maximum number of pallets this bin can hold.
     */
    @Column(name = "max_pallets")
    @NotNull
    @Min(1)
    public Integer maxPallets = 1;

    /**
     * Checks if this location requires equipment (Forklift) to access.
     * Assuming levels 1 and 2 are accessible by hand/pallet jack.
     *
     * @return true if elevation is strictly greater than 2.
     */
    public boolean requiresForklift() {
        try {
            return this.elevation != null && Integer.parseInt(this.elevation) > 2;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Calculates a checksum including base physical components and warehouse-specific fields.
     *
     * @return An integer hash representing the state.
     */
    @Override
    public int getChecksum() {
        // Combine the checksum from the parent (BaseLocation) with local fields
        return Objects.hash(super.getChecksum(), maxWeightKg, maxPallets);
    }
}