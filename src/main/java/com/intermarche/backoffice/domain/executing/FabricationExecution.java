package com.intermarche.backoffice.domain.executing;

import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Execution of a fabrication order (§ 5, F16).
 * <p>
 * It carries its {@link LoadedTote}s and {@link PickedItem}s like a picking execution,
 * so the existing mechanisms that start from an order's picked items (progress, buffers,
 * cancellation, handover, loading, shipping) see catering supports as ordinary bins.
 * The association uses a dedicated {@code fabrication_execution_id} foreign-key column.
 */
@Entity
@DiscriminatorValue("FABRICATION")
public class FabricationExecution extends HandlingExecution {

    /**
     * The loaded totes used by this fabrication execution.
     * <p>
     * Unidirectional with a dedicated foreign key {@code fabrication_execution_id}
     * on the {@link LoadedTote} table.
     */
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "fabrication_execution_id")
    public Set<LoadedTote> loadedTotes = new HashSet<>();

    /**
     * The finished or received items held by this fabrication execution.
     * <p>
     * Unidirectional with a dedicated foreign key {@code fabrication_execution_id}
     * on the {@link PickedItem} table.
     */
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "fabrication_execution_id")
    public Set<PickedItem> itemsToPick = new HashSet<>();

    /**
     * Default constructor required by JPA.
     */
    public FabricationExecution() {
    }

    /**
     * Creates a fabrication execution for the given order and employee.
     *
     * @param order    the fabrication order being executed.
     * @param employee the employee performing the execution.
     */
    public FabricationExecution(BaseHandlingOrder order, Employee employee) {
        this.handlingOrder = order;
        this.employee = employee;
    }

    /**
     * Computes a checksum including the loaded totes and held items.
     *
     * @return an integer checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                super.getChecksum(),
                loadedTotes,
                itemsToPick);
    }
}
