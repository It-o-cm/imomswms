package com.intermarche.backoffice.domain.wms;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import io.smallrye.graphql.api.Union;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Entity representing a line within a Lot.
 * <p>
 * Links to a specific {@link FulfillmentLine} and directly to the {@link Product}.
 * <p>
 * <b>Constraints:</b>
 * <ul>
 *   <li>Does NOT hold a reference to the parent {@link Lot} (Unidirectional).</li>
 *   <li>{@link FulfillmentLine} is Strictly Mandatory (Not Null).</li>
 *   <li>{@link Product} is Strictly Mandatory (Not Null).</li>
 * </ul>
 */
@Entity
@Table(name = "lot_lines",
        indexes = {
                @Index(name = "idx_lotline_fline", columnList = "fulfillment_line_id"),
                @Index(name = "idx_lotline_product", columnList = "product_id")
        }
)
public class LotLine extends BaseEntity {

    public LotLine() {
    }
    /**
     * The quantity picked or processed for this specific line within the Lot.
     */
    @Column(name = "picked_quantity", precision = 10, scale = 3)
    public BigDecimal quantity = BigDecimal.ZERO;

    // --------------------------------------------------
    // Relationships
    // --------------------------------------------------

    /**
     * The reference to the original FulfillmentLine.
     * <b>Strictly Mandatory:</b> A LotLine must reference a valid FulfillmentLine.
     */
    @ManyToOne
    @JoinColumn(name = "fulfillment_line_id", foreignKey = @ForeignKey(name = "fk_lotline_fline"))
    public FulfillmentLine fulfillmentLine;

    /**
     * Direct reference to the Product associated with this line.
     * <b>Strictly Mandatory:</b> Allows quick access to product details without joining the FulfillmentLine.
     */
    @ManyToOne
    @JoinColumn(name = "product_id", nullable = false, foreignKey = @ForeignKey(name = "fk_lotline_product"))
    public Product product;

    /**
     * The physical location where this Lot line is stored or staged.
     * <b>Mandatory:</b> A Lot line must be assigned to a specific physical location.
     */
    @ManyToOne
    @JoinColumn(name = "location_id", nullable = false, foreignKey = @ForeignKey(name = "fk_lotline_location"))
    public BaseLocation location;

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Sets the FulfillmentLine associated with the LotLine and updates the location data
     * based on the FulfillmentLine's location.
     *
     * @param fulfillmentLine the FulfillmentLine to be associated with this LotLine.
     *                         Must not be null and should represent a valid fulfillment line.
     * @throws IllegalArgumentException if the FulfillmentLine is null.
     */
    public void setFulfillmentLine(FulfillmentLine fulfillmentLine) {
        if (fulfillmentLine == null) {
            throw new IllegalArgumentException("FulfillmentLine cannot be null.");
        }
        this.fulfillmentLine = fulfillmentLine;
        this.location = fulfillmentLine.location;
    }

    /**
     * Computes a checksum for this LotLine based on its key attributes.
     * This method calculates a hash value using the quantity, the ID of the associated FulfillmentLine (if present),
     * and the ID of the associated Product (if present). It is useful for detecting changes to the LotLine's
     * critical state, which may impact related business logic.
     *
     * @return An integer hash value representing the current state of the LotLine.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                quantity,
                fulfillmentLine != null ? fulfillmentLine.id : null,
                product != null ? product.id : null
        );
    }

}