package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

/**
 * Specific implementation for a Retail Store Building.
 * <p>
 * Represents the physical shop where customer orders are fulfilled.
 * <p>
 * This class currently acts as a placeholder for specific OMS (Order Management System)
 * attributes related to store-based order assembly and device configuration.
 */
@Entity
@DiscriminatorValue("STORE")
public class StoreBuilding extends Building {

    /**
     * Calculates a checksum including base physical components and store-specific fields.
     *
     * @return An integer hash representing the state.
     */
    @Override
    public int getChecksum() {
        return super.getChecksum();
    }

}