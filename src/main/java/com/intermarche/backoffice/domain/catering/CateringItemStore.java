package com.intermarche.backoffice.domain.catering;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
import java.util.Objects;

/**
 * Per-store override of a {@link CateringItem} (§ 5).
 * <p>
 * A null {@code sourcingMode} or {@code station} leaves the datasheet value in place.
 */
@Entity
@Table(name = "catering_item_stores",
        uniqueConstraints = @UniqueConstraint(name = "uk_item_store", columnNames = {"item_id", "store_id"}))
public class CateringItemStore extends BaseEntity {

    /** The catering datasheet being overridden. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "item_id")
    public CateringItem item;

    /** The store the override applies to. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "store_id")
    public Store store;

    /** Overriding obtention mode, or null to keep the datasheet value. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    public SourcingMode sourcingMode;

    /** Overriding station, or null to keep the datasheet value. */
    @ManyToOne
    @JoinColumn(name = "station_id")
    public FabricationStation station;

    /** Whether the override is active. */
    @Column(nullable = false)
    public boolean active = true;

    /**
     * Computes the checksum from the override's business fields.
     *
     * @return an integer checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                item != null ? item.id : null,
                store != null ? store.id : null,
                sourcingMode,
                station != null ? station.id : null,
                active);
    }
}
