package com.intermarche.backoffice.domain.wms.handling;

import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.executing.GroupingExecution;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.wms.Lot;
import jakarta.persistence.*;

import java.util.HashSet;
import java.util.Set;

/**
 * Specific implementation of a Handling Order from several buildings for Grouping lots for a given customer.
 * <p>
 * Groups a set of {@link Lot}s linked to a specific building.
 * Resembles {@link PackingOrder} but adds a precise location (Building).
 */
@Entity
@DiscriminatorValue("GROUPING")
public class GroupingOrder extends PreparingOrder {

    /**
     * The Customer associated with this order (optional, depending on the operation type).
     */
    @ManyToOne
    @JoinColumn(name = "customer_id")
    public Customer customer;

    /**
     * The building (warehouse or store) associated with this grouping.
     */
    @ManyToOne
    @JoinColumn(name = "buffer_id")
    public Buffer buffer;

    /**
     * Returns the prefix associated with the GroupingOrder.
     *
     * @return The string "GRP", representing the prefix for GroupingOrder codes.
     */
    @Override
    public String getPrefix() {
        return "GRP";
    }

    /**
     * Creates a new HandlingExecution specific to this GroupingOrder.
     *
     * @param employee The employee responsible for executing this order.
     * @return A new instance of HandlingExecution linked to this order.
     */
    public HandlingExecution createExecution(Employee employee) {
        GroupingExecution execution = new GroupingExecution(this, employee);
        return execution;
    }

}