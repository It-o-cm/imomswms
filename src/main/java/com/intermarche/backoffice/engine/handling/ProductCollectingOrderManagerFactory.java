package com.intermarche.backoffice.engine.handling;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.wms.HandlingOrderSequence;
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
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
 * Factory for creating the Product Collecting Order Manager.
 * <p>
 * This builder is responsible for generating collecting handlers by grouping orphan lots
 * based on a specific list of products and multi-dimensional capacity constraints.
 * <p>
 * The logic enforces the following rules:
 * <ul>
 *   <li>Filters lots matching a specific building and a specific list of Product EANs.</li>
 *   <li>Aggregates Quantity, Volume, and Weight by traversing {@link LotLine}s and
 *       {@link Product}s.</li>
 *   <li><b>Constraints:</b> Supports defining Min/Max for Quantity, Weight, and Volume simultaneously or independently.
 *   <li><b>Bin Packing:</b> An order is considered "Full" if <b>any</b> defined Max constraint is exceeded.
 *   <li><b>Order Creation:</b> An order is created only if <b>all</b> defined Min constraints are met.
 *   <li>Groups orders by Customer, Building, and Delivery Time Slot.</li>
 * </ul>
 */
@ApplicationScoped
public class ProductCollectingOrderManagerFactory extends BaseOrderHandlerBuilderFactory {

    private static final Logger LOGGER = Logger.getLogger(ProductCollectingOrderManagerFactory.class);

    // --------------------------------------------------
    // JSON Schema for Configuration Validation
    // --------------------------------------------------
    /**
     * JSON Schema definition for the ProductCollectingOrderManager configuration.
     * <p>
     * Defines constraints for building codes, product list, and min/max thresholds for Qty, Weight, and Volume.
     */
    public static final String COLLECTING_MANAGER_SCHEMA = """
        {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "title": "ProductCollectingOrderManagerConfiguration",
          "type": "object",
          "description": "Configuration for the Product Collecting Order Manager.",
          "properties": {
            "name": {
              "type": "string",
              "description": "Optional: Name to identify or document the handler manager. "
            },
            "buildingCode": {
              "type": "string",
              "description": "Mandatory: The code of the building."
            },
            "eans": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Mandatory: The list of EAN codes to collect."
            },
            "quantityMin": {
              "type": "integer",
              "minimum": 1
            },
            "quantityMax": {
              "type": "integer",
              "minimum": 1
            },
            "weightMin": {
              "type": "number",
              "minimum": 0
            },
            "weightMax": {
              "type": "number",
              "minimum": 0
            },
            "volumeMin": {
              "type": "number",
              "minimum": 0
            },
            "volumeMax": {
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
          "required": ["buildingCode", "eans"]
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
    public ProductCollectingOrderManagerFactory() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        this.schema = factory.getSchema(COLLECTING_MANAGER_SCHEMA);
    }

    /**
     * Returns the unique name of this factory.
     *
     * @return The factory name "ProductCollectingOrderManager".
     */
    @Override
    public String getName() {
        return "ProductCollecting";
    }

    /**
     * Builds a new instance of the {@link ProductCollectingOrderManager} based on the provided JSON specification.
     *
     * @param spec The JSON specification containing configuration parameters.
     * @return A configured {@link ProductCollectingOrderManager} instance.
     */
    @Override
    public BaseOrderHandlerBuilder build(JsonNode spec) {
        validateConfiguration(spec);
        ProductCollectingOrderManagerConfig config = parseConfiguration(spec);
        return new ProductCollectingOrderManager(config);
    }

    /**
     * Validates the provided configuration against the defined JSON schema.
     *
     * @param spec The JSON node to validate.
     * @throws IllegalArgumentException if the validation fails.
     */
    private void validateConfiguration(JsonNode spec) {
        Set<ValidationMessage> errors = schema.validate(spec);
        if (!errors.isEmpty()) {
            String errorMsg = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining(", "));
            LOGGER.errorf("Invalid ProductCollectingOrderManager configuration: %s", errorMsg);
            throw new IllegalArgumentException("Invalid ProductCollectingOrderManager configuration: " + errorMsg);
        }
    }

    /**
     * Parses the JSON specification into a strongly typed configuration object.
     * <p>
     * Validates that if a Min value is set, a corresponding Max value must also be set.
     *
     * @param spec The validated JSON node.
     * @return The parsed {@link ProductCollectingOrderManagerConfig}.
     * @throws IllegalArgumentException if validation fails.
     */
    private ProductCollectingOrderManagerConfig parseConfiguration(JsonNode spec) {
        String buildingCode = spec.path("buildingCode").asText();

        Set<String> eans = new HashSet<>();
        if (spec.has("eans") && spec.get("eans").isArray()) {
            spec.get("eans").forEach(node -> eans.add(node.asText()));
        }

        // Parse with defaults (0 indicates constraint is inactive)
        int qtyMin = spec.has("quantityMin") ? spec.get("quantityMin").asInt() : 0;
        int qtyMax = spec.has("quantityMax") ? spec.get("quantityMax").asInt() : 0;
        double wgtMin = spec.has("weightMin") ? spec.get("weightMin").asDouble() : 0;
        double wgtMax = spec.has("weightMax") ? spec.get("weightMax").asDouble() : 0;
        double volMin = spec.has("volumeMin") ? spec.get("volumeMin").asDouble() : 0;
        double volMax = spec.has("volumeMax") ? spec.get("volumeMax").asDouble() : 0;

        int validityDelay = spec.has("validityDelayMinutes") ? spec.get("validityDelayMinutes").asInt() : 0;
        int groupingWindow = spec.has("groupingWindowMinutes") ? spec.get("groupingWindowMinutes").asInt() : 60;

        // Validation Logic: If Min is defined (>0), Max must be >= Min.
        validateMinMax("Quantity", qtyMin, qtyMax);
        validateMinMax("Weight", wgtMin, wgtMax);
        validateMinMax("Volume", volMin, volMax);

        return new ProductCollectingOrderManagerConfig(
                buildingCode, eans,
                qtyMin, qtyMax,
                wgtMin, wgtMax,
                volMin, volMax,
                validityDelay, groupingWindow);
    }

    /**
     * Validates that a maximum is not lower than a minimum.
     *
     * @param dimension The name of the dimension (e.g., "Quantity").
     * @param minVal   The minimum value.
     * @param maxVal   The maximum value.
     * @throws IllegalArgumentException if max < min.
     */
    private void validateMinMax(String dimension, double minVal, double maxVal) {
        if (minVal > 0 && maxVal > 0 && minVal > maxVal) {
            throw new IllegalArgumentException(String.format("Configuration Error: %s Max (%s) must be greater than or equal to %s Min (%s).", dimension, maxVal, dimension, minVal));
        }
    }

    /**
     * Data holder for the Product Collecting Order Manager configuration.
     * <p>
     * Contains filtering criteria (building, product list) and capacity thresholds (min/max for Qty, Weight, Volume).
     */
    private record ProductCollectingOrderManagerConfig (
        String buildingCode, Set<String> eans,
        double quantityMin, double quantityMax,
        double weightMin, double weightMax,
        double volumeMin, double volumeMax,
        int validityDelayMinutes, int groupingWindowMinutes) {
    }

    /**
     * The worker implementation that processes a Store to create or update Product Collecting Orders.
     * <p>
     * This manager implements a bin-packing algorithm strategy where lots are grouped
     * into orders based on product identity and capacity limits (Qty, Weight, Volume).
     */
    public static class ProductCollectingOrderManager extends BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder {

        private static final Logger LOGGER = Logger.getLogger(ProductCollectingOrderManager.class);

        /** The configuration parameters for this manager instance. */
        final ProductCollectingOrderManagerConfig config;

        /**
         * Constructor initializing the manager with its specific configuration.
         *
         * @param config The configuration parameters.
         */
        public ProductCollectingOrderManager(ProductCollectingOrderManagerConfig config) {
            this.config = config;
        }

        /**
         * Main processing method for the store.
         * <p>
         * 1. Retrieves candidate lots matching the product list in the specific building.
         * 2. Groups them by (Customer, Building, Time Slot).
         * 3. Processes each group to generate {@link ProductCollectingOrder}s respecting Multi-Dimension Min/Max capacity.
         *
         * @param store The store to process.
         * @throws Exception if processing fails.
         */
        @Override
        public void process(Store store) throws Exception {
            LOGGER.infof("Starting Product Collecting Order Manager for Store %s", store.code);
            // 1. Find and Filter Lots
            List<Lot> candidateLots = findCandidateLots(store);
            if (candidateLots.isEmpty()) {
                LOGGER.info("No candidate lots found for this configuration.");
                return;
            }
            // 2. Group Lots by (Customer, Building, TimeSlot)
            Map<GroupKey, List<Lot>> groups = candidateLots.stream()
                    .collect(Collectors.groupingBy(lot -> {
                        LocalDateTime slotStart = (lot.chunk.deliveryDate != null)
                                ? lot.chunk.deliveryDate.withMinute(0).withSecond(0).withNano(0).plusHours(1) // Grouping by Hour (e.g., all orders for 10:00 to 11:00).
                                : null;
                        return new GroupKey(lot.customer.id, lot.store.id, lot.chunk.building, slotStart);
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
         * - Checks multi-dimensional capacity constraints (Qty/Weight/Vol) to decide when to close an order.
         *
         * @param key   The grouping key (identifies the context: customer, building, time).
         * @param lots  The list of lots to process.
         */
        private void processGroup(GroupKey key, List<Lot> lots) {
            LOGGER.infof("Processing Group: %s with %d lots", key, lots.size());
            // 1. Sort lots to ensure deterministic processing order
            sortLots(lots);
            ProductCollectingOrder currentOrder = null;
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
            // 3. Finalize the last order (groups are never empty, so an order was always created)
            finalizeOrder(currentOrder, currentMetrics);
        }

        /**
         * Sorts the list of lots by ID to ensure consistent processing order.
         *
         * @param lots The list of lots to sort.
         */
        private void sortLots(List<Lot> lots) {
            lots.sort(Comparator.comparing(l -> l.id));
        }

        /**
         * Determines if adding a lot's metrics to the current order would exceed Maximum capacity.
         * <p>
         * Checks all active dimensions: If ANY limit is exceeded, returns true.
         *
         * @param currentMetrics The metrics of the current order.
         * @param addition      The metrics of the lot to add.
         * @return true if any Max capacity is exceeded.
         */
        private boolean isCapacityExceeded(OrderMetrics currentMetrics, OrderMetrics addition) {
            // If Quantity is active and exceeded
            boolean qtyExceeded = (config.quantityMax > 0 && (currentMetrics.quantity + addition.quantity) > config.quantityMax);
            // If Weight is active and exceeded
            boolean wgtExceeded = (config.weightMax > 0 && (currentMetrics.weight + addition.weight) > config.weightMax);
            // If Volume is active and exceeded
            boolean volExceeded = (config.volumeMax > 0 && (currentMetrics.volume + addition.volume) > config.volumeMax);
            // If ANY constraint is exceeded, the order is considered full
            return qtyExceeded || wgtExceeded || volExceeded;
        }

        /**
         * Adds a lot to an order and updates the running metrics.
         *
         * @param order         The order to update.
         * @param orderMetrics  The metrics of the order to update.
         * @param lot           The lot to add.
         * @param lotMetrics    The metrics of the lot to add.
         */
        private void addLotToOrder(ProductCollectingOrder order, OrderMetrics orderMetrics, Lot lot, OrderMetrics lotMetrics) {
            order.addLot(lot);
            orderMetrics.add(lotMetrics);
        }

        /**
         * Finalizes an order by checking Min requirements and persisting it.
         * <p>
         * If the Min requirements are not met, the order is discarded (not persisted).
         *
         * @param order  The order to finalize.
         * @param metrics The accumulated metrics of the order.
         */
        private void finalizeOrder(ProductCollectingOrder order, OrderMetrics metrics) {
            if (meetsMinRequirements(metrics)) {
                persistOrder(order);
            } else {
                LOGGER.warnf("Discarding Order %s as it did not meet Min requirements.", order.id);
            }
        }

        /**
         * Checks if the accumulated metrics meet the minimum requirements defined in the configuration.
         * <p>
         * A requirement is considered "active" if its Max is > 0.
         *   For active constraints, the current total must be >= the configured minimum.
         *
         * @param metrics The accumulated metrics of the order.
         * @return true if the order satisfies the minimum criteria.
         */
        private boolean meetsMinRequirements(OrderMetrics metrics) {
            // All active constraints must be met. Inactive constraints (min <= 0) are ignored.
            return (!isQtyActive() || metrics.quantity >= config.quantityMin) &&
                    (!isWgtActive() || metrics.weight >= config.weightMin) &&
                    (!isVolActive() || metrics.volume >= config.volumeMin);
        }

        // Helper to determine which constraints are "Active" (Min > 0 or Max > 0)
        private boolean isQtyActive() { return (config.quantityMin > 0 || config.quantityMax > 0); }
        private boolean isWgtActive() { return (config.weightMin > 0 || config.weightMax > 0); }
        private boolean isVolActive()  { return (config.volumeMin > 0 || config.volumeMax > 0); }

        /**
         * Retrieves lots that are eligible for Product Collecting based on Config and Orphan status.
         * <p>
         * Criteria:
         * <ul>
         *   <li>Lot status is PENDING.</li>
         *   <li>Lot belongs to the store.</li>
         *   <li>Lot delivery date is within the validity delay.</li>
         *   <li>Lot belongs to the target Building.</li>
         *   <li><b>Strict Constraint:</b> The Lot must contain <b>ONLY</b> products present in the configured EAN list.
         *   <li>Lot is not already in an OPEN or PROCESSING ProductCollectingOrder.</li>
         * </ul>
         * <p>
         * <b>Performance:</b> Uses JPQL `JOIN FETCH` to eagerly load lines, fulfillment lines, and products.
         * <p>
         * <b>Filtering Logic:</b>
         * Uses a double EXISTS check:
         * <ol>
         *   <li>MUST have at least one product from the EAN list.</li>
         *   <li>MUST NOT have any product outside the EAN list.</li>
         * </ol>
         *
         * @param store The store to search in.
         * @return A list of candidate lots with initialized relationships.
         */
        private List<Lot> findCandidateLots(Store store) {
            LocalDateTime cutoffDate = DateTimeProvider.now().plusMinutes(config.validityDelayMinutes);
            String jpql = """
                SELECT DISTINCT l
                FROM Lot l
                JOIN FETCH l.lines ll
                JOIN FETCH ll.fulfillmentLine fl
                JOIN FETCH fl.product p
                WHERE l.status = LotStatus.PENDING
                  AND l.store = :store
                  AND l.chunk.deliveryDate IS NOT NULL
                  AND l.chunk.deliveryDate <= :cutoffDate
                  AND l.chunk.building.buildingCode = :bCode
                  AND fl.product.ean IN :eans
                  AND NOT EXISTS (
                     SELECT 1
                     FROM ProductCollectingOrder po
                     JOIN po.lots pl
                     WHERE pl.id = l.id
                        AND po.store = :store
                  )
                  AND NOT EXISTS (
                     SELECT 1
                     FROM Lot l2
                     JOIN l2.lines ll2
                     JOIN ll2.fulfillmentLine fl2
                     WHERE l2 = l
                       AND fl2.product.ean NOT IN :eans
                  )
                  ORDER BY l.id
                """;

            EntityManager em = Panache.getEntityManager();
            return em.createQuery(jpql, Lot.class)
                    .setParameter("store", store)
                    .setParameter("cutoffDate", cutoffDate)
                    .setParameter("bCode", config.buildingCode)
                    .setParameter("eans", config.eans)
                    .getResultList();
        }

        /**
         * Creates a new {@link ProductCollectingOrder} instance based on the grouping key and configuration.
         * <p>
         * Sets the status to OPEN and calculates the time window.
         * <p>
         * <b>Note:</b> Area and Aisle are not applicable for pure product grouping and are left null.
         *
         * @param key The grouping key containing location and time context.
         * @return A new, un-persisted ProductCollectingOrder.
         */
        private ProductCollectingOrder createNewOrder(GroupKey key) {
            ProductCollectingOrder newOrder = new ProductCollectingOrder();
            newOrder.store = Store.findById(key.storeId);
            newOrder.building = key.building;
            newOrder.eans = new ArrayList<>(config.eans);
            newOrder.requestedStart = key.slotStart;
            newOrder.code = HandlingOrderSequence.getNextCode(newOrder.store, newOrder.getPrefix());
            if (key.slotStart != null) {
                newOrder.requestedEnd = key.slotStart.plusMinutes(config.groupingWindowMinutes);
            }
            newOrder.status = BaseHandlingOrder.HandlingOrderStatus.OPEN;
            return newOrder;
        }

        /**
         * Persists the ProductCollectingOrder to the database.
         *
         * @param order The order to persist.
         */
        private void persistOrder(ProductCollectingOrder order) {
            order.persist();
            LOGGER.infof("Persisted ProductCollectingOrder ID %s with %d lots (Qty: %d, Wgt: %.2f, Vol: %.2f)",
                    order.id, order.lots.size(), calculateOrderQuantity(order), calculateOrderWeight(order), calculateOrderVolume(order));
        }

        /**
         * Calculates Quantity, Volume and Weight for a specific Lot based on its LotLines.
         * <p>
         * Assumes LotLines and Products are already initialized (via JOIN FETCH in {@link #findCandidateLots(Store)}).
         * <p>
         * Sums up the requested quantity (Count), Volume, and Weight.
         *
         * @param lot The lot to calculate metrics for.
         * @return An {@link OrderMetrics} object containing quantity, volume, and weight.
         */
        private OrderMetrics calculateLotMetrics(Lot lot) {
            OrderMetrics metrics = new OrderMetrics();
            if (lot.lines != null) {
                for (LotLine line : lot.lines) {
                    if (line.fulfillmentLine != null && line.fulfillmentLine.product != null) {
                        BigDecimal qty = line.fulfillmentLine.requestedQuantity;
                        // 1. Quantity: Sum of requested quantity (Count)
                        metrics.quantity += qty.intValue();
                        // 2. Weight: Qty * RefWeight
                        double refWgt = (line.fulfillmentLine.product.referenceWeight != null)
                                ? line.fulfillmentLine.product.referenceWeight.doubleValue() : 0.0;
                        metrics.weight += qty.doubleValue() * refWgt;
                        // 3. Volume: Qty * RefVolume
                        double refVol = (line.fulfillmentLine.product.referenceVolume != null)
                                ? line.fulfillmentLine.product.referenceVolume.doubleValue() : 0.0;
                        metrics.volume += qty.doubleValue() * refVol;
                    }
                }
            }
            return metrics;
        }

        /**
         * Helper to re-accumulate totals for an order.
         */
        private int calculateOrderQuantity(ProductCollectingOrder order) {
            int qty = 0;
            for(Lot l : order.lots) qty += calculateLotMetrics(l).quantity;
            return qty;
        }
        private double calculateOrderWeight(ProductCollectingOrder order) {
            double wgt = 0;
            for(Lot l : order.lots) wgt += calculateLotMetrics(l).weight;
            return wgt;
        }
        private double calculateOrderVolume(ProductCollectingOrder order) {
            double vol = 0;
            for(Lot l : order.lots) vol += calculateLotMetrics(l).volume;
            return vol;
        }

        /**
         * Value object used as a key for grouping lots together.
         * <p>
         * Two lots should be in the same ProductCollectingOrder processing group if they share:
         * Customer, Store, Building, and Time Slot.
         */
        static class GroupKey {
            final Long customerId;
            final Long storeId;
            final Building building;
            final LocalDateTime slotStart;

            /**
             * Constructs a GroupKey.
             *
             * @param customerId The ID of the customer.
             * @param storeId    The ID of the store.
             * @param building   The building entity.
             * @param slotStart  The start time of the delivery slot.
             */
            public GroupKey(Long customerId, Long storeId, Building building, LocalDateTime slotStart) {
                this.customerId = customerId;
                this.storeId = storeId;
                this.building = building;
                this.slotStart = slotStart;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                GroupKey groupKey = (GroupKey) o;
                return Objects.equals(customerId, groupKey.customerId) &&
                        Objects.equals(storeId, groupKey.storeId) &&
                        Objects.equals(building, groupKey.building) &&
                        Objects.equals(slotStart, groupKey.slotStart);
            }

            @Override
            public int hashCode() {
                return Objects.hash(customerId, storeId, building, slotStart);
            }

            @Override
            public String toString() {
                return "GroupKey{c=" + customerId + ", s=" + storeId + ", b=" + (building!=null?building.buildingCode:"null") + ", t=" + slotStart + "}";
            }
        }

        /**
         * Helper class to hold running totals for Quantity, Weight, and Volume.
         */
        static class OrderMetrics {
            int quantity = 0;
            double weight = 0.0;
            double volume = 0.0;

            /**
             * Adds the metrics from another object to this one.
             *
             * @param other The other metrics to add.
             */
            void add(OrderMetrics other) {
                this.quantity += other.quantity;
                this.weight += other.weight;
                this.volume += other.volume;
            }
        }
    }
}