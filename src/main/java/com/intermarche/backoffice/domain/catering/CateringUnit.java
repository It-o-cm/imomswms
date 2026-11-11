package com.intermarche.backoffice.domain.catering;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.OrderLine;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One received catering line of an order, tracked from submission to handover (§ 5, F3).
 * <p>
 * It keeps the composition exactly as received. {@code item} is null when the EAN is
 * unknown; {@code fulfillmentLine} is null when the EAN is unknown or the unit is
 * {@code STOCKED}. States are defined by {@link CateringUnitState}.
 */
@Entity
@Table(name = "catering_units",
        uniqueConstraints = @UniqueConstraint(name = "uk_unit_order_line", columnNames = {"order_id", "lineId"}))
public class CateringUnit extends BaseEntity {

    /** The order this unit belongs to. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "order_id")
    public Order order;

    /** The order line mirroring this catering line. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "orderLine_id")
    public OrderLine orderLine;

    /** Catering line identifier (UUID), unique within the order. */
    @Column(nullable = false, length = 40)
    public String lineId;

    /** EAN as received (kept even when unknown to the referential). */
    @Column(nullable = false, length = 40)
    public String ean;

    /** The catering article, or null when the EAN is unknown. */
    @ManyToOne
    @JoinColumn(name = "item_id")
    public CateringItem item;

    /** Obtention mode resolved at reception and frozen. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public SourcingMode sourcingMode;

    /** Variant identifier. */
    @Column(length = 20)
    public String variantId;

    /** Variant label as received. */
    @Column(length = 60)
    public String variantLabel;

    /** Configuration key (SHA-256 hex) as received. */
    @Column(length = 64)
    public String configurationKey;

    /** Raw configuration JSON (composition or cake) as received. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    public String configuration;

    /** Display choice labels as received (JSON array string). */
    @Column(columnDefinition = "TEXT")
    public String choiceLabels;

    /** Allergens text as received. */
    @Column(columnDefinition = "TEXT")
    public String allergens;

    /** Ingredients text as received. */
    @Column(columnDefinition = "TEXT")
    public String ingredients;

    /** Storage advice as received. */
    @Column(length = 1000)
    public String storage;

    /** Serving advice as received. */
    @Column(length = 1000)
    public String servingAdvice;

    /** Requested quantity. */
    @Column(nullable = false, precision = 19, scale = 6)
    public BigDecimal quantity;

    /** Quantity produced so far. */
    @Column(nullable = false, precision = 19, scale = 6)
    public BigDecimal producedQuantity = BigDecimal.ZERO;

    /** Quantity definitively not served. */
    @Column(nullable = false, precision = 19, scale = 6)
    public BigDecimal shortQuantity = BigDecimal.ZERO;

    /** Pickup slot start (availability). */
    @Column(nullable = false)
    public LocalDateTime availableAt;

    /** The catering chunk line, or null when the EAN is unknown or the unit is STOCKED. */
    @ManyToOne
    @JoinColumn(name = "fulfillmentLine_id")
    public FulfillmentLine fulfillmentLine;

    /** Current lifecycle state. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public CateringUnitState state = CateringUnitState.RECEIVED;

    /** Revision counter, incremented on each state change. */
    @Column(nullable = false)
    public int revision = 0;

    /**
     * Computes the checksum from the unit's business fields.
     *
     * @return an integer checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                order != null ? order.id : null,
                orderLine != null ? orderLine.id : null,
                lineId, ean,
                item != null ? item.id : null,
                sourcingMode, variantId, variantLabel, configurationKey, configuration,
                choiceLabels, allergens, ingredients, storage, servingAdvice,
                quantity, producedQuantity, shortQuantity, availableAt,
                fulfillmentLine != null ? fulfillmentLine.id : null,
                state, revision);
    }
}
