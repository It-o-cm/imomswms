package com.intermarche.backoffice.domain.wms.handling;

import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import jakarta.persistence.*;

import java.util.HashSet;
import java.util.Set;

/**
 * Generic implementation of a Handling Order for Collecting (Picking).
 */
@Entity
@DiscriminatorValue("PREPARING")
public abstract class PreparingOrder extends BaseHandlingOrder {

    /**
     * The collection of Lots assigned to this collecting order.
     * <p>
     * Unidirectional relationship via a join table.
     */
    @ManyToMany
    @JoinTable(
        name = "collecting_order_lots",
        joinColumns = @JoinColumn(name = "collecting_order_id"),
        inverseJoinColumns = @JoinColumn(name = "lot_id"),
        foreignKey = @ForeignKey(name = "fk_ZoneCollectingOrder_lot"),
        inverseForeignKey = @ForeignKey(name = "fk_lot_ZoneCollectingOrder")
    )
    public Set<Lot> lots = new HashSet<>();

    /**
     * Helper method to add a Lot to this collecting order.
     *
     * @param lot The lot to add.
     */
    public void addLot(Lot lot) {
        this.lots.add(lot);
        lot.status = Lot.LotStatus.PLANNED_TO_BE_PACKED;
    }

}