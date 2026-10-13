package com.intermarche.backoffice.engine;

import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.*;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service responsible for the backward scheduling of {@link PreparingOrder} instances.
 * <p>
 * This sequencer starts from the {@link PackingOrder} deadlines and calculates the required
 * start times for upstream orders ({@link GroupingOrder}, {@link CollectingOrder}) by
 * tracing shared {@link Lot}s. The algorithm guarantees strict sequential execution
 * and enforces a "no advance" rule: an order can only be pushed back in time or kept
 * at its current scheduled time, but never moved forward.
 * <p>
 * The original duration of each order (defined as {@code requestedEnd - requestedStart})
 * is strictly preserved during the scheduling process.
 */
@ApplicationScoped
public class OrderScheduler {

    /**
     * Schedules all open handling orders for a specific store using an incremental backward scheduling strategy.
     * <p>
     * This method orchestrates the scheduling process across three distinct phases:
     * {@link PackingOrder}, {@link GroupingOrder}, and {@link CollectingOrder}. It acts as the main
     * entry point, triggering each phase sequentially and passing the resulting lot availability
     * constraints from one phase to the next.
     *
     * @param store The store for which the scheduling process is executed.
     */
    @Transactional
    public void scheduleOpenedOrders(Store store) {
        Log.infof("Starting incremental backward scheduling for store %s", store.code);
        Map<Long, LocalDateTime> lotAvailabilityMap = schedulePackingPhase(store);
        if (lotAvailabilityMap.isEmpty()) {
            return;
        }
        lotAvailabilityMap = scheduleGroupingPhase(store, lotAvailabilityMap);
        scheduleCollectingPhase(store, lotAvailabilityMap);
    }

    /**
     * Executes the first phase of the scheduling process, targeting {@link PackingOrder} entities.
     * <p>
     * This method retrieves all packing orders in the {@code OPEN} state for the given store.
     * For each packing order, it calculates the required start time based on its {@code deadLine}
     * and its intrinsic duration. It enforces the "no advance" rule and updates the order's status
     * to {@code SCHEDULED}.
     *
     * @param store The store for which packing orders are evaluated.
     * @return A map associating {@link Lot} IDs with their newly calculated availability times
     *         (which correspond to the start times of the scheduled packing orders).
     */
    private Map<Long, LocalDateTime> schedulePackingPhase(Store store) {
        Map<Long, LocalDateTime> lotAvailabilityMap = new HashMap<>();
        // Using JPQL JOIN FETCH with DISTINCT to avoid N+1 queries and Cartesian product duplicates
        List<PackingOrder> openPackers = PackingOrder.find(
                "SELECT DISTINCT o FROM PackingOrder o JOIN FETCH o.lots WHERE o.store = ?1 AND o.status = ?2",
                store,
                BaseHandlingOrder.HandlingOrderStatus.OPEN
        ).list();
        for (PackingOrder packer : openPackers) {
            if (packer.deadLine == null) {
                Log.warnf("PackingOrder %s has no deadLine set. Skipping.", packer.code);
                continue;
            }
            Duration initialDuration = getDuration(packer);
            LocalDateTime theoreticalStart = packer.deadLine.minus(initialDuration);
            LocalDateTime actualStart = enforceNoAdvance(packer, theoreticalStart);
            packer.requestedStart = actualStart;
            packer.requestedEnd = actualStart.plus(initialDuration);
            packer.status = BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
            for (Lot lot : packer.lots) {
                lotAvailabilityMap.put(lot.id, actualStart);
            }
        }
        return lotAvailabilityMap;
    }

    /**
     * Executes the second phase of the scheduling process, targeting {@link GroupingOrder} entities.
     * <p>
     * This method retrieves grouping orders in {@code OPEN} or {@code SCHEDULED} states. It delegates
     * the complex backward propagation logic to {@link #processUpstreamPhase}, providing the current
     * lot availability constraints gathered from the packing phase.
     *
     * @param store         The store for which grouping orders are evaluated.
     * @param currentLotMap A map of lot availability times established during the packing phase.
     * @return An updated map of lot availability times, incorporating the start times of the
     *         newly scheduled grouping orders.
     */
    private Map<Long, LocalDateTime> scheduleGroupingPhase(Store store, Map<Long, LocalDateTime> currentLotMap) {
        List<GroupingOrder> groupers = GroupingOrder.find(
            "SELECT DISTINCT o FROM GroupingOrder o JOIN FETCH o.lots WHERE o.store = ?1 AND o.status = ?2",
            store,
            BaseHandlingOrder.HandlingOrderStatus.OPEN
        ).list();
        return processUpstreamPhase(groupers, currentLotMap);
    }

    /**
     * Executes the third and final phase of the scheduling process, targeting {@link CollectingOrder} entities.
     * <p>
     * This method retrieves collecting orders (excluding instances of {@link PackingOrder}) in
     * {@code OPEN} or {@code SCHEDULED} states. It delegates the backward propagation logic to
     * {@link #processUpstreamPhase} using the lot availability constraints gathered from the
     * grouping phase.
     *
     * @param store         The store for which collecting orders are evaluated.
     * @param currentLotMap A map of lot availability times established during the grouping phase.
     */
    private void scheduleCollectingPhase(Store store, Map<Long, LocalDateTime> currentLotMap) {
        List<CollectingOrder> collectors = CollectingOrder.find(
            "SELECT DISTINCT o FROM CollectingOrder o JOIN FETCH o.lots " +
                    "WHERE o.store = ?1 AND o.status = ?2 AND TYPE(o) <> PackingOrder",
            store,
            BaseHandlingOrder.HandlingOrderStatus.OPEN
        ).list();
        processUpstreamPhase(collectors, currentLotMap);
    }

    /**
     * Processes a specific upstream phase (Grouping or Collecting) by aligning its orders
     * with the availability constraints of downstream orders.
     * <p>
     * This method acts as a pipeline: it filters relevant orders, calculates their theoretical
     * start times, sorts them to ensure proper sequential processing, and applies temporal
     * constraints before updating their schedule.
     *
     * @param phaseOrders    The list of orders belonging to the current upstream phase.
     * @param currentLotMap  A map associating {@link Lot} IDs with their required availability
     *                       times (start times of the downstream orders needing them).
     * @param <T>            The specific type of {@link PreparingOrder} being processed.
     * @return An updated map of lot availability times, incorporating the start times of the
     *         orders scheduled in this phase to be used by the next upstream phase.
     */
    private <T extends PreparingOrder> Map<Long, LocalDateTime> processUpstreamPhase(
            List<T> phaseOrders,
            Map<Long, LocalDateTime> currentLotMap) {

        Map<Long, LocalDateTime> updatedLotMap = new HashMap<>(currentLotMap);
        List<T> affectedOrders = filterAffectedOrders(phaseOrders, currentLotMap);
        if (affectedOrders.isEmpty()) {
            return updatedLotMap;
        }
        Map<T, LocalDateTime> theoreticalStarts = calculateTheoreticalStarts(affectedOrders, currentLotMap);
        sortOrdersByTheoreticalStartDesc(affectedOrders, theoreticalStarts);
        LocalDateTime previousActualStart = null;
        for (T order : affectedOrders) {
            LocalDateTime theoreticalStart = theoreticalStarts.get(order);
            LocalDateTime finalStart = resolveFinalStartTime(order, theoreticalStart, previousActualStart);
            updateOrderSchedule(order, finalStart, updatedLotMap);
            previousActualStart = finalStart;
        }
        return updatedLotMap;
    }

    /**
     * Filters a list of orders to retain only those that share at least one {@link Lot}
     * with the constraints defined in the current lot map.
     *
     * @param phaseOrders   The list of orders to filter.
     * @param currentLotMap The map containing constrained lot IDs.
     * @param <T>           The specific type of {@link PreparingOrder}.
     * @return A filtered list containing only orders affected by downstream constraints.
     */
    private <T extends PreparingOrder> List<T> filterAffectedOrders(List<T> phaseOrders, Map<Long, LocalDateTime> currentLotMap) {
        return phaseOrders.stream()
                .filter(order -> order.lots.stream().anyMatch(lot -> currentLotMap.containsKey(lot.id)))
                .collect(Collectors.toList());
    }

    /**
     * Calculates the theoretical start time for each affected order.
     * <p>
     * The theoretical start time is determined by identifying the earliest time a lot from this
     * order is required by a downstream order, and subtracting the current order's execution duration.
     *
     * @param affectedOrders The list of orders impacted by downstream constraints.
     * @param currentLotMap  The map containing constrained lot IDs and their required availability times.
     * @param <T>            The specific type of {@link PreparingOrder}.
     * @return A map associating each affected order with its calculated theoretical start time.
     */
    private <T extends PreparingOrder> Map<T, LocalDateTime> calculateTheoreticalStarts(
            List<T> affectedOrders,
            Map<Long, LocalDateTime> currentLotMap) {

        Map<T, LocalDateTime> theoreticalStarts = new HashMap<>();
        for (T order : affectedOrders) {
            LocalDateTime minLotAvail = order.lots.stream()
                    .map(lot -> currentLotMap.get(lot.id))
                    .filter(Objects::nonNull)
                    .min(LocalDateTime::compareTo)
                    .orElse(LocalDateTime.now());
            Duration orderDuration = getDuration(order);
            theoreticalStarts.put(order, minLotAvail.minus(orderDuration));
        }
        return theoreticalStarts;
    }

    /**
     * Sorts the list of affected orders in descending order based on their theoretical start times.
     * <p>
     * Sorting in descending order ensures that the algorithm processes orders closest to the
     * downstream deadline first. This prevents upstream overlaps when sequentially positioning
     * the orders backwards in time.
     *
     * @param affectedOrders    The list of orders to sort (mutated in place).
     * @param theoreticalStarts The map containing the calculated theoretical start time for each order.
     * @param <T>               The specific type of {@link PreparingOrder}.
     */
    private <T extends PreparingOrder> void sortOrdersByTheoreticalStartDesc(
            List<T> affectedOrders,
            Map<T, LocalDateTime> theoreticalStarts) {
        affectedOrders.sort((o1, o2) -> theoreticalStarts.get(o2).compareTo(theoreticalStarts.get(o1)));
    }

    /**
     * Determines the definitive start time for an order by applying temporal constraints.
     * <p>
     * This method applies two core rules:
     * <ol>
     *   <li><b>No Advance:</b> The order cannot be scheduled earlier than its current {@code requestedStart}.</li>
     *   <li><b>Strict Sequentiality:</b> The order must logically finish before the previously processed
     *       order in the same phase starts. If these two rules conflict, a warning is logged, and
     *       the "no advance" rule takes precedence, resulting in an overlap.</li>
     * </ol>
     *
     * @param order               The order being evaluated.
     * @param theoreticalStart    The ideal start time calculated from downstream lot constraints.
     * @param previousActualStart The actual start time of the order processed immediately before this one.
     * @return The resolved, safe start time to apply to the order.
     */
    private LocalDateTime resolveFinalStartTime(PreparingOrder order, LocalDateTime theoreticalStart, LocalDateTime previousActualStart) {
        Duration orderDuration = getDuration(order);
        // Apply Rule 1: Prevent advancing the order in time
        LocalDateTime startWithNoAdvance = enforceNoAdvance(order, theoreticalStart);
        // Apply Rule 2: Prevent overlapping with the previously scheduled order in this phase
        if (previousActualStart != null) {
            LocalDateTime maxAllowedStart = previousActualStart.minus(orderDuration);

            if (startWithNoAdvance.isAfter(maxAllowedStart)) {
                Log.warnf("Scheduling conflict on %s: Cannot fit sequentially. Overlap will occur.", order.code);
                return startWithNoAdvance;
            }
        }
        return startWithNoAdvance;
    }

    /**
     * Updates the scheduling state of a specific order and propagates its availability to the lot map.
     * <p>
     * This method sets the {@code requestedStart} and {@code requestedEnd} (strictly preserving
     * the original duration), transitions the order status from {@code OPEN} to {@code SCHEDULED}
     * if necessary, and updates the lot availability map for upstream phases.
     *
     * @param order         The order to update.
     * @param finalStart    The resolved start time to apply.
     * @param updatedLotMap The global map tracking when lots become available.
     */
    private void updateOrderSchedule(PreparingOrder order, LocalDateTime finalStart, Map<Long, LocalDateTime> updatedLotMap) {
        Duration orderDuration = getDuration(order);
        order.requestedStart = finalStart;
        order.requestedEnd = finalStart.plus(orderDuration);
        if (order.status == BaseHandlingOrder.HandlingOrderStatus.OPEN) {
            order.status = BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
        }
        for (Lot lot : order.lots) {
            updatedLotMap.put(lot.id, finalStart);        }
    }

    /**
     * Extracts the execution duration of a preparing order.
     * <p>
     * The duration is strictly calculated as the time span between {@code requestedEnd}
     * and {@code requestedStart}. This duration must remain constant throughout the
     * scheduling process to preserve the physical execution time allocated to the order.
     *
     * @param order The preparing order.
     * @return The {@link Duration} representing the order's execution time.
     * @throws IllegalStateException If either {@code requestedStart} or {@code requestedEnd}
     *                               is null, as the duration cannot be determined.
     */
    private Duration getDuration(PreparingOrder order) {
        if (order.requestedStart != null && order.requestedEnd != null) {
            return Duration.between(order.requestedStart, order.requestedEnd);
        }
        throw new IllegalStateException("Order " + order.id + " cannot be scheduled: requestedStart and requestedEnd must be defined to determine its duration.");
    }


    /**
     * Determines the most appropriate start time for a given order, enforcing the "no advance" rule.
     * The rule ensures that an order's start time does not move later than its requested start time,
     * if such a time is defined. If the new calculated start time is after the requested start time,
     * the requested start time is preserved.
     *
     * @param order The order for which the start time is being enforced. The order may optionally define
     *              a requested start time to guide scheduling.
     * @param newCalculatedStart The newly calculated start time for the order, derived from scheduling logic.
     * @return The resolved start time for the order, honoring the "no advance" rule. This will be either
     *         the order's requested start time (if the calculated time is later) or the new calculated start time.
     */
    private LocalDateTime enforceNoAdvance(PreparingOrder order, LocalDateTime newCalculatedStart) {
        if (order.requestedStart == null) {
            return newCalculatedStart;
        }
        if (newCalculatedStart.isAfter(order.requestedStart)) {
            return order.requestedStart;
        }
        return newCalculatedStart;
    }
}