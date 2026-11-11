package com.intermarche.backoffice.domain.catering;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.wms.handling.FabricationOrder;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A fabrication line of a {@link FabricationOrder} (§ 5, F6).
 * <p>
 * Groups the units of the same variant and composition
 * {@code (station, day, article, variantId, configurationKey)}.
 */
@Entity
@Table(name = "fabrication_lines")
public class FabricationLine extends BaseEntity {

    /** Lifecycle state of a fabrication line. */
    public enum FabricationLineState {
        /** Planned, not yet started. */
        PLANNED,
        /** First quantity fabricated. */
        IN_PROGRESS,
        /** Whole quantity fabricated or declared short. */
        DONE,
        /** Cancelled. */
        CANCELLED
    }

    /** The fabrication order this line belongs to. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "order_id")
    public FabricationOrder order;

    /** The catering article fabricated. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "item_id")
    public CateringItem item;

    /** Variant identifier. */
    @Column(length = 20)
    public String variantId;

    /** Configuration key (SHA-256 hex). */
    @Column(length = 64)
    public String configurationKey;

    /** Planned quantity. */
    @Column(nullable = false, precision = 19, scale = 6)
    public BigDecimal plannedQuantity;

    /** Quantity produced so far. */
    @Column(nullable = false, precision = 19, scale = 6)
    public BigDecimal producedQuantity = BigDecimal.ZERO;

    /** Quantity lost (declared losses). */
    @Column(nullable = false, precision = 19, scale = 6)
    public BigDecimal lostQuantity = BigDecimal.ZERO;

    /** Quantity declared not served. */
    @Column(nullable = false, precision = 19, scale = 6)
    public BigDecimal shortQuantity = BigDecimal.ZERO;

    /** Current state. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public FabricationLineState state = FabricationLineState.PLANNED;

    /** Server time of the first fabricated quantity. */
    @Column(name = "producedAt")
    public LocalDateTime producedAt;

    /** Use-by instant of the finished product. */
    @Column(name = "useBy")
    public LocalDateTime useBy;

    /** Finished lot code. */
    @Column(length = 60)
    public String lotCode;

    /**
     * Computes the checksum from the line's business fields.
     *
     * @return an integer checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                order != null ? order.id : null,
                item != null ? item.id : null,
                variantId, configurationKey,
                plannedQuantity, producedQuantity, lostQuantity, shortQuantity,
                state, producedAt, useBy, lotCode);
    }
}
