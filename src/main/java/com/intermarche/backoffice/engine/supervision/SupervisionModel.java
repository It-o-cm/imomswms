package com.intermarche.backoffice.engine.supervision;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Read model of the handling supervision, serialized as JSON for Storepilot.
 * <p>
 * Every business decision (detection, certainty, severity, proposals) is computed in Java;
 * the frontend only displays these values and calls the actions they describe.
 */
public final class SupervisionModel {

    /**
     * Utility holder: not instantiable.
     */
    private SupervisionModel() {
        throw new UnsupportedOperationException("Model holder");
    }

    /** Detected situation families. */
    public enum Type { LATE, UNASSIGNED, ASSIGNMENT, TRANSIT, STOCK, DEPENDENCY, BUFFER, FIELD_OPERATION }

    /** What is known: a proven blocking fact, a forecast risk, or missing data that prevents a verdict. */
    public enum Certainty { BLOCKING, RISK, MISSING_DATA }

    /** Decision priority. */
    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW }

    /** A building, with the type used by the shop and warehouse logos. */
    public record BuildingRef(Long id, String code, String type) {}

    /** An employee. */
    public record EmployeeRef(Long id, String name, String badgeId) {}

    /**
     * A handling order as shown on a card.
     *
     * @param kind frontend registry key (PRODUCT_COLLECTING, ZONE_COLLECTING, PACKING, GROUPING, REPLENISHMENT, STOCK_PROTECTION)
     * @param operation replenishment operation, when relevant
     * @param role why the order is listed: SUBJECT, CONCERNED, PREREQUISITE or CONSEQUENCE
     * @param stage human-readable execution stage, when known (e.g. two-stage shelf replenishment)
     */
    public record OrderRef(Long id, String code, String kind, String operation, String status,
                           LocalDateTime start, LocalDateTime end, EmployeeRef assignee,
                           List<BuildingRef> buildings, String role, String stage) {}

    /**
     * One product line concerned by a stock check.
     *
     * @param state SHORTAGE_NOW, SHORTAGE_FORECAST, LATE_SUPPLY or UNKNOWN
     * @param available physical quantity at the location now; null when unknown
     * @param incoming expected supplies arriving at the location before the operation
     * @param overdue expected supplies already past their ETA and not received
     */
    public record ProductLine(Long orderId, Long productId, Long accountId, String name, String ean, String unit, String productType,
                              BigDecimal required, BigDecimal available, BigDecimal incoming, BigDecimal overdue,
                              BuildingRef building, String locationCode, String state, String explanation) {}

    /**
     * Current occupancy of a buffer for one temperature. No future release is projected.
     */
    public record BufferZone(String zone, int occupied, int capacity, int remaining) {}

    /** A buffer with its current occupancy and why it is listed. */
    public record BufferRef(Long id, String code, BuildingRef building, boolean active,
                            List<BufferZone> zones, String status, String explanation) {}

    /**
     * A navigation or decision available for an anomaly.
     *
     * @param code OPEN_ORDER, OPEN_PREREQUISITE, REVIEW_PROPOSALS, COMPARE_SOLUTIONS, OPEN_STOCK, OPEN_BUFFERS or OPEN_POSITION
     */
    public record Action(String code, String label, boolean primary) {}

    /**
     * State of an order observed when a proposal was computed, sent back on application to detect changes.
     */
    public record Basis(Long orderId, Long version, String status, Long assigneeId,
                        LocalDateTime start, LocalDateTime end, Long bufferId) {}

    /**
     * A change the manager can validate. Nothing is applied without an explicit call.
     *
     * @param kind ASSIGN (same slot), RESCHEDULE (new slot and employee) or DESTINATION (buffer)
     */
    public record Proposal(String id, String kind, String label, String details, Long orderId, String orderCode,
                           Long employeeId, String employeeName, Long bufferId, String bufferCode,
                           LocalDateTime start, LocalDateTime end, Basis basis) {}

    /**
     * One supervision card.
     *
     * @param id stable identity: type and cause, independent of the display order
     * @param deadline threatened deadline, with its meaning in {@code deadlineLabel}
     * @param orders every concerned order, the subject first
     * @param consequences downstream orders impacted by the same cause
     * @param context parameters to open the reused views without a new search (period, buildings, employee)
     */
    public record Anomaly(String id, Type type, Certainty certainty, Severity severity, String title,
                          String explanation, String impact, LocalDateTime deadline, String deadlineLabel,
                          OrderRef order, List<OrderRef> orders, List<OrderRef> consequences,
                          List<BuildingRef> buildings, EmployeeRef employee, List<ProductLine> products,
                          List<BufferRef> buffers, List<Proposal> proposals, List<Action> actions,
                          Map<String, Object> context) {}

    /** Values available for the filters. */
    public record Filters(List<BuildingRef> buildings, List<EmployeeRef> employees, List<String> orderKinds) {}

    /**
     * Full supervision answer.
     *
     * @param analyzedOrders number of open orders examined
     * @param limited true when the order limit was reached
     * @param transitControl true when directed transits are configured, which activates position checks
     */
    public record Report(Long storeId, String storeCode, LocalDateTime generatedAt, LocalDateTime start,
                         LocalDateTime end, int analyzedOrders, boolean limited, boolean transitControl,
                         Map<String, Long> counters, List<Anomaly> anomalies, Filters filters, List<String> notes) {}

    /** Application request of one proposal. */
    public record ApplyRequest(Long storeId, String anomalyId, String proposalId, Basis basis,
                               LocalDateTime start, LocalDateTime end) {}

    /**
     * Application answer.
     *
     * @param status APPLIED or STALE
     * @param reasons precise reasons when the proposal is no longer valid
     * @param anomaly the recomputed anomaly, when it still exists
     */
    public record ApplyResult(String status, String message, List<String> reasons, Anomaly anomaly) {}
}
