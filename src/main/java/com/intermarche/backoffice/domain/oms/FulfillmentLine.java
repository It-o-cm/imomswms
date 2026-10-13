package com.intermarche.backoffice.domain.oms;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.order.OrderLine;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Entity representing a line item within a Fulfillment Chunk.
 * <p>
 * This entity acts as a flattened copy of an {@link OrderLine}.
 * It retains essential business data (Product, Quantity) but intentionally excludes the
 * 'specification' JSON payload to optimize storage and performance for the fulfillment process.
 * <p>
 * <b>Relationship:</b> This entity does NOT hold a reference to the parent {@link FulfillmentChunk}.
 * It is a standalone entity managed via a join table defined in the parent.
 * <p>
 * Extends {@link BaseEntity} for ID generation, versioning, and auditing.
 */
@Entity
@Table(name = "fulfillment_lines",
        indexes = {
                @Index(name = "idx_fline_product", columnList = "product_id")
        }
)
public class FulfillmentLine extends BaseEntity {

    // --------------------------------------------------
    // Line Details (Copied from OrderLine)
    // --------------------------------------------------

    /**
     * The sequence number of the line within the context of the fulfillment.
     * <p>
     * Copied from {@link OrderLine#lineNumber}.
     * Used to preserve the order of items as added by the customer.
     */
    @Column(name = "line_number", nullable = false)
    public int lineNumber;

    /**
     * The Product being fulfilled.
     * <p>
     * Links to the master product catalog. Can be null if the product was deleted
     * from the catalog but the historical fulfillment line must be preserved.
     * Copied from {@link OrderLine#product}.
     */
    @ManyToOne
    @JoinColumn(name = "product_id", nullable = false, foreignKey = @ForeignKey(name = "fk_fline_product"))
    public Product product;

    /**
     * The quantity requested for fulfillment.
     * <p>
     * Uses BigDecimal for precision (supports weight in kg, volume in L, or unit count).
     * Copied from {@link OrderLine#requestedQuantity}.
     */
    @Column(name = "requested_quantity", nullable = false, precision = 10, scale = 3)
    @NotNull
    public BigDecimal requestedQuantity;


    /**
     * Represents the geographical or logistical location associated with this fulfillment chunk.
     * This could refer to a specific section, area, or broader region within the fulfillment
     * operation where the items in the chunk are handled or stored.
     *
     * The location is modeled as a many-to-one relationship, indicating that multiple chunks
     * can share the same location.
     */
    @ManyToOne
    @JoinColumn(name = "location_id")
    public BaseLocation location = null;

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum representing the current state of the fulfillment line.
     * <p>
     * The checksum is based on the line number, product ID, and requested quantity.
     * It is used by {@link BaseEntity} lifecycle callbacks to detect data changes.
     *
     * @return An integer hash representing the line's state.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                lineNumber,
                product != null ? product.id : null,
                location != null ? location.id : null,
                requestedQuantity
        );
    }
}