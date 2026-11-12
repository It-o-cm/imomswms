package com.intermarche.backoffice.engine.supervision;

import com.intermarche.backoffice.engine.supervision.SupervisionModel.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Data returned by the activity supervision. Every aggregate, projection and priority is computed by the server; the
 * frontend only presents them.
 */
public final class ActivityModel {

    /**
     * Creates nothing: this class only groups the records of the API.
     */
    private ActivityModel() {}

    /** Business domain of a situation. */
    public enum Domain { ORDERS, STOCK, REPLENISHMENT, HANDLING, TEAM, BUFFERS, SHIPPING, INCIDENTS, QUALITY, INVENTORY, RETURNS, PRINTING }

    /**
     * Nature of a situation: proven problem, forecast risk, missing data, or stale data / diagnostic temporarily
     * unavailable. The first three keep the names of the handling supervision.
     */
    public enum Certainty { BLOCKING, RISK, MISSING_DATA, STALE }

    /**
     * A customer commitment concerned by a situation.
     *
     * @param id order identifier
     * @param code order code
     * @param customer customer name
     * @param deadline customer deadline (earliest chunk delivery date)
     * @param stage current stage code
     * @param stageLabel stage label
     * @param overdue whether the deadline has passed
     * @param link ESTABLISHED when the data prove the dependency, ESTIMATED when it is only probable
     * @param via how the order depends on the cause
     * @param buildings buildings of the order's chunks
     * @param otherSituations other situations threatening the same order
     */
    public record Commitment(Long id, String code, String customer, LocalDateTime deadline, String stage, String stageLabel,
                             boolean overdue, String link, String via, List<BuildingRef> buildings, List<String> otherSituations) {}

    /**
     * A proposal of the handling supervision offered by a situation, with the anomaly it belongs to.
     *
     * @param anomalyId handling anomaly identifier, used for the revalidated application
     * @param proposal the proposal
     */
    public record SituationProposal(String anomalyId, Proposal proposal) {}

    /**
     * Relation with another situation.
     *
     * @param situationId related situation
     * @param kind ESTABLISHED, ESTIMATED or CONCOMITANT (same time or place without proven link)
     * @param label explanation
     */
    public record Relation(String situationId, String kind, String label) {}

    /**
     * Node of the dependency graph of a situation.
     *
     * @param id node key
     * @param kind CAUSE, OPERATION, SUPPLY, SLOT, BUFFER, PRODUCT or ORDER
     * @param label short label
     * @param detail detail shown on hover or focus
     * @param state BLOCKING, RISK, MISSING_DATA, STALE or OK
     */
    public record GraphNode(String id, String kind, String label, String detail, String state) {}

    /**
     * Edge of the dependency graph.
     *
     * @param from source node
     * @param to target node
     * @param kind ESTABLISHED or ESTIMATED
     * @param label what flows from the source to the target
     */
    public record GraphEdge(String from, String to, String kind, String label) {}

    /**
     * Dependency graph from a cause to the commitments it compromises.
     *
     * @param nodes nodes, causes first
     * @param edges edges
     * @param hiddenOrders orders not drawn to keep the graph readable
     */
    public record Graph(List<GraphNode> nodes, List<GraphEdge> edges, int hiddenOrders) {}

    /**
     * A grouped situation: one cause, its consequences and the decisions available.
     *
     * @param id stable identity; the identifier of the handling anomaly when the situation is a handling diagnostic
     * @param kind situation code
     * @param domains business domains, the first one being the cause's
     * @param certainty nature
     * @param severity severity
     * @param rank priority rank, 1 being the first to handle
     * @param priorityReasons explainable criteria of the rank
     * @param title short title
     * @param summary one-sentence cause and impact
     * @param cause identified cause
     * @param consequences direct and indirect consequences
     * @param impact established impact
     * @param uncertainties alternative causes or missing information
     * @param deadline next deadline concerned
     * @param deadlineLabel meaning of the deadline
     * @param commitments customer commitments concerned
     * @param overdueCommitments number of concerned commitments already past their deadline
     * @param operations handling orders concerned
     * @param products products concerned
     * @param buffers buffers concerned
     * @param buildings buildings concerned
     * @param employee employee concerned
     * @param handlingAnomalyIds handling anomalies composing the situation
     * @param proposals proposals that can be validated
     * @param actions available actions, the primary one first
     * @param related related situations
     * @param graph dependency graph
     * @param context identifiers passed to the reused views
     */
    public record Situation(String id, String kind, List<Domain> domains, Certainty certainty, Severity severity, int rank,
                            List<String> priorityReasons, String title, String summary, String cause, List<String> consequences,
                            String impact, List<String> uncertainties, LocalDateTime deadline, String deadlineLabel,
                            List<Commitment> commitments, int overdueCommitments, List<OrderRef> operations, List<ProductLine> products,
                            List<BufferRef> buffers, List<BuildingRef> buildings, EmployeeRef employee, List<String> handlingAnomalyIds,
                            List<SituationProposal> proposals, List<Action> actions, List<Relation> related, Graph graph,
                            Map<String, Object> context) {}

    /**
     * An actionable indicator.
     *
     * @param code indicator code
     * @param label label
     * @param value numeric value, null when unavailable
     * @param display formatted value
     * @param detail complement
     * @param definition precise meaning
     * @param tone CRITICAL, WARNING, OK, INFO or UNKNOWN
     * @param situationIds situations shown when the indicator is selected
     * @param available whether the data needed are available
     */
    public record Kpi(String code, String label, Long value, String display, String detail, String definition, String tone,
                      List<String> situationIds, boolean available) {}

    /**
     * Commitments of one time bucket.
     *
     * @param start bucket start
     * @param end bucket end
     * @param onTrack commitments without situation
     * @param ready prepared commitments waiting for their departure
     * @param threatened commitments compromised by a proven or forecast problem
     * @param check commitments only concerned by missing data
     */
    public record DeadlineBucket(LocalDateTime start, LocalDateTime end, int onTrack, int ready, int threatened, int check) {}

    /**
     * One commitment of the deadline timeline.
     *
     * @param orderId order identifier
     * @param code order code
     * @param deadline deadline
     * @param state LATE, THREATENED, CHECK, READY or ON_TRACK
     * @param stage stage code
     * @param situationIds situations concerning it
     */
    public record DeadlinePoint(Long orderId, String code, LocalDateTime deadline, String state, String stage, List<String> situationIds) {}

    /**
     * Timeline of the customer deadlines of the horizon.
     *
     * @param from timeline start (now)
     * @param to horizon end
     * @param bucketMinutes bucket duration
     * @param late overdue commitments in the loaded window
     * @param buckets future buckets
     * @param points commitments, overdue first, then by deadline
     * @param hiddenPoints commitments not listed
     */
    public record DeadlineChart(LocalDateTime from, LocalDateTime to, int bucketMinutes, int late, List<DeadlineBucket> buckets,
                                List<DeadlinePoint> points, int hiddenPoints) {}

    /**
     * Planned load and capacity of one slot, in minutes.
     *
     * @param start slot start
     * @param end slot end
     * @param current whether now is inside the slot
     * @param capacity presence minus absences, null when no hours are entered that day
     * @param assigned handling minutes assigned to employees whose hours are entered that day
     * @param untracked handling minutes assigned to employees without any hours that day, not compared to the capacity
     * @param unassigned handling minutes without employee
     * @param tasks task minutes
     * @param transits planned transit minutes between buildings
     * @param absences absence minutes inside presences
     * @param shortfall load minus capacity when positive
     * @param situationId capacity situation of the slot
     */
    public record WorkloadSlot(LocalDateTime start, LocalDateTime end, boolean current, Integer capacity, int assigned, int untracked, int unassigned,
                               int tasks, int transits, int absences, int shortfall, String situationId) {}

    /**
     * Load and capacity of the team over the horizon.
     *
     * @param slotMinutes slot duration
     * @param slots slots
     * @param definitions meaning of the series
     */
    public record WorkloadChart(int slotMinutes, List<WorkloadSlot> slots, List<String> definitions) {}

    /**
     * Commitments of one stage (stages may overlap in time: they are not a sequential funnel).
     *
     * @param stage code
     * @param label label
     * @param count commitments
     * @param threatened commitments concerned by a proven or forecast problem
     * @param overdue commitments past their deadline
     * @param oldestAgeMinutes age of the oldest order since its reception
     * @param oldestCode code of the oldest order
     * @param maxOverdueMinutes longest delay past the deadline
     * @param orderIds orders of the stage
     */
    public record StageBar(String stage, String label, int count, int threatened, int overdue, Long oldestAgeMinutes, String oldestCode,
                           Long maxOverdueMinutes, List<Long> orderIds) {}

    /**
     * Current occupancy of one temperature of a buffer.
     *
     * @param zone temperature
     * @param occupied totes present
     * @param capacity tote places
     * @param percent occupancy percentage
     */
    public record BufferZoneBar(String zone, int occupied, int capacity, int percent) {}

    /**
     * Current occupancy of a buffer.
     *
     * @param id buffer identifier
     * @param code buffer code
     * @param building building
     * @param active whether the buffer accepts deposits
     * @param zones occupancy by temperature
     * @param plannedDeposits open operations planning a deposit
     * @param immobilized totes of orders whose deadline passed beyond the immobilization threshold
     * @param state OK, NEAR, FULL or INACTIVE
     * @param situationId situation concerning the buffer
     */
    public record BufferBar(Long id, String code, BuildingRef building, boolean active, List<BufferZoneBar> zones, int plannedDeposits,
                            int immobilized, String state, String situationId) {}

    /**
     * A product of the stock indicator.
     *
     * @param accountId stock account
     * @param productId product
     * @param name name
     * @param ean EAN
     * @param unit unit
     * @param productType product type
     * @param status UNAVAILABLE or TENSION
     * @param neededBy open commitments needing it
     */
    public record StockProduct(Long accountId, Long productId, String name, String ean, String unit, String productType, String status, int neededBy) {}

    /**
     * Availability states of the stock accounts, as maintained by the stock availability alerts.
     *
     * @param total accounts of the store
     * @param unavailable available balance exhausted now
     * @param tension exhaustion forecast inside the alert horizon
     * @param available available over the alert horizon
     * @param unevaluated never evaluated
     * @param stale evaluation pending for longer than the stale threshold
     * @param alertHorizonHours alert horizon
     * @param oldestCheck oldest evaluation time
     * @param products unavailable or in tension products, those needed by commitments first
     */
    public record StockChart(int total, int unavailable, int tension, int available, int unevaluated, int stale, int alertHorizonHours,
                             LocalDateTime oldestCheck, List<StockProduct> products) {}

    /**
     * Series of the overview charts.
     *
     * @param deadlines deadline timeline
     * @param workload load and capacity
     * @param stages commitments by stage
     * @param buffers buffer occupancy
     * @param stock stock states
     */
    public record CaseAgeBucket(String code,String label,int count,List<String> situationIds) {}
    public record CaseAgeChart(LocalDateTime asOf,String scope,List<CaseAgeBucket> buckets) {}
    public record Charts(DeadlineChart deadlines, WorkloadChart workload, List<StageBar> stages, List<BufferBar> buffers, StockChart stock,CaseAgeChart caseAges) {}

    /**
     * Freshness and completeness of one data source.
     *
     * @param code HANDLING, ORDERS, STOCK, PLANNING or BUFFERS
     * @param label label
     * @param status OK, PARTIAL, STALE, UNAVAILABLE or NO_DATA
     * @param message explanation
     * @param asOf time of the data
     */
    public record Source(String code, String label, String status, String message, LocalDateTime asOf) {}

    /**
     * Values of the filters.
     *
     * @param buildings buildings of the store
     * @param domains domains present
     * @param horizons horizons a manager may choose, in hours
     */
    public record Filters(List<BuildingRef> buildings, List<Domain> domains, List<Integer> horizons) {}

    /**
     * Link with the handling supervision: its period, used to revalidate a proposal, and the full anomalies composing
     * the situations, opened in the handling detail view.
     *
     * @param start handling period start
     * @param end handling period end
     * @param analyzedOrders handling orders analyzed
     * @param anomalies handling anomalies by identifier
     */
    public record HandlingLink(LocalDateTime start, LocalDateTime end, int analyzedOrders, Map<String, Anomaly> anomalies) {}

    /**
     * Settings applied to the analysis.
     *
     * @param horizonHours horizon used
     * @param values every threshold, with their origin and the ignored values
     */
    public record AppliedSettings(int horizonHours, SupervisionSettings values) {}

    /**
     * The activity supervision of a store.
     *
     * @param storeId store
     * @param storeCode store code
     * @param generatedAt computation time
     * @param start window start
     * @param end window end (horizon)
     * @param settings applied settings
     * @param kpis indicators
     * @param charts chart series
     * @param situations situations, by priority
     * @param counters counters by certainty, severity and domain
     * @param threatenedOrders distinct commitments compromised by a proven or forecast problem
     * @param filters filter values
     * @param sources data freshness
     * @param notes limits of the analysis
     * @param handling link with the handling supervision
     */
    public record Report(Long storeId, String storeCode, LocalDateTime generatedAt, LocalDateTime start, LocalDateTime end,
                         AppliedSettings settings, List<Kpi> kpis, Charts charts, List<Situation> situations, Map<String, Long> counters,
                         int threatenedOrders, Filters filters, List<Source> sources, List<String> notes, HandlingLink handling) {}

    /**
     * Progress of one line of a customer order.
     *
     * @param lineNumber line number
     * @param productId product
     * @param accountId stock account, if any
     * @param name product name
     * @param ean EAN
     * @param unit unit
     * @param requested requested quantity
     * @param prepared prepared quantity
     * @param shipped shipped quantity
     * @param remaining quantity still to prepare
     * @param location pick location
     * @param building building
     * @param totes totes holding the prepared quantity, with their buffer
     */
    public record OrderLine(int lineNumber, Long productId, Long accountId, String name, String ean, String unit,
                            java.math.BigDecimal requested, java.math.BigDecimal prepared, java.math.BigDecimal shipped,
                            java.math.BigDecimal remaining, String location, BuildingRef building, List<String> totes) {}

    /**
     * Detail of a customer order loaded on demand, in the store scope.
     *
     * @param id order identifier
     * @param code order code
     * @param customer customer
     * @param status order status
     * @param deadline deadline
     * @param stage stage code
     * @param stageLabel stage label
     * @param lines line progress
     * @param chunks chunks in the shape of the fulfillment chunk editor
     * @param preparations handling orders preparing it
     */
    public record OrderDetail(Long id, String code, String customer, String status, LocalDateTime deadline, String stage, String stageLabel,
                              List<OrderLine> lines, List<Map<String, Object>> chunks, List<OrderRef> preparations) {}
}
