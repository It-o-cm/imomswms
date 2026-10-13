package com.intermarche.backoffice.domain.order;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Entity representing a Customer Order.
 * <p>
 * Acts as the aggregate root for the order transaction.
 * It links to a {@link Store}, a {@link Customer}, and a list of {@link OrderLine}s.
 * <p>
 * The full JSON payload (header, customer, fulfillment, summary) is stored
 * in the 'specification' field for audit trail and flexible access.
 */
@Entity
@Table(name = "orders",
        indexes = {
                @Index(name = "idx_order_id", columnList = "order_id", unique = true),
                @Index(name = "idx_order_date", columnList = "order_date"),
                @Index(name = "idx_order_status", columnList = "status"),
                @Index(name = "idx_order_customer", columnList = "customer_id")
        }
)
public class Order extends BaseEntity {

    // --------------------------------------------------
    // Business Identity
    // --------------------------------------------------

    /**
     * The unique business identifier for the order (e.g., "ORD-2023-1024-8971").
     * This is distinct from the technical database ID and is used for external lookups.
     */
    @Column(name = "order_id", unique = true, nullable = false, length = 50)
    public String orderId;

    /**
     * The date and time the order was placed (business date).
     * Used for reporting and filtering.
     */
    @Column(name = "order_date", nullable = false)
    public LocalDateTime orderDate;

    /**
     * The channel through which the order was placed (e.g., "ECOMMERCE_APP", "POS_TERMINAL").
     */
    @Column(name = "channel", nullable = false, length = 30)
    public String channel;

    /**
     * Current status of the order (e.g., "VALIDATED", "IN_PROGRESS", "SHIPPED").
     */
    @Column(name = "status", nullable = false, length = 30)
    public String status;

    // --------------------------------------------------
    // Relationships
    // --------------------------------------------------

    /**
     * The Store fulfilling the order.
     */
    @ManyToOne
    @JoinColumn(name = "store_id", nullable = false, foreignKey = @ForeignKey(name = "fk_order_store"))
    public Store store;

    /**
     * The Customer who placed the order.
     * Can be null for anonymous transactions if business rules allow.
     */
    @ManyToOne
    @JoinColumn(name = "customer_id", foreignKey = @ForeignKey(name = "fk_order_customer"))
    public Customer customer;

    /**
     * The list of items in the order.
     * <p>
     * Cascade ALL ensures that persisting an Order also persists its lines.
     * Orphan removal ensures that removing a line from the list deletes it from the database.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", foreignKey = @ForeignKey(name = "fk_order_orderline"))
    public Set<OrderLine> lines = new HashSet<>();

    // --------------------------------------------------
    // Data Payload
    // --------------------------------------------------

    /**
     * Raw JSON representation of the order details that are not mapped to columns.
     * <p>
     * Includes: orderHeader (denormalized), fulfillment details, packaging, and financial summary.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "specification", columnDefinition = "json")
    public String specification;

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Helper method to add a line to the order.
     * <p>
     * This maintains the bidirectional consistency: it sets the 'order' reference
     * on the OrderLine and adds the line to the Order's list.
     *
     * @param line The OrderLine to add to this order.
     */
    public void addLine(OrderLine line) {
        this.lines.add(line);
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum based on the order's business fields.
     *
     * @return An integer hash representing the order's state.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(orderId, orderDate, channel, status,
                store != null ? store.id : null,
                customer != null ? customer.id : null,
                specification);
    }
}