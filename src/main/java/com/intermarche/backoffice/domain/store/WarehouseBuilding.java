package com.intermarche.backoffice.domain.store;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

/**
 * Specific implementation for a Warehouse Building.
 * <p>
 * Represents the logistics center used for storage and distribution.
 * <p>
 * This class currently acts as a placeholder for specific WMS (Warehouse Management System)
 * attributes related to logistics, bins, and packaging.
 */
@Entity
@DiscriminatorValue("WAREHOUSE")
public class WarehouseBuilding extends Building {
    // Fields will be defined based on specific requirements.

    /**
     * Calculates a checksum including base physical components and warehouse-specific fields.
     *
     * @return An integer hash representing the state.
     */
    @Override
    public int getChecksum() {
        return super.getChecksum();
    }
}