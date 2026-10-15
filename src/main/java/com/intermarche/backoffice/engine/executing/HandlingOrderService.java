package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.auth.AuthenticationService;
import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.auth.Account;
import com.intermarche.backoffice.domain.executing.GroupingExecution;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.domain.store.Tote;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.PreparingOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service responsible for managing the lifecycle and execution of Handling Orders.
 * <p>
 * This service acts as the primary business logic layer for order processing. It provides capabilities for:
 * <ul>
 *   <li>Listing and reserving available orders for employees.</li>
 *   <li>Managing batch executions (grouping multiple orders).</li>
 *   <li>Orchestrating the picking process (managing totes and items).</li>
 *   <li>Handling product substitutions.</li>
 *   <li>Concluding and validating execution reports.</li>
 *   <li>Managing grouping execution workflows.</li>
 * </ul>
 * </p>
 */
@ApplicationScoped
public class HandlingOrderService {

    /**
     * Optimizer service for calculating picking routes and distances.
     * Injected to support potential runtime distance checks or future planning features.
     */
    @Inject
    AuthenticationService authenticationService;

    // --------------------------------------------------
    // Public API: Listing & Reservation
    // --------------------------------------------------

    /**
     * Retrieves a summary list of all handling orders that do not currently have an active execution.
     * <p>
     * An order is considered available if it is not linked to any existing {@link HandlingExecution}.
     * </p>
     *
     * @return A list of {@link HandlingOrderSummaryDTO} representing the available orders.
     *         Returns an empty list if no orders are available.
     */
    @Transactional
    public List<HandlingOrderSummaryDTO> listAvailableOrders() {
        List<BaseHandlingOrder> orders = BaseHandlingOrder.list(
                "id not in (select he.handlingOrder.id from HandlingExecution he)"
        );
        return orders.stream()
                .map(this::mapToSummaryDTO)
                .collect(Collectors.toList());
    }

    /**
     * Reserves a specific handling order for an employee by creating a new execution.
     * <p>
     * This operation validates that the order exists and is in the {@code SCHEDULED} status.
     * If valid, it creates a new {@link HandlingExecution}, assigns it to the employee,
     * and persists the changes.
     * </p>
     *
     * @param orderId The unique identifier of the order to reserve.
     * @param badgeId The badge ID of the employee claiming the order.
     * @return A {@link HandlingExecutionDTO} representing the newly created execution.
     * @throws IllegalStateException if the order is not found, not in the SCHEDULED status,
     *                               or the employee is not found.
     */
    @Transactional
    public HandlingExecutionDTO reserveOrder(Long orderId, String badgeId) {
        BaseHandlingOrder order = BaseHandlingOrder.findById(orderId);
        if (order == null) {
            throw new IllegalStateException("Order not found");
        } else if (order.status != BaseHandlingOrder.HandlingOrderStatus.SCHEDULED) {
            throw new IllegalStateException("Order is not yet scheduled");
        }
        Employee employee = Employee.findByBadgeId(badgeId);
        if (employee == null) {
            throw new IllegalStateException("Employee not found");
        }
        HandlingExecution execution = order.createExecution(employee);
        execution.persist();
        return new HandlingExecutionDTO(
                execution.id,
                execution.getClass().getSimpleName(),
                order.id,
                employee.badgeId,
                employee.getFullName(),
                execution.createdAt
        );
    }

    /**
     * Cancels an existing reservation (execution) for a specific handling order.
     * <p>
     * If an active execution exists for the given order ID, it is deleted from the database,
     * making the order available for reservation again.
     * </p>
     *
     * @param orderId The unique identifier of the handling order whose execution should be cancelled.
     */
    @Transactional
    public void cancelReservation(Long orderId) {
        HandlingExecution execution = HandlingExecution.find("handlingOrder.id", orderId).firstResult();
        if (execution != null) {
            execution.delete();
        }
    }

    // --------------------------------------------------
    // Public API: Batch Execution Management
    // --------------------------------------------------

    /**
     * Initiates a batch picking session for a list of orders.
     * <p>
     * Creates individual executions for each provided order ID and returns a unified state
     * object containing the mapping between orders and executions, as well as the initial
     * picking state.
     * </p>
     *
     * @param orderIds The list of unique identifiers for the orders to include in the batch.
     * @param badgeId  The badge ID of the employee initiating the batch.
     * @return A {@link BatchStartResponse} containing the execution map and the initial picking state.
     * @throws IllegalArgumentException if the list of order IDs is null or empty.
     */
    @Transactional
    public BatchStartResponse startBatchExecutions(List<Long> orderIds, String badgeId) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw new IllegalArgumentException("Order IDs list cannot be null or empty");
        }
        Map<Long, Long> executionMap = new HashMap<>();
        List<PickingExecution> executions = new ArrayList<>();
        for (Long orderId : orderIds) {
            HandlingExecutionDTO execution = reserveOrder(orderId, badgeId);
            PickingExecution pickingExecution = (PickingExecution) HandlingExecution.findById(execution.id);
            executions.add(pickingExecution);
            executionMap.put(orderId, execution.id);
        }
        PickingExecutionStateDTO state = buildCurrentState(executions);
        return new BatchStartResponse(executionMap, state);
    }

    /**
     * Retrieves the current consolidated state of a batch of picking executions.
     * <p>
     * Aggregates items, totes, and execution summaries from multiple executions into a single
     * DTO for client-side consumption.
     * </p>
     *
     * @param executionIds The list of unique identifiers for the executions to retrieve.
     * @return A {@link PickingExecutionStateDTO} representing the unified current state.
     */
    @Transactional
    public PickingExecutionStateDTO getBatchState(List<Long> executionIds) {
        if (executionIds == null || executionIds.isEmpty()) {
            return buildCurrentState(List.of());
        }
        List<PickingExecution> executions = PickingExecution.list("id in ?1", executionIds);
        return buildCurrentState(executions);
    }

    /**
     * Substitutes the product of a specific picked item.
     * <p>
     * Validates that the item belongs to the specified execution before updating the product reference.
     * </p>
     *
     * @param executionId   The ID of the execution context for validation.
     * @param pickedItemId  The ID of the picked item to update.
     * @param newProductId  The ID of the new product to assign.
     * @throws IllegalArgumentException if the item, product, or execution is not found,
     *                                  or if the item does not belong to the execution.
     */
    @Transactional
    public void substituteProduct(Long executionId, Long pickedItemId, Long newProductId) {
        PickedItem item = PickedItem.findById(pickedItemId);
        if (item == null) throw new IllegalArgumentException("PickedItem not found");
        Product newProduct = Product.findById(newProductId);
        if (newProduct == null) throw new IllegalArgumentException("Product not found");
        PickingExecution execution = PickingExecution.findById(executionId);
        if (execution == null || !execution.itemsToPick.contains(item)) {
            throw new IllegalArgumentException("Item does not belong to the specified execution");
        }
        item.product = newProduct;
    }

    // --------------------------------------------------
    // Public API: Picking Execution Management
    // --------------------------------------------------

    /**
     * Adds a physical tote to a specific picking execution.
     * <p>
     * Registers an empty tote for use in the picking process, associating it with the execution
     * and setting its intended temperature zone usage.
     * </p>
     *
     * @param executionId The unique identifier of the picking execution.
     * @param toteCode    The business code of the tote to add.
     * @param usage       The intended temperature zone usage (e.g., AMBIENT, CHILLED).
     * @throws IllegalArgumentException if the execution or tote is not found.
     */
    @Transactional
    public void addTote(Long executionId, String toteCode, TemperatureZone usage) {
        PickingExecution execution = (PickingExecution) HandlingExecution.findById(executionId);
        if (execution == null) throw new IllegalArgumentException("Execution not found");
        Tote physicalTote = Tote.find("code", toteCode).firstResult();
        if (physicalTote == null) throw new IllegalArgumentException("Tote not found with code: " + toteCode);
        LoadedTote loadedTote = LoadedTote.useAnEmptyTote(physicalTote);
        loadedTote.storageType = usage;
        loadedTote.persist();
        execution.loadedTotes.add(loadedTote);
    }

    /**
     * Removes an empty tote from a specific picking execution.
     * <p>
     * The operation fails if the tote contains any items.
     * </p>
     *
     * @param executionId The unique identifier of the picking execution.
     * @param toteCode    The business code of the tote to remove.
     * @throws IllegalArgumentException if the execution or tote is not found,
     *                                  or if the tote is not empty.
     */
    @Transactional
    public void removeEmptyTote(Long executionId, String toteCode) {
        PickingExecution execution = (PickingExecution) HandlingExecution.findById(executionId);
        if (execution == null) throw new IllegalArgumentException("Execution not found");
        LoadedTote loadedTote = LoadedTote.find("tote.code = ?1 and status = ?2", toteCode, LoadedTote.Status.IN_USE).firstResult();
        if (loadedTote == null) throw new IllegalArgumentException("LoadedTote not found");
        execution.removeEmptyTote(loadedTote.tote);
        loadedTote.delete();
    }

    /**
     * Moves or adds a picked item into a specific tote within an execution.
     * <p>
     * Assigns the item to the tote. Supports quantity allocation and product substitution
     * during the move operation.
     * </p>
     *
     * @param executionId  The unique identifier of the picking execution.
     * @param toteCode     The code of the destination tote.
     * @param pickedItemId The ID of the picked item to move.
     * @param quantity     The quantity to move. If null, the full quantity is assumed.
     * @param productId    The ID of the product for substitution. If null, the original product is kept.
     * @throws IllegalArgumentException if the execution, tote, or item is not found.
     */
    @Transactional
    public void addItemToTote(Long executionId, String toteCode, Long pickedItemId, BigDecimal quantity, Long productId) {
        PickingExecution execution = (PickingExecution) HandlingExecution.findById(executionId);
        LoadedTote tote = LoadedTote.find("tote.code = ?1 and status = ?2", toteCode, LoadedTote.Status.IN_USE).firstResult();
        PickedItem item = PickedItem.findById(pickedItemId);
        if (execution == null || tote == null || item == null) {
            throw new IllegalArgumentException("Invalid execution, tote, or item");
        }
        Product newProduct = null;
        if (productId != null) {
            newProduct = Product.findById(productId);
            if (newProduct == null) throw new IllegalArgumentException("Product not found: " + productId);
        }
        execution.addItemToTote(tote, item, quantity, newProduct);
    }

    /**
     * Moves a picked item from one tote to another within the same execution.
     * <p>
     * Removes the item from the source tote and adds it to the target tote.
     * </p>
     *
     * @param executionId    The unique identifier of the picking execution.
     * @param sourceToteCode The code of the source tote (can be null if item is unassigned).
     * @param targetToteCode The code of the destination tote.
     * @param pickedItemId   The ID of the picked item to move.
     * @throws IllegalArgumentException if the item or target tote is not found.
     */
    @Transactional
    public void moveItem(Long executionId, String sourceToteCode, String targetToteCode, Long pickedItemId) {
        PickingExecution execution = (PickingExecution) HandlingExecution.findById(executionId);
        if (execution == null) throw new IllegalArgumentException("Execution not found");
        PickedItem item = PickedItem.findById(pickedItemId);
        LoadedTote targetTote = LoadedTote.find("tote.code = ?1 and status = ?2", targetToteCode, LoadedTote.Status.IN_USE).firstResult();
        if (item == null || targetTote == null) throw new IllegalArgumentException("Item or Target Tote not found");
        LoadedTote sourceTote = null;
        if (sourceToteCode != null && !sourceToteCode.isBlank()) {
            sourceTote = LoadedTote.find("tote.code = ?1 and status = ?2", sourceToteCode, LoadedTote.Status.IN_USE).firstResult();
        }
        execution.moveItemToTote(sourceTote, targetTote, item);
    }

    /**
     * Removes a picked item from a tote and returns it to the work list.
     * <p>
     * Unassigns the item from its current tote, effectively marking it as unpicked
     * or pending re-assignment.
     * </p>
     *
     * @param executionId  The unique identifier of the picking execution.
     * @param toteCode     The code of the tote containing the item.
     * @param pickedItemId The ID of the item to remove.
     * @throws IllegalArgumentException if the item is not found.
     */
    @Transactional
    public void removeItemFromTote(Long executionId, String toteCode, Long pickedItemId) {
        PickedItem item = PickedItem.findById(pickedItemId);
        if (item == null) throw new IllegalArgumentException("Item not found");
        if (item.loadedTote != null) {
            item.loadedTote.items.remove(item);
            item.loadedTote = null;
        }
    }

    /**
     * Cancels a specific picked item in the execution work list.
     * <p>
     * Updates the status of the item to {@link PickedItem.PickStatus#CANCELED}.
     * </p>
     *
     * @param executionId  The unique identifier of the picking execution.
     * @param pickedItemId The ID of the item to cancel.
     * @throws IllegalArgumentException if the item is not found.
     */
    @Transactional
    public void cancelPickedItem(Long executionId, Long pickedItemId) {
        PickedItem item = PickedItem.findById(pickedItemId);
        if (item == null) throw new IllegalArgumentException("Item not found");
        item.status = PickedItem.PickStatus.CANCELED;
    }

    /**
     * Concludes a picking execution, assigning all totes to a specific buffer.
     * <p>
     * Validates the execution and buffer, then delegates the conclusion logic to the
     * execution entity. Updates the execution status to {@code DONE}.
     * </p>
     *
     * @param executionId The unique identifier of the picking execution.
     * @param bufferId    The unique identifier of the destination buffer.
     * @throws IllegalArgumentException if the execution or buffer is not found.
     */
    @Transactional
    public void concludePickingExecution(Long executionId, Long bufferId) {
        PickingExecution execution = (PickingExecution) HandlingExecution.findById(executionId);
        Buffer buffer = Buffer.findById(bufferId);
        if (execution == null || buffer == null) throw new IllegalArgumentException("Execution or Buffer not found");
        execution.concludeExecution(buffer);
        execution.status = HandlingExecution.ExecutionStatus.DONE;
    }

    // --------------------------------------------------
    // Public API: Grouping Execution Management
    // --------------------------------------------------

    /**
     * Moves a physical tote to the destination buffer defined by the GroupingOrder.
     * <p>
     * Identifies the active loaded tote for the provided tote code and updates its buffer assignment
     * to the target defined in the parent GroupingOrder.
     * </p>
     *
     * @param executionId The unique identifier of the grouping execution.
     * @param toteCode    The business code of the tote to move.
     */
    @Transactional
    public void moveToteToDestination(Long executionId, String toteCode) {
        HandlingExecution exec = HandlingExecution.findById(executionId);
        if (exec instanceof GroupingExecution ge) {
            Tote t = Tote.find("code", toteCode).firstResult();
            if (t != null) ge.moveToteToDestination(t);
        }
    }

    /**
     * Concludes a grouping execution after verifying all totes are at the destination.
     * <p>
     * Delegates the validation and conclusion to the GroupingExecution entity and sets the status to {@code DONE}.
     * </p>
     *
     * @param executionId The unique identifier of the grouping execution.
     */
    @Transactional
    public void concludeGroupingExecution(Long executionId) {
        HandlingExecution exec = HandlingExecution.findById(executionId);
        if (exec instanceof GroupingExecution ge) {
            ge.concludeExecution();
            ge.status = HandlingExecution.ExecutionStatus.DONE;
        }
    }

    // --------------------------------------------------
    // Public API: Bulk Execution Validation Endpoint
    // --------------------------------------------------

    /**
     * Saves the entire execution state (totes, allocations) and concludes the process.
     * <p>
     * Processes a full report of the execution state, assigns items to totes as specified,
     * and concludes the execution against the provided buffer.
     * </p>
     *
     * @param executionId The unique identifier of the execution.
     * @param request     The request body containing the final state and buffer ID.
     * @throws IllegalArgumentException if the request is null or the execution is not found.
     */
    @Transactional
    public void saveAndConcludeExecution(Long executionId, ExecutionReportRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }
        PickingExecution execution = (PickingExecution) HandlingExecution.findById(executionId);
        if (execution == null) {
            throw new IllegalArgumentException("Execution not found");
        }
        Buffer buffer = (request.bufferId != null) ? Buffer.findById(request.bufferId) : null;
        if (request.totes != null) {
            for (ToteReportDTO toteDto : request.totes) {
                assignItemsToTote(execution, toteDto, id -> PickedItem.findById(id));
            }
        }
        if (buffer != null) {
            execution.concludeExecution(buffer);
        }
        execution.status = HandlingExecution.ExecutionStatus.DONE;
    }

    /**
     * Creates executions for a batch of orders AND populates them with the provided state in one transaction.
     * <p>
     * This method handles the entire lifecycle for a batch: creating executions, validating items,
     * assigning them to totes, and concluding the execution. It creates a 'snapshot' of the execution
     * based on the provided report.
     * </p>
     *
     * @param request The request containing order IDs, buffer ID, and detailed reports for each order.
     * @throws IllegalArgumentException if input parameters are invalid (e.g., null request, null order IDs,
     *                                  order not found, or order already has an execution).
     * @throws IllegalStateException if the created execution is not of the expected type (PickingExecution).
     */
    @Transactional
    public void createAndConcludeBatch(BatchValidationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }
        if (request.orderIds == null || request.orderIds.isEmpty()) {
            throw new IllegalArgumentException("Order IDs list cannot be null or empty");
        }
        Map<Long, OrderReportDTO> reportMap = new HashMap<>();
        if (request.reports != null) {
            for (OrderReportDTO r : request.reports) {
                if (r == null) {
                    throw new IllegalArgumentException("Order report cannot be null");
                }
                reportMap.put(r.orderId, r);
            }
        }
        for (Long orderId : request.orderIds) {
            BaseHandlingOrder order = BaseHandlingOrder.findById(orderId);
            if (order == null) throw new IllegalArgumentException("Order not found: " + orderId);
            HandlingExecution existingExec = HandlingExecution.find("handlingOrder.id", orderId).firstResult();
            if (existingExec != null) {
                throw new IllegalArgumentException("Order " + orderId + " already has an execution");
            }
            //Employee employee = Employee.find("badgeId", "CURRENT_USER_BADGE").firstResult();
            Account account = authenticationService.getConnectedAccount();
            Employee employee = (Employee)(account != null && (account instanceof Employee) ? account : null);
            if (employee == null) {
                throw new IllegalStateException("Employee not found for current user");
            }
            HandlingExecution execution = order.createExecution(employee);
            execution.persist();
            if (!(execution instanceof PickingExecution pe)) {
                throw new IllegalStateException("Execution for order " + orderId + " is not a PickingExecution");
            }
            Map<Long, PickedItem> lineToItemMap = new HashMap<>();
            if (pe.itemsToPick != null) {
                for (PickedItem item : pe.itemsToPick) {
                    if (item.lotLine != null) {
                        lineToItemMap.put(item.lotLine.id, item);
                    }
                }
            }
            OrderReportDTO report = reportMap.get(orderId);
            if (report != null && report.totes != null) {
                for (ToteReportDTO toteDto : report.totes) {
                    assignItemsToTote(pe, toteDto, lineToItemMap::get);
                }
            }
            Buffer buffer = (request.bufferId != null) ? Buffer.findById(request.bufferId) : null;
            if (buffer != null) {
                ((PickingExecution) execution).concludeExecution(buffer);
            }
            execution.status = HandlingExecution.ExecutionStatus.DONE;
        }
    }

    // --------------------------------------------------
    // Private Helpers
    // --------------------------------------------------

    /**
     * Helper method to process a ToteReportDTO and assign items to a LoadedTote.
     * <p>
     * This method encapsulates the logic for synchronizing the state of a tote based on a report:
     * <ul>
     *   <li>Retrieves an existing LoadedTote by code or creates a new one if it doesn't exist.</li>
     *   <li>Associates the LoadedTote with the current execution.</li>
     *   <li>Iterates through the items in the DTO, resolving them via the provided lookup function.</li>
     *   <li>Updates the item's status to PICKED, assigns it to the tote, and sets the picked quantity.</li>
     * </ul>
     * </p>
     *
     * @param execution   The current picking execution context to which the tote belongs.
     * @param toteDto     The DTO containing the tote code, zone, and list of items to assign.
     * @param itemLookup  A function mapping an item ID (from the DTO) to the managed PickedItem entity.
     *                    This allows flexibility between looking up items directly from the database
     *                    or from a pre-loaded map (used in batch operations).
     */
    private void assignItemsToTote(PickingExecution execution, ToteReportDTO toteDto, Function<Long, PickedItem> itemLookup) {
        if (toteDto == null) {
            throw new IllegalArgumentException("Tote report cannot be null");
        }
        LoadedTote loadedTote = LoadedTote.find("tote.code = ?1", toteDto.code).firstResult();
        if (loadedTote == null) {
            Tote physicalTote = Tote.find("code", toteDto.code).firstResult();
            if (physicalTote == null) {
                throw new IllegalArgumentException("Tote not found: " + toteDto.code);
            }
            loadedTote = LoadedTote.useAnEmptyTote(physicalTote);
            loadedTote.storageType = toteDto.zone;
            loadedTote.persist();
            execution.loadedTotes.add(loadedTote);
        }
        if (toteDto.items != null) {
            for (ToteItemReportDTO itemDto : toteDto.items) {
                if (itemDto == null) {
                    throw new IllegalArgumentException("Item report cannot be null");
                }
                PickedItem item = itemLookup.apply(itemDto.pickedItemId);
                if (item != null) {
                    item.loadedTote = loadedTote;
                    item.status = PickedItem.PickStatus.PICKED;
                    item.quantity = itemDto.qty;
                    if (loadedTote.items == null) loadedTote.items = new HashSet<>();
                    loadedTote.items.add(item);
                }
            }
        }
    }

    /**
     * Constructs the unified state DTO from a list of executions.
     * <p>
     * This method aggregates data from multiple {@link PickingExecution} entities into a single
     * {@link PickingExecutionStateDTO} suitable for the client. It performs the following:
     * <ul>
     *   <li>Collects execution summaries (ID, status, codes).</li>
     *   <li>Flattens all items from all executions into a single list.</li>
     *   <li>Flattens all totes from all executions into a single list.</li>
     *   <li>Sorts the aggregated item list by aisle code for optimized display.</li>
     * </ul>
     * </p>
     *
     * @param executions The list of PickingExecution entities to process.
     * @return A comprehensive PickingExecutionStateDTO representing the current state.
     */
    private PickingExecutionStateDTO buildCurrentState(List<PickingExecution> executions) {
        PickingExecutionStateDTO state = new PickingExecutionStateDTO();
        List<PickingItemDTO> allItems = new ArrayList<>();
        List<LoadedToteDTO> allTotes = new ArrayList<>();
        List<ExecutionSummaryDTO> summaries = new ArrayList<>();
        for (PickingExecution exec : executions) {
            ExecutionSummaryDTO summary = new ExecutionSummaryDTO();
            summary.id = exec.id;
            summary.orderId = exec.handlingOrder.id;
            summary.orderCode = exec.handlingOrder.code;
            summary.status = exec.status.name();
            summaries.add(summary);
            Long executionId = exec.id;
            Long orderId = exec.handlingOrder.id;
            if (exec.itemsToPick != null) {
                for (PickedItem item : exec.itemsToPick) {
                    allItems.add(mapToItemDTO(item, executionId, orderId));
                }
            }
            if (exec.loadedTotes != null) {
                for (LoadedTote tote : exec.loadedTotes) {
                    allTotes.add(mapToToteDTO(tote, orderId));
                }
            }
        }
        allItems.sort(Comparator.comparing(i -> i.aisle != null ? i.aisle : "ZZZ"));
        state.executions = summaries;
        state.items = allItems;
        state.totes = allTotes;
        return state;
    }

    // --------------------------------------------------
    // Mapping Helpers
    // --------------------------------------------------

    /**
     * Maps a {@link BaseHandlingOrder} entity to a summary DTO.
     * <p>
     * Extracts basic information such as ID, type, status, and requested time windows.
     * </p>
     *
     * @param order The source entity.
     * @return The mapped DTO.
     */
    private HandlingOrderSummaryDTO mapToSummaryDTO(BaseHandlingOrder order) {
        HandlingOrderSummaryDTO dto = new HandlingOrderSummaryDTO();
        dto.id = order.id;
        dto.type = order.getClass().getSimpleName();
        dto.status = order.status != null ? order.status.name() : null;
        dto.requestedStart = order.requestedStart;
        dto.requestedEnd = order.requestedEnd;
        return dto;
    }

    /**
     * Maps a {@link PickedItem} entity to a picking item DTO.
     * <p>
     * Populates fields relevant to the picking interface, including product details,
     * location data (aisle, unit, position), and current picking status.
     * Note: Location info is extracted only if the underlying location is a ShelfLocation.
     * </p>
     *
     * @param item        The source entity.
     * @param executionId The ID of the execution context (injected into DTO).
     * @param orderId     The ID of the parent order (injected into DTO).
     * @return The mapped DTO.
     */
    private PickingItemDTO mapToItemDTO(PickedItem item, Long executionId, Long orderId) {
        PickingItemDTO dto = new PickingItemDTO();
        dto.uid = item.id;
        dto.executionId = executionId;
        dto.orderId = orderId;
        dto.productName = (item.product != null) ? item.product.name : "Unknown";
        dto.ean = (item.product != null) ? item.product.ean : null;
        dto.temperatureZone = (item.product != null) ? item.product.temperatureZone : null;
        dto.qtyRequested = item.quantity;
        dto.unitLabel = "pcs";
        if (item.lotLine != null && item.lotLine.fulfillmentLine != null && item.lotLine.fulfillmentLine.location instanceof com.intermarche.backoffice.domain.store.ShelfLocation shelfLoc) {
            dto.aisle = shelfLoc.aisle;
            dto.unit = shelfLoc.unit;
            dto.pos = shelfLoc.position;
        }
        dto.picked = (item.loadedTote != null);
        dto.qtyAllocated = (item.loadedTote != null) ? item.quantity : BigDecimal.ZERO;
        return dto;
    }

    /**
     * Maps a {@link LoadedTote} entity to a tote DTO.
     * <p>
     * Populates tote details including code, type, zone, and calculates aggregate metrics
     * (total weight and volume) by summing the properties of contained items.
     * </p>
     *
     * @param tote    The source entity.
     * @param orderId The ID of the parent order (injected into DTO).
     * @return The mapped DTO.
     */
    private LoadedToteDTO mapToToteDTO(LoadedTote tote, Long orderId) {
        LoadedToteDTO dto = new LoadedToteDTO();
        dto.id = tote.id;
        dto.code = (tote.tote != null) ? tote.tote.code : "Unknown";
        dto.type = (tote.tote != null) ? tote.tote.toteType.name() : "STANDARD";
        dto.zone = tote.storageType;
        dto.orderId = orderId;
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal totalVolume = BigDecimal.ZERO;
        List<ToteItemDTO> itemDtos = new ArrayList<>();
        if (tote.items != null) {
            for (PickedItem item : tote.items) {
                ToteItemDTO itemDto = new ToteItemDTO();
                itemDto.pickedItemId = item.id;
                itemDto.qty = item.quantity;
                itemDto.productName = (item.product != null) ? item.product.name : "Unknown";
                itemDtos.add(itemDto);
                if (item.product != null && item.product.referenceWeight != null && item.quantity != null) {
                    totalWeight = totalWeight.add(item.product.referenceWeight.multiply(item.quantity));
                }
                if (item.product != null && item.product.referenceVolume != null && item.quantity != null) {
                    totalVolume = totalVolume.add(item.product.referenceVolume.multiply(item.quantity));
                }
            }
        }
        dto.items = itemDtos;
        dto.totalWeight = totalWeight;
        dto.totalVolume = totalVolume;
        return dto;
    }

    // --------------------------------------------------
    // Inner Classes & DTOs
    // --------------------------------------------------

    /**
     * Response container for batch execution start operations.
     * <p>
     * Contains the mapping between Order IDs and the newly created Execution IDs,
     * along with the initial picking state.
     * </p>
     */
    public static class BatchStartResponse {
        /** Map of Order ID to Execution ID. */
        public Map<Long, Long> executionMap;
        /** The initial state of the picking execution. */
        public PickingExecutionStateDTO state;

        /**
         * Constructs a new BatchStartResponse.
         *
         * @param map   The map of IDs.
         * @param state The initial state.
         */
        public BatchStartResponse(Map<Long, Long> map, PickingExecutionStateDTO state) {
            this.executionMap = map; this.state = state;
        }
    }

    /**
     * DTO representing the consolidated state of a picking execution batch.
     * <p>
     * Used to transmit the complete picture of active executions, pending items, and loaded totes
     * to the client application.
     * </p>
     */
    public static class PickingExecutionStateDTO {
        /** List of execution summaries. */
        public List<ExecutionSummaryDTO> executions;
        /** List of items to be picked. */
        public List<PickingItemDTO> items;
        /** List of totes currently loaded. */
        public List<LoadedToteDTO> totes;
    }

    /** Summary DTO for an execution, containing high-level details. */
    public static class ExecutionSummaryDTO {
        /** The unique ID of the execution. */
        public Long id;
        /** The ID of the parent order. */
        public Long orderId;
        /** The code of the parent order. */
        public String orderCode;
        /** The current status of the execution. */
        public String status;
    }

    /** DTO representing a single picking item. */
    public static class PickingItemDTO {
        /** The unique ID of the picked item. */
        public Long uid;
        /** The ID of the execution context. */
        public Long executionId;
        /** The ID of the parent order. */
        public Long orderId;
        /** The name of the product. */
        public String productName;
        /** The EAN code of the product. */
        public String ean;
        /** The requested quantity. */
        public BigDecimal qtyRequested;
        /** The allocated quantity (amount already picked). */
        public BigDecimal qtyAllocated;
        /** The unit label (e.g., "pcs"). */
        public String unitLabel;
        /** Flag indicating if the item has been fully picked. */
        public boolean picked;
        /** The aisle location. */
        public String aisle;
        /** The unit location. */
        public String unit;
        /** The position location. */
        public String pos;
        /** The temperature zone required. */
        public TemperatureZone temperatureZone;
    }

    /** DTO representing a loaded tote. */
    public static class LoadedToteDTO {
        /** The unique ID of the loaded tote. */
        public Long id;
        /** The code of the physical tote. */
        public String code;
        /** The type of the tote (e.g., STANDARD, INSULATED). */
        public String type;
        /** The storage type/zone of the tote. */
        public TemperatureZone zone;
        /** The ID of the associated order. */
        public Long orderId;
        /** The total calculated weight of contents. */
        public BigDecimal totalWeight;
        /** The total calculated volume of contents. */
        public BigDecimal totalVolume;
        /** List of items inside the tote. */
        public List<ToteItemDTO> items;
    }

    /** DTO for an item inside a tote. */
    public static class ToteItemDTO {
        /** The ID of the picked item. */
        public Long pickedItemId;
        /** The quantity placed in the tote. */
        public BigDecimal qty;
        /** The name of the product. */
        public String productName;
    }

    /** Summary DTO for a handling order. */
    public static class HandlingOrderSummaryDTO {
        /** The unique ID of the order. */
        public long id;
        /** The type of the order (class name). */
        public String type;
        /** The status of the order. */
        public String status;
        /** The requested start time. */
        public LocalDateTime requestedStart;
        /** The requested end time. */
        public LocalDateTime requestedEnd;
    }

    /** DTO representing a handling execution. */
    public static class HandlingExecutionDTO {
        /** The unique ID of the execution. */
        public Long id;
        /** The type of execution (class name). */
        public String type;
        /** The ID of the parent order. */
        public Long orderId;
        /** The badge ID of the employee. */
        public String badgeId;
        /** The full name of the employee. */
        public String employeeName;
        /** The creation timestamp. */
        public LocalDateTime createdAt;

        /**
         * Constructs a new HandlingExecutionDTO.
         *
         * @param id   Execution ID.
         * @param type Type string.
         * @param oid  Order ID.
         * @param bid  Badge ID.
         * @param name Employee name.
         * @param date Creation date.
         */
        public HandlingExecutionDTO(Long id, String type, Long oid, String bid, String name, LocalDateTime date) {
            this.id = id; this.type = type; orderId = oid; badgeId = bid; employeeName = name; createdAt = date;
        }
    }

    /** Request body for validating and concluding an execution. */
    public static class ExecutionReportRequest {
        /** The target buffer ID. */
        public Long bufferId;
        /** List of totes with their contents. */
        public List<ToteReportDTO> totes;
        /** List of remaining/unallocated items. */
        public List<PickingItemDTO> remainingItems;
    }

    /** Report DTO for a specific tote in the validation request. */
    public static class ToteReportDTO {
        /** The code of the tote. */
        public String code;
        /** The storage zone. */
        public TemperatureZone zone;
        /** List of items in the tote. */
        public List<ToteItemReportDTO> items;
    }

    /** Report DTO for an item inside a tote validation request. */
    public static class ToteItemReportDTO {
        /** The ID of the picked item. */
        public Long pickedItemId;
        /** The quantity. */
        public BigDecimal qty;
    }

    /** Request body for creating and concluding a batch in one go. */
    public static class BatchValidationRequest {
        /** List of order IDs to process. */
        public List<Long> orderIds;
        /** The target buffer ID. */
        public Long bufferId;
        /** Detailed reports for each order. */
        public List<OrderReportDTO> reports;
    }

    /** Report DTO for a specific order in the batch validation request. */
    public static class OrderReportDTO {
        /** The ID of the order. */
        public Long orderId;
        /** List of totes associated with this order. */
        public List<ToteReportDTO> totes;
    }
}