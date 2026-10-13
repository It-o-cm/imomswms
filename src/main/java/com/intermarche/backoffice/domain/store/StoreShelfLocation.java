package com.intermarche.backoffice.domain.store;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;

/**
 * Specific location for a Retail Store Shelf.
 * <p>
 * Adds retail-specific attributes like Facings (visual width) and Department context.
 */
@Entity
@DiscriminatorValue("SHELF")
public class StoreShelfLocation extends ShelfLocation {

    /**
     * Number of products facing the customer (Width).
     * Only relevant for Store context. Used for Planogram compliance.
     */
    @Column(name = "facings")
    @NotNull
    @Min(1)
    public Integer facings = 1;

    /**
     * Department is crucial for Store navigation apps and category management.
     * Often duplicates the 'area' field for specific categorization logic.
     */
    @Column(name = "department", length = 50)
    public String department;

    /**
     * Checks if this location is at eye level for an average adult.
     *
     * @return true if elevation suggests eye level (typically levels 2 or 3).
     */
    public boolean isEyeLevel() {
        return "2".equals(this.elevation) || "3".equals(this.elevation);
    }

    /**
     * Calculates a checksum including base physical components and store-specific fields.
     *
     * @return An integer hash representing the state.
     */
    @Override
    public int getChecksum() {
        // Combine the checksum from the parent (BaseLocation) with local fields
        return Objects.hash(super.getChecksum(), facings, department);
    }
}