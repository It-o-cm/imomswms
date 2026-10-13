package com.intermarche.backoffice.domain.store;

/**
 * Enumeration representing the temperature zone required for storing and handling products.
 * <p>
 * This classification determines the storage location in the warehouse or store (e.g., dry storage,
 * cold room, freezer) as well as the type of bin (standard or insulated) required for order
 * preparation and transport.
 * </p>
 */
public enum TemperatureZone {

    /**
     * Ambient temperature zone.
     * <p>
     * Products that do not require refrigeration and are stored at room temperature.
     * Typically includes dry goods such as pasta, rice, canned food, and beverages.
     * </p>
     */
    AMBIENT,

    /**
     * Chilled temperature zone.
     * <p>
     * Products requiring a positive cold temperature (typically between 0°C and 4°C).
     * Includes fresh meat, dairy products, deli items, and fresh produce.
     * These products must be kept in insulated bins during order preparation.
     * </p>
     */
    CHILLED,

    /**
     * Frozen temperature zone.
     * <p>
     * Products requiring a negative cold temperature (typically -18°C or lower).
     * Includes frozen vegetables, ice cream, and frozen meats.
     * These products require insulated bins with increased ice pack capacity.
     * </p>
     */
    FROZEN,

    /**
     * Household and non-food zone.
     * <p>
     * Non-food items, primarily cleaning supplies and home maintenance products.
     * Although stored at ambient temperature, these are often handled and stored separately
     * from edible ambient goods to prevent chemical contamination or odor transfer.
     * </p>
     */
    HOUSEHOLD
}