package com.intermarche.backoffice.engine.supervision;

import com.intermarche.backoffice.domain.stock.ExpectedSupply;
import com.intermarche.backoffice.domain.store.BaseLocation;
import org.hibernate.Hibernate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Sequential availability of the quantities to withdraw at stock locations, shared by the handling supervision and the
 * activity supervision so that both apply exactly the same rules.
 * <p>
 * A need is available when the current physical balance of its location, minus the quantities reserved by the needs
 * evaluated before it, plus the expected supplies arriving between now and the time it is needed, covers it. Overdue
 * supplies are never counted as available: a need they would cover is a late-supply situation. A location without
 * any ledger history nor expected supply has an unknown stock, which is not a shortage.
 */
final class LocationAvailability {

    /** States of an assessment; {@code null} means available. */
    static final String UNKNOWN = "UNKNOWN", LATE_SUPPLY = "LATE_SUPPLY", SHORTAGE_NOW = "SHORTAGE_NOW", SHORTAGE_FORECAST = "SHORTAGE_FORECAST";

    /**
     * Result of one need.
     *
     * @param consumer key of the consumer: {@code H:<handling order id>} or {@code C:<customer order id>}
     * @param consumerCode code shown when a later need shares the location
     * @param reference caller object describing the need (handling need or customer order line)
     * @param locationId location, or null when unknown
     * @param locationCode location code
     * @param buildingId building of the location in the store, or null
     * @param quantity quantity needed
     * @param neededAt time the quantity is needed
     * @param state null when available, otherwise UNKNOWN, LATE_SUPPLY, SHORTAGE_NOW or SHORTAGE_FORECAST
     * @param unknownReason NO_LOCATION, OUTSIDE_STORE or NO_HISTORY for an unknown stock, otherwise null
     * @param physical current physical balance, null when the stock is unknown
     * @param free physical balance minus earlier reservations, null when the stock is unknown
     * @param incoming expected supplies arriving between now and the needed time
     * @param overdue expected supplies whose arrival time has passed without receipt
     * @param requiredSupplies on-time supplies without which the need is not covered, in arrival order
     * @param overdueSupplies overdue supplies of the location
     * @param earlierConsumers codes of the needs evaluated before at the same location
     */
    record Assessment(String consumer, String consumerCode, Object reference, Long locationId, String locationCode, Long buildingId,
                      BigDecimal quantity, LocalDateTime neededAt, String state, String unknownReason, BigDecimal physical, BigDecimal free,
                      BigDecimal incoming, BigDecimal overdue, List<ExpectedSupply> requiredSupplies, List<ExpectedSupply> overdueSupplies,
                      List<String> earlierConsumers) {

        /**
         * Tells whether the need is covered.
         *
         * @return true when available
         */
        boolean available() {
            return state == null;
        }
    }

    private final LocalDateTime now;
    private final Map<Long, BigDecimal> physicalByLocation;
    private final Set<Long> locationsWithHistory;
    private final List<ExpectedSupply> incoming;
    private final Map<Long, Long> buildingOfLocation;
    private final Map<Long, BigDecimal> consumed = new HashMap<>();
    private final Map<Long, List<String>> consumers = new HashMap<>();
    private final Map<Long, BigDecimal> supplyUsed = new HashMap<>();
    private final List<Assessment> history = new ArrayList<>();

    /**
     * Creates the availability state from the loaded ledger data.
     *
     * @param now reference time
     * @param physicalByLocation current balances by location
     * @param locationsWithHistory locations having at least one ledger movement
     * @param incoming expected supplies of the concerned locations
     * @param buildingOfLocation building of each concerned location in the store
     */
    LocationAvailability(LocalDateTime now, Map<Long, BigDecimal> physicalByLocation, Set<Long> locationsWithHistory,
                         List<ExpectedSupply> incoming, Map<Long, Long> buildingOfLocation) {
        this.now = now;
        this.physicalByLocation = physicalByLocation;
        this.locationsWithHistory = locationsWithHistory;
        this.incoming = incoming;
        this.buildingOfLocation = buildingOfLocation;
    }

    /**
     * Evaluates one need and, when the stock is known, reserves its quantity for the needs evaluated afterwards.
     *
     * @param consumer consumer key
     * @param consumerCode consumer code
     * @param reference caller description of the need
     * @param locationId location, or null
     * @param locationCode location code
     * @param quantity quantity needed
     * @param neededAt time the quantity is needed
     * @param begun whether the need is already due, which turns a shortage into a current one
     * @return the assessment, also kept in the history
     */
    Assessment assess(String consumer, String consumerCode, Object reference, Long locationId, String locationCode,
                      BigDecimal quantity, LocalDateTime neededAt, boolean begun) {
        Long buildingId = locationId == null ? null : buildingOfLocation.get(locationId);
        var supplies = locationId == null ? List.<ExpectedSupply>of()
                : incoming.stream().filter(x -> Hibernate.unproxy(x.destination, BaseLocation.class).id.equals(locationId))
                .sorted(Comparator.comparing((ExpectedSupply x) -> x.availableAt).thenComparing(x -> x.id)).toList();
        var onTime = supplies.stream().filter(x -> !x.availableAt.isBefore(now) && !x.availableAt.isAfter(neededAt)).toList();
        var late = supplies.stream().filter(x -> x.availableAt.isBefore(now)).toList();
        BigDecimal incomingQuantity = onTime.stream().map(LocationAvailability::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overdueQuantity = late.stream().map(LocationAvailability::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
        String unknown = locationId == null ? "NO_LOCATION" : buildingId == null ? "OUTSIDE_STORE"
                : !locationsWithHistory.contains(locationId) && supplies.isEmpty() ? "NO_HISTORY" : null;
        Assessment result;
        if (unknown != null) {
            result = new Assessment(consumer, consumerCode, reference, locationId, locationCode, buildingId, quantity, neededAt, UNKNOWN, unknown,
                    null, null, incomingQuantity, overdueQuantity, List.of(), late, List.of());
        } else {
            BigDecimal physical = physicalByLocation.getOrDefault(locationId, BigDecimal.ZERO);
            BigDecimal free = physical.subtract(consumed.getOrDefault(locationId, BigDecimal.ZERO));
            List<String> earlier = List.copyOf(consumers.getOrDefault(locationId, List.of()));
            String state;
            if (free.compareTo(quantity) >= 0 || free.add(incomingQuantity).compareTo(quantity) >= 0) state = null;
            else if (free.add(incomingQuantity).add(overdueQuantity).compareTo(quantity) >= 0) state = LATE_SUPPLY;
            else state = begun ? SHORTAGE_NOW : SHORTAGE_FORECAST;
            var required = state == null && free.compareTo(quantity) < 0 ? requiredSupplies(onTime, quantity.subtract(free.max(BigDecimal.ZERO))) : List.<ExpectedSupply>of();
            result = new Assessment(consumer, consumerCode, reference, locationId, locationCode, buildingId, quantity, neededAt, state, null,
                    physical, free, incomingQuantity, overdueQuantity, required, late, earlier);
            consumed.merge(locationId, quantity, BigDecimal::add);
            consumers.computeIfAbsent(locationId, k -> new ArrayList<>()).add(consumerCode);
        }
        history.add(result);
        return result;
    }

    /**
     * Selects, in arrival order, the on-time supplies whose not yet promised quantities cover a missing quantity.
     *
     * @param onTime supplies arriving before the needed time
     * @param missing quantity the current free stock does not cover
     * @return the supplies the need depends on
     */
    private List<ExpectedSupply> requiredSupplies(List<ExpectedSupply> onTime, BigDecimal missing) {
        var result = new ArrayList<ExpectedSupply>();
        BigDecimal left = missing;
        for (var supply : onTime) {
            if (left.signum() <= 0) break;
            BigDecimal open = remaining(supply).subtract(supplyUsed.getOrDefault(supply.id, BigDecimal.ZERO));
            if (open.signum() <= 0) continue;
            BigDecimal taken = open.min(left);
            supplyUsed.merge(supply.id, taken, BigDecimal::add);
            left = left.subtract(taken);
            result.add(supply);
        }
        return result;
    }

    /**
     * Returns the quantity of a supply still to receive.
     *
     * @param supply the supply
     * @return expected minus received
     */
    private static BigDecimal remaining(ExpectedSupply supply) {
        return supply.quantity.subtract(supply.received);
    }

    /**
     * Returns every assessment in evaluation order.
     *
     * @return the history
     */
    List<Assessment> history() {
        return Collections.unmodifiableList(history);
    }
}
