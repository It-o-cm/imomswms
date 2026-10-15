package com.intermarche.backoffice.client;

import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.executing.PickingOperations;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.CollectingOrder;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Specific implementation of a Handling Execution for Picking operations.
 * <p>
 * Extends {@link HandlingExecution} and implements {@link PickingOperations}.
 * Adds the list of {@link LoadedTote}s and the list of {@link PickedItem}s that must be collected.
 */
@Entity
@DiscriminatorValue("PICKING")
public class PickingExecution extends HandlingExecution implements PickingOperations {

    /**
     * The list of LoadedTotes involved in this picking execution.
     * <p>
     * <b>Unidirectional with FK:</b> Maps the relationship using a foreign key column
     * {@code picking_execution_id} in the {@link LoadedTote} table.
     */
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "picking_execution_id")
    public Set<LoadedTote> loadedTotes = new HashSet<>();

    /**
     * The list of PickedItems assigned to this picking execution (items to be picked).
     * <p>
     * <b>Unidirectional with FK:</b> Maps the relationship using a foreign key column
     * {@code picking_execution_id} in the {@link PickedItem} table.
     */
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "picking_execution_id")
    public Set<PickedItem> itemsToPick = new HashSet<>();

    /**
     * Default constructor required by JPA.
     */
    public PickingExecution() {
    }

    /**
     * Constructor for a new PickingExecution based on a CollectingOrder.
     * <p>
     * Initializes the execution with the provided order and employee.
     * It optimizes the creation of {@link PickedItem}s by fetching all existing items
     * in a single query (avoiding N+1 problem) and only creating new items for
     * {@link LotLine}s that have not yet been picked globally.
     *
     * @param collectingOrder the collecting order to be executed.
     * @param employee        the employee performing the execution.
     */
    public PickingExecution(CollectingOrder collectingOrder, Employee employee) {
        this.handlingOrder = collectingOrder;
        this.employee = employee;

        // 1. Collect all LotLine IDs from the order's structure
        Set<Long> lotLineIds = collectingOrder.lots.stream()
                .flatMap(lot -> lot.lines.stream())
                .map(lotLine -> lotLine.id)
                .collect(Collectors.toSet());

        if (!lotLineIds.isEmpty()) {
            // 2. Execute a SINGLE query to fetch all PickedItems that already exist for these LotLines
            //    This prevents the N+1 select issue.
            List<PickedItem> existingItems = PickedItem.list("lotLine.id in ?1", lotLineIds);

            // 3. Build a Set of IDs that are already processed
            Set<Long> existingLotLineIds = existingItems.stream()
                    .map(pi -> pi.lotLine.id)
                    .collect(Collectors.toSet());

            // 4. Iterate through the order structure to create PickedItems for the missing LotLines
            for (Lot lot : collectingOrder.lots) {
                for (LotLine lotLine : lot.lines) {
                    if (!existingLotLineIds.contains(lotLine.id)) {
                        createAndAddPickedItem(lotLine);
                    }
                }
            }
        }
    }

    // --------------------------------------------------
    // Implementation of PickingOperations
    // --------------------------------------------------

    /**
     * Adds a {@link Tote} to the execution for a specific usage.
     * <p>
     * Creates a new {@link LoadedTote} linking the physical Tote to this execution with a specific TemperatureZone.
     * Delegates validation to the Tote entity to check if the usage is compatible with its type.
     *
     * @param tote  The physical tote to add.
     * @param usage The intended usage (TemperatureZone) for this tote.
     * @throws IllegalArgumentException if the tote is null or if the usage is not allowed for this tote type.
     */
    @Override
    public LoadedTote addTote(Tote tote, TemperatureZone usage) {
        if (tote == null) {
            throw new IllegalArgumentException("Tote cannot be null");
        }
        if (!tote.isUsageAllowed(usage)) {
            throw new IllegalArgumentException(
                    String.format("Usage %s is not allowed for Tote type %s", usage, tote.toteType));
        }
        LoadedTote loadedTote = LoadedTote.useAnEmptyTote(tote);
        loadedTote.storageType = usage;
        // Persist before adding to the HashSet: BaseEntity.hashCode() is based on id,
        // which changes on persist and would break the Set contract.
        loadedTote.persist();
        this.loadedTotes.add(loadedTote);
        return loadedTote;
    }

    /**
     * Removes a {@link Tote} from the execution.
     * <p>
     * Finds the {@link LoadedTote} associated with the provided Tote and removes it.
     * The physical Tote entity is not deleted.
     * The LoadedTote must be empty (contain no {@link PickedItem}s) to be removed.
     *
     * @param tote The physical tote to remove.
     * @throws IllegalArgumentException if the tote is null, not part of the execution, or its LoadedTote contains items.
     */
    @Override
    public void removeEmptyTote(Tote tote) {
        if (tote == null) {
            throw new IllegalArgumentException("Tote cannot be null");
        }

        LoadedTote loadedTote = findLoadedToteByTote(tote);
        if (loadedTote == null) {
            throw new IllegalArgumentException("The provided tote does not belong to this execution");
        }

        // Ensure the loaded tote is empty before removing
        if (loadedTote.items != null && !loadedTote.items.isEmpty()) {
            throw new IllegalArgumentException("Cannot remove a tote that contains items");
        }

        this.loadedTotes.remove(loadedTote);
    }

    /**
     * Adds all or part of a {@link PickedItem} from the work list into a specific {@link LoadedTote}.
     * <p>
     * Behavior regarding parameters:
     * <ul>
     *   <li>If {@code quantity} is null, the full quantity of the item is moved.</li>
     *   <li>If {@code newProduct} is null, the original product of the item is kept.</li>
     * </ul>
     * <p>
     * If the quantity to move is less than the original quantity, the item is split:
     * the original item remains in the work list with the remaining quantity,
     * and a new item is created for the moved quantity.
     * <p>
     * The item is removed from the "itemsToPick" list (work list) and associated with the tote.
     *
     * @param loadedTote The target tote. Must be part of this execution.
     * @param pickedItem The item to pick/move. Must be in the "itemsToPick" list.
     * @param quantity   The quantity to move. If null, moves all.
     * @param newProduct The product to associate with the picked item (allows substitution). If null, keeps original.
     * @throws IllegalArgumentException if tote or item is null, or quantity is invalid.
     */
    @Override
    public void addItemToTote(LoadedTote loadedTote, PickedItem pickedItem, BigDecimal quantity, Product newProduct) {
        if (loadedTote == null || pickedItem == null) {
            throw new IllegalArgumentException("Tote and PickedItem cannot be null");
        }
        if (!this.loadedTotes.contains(loadedTote)) {
            throw new IllegalArgumentException("The provided tote does not belong to this execution");
        }
        if (!this.itemsToPick.contains(pickedItem)) {
            throw new IllegalArgumentException("The provided pickedItem is not in the pending list of this execution");
        }

        // Default null parameters
        BigDecimal quantityToMove = (quantity != null) ? quantity : pickedItem.quantity;
        Product productToAssign = (newProduct != null) ? newProduct : pickedItem.product;

        int comparison = pickedItem.quantity.compareTo(quantityToMove);

        if (comparison == 0) {
            // Move the whole item
            this.itemsToPick.remove(pickedItem);
            moveItemToToteInternal(loadedTote, pickedItem);
            pickedItem.product = productToAssign;
            pickedItem.status = PickedItem.PickStatus.PICKED;

        } else if (comparison > 0) {
            // Split the item: Keep the remainder in itemsToPick, create a new one for the Tote
            pickedItem.quantity = pickedItem.quantity.subtract(quantityToMove);

            PickedItem movedItem = new PickedItem();
            movedItem.lotLine = pickedItem.lotLine;
            movedItem.product = productToAssign;
            movedItem.quantity = quantityToMove;
            movedItem.status = PickedItem.PickStatus.PICKED;
            // Persist so the item has an id and can be deleted later (e.g. by removeItemFromTote merge)
            movedItem.persist();

            moveItemToToteInternal(loadedTote, movedItem);
        } else {
            throw new IllegalArgumentException("Quantity to move cannot exceed the item's current quantity");
        }
    }

    /**
     * Moves a {@link PickedItem} from a source tote to a target tote.
     * <p>
     * Validates that the item belongs to a {@link LotLine} that is part of the
     * {@link CollectingOrder} linked to this execution.
     *
     * @param sourceTote The tote containing the item (may or may not be in this execution).
     * @param targetTote The destination tote (must be in this execution).
     * @param pickedItem The item to move.
     * @throws IllegalArgumentException if target tote is not in execution or item is invalid for order.
     */
    @Override
    public void moveItemToTote(LoadedTote sourceTote, LoadedTote targetTote, PickedItem pickedItem) {
        if (targetTote == null || pickedItem == null) {
            throw new IllegalArgumentException("Target Tote and PickedItem cannot be null");
        }
        if (!this.loadedTotes.contains(targetTote)) {
            throw new IllegalArgumentException("The target tote does not belong to this execution");
        }

        if (!isValidItemForOrder(pickedItem)) {
            throw new IllegalArgumentException("PickedItem is not linked to a LotLine of the CollectingOrder");
        }

        if (sourceTote != null && sourceTote.items != null) {
            sourceTote.items.remove(pickedItem);
        }

        moveItemToToteInternal(targetTote, pickedItem);
    }

    /**
     * Removes a {@link PickedItem} from a tote and puts it back into the work list ({@code itemsToPick}).
     * <p>
     * The item's product is reset to the original product from its {@link LotLine}.
     * If an item for the same {@link LotLine} already exists in the work list, the quantities are merged
     * and the removed item is deleted from the database.
     *
     * @param loadedTote The tote containing the item.
     * @param pickedItem The item to remove.
     * @throws IllegalArgumentException if tote or item is null, or item is not in the tote.
     */
    @Override
    public void removeItemFromTote(LoadedTote loadedTote, PickedItem pickedItem) {
        if (loadedTote == null || pickedItem == null) {
            throw new IllegalArgumentException("Tote and PickedItem cannot be null");
        }
        if (!this.loadedTotes.contains(loadedTote)) {
            throw new IllegalArgumentException("The provided tote does not belong to this execution");
        }
        if (!loadedTote.items.contains(pickedItem)) {
            throw new IllegalArgumentException("The provided pickedItem is not in the tote");
        }

        // 1. Remove from tote
        loadedTote.items.remove(pickedItem);
        pickedItem.loadedTote = null;

        // 2. Reset product to original LotLine product and status
        pickedItem.product = pickedItem.lotLine.product;
        pickedItem.status = PickedItem.PickStatus.PENDING;

        // 3. Check for an existing item in the work list for the same LotLine
        Optional<PickedItem> existingItemOpt = this.itemsToPick.stream()
                .filter(item -> item.lotLine.equals(pickedItem.lotLine))
                .findFirst();

        if (existingItemOpt.isPresent()) {
            // 4a. Merge: Add the quantity to the existing item
            PickedItem existingItem = existingItemOpt.get();
            existingItem.quantity = existingItem.quantity.add(pickedItem.quantity);

            // The item removed from the tote is now redundant (quantity absorbed). Delete it.
            pickedItem.delete();
        } else {
            // 4b. Add: Simply put it back in the work list
            this.itemsToPick.add(pickedItem);
        }
    }

    /**
     * Cancels a {@link PickedItem}.
     * <p>
     * This operation is only authorized if the item is currently present in the picking list (itemsToPick).
     * The item's status is updated to {@link PickedItem.PickStatus#CANCELED}.
     *
     * @param pickedItem The item to cancel.
     * @throws IllegalArgumentException if the item is null or not in the picking list.
     */
    @Override
    public void cancelItem(PickedItem pickedItem) {
        if (pickedItem == null) {
            throw new IllegalArgumentException("PickedItem cannot be null");
        }

        if (!this.itemsToPick.contains(pickedItem)) {
            throw new IllegalArgumentException("The PickedItem is not in the picking list and cannot be cancelled.");
        }

        pickedItem.status = PickedItem.PickStatus.CANCELED;
    }

    /**
     * Concludes the picking execution.
     * <p>
     * Validates that all picked items are either PICKED (in totes) or CANCELED (in the picking list).
     * Assigns the specified buffer to all totes and sets the execution status to DONE.
     *
     * @param buffer The buffer to assign to all totes.
     * @throws IllegalStateException if any item is still PENDING.
     */
    @Override
    public void concludeExecution(Buffer buffer) {
        // Check if any item in the work list is still PENDING
        boolean hasPendingItems = this.itemsToPick.stream()
                .anyMatch(item -> item.status == PickedItem.PickStatus.PENDING);

        if (hasPendingItems) {
            throw new IllegalStateException("Cannot conclude execution: Some items are still pending.");
        }

        // Assign the buffer to all loaded totes
        for (LoadedTote tote : this.loadedTotes) {
            tote.buffer = buffer;
        }

        // Update execution status to DONE
        this.status = ExecutionStatus.DONE;
    }

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
    @Override
    public Set<String> getAisleCodesFromItemsToPick() {
        return this.itemsToPick.stream()
                .filter(item -> item.lotLine.fulfillmentLine.location instanceof ShelfLocation)
                .map(item -> ((ShelfLocation) item.lotLine.fulfillmentLine.location).aisle)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    // --------------------------------------------------
    // Helpers for ItemsToPick
    // --------------------------------------------------

    /**
     * Helper method to add a {@link PickedItem} to the list of items to pick.
     *
     * @param itemToPick The PickedItem to add.
     */
    public void addItemToPick(PickedItem itemToPick) {
        this.itemsToPick.add(itemToPick);
    }

    /**
     * Helper method to remove a {@link PickedItem} from the list of items to pick.
     * <p>
     * This breaks the association with the execution (sets the foreign key to null),
     * marking the item as "done" for this execution, but preserves the PickedItem entity.
     *
     * @param itemToPick The PickedItem to remove from the work list.
     * @return {@code true} if the set contained the specified element.
     */
    public boolean deleteItemToPick(PickedItem itemToPick) {
        return this.itemsToPick.remove(itemToPick);
    }

    // --------------------------------------------------
    // Internal Helpers
    // ---------------------------------------------------------

    /**
     * Helper method to instantiate and add a PickedItem to the work list.
     *
     * @param lotLine the source lot line.
     */
    private void createAndAddPickedItem(LotLine lotLine) {
        PickedItem item = new PickedItem();
        item.lotLine = lotLine;
        item.product = lotLine.product;
        item.quantity = lotLine.quantity;
        item.status = PickedItem.PickStatus.PENDING;
        // Persist before adding to the HashSet: BaseEntity.hashCode() is based on id,
        // which changes on persist and would break the Set contract.
        item.persist();

        this.addItemToPick(item);
    }

    /**
     * Internal helper to link an item to a tote and update the tote's collection.
     * <p>
     * Handles the bidirectional relationship: sets the item's loadedTote
     * and adds the item to the tote's items set.
     *
     * @param tote The destination tote.
     * @param item The item to link.
     */
    private void moveItemToToteInternal(LoadedTote tote, PickedItem item) {
        item.loadedTote = tote;
        if (tote.items == null) {
            tote.items = new HashSet<>();
        }
        tote.items.add(item);
    }

    /**
     * Validates if a PickedItem belongs to the structure of the Order associated with this execution.
     * <p>
     * Checks if the item's LotLine is contained within any Lot of the CollectingOrder.
     *
     * @param pickedItem The item to validate.
     * @return true if the item is part of the order, false otherwise.
     */
    private boolean isValidItemForOrder(PickedItem pickedItem) {
        if (this.handlingOrder instanceof CollectingOrder) {
            CollectingOrder order = (CollectingOrder) this.handlingOrder;
            // Since the relationship is unidirectional (Lot -> LotLine), we iterate lots to find the line
            return order.lots.stream()
                    .anyMatch(lot -> lot.lines.contains(pickedItem.lotLine));
        }
        return false;
    }

    /**
     * Finds the LoadedTote associated with a specific physical Tote in this execution.
     *
     * @param tote The physical Tote.
     * @return The LoadedTote, or null if not found.
     */
    private LoadedTote findLoadedToteByTote(Tote tote) {
        return this.loadedTotes.stream()
                .filter(lt -> lt.tote.equals(tote))
                .findFirst()
                .orElse(null);
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum including the list of loaded totes and items to pick.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                super.getChecksum(),
                loadedTotes,
                itemsToPick
        );
    }
}