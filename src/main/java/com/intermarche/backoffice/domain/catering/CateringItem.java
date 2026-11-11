package com.intermarche.backoffice.domain.catering;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.*;
import java.util.Objects;

/**
 * Catering datasheet of a {@link Product} (§ 5, F1).
 * <p>
 * A product is a catering article when it has an active {@code CateringItem}.
 * {@code station} is required when registering a {@code FABRICATED} or
 * {@code SUPPLIED} datasheet through the referential; only a datasheet created by
 * I5 may be stationless. Null {@code shelfLifeHours}, {@code maxAdvanceHours},
 * {@code supplierLeadDays} and {@code cancelDispositions} fall back to the policy.
 * Per-store overrides live in {@link CateringItemStore}.
 */
@Entity
@Table(name = "catering_items")
public class CateringItem extends BaseEntity {

    /** The product this datasheet describes (unique). */
    @ManyToOne(optional = false)
    @JoinColumn(name = "product_id", unique = true)
    public Product product;

    /** Default obtention mode for the article. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public SourcingMode sourcingMode;

    /** Fabrication station (required for FABRICATED/SUPPLIED except for an I5-created datasheet). */
    @ManyToOne
    @JoinColumn(name = "station_id")
    public FabricationStation station;

    /** Shelf life in hours; null falls back to the policy. */
    public Integer shelfLifeHours;

    /** Maximum advance in hours; null falls back to the policy. */
    public Integer maxAdvanceHours;

    /** Supplier name for a SUPPLIED article. */
    @Column(length = 200)
    public String supplierName;

    /** Supplier lead time in days; null falls back to the policy. */
    public Integer supplierLeadDays;

    /** Allowed cancellation dispositions, comma-separated; null falls back to the policy. */
    @Column(length = 60)
    public String cancelDispositions;

    /** Whether the article is fragile (occupies its support alone). */
    @Column(nullable = false)
    public boolean fragile = false;

    /** Whether the datasheet is active. */
    @Column(nullable = false)
    public boolean active = true;

    /**
     * Computes the checksum from the datasheet's business fields.
     *
     * @return an integer checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                product != null ? product.id : null,
                sourcingMode,
                station != null ? station.id : null,
                shelfLifeHours, maxAdvanceHours, supplierName, supplierLeadDays,
                cancelDispositions, fragile, active);
    }
}
