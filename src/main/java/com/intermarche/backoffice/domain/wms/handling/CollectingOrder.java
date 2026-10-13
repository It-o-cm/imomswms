package com.intermarche.backoffice.domain.wms.handling;

import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.wms.Lot;
import jakarta.persistence.*;

/**
 * Generic implementation of a Handling Order for Collecting (Picking).
 */
@Entity
@DiscriminatorValue("COLLECTING")
public abstract class CollectingOrder extends PreparingOrder {

    /**
     * The building (warehouse or store) associated with this collecting order.
     */
    @ManyToOne
    @JoinColumn(name = "building_id")
    public Building building;

    /**
     * Helper method to add a Lot to this collecting order.
     *
     * @param lot The lot to add.
     */
    public void addLot(Lot lot) {
        super.addLot(lot);
        lot.status = Lot.LotStatus.PLANNED_TO_BE_PACKED;
    }

    /**
     * Creates a new PickingExecution specific to this ProductCollectingOrder.
     *
     * @param employee The employee responsible for executing this order.
     * @return A new instance of PickingExecution linked to this order.
     */
    @Override
    public HandlingExecution createExecution(Employee employee) {
        PickingExecution execution = new PickingExecution(this, employee);
        return execution;
    }

}