package com.intermarche.backoffice.domain.wms.handling;

import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.wms.Lot;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Specific implementation of a Handling Order for Picking operations.
 * <p>
 * Groups a set of {@link Lot}s that need to be picked for a specific customer,
 * location, and time slot.
 */
@Entity
@DiscriminatorValue("PACKING")
public class PackingOrder extends CollectingOrder {

    /**
     * The Customer associated with this order (optional, depending on the operation type).
     */
    @ManyToOne
    @JoinColumn(name = "customer_id")
    public Customer customer;

    /**
     * The `deadLine` variable represents the deadline for the PackingOrder.
     * This deadline specifies the time by which the packing operation must be completed.
     * It is stored as a column in the associated database table.
     */
    @Column(name = "deadline")
    public LocalDateTime deadLine;

    /**
     * Returns the prefix string associated with a PackingOrder.
     *
     * @return The string "PAK", representing the prefix for PackingOrder codes.
     */
    @Override
    public String getPrefix() {
        return "PAK";
    }

}