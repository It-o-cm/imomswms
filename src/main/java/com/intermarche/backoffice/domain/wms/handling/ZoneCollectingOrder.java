package com.intermarche.backoffice.domain.wms.handling;

import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.store.Employee;
import jakarta.persistence.*;

import java.util.Objects;

/**
 * Specific implementation of a Handling Order for Collecting (Picking).
 * <p>
 * Specifies a precise geographic zone to target the collecting of lots.
 */
@Entity
@DiscriminatorValue("ZONE_COLLECTING")
public class ZoneCollectingOrder extends CollectingOrder {

    /**
     * The target area for collecting.
     * <p>
     * Allows filtering or grouping tasks by sector (e.g., "FRESH", "AMBIENT", "DRIVE").
     */
    @Column(name = "area", length = 50)
    public String area;

    /**
     * The target aisle for collecting (Optional).
     * <p>
     * Allows finer targeting within the zone.
     */
    @Column(name = "aisle", length = 10)
    public String aisle;

    /**
     * Returns the prefix associated with the ZoneCollectingOrder.
     *
     * @return The string "ZON", representing the prefix specific to ZoneCollectingOrder codes.
     */
    @Override
    public String getPrefix() {
        return "ZON";
    }

    /**
     * Calculates the checksum including specific fields (Building, Area, Aisle).
     *
     * @return A hash representing the state of the order.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                super.getChecksum(), // Includes fields from BaseHandlingOrder and CollectingOrder (status, dates, building, etc.)
                area,
                aisle,
                lots
        );
    }
}