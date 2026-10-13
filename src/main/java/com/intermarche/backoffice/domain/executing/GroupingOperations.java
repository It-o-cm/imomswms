package com.intermarche.backoffice.domain.executing;

import com.intermarche.backoffice.domain.store.Tote;

/**
 * Interface defining the operational capabilities for a grouping execution.
 * <p>
 * Defines the contract for managing the movement of totes to a destination buffer
 * and finalizing the grouping process.
 */
public interface GroupingOperations {

    /**
     * Moves a physical Tote to the destination Buffer specified by the associated {@link com.intermarche.backoffice.domain.wms.handling.GroupingOrder}.
     * <p>
     * This method locates the active {@link com.intermarche.backoffice.domain.executing.LoadedTote} for the given physical Tote and updates its
     * buffer assignment to the target destination defined in the GroupingOrder.
     *
     * @param physicalTote The physical Tote to move.
     * @throws IllegalStateException if the associated handling order is not a GroupingOrder.
     * @throws IllegalArgumentException if the Tote is not found or not active.
     */
    void moveToteToDestination(Tote physicalTote);

    /**
     * Terminates the Grouping Execution.
     * <p>
     * Performs final checks to ensure that all {@link com.intermarche.backoffice.domain.executing.LoadedTote}s involved in this execution
     * have been successfully moved to the destination Buffer specified by the {@link com.intermarche.backoffice.domain.wms.handling.GroupingOrder}.
     * If the check passes, it marks the execution and the order as DONE.
     *
     * @throws IllegalStateException if any involved tote is not in the correct destination buffer.
     */
    void concludeExecution();
}