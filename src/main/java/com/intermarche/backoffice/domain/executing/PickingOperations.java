package com.intermarche.backoffice.domain.executing;

import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.domain.store.Tote;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Interface defining the operational capabilities for a picking execution.
 * <p>
 * Defines the contract for managing totes, moving items, retrieving information, and finalizing the execution during the picking process.
 */
public interface PickingOperations {

    /**
     * Adds a {@link Tote} to the execution for a specific usage.
     * <p>
     * Creates a new {@link LoadedTote} linking the physical Tote to this execution with a specific TemperatureZone.
     * The method validates that the Tote's type is compatible with the requested usage (TemperatureZone).
     *
     * @param tote  The physical tote to add.
     * @param usage The intended usage (TemperatureZone) for this tote.
     * @throws IllegalArgumentException if the tote is null or if the usage is not allowed for this tote type.
     */
    LoadedTote addTote(Tote tote, TemperatureZone usage);

    /**
     * Removes a {@link Tote} from the execution.
     * <p>
     * This method finds the {@link LoadedTote} associated with the provided Tote and removes it.
     * The physical Tote entity is not deleted.
     * The LoadedTote must be empty (contain no {@link PickedItem}s) to be removed.
     *
     * @param tote The physical tote to remove.
     * @throws IllegalArgumentException if the tote is null, not part of the execution, or its LoadedTote contains items.
     */
    void removeEmptyTote(Tote tote);

    /**
     * Adds all or part of a {@link PickedItem} from the work list into a specific {@link LoadedTote}.
     * <p>
     * Behavior regarding parameters:
     * <ul>
     *   <li>If {@code quantity} is null, the full quantity of the item is moved.</li>
     *   <li>If {@code newProduct} is null, the original product of the item is kept.</li>
     * </ul>
     *
     * @param loadedTote The target tote.
     * @param pickedItem The item to pick/move.
     * @param quantity   The quantity to move. If null, moves all.
     * @param newProduct The product to associate (substitution). If null, keeps original.
     */
    void addItemToTote(LoadedTote loadedTote, PickedItem pickedItem, BigDecimal quantity, Product newProduct);

    /**
     * Moves a {@link PickedItem} from a source tote to a target tote.
     * <p>
     * Validates that the item belongs to the Order's structure before moving.
     *
     * @param sourceTote The tote containing the item.
     * @param targetTote The destination tote.
     * @param pickedItem The item to move.
     */
    void moveItemToTote(LoadedTote sourceTote, LoadedTote targetTote, PickedItem pickedItem);

    /**
     * Removes a {@link PickedItem} from a tote and puts it back into the work list ({@code itemsToPick}).
     * <p>
     * The item's product is reset to the original product from its {@link com.intermarche.backoffice.domain.wms.LotLine}.
     * If an item for the same LotLine already exists in the work list, the quantities are merged
     * and the removed item is deleted from the database.
     *
     * @param loadedTote The tote containing the item.
     * @param pickedItem The item to remove.
     */
    void removeItemFromTote(LoadedTote loadedTote, PickedItem pickedItem);

    /**
     * Cancels a {@link PickedItem}.
     * <p>
     * This operation is only authorized if the item is currently present in the picking list (itemsToPick).
     * If the item has already been moved to a tote, it is no longer in the picking list and cannot be cancelled via this method.
     *
     * @param pickedItem The item to cancel.
     * @throws IllegalArgumentException if the item is null or not in the picking list.
     */
    void cancelItem(PickedItem pickedItem);

    /**
     * Concludes the picking execution.
     * <p>
     * This operation verifies that all picked items associated with this execution are either
     * in the {@link PickedItem.PickStatus#PICKED} state (in totes) or
     * {@link PickedItem.PickStatus#CANCELED} state (in the picking list).
     * If any item remains in {@link PickedItem.PickStatus#PENDING}, the conclusion fails.
     * <p>
     * Upon successful validation:
     * <ul>
     *   <li>The provided {@link Buffer} is assigned to all {@link LoadedTote}s.</li>
     *   <li>The execution status is updated to {@link com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus#DONE}.</li>
     * </ul>
     *
     * @param buffer The buffer to assign to all totes.
     * @throws IllegalStateException if any item is still PENDING.
     */
    void concludeExecution(Buffer buffer);

    /**
     * Retrieves all unique aisle codes for the items currently in the picking list.
     * <p>
     * Iterates through the {@code itemsToPick} collection. For each item, it navigates
     * the relationships (PickedItem -> LotLine -> FulfillmentLine -> BaseLocation)
     * to extract the aisle code.
     *
     * @return A set of unique aisle codes. Returns an empty set if no items are present
     *         or if location information is missing.
     */
    Set<String> getAisleCodesFromItemsToPick();
}