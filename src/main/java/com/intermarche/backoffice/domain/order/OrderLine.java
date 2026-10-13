package com.intermarche.backoffice.domain.order;

import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Entity representing a single line item within an Order.
 * <p>
 * Stores the quantity ordered and a reference to the Product.
 * Detailed pricing, offers, and logistics info are stored in the JSON specification.
 * <p>
 * This entity owns the relationship to {@link Order} via a foreign key.
 */
@Entity
@Table(name = "order_lines",
        indexes = {
                @Index(name = "idx_line_order", columnList = "order_id"),
                @Index(name = "idx_line_product", columnList = "product_id")
        }
)
public class OrderLine extends BaseEntity {

    // --------------------------------------------------
    // Line Details
    // --------------------------------------------------

    /**
     * The line number within the order (sequence).
     * Used to preserve the order of items as added by the customer.
     */
    @Column(name = "line_number", nullable = false)
    public int lineNumber;

    /**
     * The product being ordered.
     * <p>
     * Links to the master product catalog. Can be null if the product was deleted
     * from the catalog but the historical order line must be preserved.
     */
    @ManyToOne
    @JoinColumn(name = "product_id", foreignKey = @ForeignKey(name = "fk_orderline_product"))
    public Product product;

    /**
     * The requested quantity.
     * <p>
     * Using BigDecimal for precision (supports weight in kg, volume in L, or unit count).
     */
    @Column(name = "requested_quantity", nullable = false, precision = 10, scale = 3)
    @NotNull
    public BigDecimal requestedQuantity;

    // --------------------------------------------------
    // Data Payload
    // --------------------------------------------------

    /**
     * Raw JSON representation of the line details.
     * <p>
     * Includes: product snapshot (denormalized), pricing details, applied offers,
     * specific instructions (e.g., butchery), and logistics constraints (temperature).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "specification", columnDefinition = "json")
    public String specification;

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum based on the line's business fields.
     *
     * @return An integer hash representing the line's state.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(lineNumber, product != null ? product.id : null, requestedQuantity, specification);
    }
}