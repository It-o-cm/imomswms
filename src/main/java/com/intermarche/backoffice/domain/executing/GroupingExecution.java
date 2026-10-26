package com.intermarche.backoffice.domain.executing;

import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Tote;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Specific implementation of a Handling Execution for Grouping operations.
 * <p>
 * This class represents the execution phase of a {@link GroupingOrder}.
 * It validates integrity, manages the movement of {@link LoadedTote}s to the destination buffer,
 * and finalizes the process once all totes are assembled.
 * <p>
 * It maintains a collection of {@link LoadedTote}s to track the physical bins involved
 * in the current grouping operation.
 */
@Entity
@DiscriminatorValue("GROUPING")
public class GroupingExecution extends HandlingExecution implements GroupingOperations {

    /**
     * The collection of LoadedTotes involved in this GroupingExecution.
     * <p>
     * This set allows the execution to manage and verify the location of the physical bins
     * throughout the grouping process.
     */
    @OneToMany
    public Set<LoadedTote> loadedTotes = new HashSet<>();

    /**
     * Default constructor required by JPA.
     */
    public GroupingExecution() {
        super();
    }

    /**
     * Constructor for creating a new GroupingExecution.
     * <p>
     * Initializes the execution with the provided order and employee, and immediately
     * triggers the integrity validation process.
     *
     * @param groupingOrder The grouping order to execute.
     * @param employee     The employee responsible for the execution.
     * @throws IllegalStateException if any of the validation rules are violated.
     */
    public GroupingExecution(GroupingOrder groupingOrder, Employee employee) {
        super();
        this.handlingOrder = groupingOrder;
        this.employee = employee;
        validateGroupingIntegrity((GroupingOrder) groupingOrder);
    }

    // --------------------------------------------------
    // Implementation of GroupingOperations
    // --------------------------------------------------

    /**
     * Moves a physical Tote to the destination Buffer specified by the associated {@link GroupingOrder}.
     * <p>
     * This method locates the active {@link LoadedTote} for the given physical Tote and updates its
     * buffer assignment to the target destination defined in the GroupingOrder.
     *
     * @param physicalTote The physical Tote to move.
     * @throws IllegalArgumentException if the Tote is null, not found or not active.
     */
    @Override
    public void moveToteToDestination(Tote physicalTote) {
        if (physicalTote == null) {
            throw new IllegalArgumentException("Physical Tote cannot be null.");
        }
        if (this.handlingOrder == null || !(this.handlingOrder instanceof GroupingOrder)) {
            throw new IllegalStateException("No GroupingOrder associated with this execution.");
        }
        GroupingOrder order = (GroupingOrder) this.handlingOrder;
        // Find the active LoadedTote for this physical Tote
        LoadedTote loadedTote = LoadedTote.find("tote = ?1 and status = ?2", physicalTote, LoadedTote.Status.IN_USE).firstResult();
        if (loadedTote == null) {
            throw new IllegalArgumentException("No active LoadedTote found for Tote: " + physicalTote.code);
        }
        // Update the buffer to the destination specified by the GroupingOrder
        loadedTote.clearCell();loadedTote.buffer = order.buffer;
    }

    /**
     * Terminates the Grouping Execution.
     * <p>
     * Performs final checks to ensure that all {@link LoadedTote}s involved in this execution
     * have been successfully moved to the destination Buffer specified by the {@link GroupingOrder}.
     * If the check passes, it marks the execution and the order as DONE.
     *
     * @throws IllegalStateException if any involved tote is not in the correct destination buffer.
     */
    @Override
    public void concludeExecution() {
        if (this.handlingOrder == null || !(this.handlingOrder instanceof GroupingOrder)) {
            throw new IllegalStateException("No GroupingOrder associated with this execution.");
        }

        GroupingOrder order = (GroupingOrder) this.handlingOrder;
        Buffer targetBuffer = order.buffer;

        if (targetBuffer == null) {
            throw new IllegalStateException("GroupingOrder does not specify a destination Buffer.");
        }

        // Verify all involved totes are in the destination buffer
        verifyAllTotesAtDestination(targetBuffer);

        // Finalize the execution
        this.handlingOrder.status = BaseHandlingOrder.HandlingOrderStatus.DONE;
    }

    // --------------------------------------------------
    // Validation Logic
    // --------------------------------------------------

    /**
     * Orchestrates the validation steps for the grouping order.
     *
     * @param groupingOrder The order to validate.
     * @throws IllegalStateException if validation fails at any step.
     */
    private void validateGroupingIntegrity(GroupingOrder groupingOrder) {
        Set<Long> targetLotLineIds = extractLotLineIds(groupingOrder);
        List<PickedItem> pickedItems = fetchPickedItems(targetLotLineIds);
        validateItemCoverage(targetLotLineIds, pickedItems);
        Set<Long> involvedLoadedToteIds = validateItemsAndCollectTotes(pickedItems);
        List<LoadedTote> involvedLoadedTotes = fetchActiveLoadedTotes(involvedLoadedToteIds);
        validateTotePurity(involvedLoadedTotes, targetLotLineIds);
        // Track the physical bins involved in this execution so that
        // concludeExecution can verify they all reach the destination buffer.
        this.loadedTotes.addAll(involvedLoadedTotes);
    }

    private Set<Long> extractLotLineIds(GroupingOrder groupingOrder) {
        Set<Long> lotLineIds = new HashSet<>();
        if (groupingOrder.lots != null) {
            for (Lot lot : groupingOrder.lots) {
                if (lot.lines != null) {
                    for (LotLine lotLine : lot.lines) {
                        lotLineIds.add(lotLine.id);
                    }
                }
            }
        }
        if (lotLineIds.isEmpty()) {
            throw new IllegalStateException("GroupingOrder contains no Lots or LotLines.");
        }
        return lotLineIds;
    }

    private List<PickedItem> fetchPickedItems(Set<Long> lotLineIds) {
        return PickedItem.list("lotLine.id in ?1", lotLineIds);
    }

    private void validateItemCoverage(Set<Long> expectedLotLineIds, List<PickedItem> pickedItems) {
        if (pickedItems.size() != expectedLotLineIds.size()) {
            throw new IllegalStateException(
                    String.format("Validation Failed: Missing PickedItems. Expected %d items for LotLines, but found %d.",
                            expectedLotLineIds.size(), pickedItems.size())
            );
        }
    }

    private Set<Long> validateItemsAndCollectTotes(List<PickedItem> items) {
        Set<Long> involvedLoadedToteIds = new HashSet<>();
        for (PickedItem item : items) {
            validateItemStatus(item);
            validateItemToteAssignment(item);
            validateLoadedToteStatus(item);
            involvedLoadedToteIds.add(item.loadedTote.id);
        }
        return involvedLoadedToteIds;
    }

    /**
     * Checks if a single item has a valid status (PICKED or CANCELED).
     *
     * @param item The item to check.
     * @throws IllegalStateException if the status is invalid.
     */
    private void validateItemStatus(PickedItem item) {
        if (!(item.status == PickedItem.PickStatus.PICKED || item.status == PickedItem.PickStatus.CANCELED)) {
            throw new IllegalStateException(
                    String.format("Validation Failed: PickedItem %d has status %s. Expected PICKED or CANCELED.",
                            item.id, item.status)
            );
        }
    }

    /**
     * Checks if a single item is assigned to a LoadedTote.
     *
     * @param item The item to check.
     * @throws IllegalStateException if not assigned.
     */
    private void validateItemToteAssignment(PickedItem item) {
        if (item.loadedTote == null) {
            throw new IllegalStateException(
                    String.format("Validation Failed: PickedItem %d is not assigned to a LoadedTote.", item.id)
            );
        }
    }

    /**
     * Checks if the LoadedTote associated with the item is currently IN_USE.
     *
     * @param item The item to check.
     * @throws IllegalStateException if the LoadedTote is not IN_USE.
     */
    private void validateLoadedToteStatus(PickedItem item) {
        if (item.loadedTote.status != LoadedTote.Status.IN_USE) {
            throw new IllegalStateException(
                    String.format("Validation Failed: PickedItem %d is in a LoadedTote with status %s. Expected IN_USE.",
                            item.id, item.loadedTote.status)
            );
        }
    }

    /**
     * Validates that the physical Totes involved do not contain items from other orders.
     * <p>
     * This method splits the logic into three clear steps: extracting physical totes,
     * fetching their contents, and verifying purity.
     *
     * @param activeLoadedTotes  The active LoadedTotes involved in this execution.
     * @param allowedLotLineIds The set of LotLine IDs permitted to be in these totes.
     * @throws IllegalStateException if a foreign item is found.
     */
    private void validateTotePurity(List<LoadedTote> activeLoadedTotes, Set<Long> allowedLotLineIds) {
        List<Tote> physicalTotes = extractPhysicalTotes(activeLoadedTotes);
        checkTotesPurity(physicalTotes, allowedLotLineIds);
    }

    /**
     * Fetches the active LoadedTote entities from the database.
     *
     * @param ids The set of IDs to fetch.
     * @return A list of LoadedTote entities in IN_USE status.
     */
    private List<LoadedTote> fetchActiveLoadedTotes(Set<Long> ids) {
        return LoadedTote.list("id in ?1 and status = ?2", ids, LoadedTote.Status.IN_USE);
    }

    /**
     * Extracts the physical Tote entities from the active LoadedTotes.
     *
     * @param loadedTotes The list of loaded totes.
     * @return A list of physical Tote entities.
     */
    private List<Tote> extractPhysicalTotes(List<LoadedTote> loadedTotes) {
        return loadedTotes.stream()
                .map(lt -> lt.tote)
                .collect(Collectors.toList());
    }

    /**
     * Verifies that the specified physical Totes contain only allowed items.
     *
     * @param physicalTotes     The physical totes to check.
     * @param allowedLotLineIds The permitted LotLine IDs.
     * @throws IllegalStateException if a foreign item is found.
     */
    private void checkTotesPurity(List<Tote> physicalTotes, Set<Long> allowedLotLineIds) {
        if (physicalTotes.isEmpty()) {
            throw new IllegalStateException("Validation Failed: No IN_USE LoadedTotes found for the involved IDs.");
        }

        List<PickedItem> allItemsInPhysicalTotes = PickedItem.list(
                "SELECT pi FROM PickedItem pi " +
                        "JOIN pi.loadedTote lt " +
                        "WHERE lt.tote IN ?1",
                physicalTotes
        );

        for (PickedItem itemInTote : allItemsInPhysicalTotes) {
            if (!allowedLotLineIds.contains(itemInTote.lotLine.id)) {
                throw new IllegalStateException(
                        String.format("Validation Failed: Physical Tote %d (via LoadedTote %d) contains PickedItem %d (LotLine %d) which belongs to another order. Tote purity violated.",
                                itemInTote.loadedTote.tote.id, itemInTote.loadedTote.id, itemInTote.id, itemInTote.lotLine.id)
                );
            }
        }
    }

    /**
     * Verifies that all active LoadedTotes are located at the specified destination buffer.
     *
     * @param targetBuffer The expected destination buffer.
     * @throws IllegalStateException if any tote is elsewhere.
     */
    private void verifyAllTotesAtDestination(Buffer targetBuffer) {
        if (this.loadedTotes == null || this.loadedTotes.isEmpty()) {
            return; // Nothing to verify
        }
        for (LoadedTote loadedTote : this.loadedTotes) {
            // Ensure we are checking the current state from DB if needed, but checking the object graph is usually fine in transaction
            if (loadedTote.buffer == null) {
                throw new IllegalStateException(
                        String.format("LoadedTote %d (Tote %s) is not assigned to any buffer.",
                                loadedTote.id, loadedTote.tote.code)
                );
            }
            if (!loadedTote.buffer.equals(targetBuffer)) {
                throw new IllegalStateException(
                        String.format("LoadedTote %d (Tote %s) is not at the destination buffer. Expected %s, found %s.",
                                loadedTote.id, loadedTote.tote.code, targetBuffer.locationDescription, loadedTote.buffer.locationDescription)
                );
            }
        }
    }
}