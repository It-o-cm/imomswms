package com.intermarche.backoffice.domain.wms;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Entity representing a physical Lot (Picking Lot).
 * <p>
 * A Lot is a subdivision or a grouping of items from a {@link FulfillmentChunk}.
 * It is physically located at a specific {@link BaseLocation}.
 * <p>
 * <b>Constraints:</b>
 * <ul>
 *   <li>Parent {@link FulfillmentChunk} is Mandatory (Not Null).</li>
 *   <li>{@link BaseLocation} is Mandatory (Not Null).</li>
 *   <li>Linked to a specific {@link Customer} for the purpose of the operation.</li>
 *   <li>Unidirectional OneToMany to {@link LotLine} using a {@link JoinColumn} (No JoinTable).</li>
 *   <li>No Bidirectional relationships.</li>
 * </ul>
 */
@Entity
@Table(name = "lots",
        indexes = {
                @Index(name = "idx_lot_chunk", columnList = "chunk_id"),
                @Index(name = "idx_lot_customer", columnList = "customer_id"),
                @Index(name = "idx_lot_status", columnList = "status")
        }
)
public class Lot extends BaseEntity {

    /**
     * Enumeration representing the current status of the Lot.
     */
    public enum LotStatus {
        /**
         * The lot has been created and is waiting to be assigned to a handling order or picked.
         */
        PENDING,
        /**
         * The lot is planned to be collected
         */
        PLANNED_TO_BE_COLLECTED,
        /**
         * The lot is currently being picked or has been assigned to a tote.
         */
        PLANNED_TO_BE_PACKED,
        /**
         * Indicates that have been gathered or accumulated in a Tote.
         */
        COLLECTED,
        /**
         * Indicates that the order the lot elongs to is packed and ready to be delivered
         */
        PACKED,
        /**
         * Represents the status of a Lot that is on the way to be delivered to the customer.
         */
        DELIVERED,
        /**
         * The lot has been cancelled.
         */
        CANCELED
    }

    /**
     * Current status of the Lot (e.g., "PENDING", "PICKED", "PACKED").
     */
    @Column(name = "status", nullable = false, length = 20)
    public LotStatus status = LotStatus.PENDING;

    // --------------------------------------------------
    // Relationships
    // --------------------------------------------------

    /**
     * The Store to which this Lot belongs.
     * <b>Mandatory:</b> A Lot cannot exist without being associated with a Store.
     */
    @ManyToOne
    @JoinColumn(name = "store_id", nullable = false, foreignKey = @ForeignKey(name = "fk_lot_store"))
    public Store store;

    /**
     * The parent FulfillmentChunk from which this Lot is derived.
     * <b>Mandatory:</b> A Lot cannot exist without a source Chunk.
     */
    @ManyToOne
    @JoinColumn(name = "chunk_id", nullable = false, foreignKey = @ForeignKey(name = "fk_lot_chunk"))
    public FulfillmentChunk chunk;

    /**
     * The parent Fulfillment from which the associated chunk belongs to.
     * <b>Mandatory:</b> A Lot cannot exist without a source fulfillment.
     */
    @ManyToOne
    @JoinColumn(name = "fulfillment_id", nullable = false, foreignKey = @ForeignKey(name = "fk_lot_fulfillment"))
    public Fulfillment fulfillment;

    /**
     * The Customer associated with this Lot.
     * <p>
     * This field is denormalized from the Order/Fulfillment hierarchy to provide direct
     * access to customer information for the picking/delivery workflow without traversing
     * the entire entity graph.
     */
    @ManyToOne
    @JoinColumn(name = "customer_id", nullable = false, foreignKey = @ForeignKey(name = "fk_lot_customer"))
    public Customer customer;

    /**
     * The list of lines contained in this Lot.
     * <p>
     * <b>Unidirectional:</b> Uses {@link JoinColumn} instead of {@link JoinTable}.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "lot_id", foreignKey = @ForeignKey(name = "fk_lotline_lot"))
    public Set<LotLine> lines = new HashSet<>();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Adds a line to this Lot.
     *
     * @param line The LotLine to add.
     */
    public void addLine(LotLine line) {
        this.lines.add(line);
    }

    /**
     * Retrieves the area (e.g., "FRESH", "AMBIENT") of the Lot.
     * <p>
     * Returns the area code only if all lines in this lot share the exact same area.
     * If any line differs in area, or if the location data is missing/invalid for any line, returns null.
     *
     * @return The common area code, or null if areas are mixed or data is missing.
     */
    public String getArea() {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        // Find the first valid area from the first line
        String referenceArea = extractArea(lines.iterator().next());
        if (referenceArea == null) {
            return null;
        }
        // Check if all lines match the reference area
        boolean allSame = lines.stream().allMatch(line -> referenceArea.equals(extractArea(line)));
        return allSame ? referenceArea : null;
    }

    /**
     * Retrieves the aisle (e.g., "A05", "B12") of the Lot.
     * <p>
     * Returns the aisle code only if all lines in this lot share the exact same aisle.
     * If any line differs in aisle, or if the location data is missing/invalid for any line, returns null.
     *
     * @return The common aisle code, or null if aisles are mixed or data is missing.
     */
    public String getAisle() {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        // Find the first valid aisle from the first line
        String referenceAisle = extractAisle(lines.iterator().next());
        if (referenceAisle == null) {
            return null;
        }
        // Check if all lines match the reference aisle
        boolean allSame = lines.stream().allMatch(line -> referenceAisle.equals(extractAisle(line)));
        return allSame ? referenceAisle : null;
    }

    // --------------------------------------------------
    // Internal Helpers for Data Extraction
    // --------------------------------------------------

    /**
     * Safely extracts the area code from a LotLine.
     * <p>
     * Navigates the graph: LotLine -> FulfillmentLine -> BaseLocation -> Area.
     *
     * @param line The LotLine.
     * @return The area code, or null if any relationship is missing.
     */
    private String extractArea(LotLine line) {
        if (line != null
                && line.fulfillmentLine != null
                && line.fulfillmentLine.location != null
                && (line.fulfillmentLine.location instanceof ShelfLocation)) {
            ShelfLocation shelfLocation = (ShelfLocation) line.fulfillmentLine.location;
            return shelfLocation.area;
        }
        return null;
    }

    /**
     * Safely extracts the aisle code from a LotLine.
     * <p>
     * Navigates the graph: LotLine -> FulfillmentLine -> BaseLocation -> Aisle.
     *
     * @param line The LotLine.
     * @return The aisle code, or null if any relationship is missing.
     */
    private String extractAisle(LotLine line) {
        if (line != null
                && line.fulfillmentLine != null
                && line.fulfillmentLine.location != null
                && (line.fulfillmentLine.location instanceof ShelfLocation)) {
            ShelfLocation shelfLocation = (ShelfLocation) line.fulfillmentLine.location;
            return shelfLocation.aisle;
        }
        return null;
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    @Override
    public int getChecksum() {
        return Objects.hash(
                status,
                chunk,
                customer,
                lines
        );
    }

    /**
     * Finds the common location specification for all locations present within the lines of the given lot.
     *
     * @param lot The lot containing lines with associated locations.
     * @return A {@link BaseLocation.LocationSpecification} representing the common specification
     *         of all locations in the lot's lines, or null if no common specification exists.
     */
    public static BaseLocation.LocationSpecification findCommonSpecification(Lot lot) {
        List<? extends BaseLocation> locations = lot.lines.stream().map(l -> l.location).toList();
        return ShelfLocation.findCommonSpecification(locations);
    }

}