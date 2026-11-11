package com.intermarche.backoffice.domain.catering;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.stock.ExpectedSupply;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A purchase need for a {@code SUPPLIED} catering unit (§ 5, F10).
 */
@Entity
@Table(name = "catering_purchase_needs")
public class CateringPurchaseNeed extends BaseEntity {

    /** Lifecycle state of a purchase need. */
    public enum PurchaseNeedState {
        /** Proposed by the server. */
        PROPOSED,
        /** Validated by the manager. */
        VALIDATED,
        /** Received from the supplier. */
        RECEIVED,
        /** Cancelled. */
        CANCELLED
    }

    /** The store the need belongs to. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "store_id")
    public Store store;

    /** The catering unit the need covers. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "unit_id")
    public CateringUnit unit;

    /** Supplier name. */
    @Column(length = 200)
    public String supplierName;

    /** Quantity to order. */
    @Column(nullable = false, precision = 19, scale = 6)
    public BigDecimal quantity;

    /** Latest instant to place the order with the supplier. */
    @Column(name = "orderBy")
    public LocalDateTime orderBy;

    /** Instant the supply is due. */
    @Column(name = "dueAt")
    public LocalDateTime dueAt;

    /** Current state. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public PurchaseNeedState state = PurchaseNeedState.PROPOSED;

    /** The expected supply created at validation. */
    @ManyToOne
    @JoinColumn(name = "expectedSupply_id")
    public ExpectedSupply expectedSupply;

    /**
     * Computes the checksum from the need's business fields.
     *
     * @return an integer checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                store != null ? store.id : null,
                unit != null ? unit.id : null,
                supplierName, quantity, orderBy, dueAt, state,
                expectedSupply != null ? expectedSupply.id : null);
    }
}
