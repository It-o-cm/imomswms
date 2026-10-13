package com.intermarche.backoffice.engine.handling;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.wms.HandlingOrderSequence;
import com.intermarche.backoffice.domain.wms.handling.ZoneCollectingOrder;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Factory for creating the Collecting Order Manager.
 * <p>
 * This builder is responsible for generating collecting handlers by grouping orphan lots
 * based on their physical location (Building, Area, Aisle) and capacity constraints (Volume and Weight).
 * <p>
 * The logic enforces the following rules:
 * <ul>
 *   <li>Filters lots matching a specific building, area, and optionally an aisle.</li>
 *   <li>Aggregates volume and weight by traversing {@link LotLine}s and
 *       {@link Product}s.</li>
 *   <li><b>Constraint:</b> Capacity constraints must be defined for <b>EITHER</b> Volume <b>OR</b> Weight, but never both simultaneously.</li>
 *   <li>Creates a {@link ZoneCollectingOrder} only if the minimum threshold of the active metric is met.</li>
 *   <li>Splits a collection into a new order if adding a lot would exceed the maximum capacity of the active metric.</li>
 *   <li>Groups orders by Location (Building/Area/Aisle) and Delivery Time Slot. (Customer is excluded to allow batch picking).</li>
 * </ul>
 */
@ApplicationScoped
public class ZoneCollectingOrderManagerFactory extends BaseOrderHandlerBuilderFactory {

    private static final Logger LOGGER = Logger.getLogger(ZoneCollectingOrderManagerFactory.class);

    // --------------------------------------------------
    // JSON Schema for Configuration Validation
    // --------------------------------------------------
    /**
     * JSON Schema definition for the ZoneCollectingOrderManager configuration.
     * <p>
     * Defines constraints for building codes, area/aisle filters, and min/max volume/weight thresholds.
     */
    private static final String COLLECTING_MANAGER_SCHEMA = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "title": "ZoneCollectingOrderManagerConfiguration",
              "type": "object",
              "description": "Configuration for the Collecting Order Manager.",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "Optional: Name to identify or document the handler manager. "
                },
                "buildingCode": {
                  "type": "string",
                  "description": "Mandatory: The code of the building."
                },
                "area": {
                  "type": "string",
                  "description": "The area to collect from."
                },
                "aisle": {
                  "type": "string",
                  "description": "Optional: The specific aisle. If omitted, matches all aisles in the area."
                },
                "volumeMin": {
                  "type": "number",
                  "minimum": 0
                },
                "volumeMax": {
                  "type": "number",
                  "minimum": 0
                },
                "weightMin": {
                  "type": "number",
                  "minimum": 0
                },
                "weightMax": {
                  "type": "number",
                  "minimum": 0
                },
                "validityDelayMinutes": {
                  "type": "integer",
                  "minimum": 0
                },
                "groupingWindowMinutes": {
                  "type": "integer",
                  "minimum": 1
                }
              },
              "required": ["buildingCode"]
            }
            """;

    /**
     * The compiled JSON Schema validator instance.
     */
    private final JsonSchema schema;

    /**
     * Default constructor.
     * Initializes the JSON Schema validator using Draft 7 specification.
     */
    public ZoneCollectingOrderManagerFactory() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        this.schema = factory.getSchema(COLLECTING_MANAGER_SCHEMA);
    }

    /**
     * Returns the unique name of this factory.
     *
     * @return The factory name "ZoneCollectingOrderManager".
     */
    @Override
    public String getName() {
        return "ZoneCollecting";
    }

    /**
     * Builds a new instance of the {@link ZoneCollectingOrderManager} based on the provided JSON specification.
     *
     * @param spec The JSON specification containing configuration parameters (filters and thresholds).
     * @return A configured {@link ZoneCollectingOrderManager} instance.
     */
    @Override
    public BaseOrderHandlerBuilder build(JsonNode spec) {
        validateConfiguration(spec);
        ZoneCollectingOrderManagerConfig config = parseConfiguration(spec);
        return new ZoneCollectingOrderManager(config);
    }

    /**
     * Validates the provided configuration against the defined JSON schema.
     *
     * @param spec The JSON node to validate.
     * @throws IllegalArgumentException if the validation fails or required fields are missing.
     */
    private void validateConfiguration(JsonNode spec) {
        Set<ValidationMessage> errors = schema.validate(spec);
        if (!errors.isEmpty()) {
            String errorMsg = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining(", "));
            LOGGER.errorf("Invalid ZoneCollectingOrderManager configuration: %s", errorMsg);
            throw new IllegalArgumentException("Invalid ZoneCollectingOrderManager configuration: " + errorMsg);
        }
    }

    /**
     * Parses the JSON specification into a strongly typed configuration object.
     * <p>
     * Enforces the business rule: Volume constraints XOR Weight constraints.
     *
     * @param spec The validated JSON node.
     * @return The parsed {@link ZoneCollectingOrderManagerConfig}.
     * @throws IllegalArgumentException if both volume and weight constraints are defined, or if none are defined.
     */
    private ZoneCollectingOrderManagerConfig parseConfiguration(JsonNode spec) {
        ZoneCollectingOrderManagerConfig config = new ZoneCollectingOrderManagerConfig();
        config.buildingCode = spec.path("buildingCode").asText();
        config.area = spec.has("area") ? spec.path("area").asText() : null;
        config.aisle = spec.has("aisle") ? spec.path("aisle").asText() : null;

        config.volumeMin = spec.has("volumeMin") ? spec.get("volumeMin").asDouble() : 0;
        config.volumeMax = spec.has("volumeMax") ? spec.get("volumeMax").asDouble() : 0;
        config.weightMin = spec.has("weightMin") ? spec.get("weightMin").asDouble() : 0;
        config.weightMax = spec.has("weightMax") ? spec.get("weightMax").asDouble() : 0;

        config.validityDelayMinutes = spec.has("validityDelayMinutes") ? spec.get("validityDelayMinutes").asInt() : 0;
        config.groupingWindowMinutes = spec.has("groupingWindowMinutes") ? spec.get("groupingWindowMinutes").asInt() : 60;

        // Business Rule Validation: Exclusive OR for Volume and Weight
        boolean hasVolumeConfig = (config.volumeMin > 0 || config.volumeMax > 0);
        boolean hasWeightConfig = (config.weightMin > 0 || config.weightMax > 0);

        if (hasVolumeConfig && hasWeightConfig) {
            throw new IllegalArgumentException("Configuration Error: You must define EITHER Volume OR Weight constraints, not both.");
        }
        if (!hasVolumeConfig && !hasWeightConfig) {
            throw new IllegalArgumentException("Configuration Error: You must define at least Volume OR Weight constraints.");
        }

        return config;
    }

    /**
     * Data holder for the Collecting Order Manager configuration.
     * <p>
     * Contains filtering criteria (building, area, aisle) and capacity thresholds (min/max volume/weight).
     */
    private static class ZoneCollectingOrderManagerConfig {
        /**
         * The code of the building to process.
         */
        String buildingCode;
        /**
         * The specific area within the building (optional).
         */
        String area;
        /**
         * The specific aisle within the area (optional).
         */
        String aisle;
        /**
         * Minimum volume required to create an order.
         */
        double volumeMin;
        /**
         * Maximum volume allowed per order.
         */
        double volumeMax;
        /**
         * Minimum weight required to create an order.
         */
        double weightMin;
        /**
         * Maximum weight allowed per order.
         */
        double weightMax;
        /**
         * Time window in minutes to consider a lot as valid.
         */
        int validityDelayMinutes;
        /**
         * Time window in minutes for grouping orders into a single slot.
         */
        int groupingWindowMinutes;
    }

    /**
     * The worker implementation that processes a Store to create or update Collecting Orders.
     * <p>
     * This manager implements a bin-packing algorithm strategy where lots are grouped
     * into orders based on location and capacity limits (Volume OR Weight).
     * <p>
     * Grouping is based on Location (Building/Area/Aisle) and Time Slot only, ignoring Customer ID.
     */
    public static class ZoneCollectingOrderManager extends BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder {

        private static final Logger LOGGER = Logger.getLogger(ZoneCollectingOrderManager.class);

        /**
         * The configuration parameters for this manager instance.
         */
        final ZoneCollectingOrderManagerConfig config;

        /**
         * Constructor initializing the manager with its specific configuration.
         *
         * @param config The configuration parameters.
         */
        public ZoneCollectingOrderManager(ZoneCollectingOrderManagerConfig config) {
            this.config = config;
        }

        /**
         * Main processing method for the store.
         * <p>
         * 1. Retrieves candidate lots matching the location filters.
         * 2. Groups them by (Store, Building, Area, Aisle, TimeSlot). Customer is excluded.
         * 3. Processes each group to generate {@link ZoneCollectingOrder}s respecting Min/Max capacity.
         *
         * @param store The store to process.
         * @throws Exception if processing fails.
         */
        @Override
        public void process(Store store) throws Exception {
            LOGGER.infof("Starting Collecting Order Manager for Store %s (Mode: %s)",
                    store.code, isVolumeMode() ? "VOLUME" : "WEIGHT");

            // 1. Find and Filter Lots
            List<Lot> candidateLots = findCandidateLots(store);

            if (candidateLots.isEmpty()) {
                LOGGER.info("No candidate lots found for this configuration.");
                return;
            }

            // 2. Group Lots by (Store, Building, Area, Aisle, TimeSlot)
            // Note: Customer is intentionally excluded from grouping to allow batch picking.
            Map<GroupKey, List<Lot>> groups = candidateLots.stream()
                    .collect(Collectors.groupingBy(lot -> {
                        LocalDateTime slotStart = (lot.chunk.deliveryDate != null)
                                ? lot.chunk.deliveryDate.withMinute(0).withSecond(0).withNano(0)
                                : null;

                        String lotArea = lot.getArea();
                        String lotAisle = lot.getAisle();

                        // Grouping by Location and Time only (Customer ID removed)
                        return new GroupKey(
                                lot.store.id,
                                lot.chunk.building,
                                lotArea,
                                lotAisle,
                                slotStart
                        );
                    }));

            // 3. Process each group with Capacity Logic
            for (Map.Entry<GroupKey, List<Lot>> entry : groups.entrySet()) {
                processGroup(entry.getKey(), entry.getValue());
            }
        }

        /**
         * Processes a list of lots belonging to the same group key.
         * <p>
         * This method orchestrates the bin-packing:
         * - It sorts the lots.
         * - Iterates through them, assigning them to orders.
         * - Checks capacity constraints (Min/Max) to decide when to close an order.
         *
         * @param key  The grouping key (identifies the context: location, time).
         * @param lots The list of lots to process.
         */
        private void processGroup(GroupKey key, List<Lot> lots) {
            LOGGER.infof("Processing Group: %s with %d lots", key, lots.size());
            // 1. Sort lots to ensure deterministic processing order
            sortLots(lots);
            ZoneCollectingOrder currentOrder = null;
            OrderMetrics currentMetrics = new OrderMetrics();
            // 2. Iterate and assign
            for (Lot lot : lots) {
                OrderMetrics lotMetrics = calculateLotMetrics(lot);
                if (currentOrder == null) {
                    // Start the first order
                    currentOrder = createNewOrder(key);
                    currentMetrics = lotMetrics;
                    currentOrder.addLot(lot);
                    LOGGER.debugf("Started new Order %s with Lot %s", currentOrder.id, lot.id);
                } else {
                    // Check if adding this lot would exceed capacity
                    if (isCapacityExceeded(currentMetrics, lotMetrics)) {
                        // Finalize current order (it is full)
                        finalizeOrder(currentOrder, currentMetrics);

                        // Start a new order with the current lot (the one causing overflow)
                        currentOrder = createNewOrder(key);
                        currentMetrics = lotMetrics;
                        currentOrder.addLot(lot);
                        LOGGER.debugf("Overflow: Started new Order %s with Lot %s", currentOrder.id, lot.id);
                    } else {
                        // Fits in current order
                        addLotToOrder(currentOrder, currentMetrics, lot, lotMetrics);
                    }
                }
            }
            setStatusToLot(lots, Lot.LotStatus.PLANNED_TO_BE_COLLECTED);
            // 3. Finalize the last order if it exists
            if (currentOrder != null) {
                finalizeOrder(currentOrder, currentMetrics);
            }
        }

        /**
         * Sorts the list of lots by ID to ensure consistent processing.
         *
         * @param lots The list of lots to sort.
         */
        private void sortLots(List<Lot> lots) {
            lots.sort(Comparator.comparing(l -> l.id));
        }

        /**
         * Determines if adding a lot's metrics to the current order would exceed the Maximum capacity.
         * <p>
         * This check relies on the active mode (Volume OR Weight).
         *
         * @param currentMetrics The metrics of the current order.
         * @param addition       The metrics of the lot to add.
         * @return true if the max capacity is exceeded.
         */
        private boolean isCapacityExceeded(OrderMetrics currentMetrics, OrderMetrics addition) {
            if (isVolumeMode()) {
                return config.volumeMax > 0 && (currentMetrics.volume + addition.volume) > config.volumeMax;
            } else {
                return config.weightMax > 0 && (currentMetrics.weight + addition.weight) > config.weightMax;
            }
        }

        /**
         * Adds a lot to an order and updates the running metrics.
         *
         * @param order        The order to update.
         * @param orderMetrics The metrics of the order to update.
         * @param lot          The lot to add.
         * @param lotMetrics   The metrics of the lot to add.
         */
        private void addLotToOrder(ZoneCollectingOrder order, OrderMetrics orderMetrics, Lot lot, OrderMetrics lotMetrics) {
            order.addLot(lot);
            orderMetrics.add(lotMetrics);
        }

        /**
         * Finalizes an order by checking Min requirements and persisting it.
         * <p>
         * If the Min requirements are not met, the order is discarded (not persisted).
         *
         * @param order   The order to finalize.
         * @param metrics The accumulated metrics of the order.
         */
        private void finalizeOrder(ZoneCollectingOrder order, OrderMetrics metrics) {
            if (meetsMinRequirements(metrics)) {
                persistOrder(order);
            } else {
                LOGGER.warnf("Discarding Order %s as it did not meet Min requirements.", order.id);
            }
        }

        /**
         * Determines if the manager is operating in Volume mode or Weight mode.
         *
         * @return true if volume constraints are active, false if weight constraints are active.
         */
        private boolean isVolumeMode() {
            return (config.volumeMax > 0 || config.volumeMin > 0);
        }

        /**
         * Retrieves lots that are eligible for Collecting based on Config and Orphan status.
         * <p>
         * Criteria:
         * <ul>
         *   <li>Lot status is PENDING.</li>
         *   <li>Lot belongs to the store.</li>
         *   <li>Lot delivery date is within the validity delay.</li>
         *   <li>Lot location matches Config (Building, Area, Aisle).</li>
         *   <li>Lot is not already in an OPEN or PROCESSING ZoneCollectingOrder.</li>
         * </ul>
         * <p>
         * <b>Performance:</b> Uses JPQL `JOIN FETCH` to eagerly load lines, fulfillment lines, and products
         * to allow immediate metric calculation.
         * <p>
         * <b>Correction:</b> Uses `l.chunk.building` to filter by building as `Location` does not reference `Building`.
         *
         * @param store The store to search in.
         * @return A list of candidate lots with initialized relationships.
         */
        private List<Lot> findCandidateLots(Store store) {
            LocalDateTime cutoffDate = DateTimeProvider.now().plusMinutes(config.validityDelayMinutes);
            String jpql = """
                SELECT DISTINCT l
                FROM Lot l
                JOIN l.chunk c
                JOIN FETCH l.lines ll
                JOIN FETCH ll.fulfillmentLine fl
                JOIN FETCH TREAT(fl.location AS ShelfLocation) loc
                WHERE l.status = LotStatus.PENDING
                  AND l.store = :store
                  AND l.chunk.deliveryDate IS NOT NULL
                  AND l.chunk.deliveryDate <= :cutoffDate
                  AND c.building.buildingCode = :bCode
                  AND fl.location IS NOT NULL
                  AND (:area IS NULL OR loc.area = :area)
                  AND (:aisle IS NULL OR loc.aisle = :aisle)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM l.lines sll
                      WHERE TYPE(sll.location) = ShelfLocation
                        AND (
                            (:area IS NOT NULL AND TREAT(sll.location AS ShelfLocation).area <> :area)
                            OR
                            (:aisle IS NOT NULL AND TREAT(sll.location AS ShelfLocation).aisle <> :aisle)
                        )
                  )
                ORDER BY l.id
                """;
            EntityManager em = Panache.getEntityManager();
            return em.createQuery(jpql, Lot.class)
                    .setParameter("store", store)
                    .setParameter("cutoffDate", DateTimeProvider.now())
                    .setParameter("bCode", config.buildingCode)
                    .setParameter("area", config.area)
                    .setParameter("aisle", config.aisle)
                    .getResultList();
        }

        /**
         * Checks if the accumulated metrics meet the minimum requirements defined in the configuration.
         * <p>
         * The check relies on the active mode determined by {@link #isVolumeMode()}.
         *
         * @param metrics The accumulated metrics of the order.
         * @return true if the order satisfies the minimum criteria.
         */
        private boolean meetsMinRequirements(OrderMetrics metrics) {
            if (isVolumeMode()) {
                return (config.volumeMin <= 0) || (metrics.volume >= config.volumeMin);
            } else {
                return (config.weightMin <= 0) || (metrics.weight >= config.weightMin);
            }
        }

        /**
         * Creates a new {@link ZoneCollectingOrder} instance based on the grouping key and configuration.
         * <p>
         * Sets the status to OPEN and calculates the time window.
         *
         * @param key The grouping key containing location and time context.
         * @return A new, un-persisted ZoneCollectingOrder.
         */
        private ZoneCollectingOrder createNewOrder(GroupKey key) {
            ZoneCollectingOrder newOrder = new ZoneCollectingOrder();
            newOrder.store = Store.findById(key.storeId);
            newOrder.building = key.building; // Comes from lot.chunk.building
            newOrder.area = key.area;
            newOrder.aisle = key.aisle;
            newOrder.requestedStart = key.slotStart;
            newOrder.code = HandlingOrderSequence.getNextCode(newOrder.store, newOrder.getPrefix());
            if (key.slotStart != null) {
                newOrder.requestedEnd = key.slotStart.plusMinutes(config.groupingWindowMinutes);
            }
            newOrder.status = BaseHandlingOrder.HandlingOrderStatus.OPEN;
            return newOrder;
        }

        /**
         * Persists the ZoneCollectingOrder to the database.
         *
         * @param order The order to persist.
         */
        private void persistOrder(ZoneCollectingOrder order) {
            order.persist();
            LOGGER.infof("Persisted ZoneCollectingOrder ID %s with %d lots (Vol: %.2f, Wgt: %.2f)",
                    order.id, order.lots.size(), calculateOrderVolume(order), calculateOrderWeight(order));
        }

        /**
         * Calculates Volume and Weight for a specific Lot based on its LotLines.
         * <p>
         * Assumes LotLines and Products are already initialized (via JOIN FETCH in {@link #findCandidateLots(Store)}).
         *
         * @param lot The lot to calculate metrics for.
         * @return An {@link OrderMetrics} object containing volume and weight.
         */
        private OrderMetrics calculateLotMetrics(Lot lot) {
            OrderMetrics metrics = new OrderMetrics();
            if (lot.lines != null) {
                for (LotLine line : lot.lines) {
                    if (line.fulfillmentLine != null && line.fulfillmentLine.product != null) {
                        BigDecimal qty = line.quantity;
                        double refVol = (line.fulfillmentLine.product.referenceVolume != null)
                                ? line.fulfillmentLine.product.referenceVolume.doubleValue() : 0.0;
                        double refWgt = (line.fulfillmentLine.product.referenceWeight != null)
                                ? line.fulfillmentLine.product.referenceWeight.doubleValue() : 0.0;

                        metrics.volume += qty.doubleValue() * refVol;
                        metrics.weight += qty.doubleValue() * refWgt;
                    }
                }
            }
            return metrics;
        }

        /**
         * Helper to recalculate volume for an order (used for logging).
         *
         * @param order The collecting order.
         * @return The total volume.
         */
        private double calculateOrderVolume(ZoneCollectingOrder order) {
            double vol = 0;
            for (Lot l : order.lots) vol += calculateLotMetrics(l).volume;
            return vol;
        }

        /**
         * Helper to recalculate weight for an order (used for logging).
         *
         * @param order The collecting order.
         * @return The total weight.
         */
        private double calculateOrderWeight(ZoneCollectingOrder order) {
            double wgt = 0;
            for (Lot l : order.lots) wgt += calculateLotMetrics(l).weight;
            return wgt;
        }

        /**
         * Value object used as a key for grouping lots together.
         * <p>
         * Two lots should be in the same ZoneCollectingOrder processing group if they share:
         * Store, Building, Area, Aisle, and Time Slot.
         * (Customer is ignored to allow mixed orders/batch picking).
         */
        static class GroupKey {
            final Long storeId;
            final Building building;
            final String area;
            final String aisle;
            final LocalDateTime slotStart;

            /**
             * Constructs a GroupKey.
             *
             * @param storeId   The ID of the store.
             * @param building  The building entity.
             * @param area      The physical area.
             * @param aisle     The physical aisle.
             * @param slotStart The start time of the delivery slot.
             */
            public GroupKey(Long storeId, Building building, String area, String aisle, LocalDateTime slotStart) {
                this.storeId = storeId;
                this.building = building;
                this.area = area;
                this.aisle = aisle;
                this.slotStart = slotStart;
            }

            /**
             * Compares this object to the specified object for equality.
             * Determines whether two GroupKey objects are equivalent based on their storeId, building, area, aisle, and slotStart properties.
             *
             * @param o the reference object with which to compare
             * @return true if this object is equal to the specified object; false otherwise
             */
            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                GroupKey groupKey = (GroupKey) o;
                return Objects.equals(storeId, groupKey.storeId) &&
                        Objects.equals(building, groupKey.building) &&
                        Objects.equals(area, groupKey.area) &&
                        Objects.equals(aisle, groupKey.aisle) &&
                        Objects.equals(slotStart, groupKey.slotStart);
            }

            /**
             * Computes the hash code for this GroupKey instance.
             * The hash code is computed based on the storeId, building, area, aisle, and slotStart properties.
             *
             * @return an integer hash code value for this GroupKey
             */
            @Override
            public int hashCode() {
                return Objects.hash(storeId, building, area, aisle, slotStart);
            }

            /**
             * Returns a string representation of the GroupKey object.
             * The string includes the store ID, building code (if available), area, and aisle properties.
             *
             * @return a string representation of this GroupKey instance
             */
            @Override
            public String toString() {
                return "GroupKey{s=" + storeId + ", b=" + (building != null ? building.buildingCode : "null") + ", a=" + area + ", as=" + aisle + "}";
            }
        }

        /**
         * Helper class to hold running totals for Volume and Weight.
         */
        static class OrderMetrics {
            double volume = 0.0;
            double weight = 0.0;

            /**
             * Adds the metrics from another object to this one.
             *
             * @param other The other metrics to add.
             */
            void add(OrderMetrics other) {
                this.volume += other.volume;
                this.weight += other.weight;
            }
        }
    }
}