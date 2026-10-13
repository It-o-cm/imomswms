package com.intermarche.backoffice.engine.handling;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.wms.HandlingOrderSequence;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Factory for creating the Grouping Handler Manager.
 * <p>
 * This builder is responsible for generating grouping handlers by grouping orphan lots
 * based on customer, building, and delivery time slots.
 * <p>
 * Unlike the Packing Order Manager, this creates one Handler per Building.
 */
@ApplicationScoped
public class GroupingOrderManagerFactory extends BaseOrderHandlerBuilderFactory {

    private static final Logger LOGGER = Logger.getLogger(GroupingOrderManagerFactory.class);

    // --------------------------------------------------
    // JSON Schema for Configuration Validation
    // --------------------------------------------------
    private static final String GROUPING_MANAGER_SCHEMA = """
        {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "title": "GroupingOrderManagerConfiguration",
          "type": "object",
          "description": "Configuration for the Grouping Handler Manager.",
          "properties": {
            "name": {
              "type": "string",
              "description": "Optional: Name to identify or document the handler manager. "
            },
            "validityDelayMinutes": {
              "type": "integer",
              "description": "The delay in minutes added to the current time to determine the cutoff date for grouping.",
              "minimum": 0
            },
            "groupingWindowMinutes": {
              "type": "integer",
              "description": "The duration of the grouping window in minutes (e.g., 60 for a 1-hour window).",
              "minimum": 1
            },
            "targetBufferCode": {
              "type": "string",
              "description": "The buffer to target for grouping."
            }
          }
        }
        """;

    private final JsonSchema schema;

    /**
     * Default constructor initializing the JSON schema validator.
     */
    public GroupingOrderManagerFactory() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        this.schema = factory.getSchema(GROUPING_MANAGER_SCHEMA);
    }

    /**
     * Returns the unique name of this factory.
     *
     * @return The factory name.
     */
    @Override
    public String getName() {
        return "Grouping";
    }

    /**
     * Builds a new instance of the GroupingOrderManager based on the provided JSON specification.
     *
     * @param spec The JSON specification containing configuration parameters.
     * @return A configured GroupingOrderManager instance.
     */
    @Override
    public BaseOrderHandlerBuilder build(JsonNode spec) {
        validateConfiguration(spec);
        GroupingOrderManagerConfig config = parseConfiguration(spec);
        return new GroupingOrderManager(config);
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
            LOGGER.errorf("Invalid GroupingOrderManager configuration: %s", errorMsg);
            throw new IllegalArgumentException("Invalid GroupingOrderManager configuration: " + errorMsg);
        }
    }

    /**
     * Parses the JSON specification into a typed configuration object.
     *
     * @param spec The validated JSON node.
     * @return The parsed configuration.
     */
    private GroupingOrderManagerConfig parseConfiguration(JsonNode spec) {
        int validityDelay = 0;
        int groupingWindow = 60; // Default values
        Buffer buffer = null;
        String targetBuffer = "";
        if (spec.has("validityDelayMinutes")) {
            validityDelay = spec.get("validityDelayMinutes").asInt();
        }
        if (spec.has("groupingWindowMinutes")) {
            groupingWindow = spec.get("groupingWindowMinutes").asInt();
        }
        if (spec.has("targetBufferCode")) {
            String bufferCode = spec.get("targetBufferCode").asText();
            buffer = Buffer.findByCode(bufferCode);
        }
        return new GroupingOrderManagerConfig(validityDelay, groupingWindow, buffer);
    }

    /**
     * Configuration data holder for the Grouping Handler Manager.
     *
     * @param validityDelayMinutes  The delay in minutes to add to the current time for the cutoff check.
     * @param groupingWindowMinutes The duration of the time slot for grouping orders.
     */
    private record GroupingOrderManagerConfig(int validityDelayMinutes, int groupingWindowMinutes, Buffer targetBuffer) {
    }

    /**
     * The worker implementation that processes a Store to create or update Grouping Handlers.
     */
    public static class GroupingOrderManager extends BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder {

        private static final Logger LOGGER = Logger.getLogger(GroupingOrderManager.class);

        final GroupingOrderManagerConfig config;

        /**
         * Constructor initializing the manager with its specific configuration.
         *
         * @param config The configuration parameters.
         */
        public GroupingOrderManager(GroupingOrderManagerConfig config) {
            this.config = config;
        }

        /**
         * Main processing method for the store.
         * <p>
         * Finds orphan lots, groups them by (Customer, Building, Time Slot),
         * and creates or updates the corresponding Grouping Handlers.
         * <p>
         * Note: A GroupingOrder is only created if lots exist for a specific Building.
         *
         * @param store The store to process.
         * @throws Exception if processing fails.
         */
        @Override
        public void process(Store store) throws Exception {
            LOGGER.infof("Starting Grouping Handler Manager for Store ID %s (Store %s)", store.id, store.code);
            // 1. Find orphan lots (pending, not covered, within time window, and associated with a building).
            List<Lot> orphanLots = findOrphanLots(store);
            if (orphanLots.isEmpty()) {
                LOGGER.info("No orphan lots found for this store.");
                return;
            }
            // 2. Group lots by (Customer, Building, Time Slot)
            Map<GroupKey, List<Lot>> groups = groupLots(orphanLots, config.targetBuffer);
            // 3. Process groups to Create/Update Handlers
            for (Map.Entry<GroupKey, List<Lot>> entry : groups.entrySet()) {
                GroupKey key = entry.getKey();
                List<Lot> lots = entry.getValue();
                // Create or find the handler for this specific Building/Customer/Time
                GroupingOrder handler = findOrCreateGroupingOrder(store, key);
                handler.lots.addAll(lots);
                handler.persist();
            }
        }

        /**
         * Retrieves lots that are eligible for Grouping but not yet assigned to a handler.
         * <p>
         * Criteria:
         * <ul>
         *   <li>Lot must belong to the given store.</li>
         *   <li>Lot status must be PENDING.</li>
         *   <li>Lot delivery date must be less than (Current Time + Validity Delay).</li>
         *   <li>Lot must be associated with a Building (via its chunk).</li>
         *   <li>Lot must not be referenced by any GroupingOrder in OPEN or PROCESSING status.</li>
         * </ul>
         *
         * @param store The store to search in.
         * @return A list of orphan lots.
         */
        private List<Lot> findOrphanLots(Store store) {
            // Calculate the cutoff date: Now + configured delay
            LocalDateTime cutoffDate = DateTimeProvider.now().plusMinutes(config.validityDelayMinutes);
            LOGGER.debugf("Scanning for orphan lots in Store %s with delivery date <= %s", store.code, cutoffDate);
            // JPQL Query
            String jpql = """
                SELECT DISTINCT l
                FROM Lot l
                WHERE l.status = LotStatus.PLANNED_TO_BE_COLLECTED
                  AND l.store = :store
                  AND l.chunk.deliveryDate IS NOT NULL
                  AND l.chunk.deliveryDate <= :cutoffDate
                  AND l.chunk.building IS NOT NULL
                  AND NOT EXISTS (
                     SELECT 1
                     FROM GroupingOrder gh
                     JOIN gh.lots gl
                     WHERE gl.id = l.id
                        AND gh.store = :store
                  )
                ORDER BY l.id
                """;
            EntityManager em = Panache.getEntityManager();
            return em.createQuery(jpql, Lot.class)
                    .setParameter("store", store)
                    .setParameter("cutoffDate", DateTimeProvider.now())
                    .getResultList();
        }

        /**
         * Groups a list of lots based on business criteria.
         * <p>
         * Lots are grouped by Customer, Building, and Time Slot (truncated to the hour).
         *
         * @param lots The list of lots to group.
         * @return A map of GroupKey to List of Lots.
         */
        private Map<GroupKey, List<Lot>> groupLots(List<Lot> lots, Buffer buffer) {
            return lots.stream()
                    .collect(Collectors.groupingBy(lot -> {
                        // Calculate Time Slot: Truncate to the Hour
                        LocalDateTime slotStart = (lot.chunk.deliveryDate != null)
                                ? lot.chunk.deliveryDate.withMinute(0).withSecond(0).withNano(0)
                                : null;

                        // We use lot.chunk.building as the grouping criteria
                        return new GroupKey(lot.customer.id, buffer, slotStart);
                    }));
        }

        /**
         * Finds an existing Grouping Handler matching the group key or creates a new one.
         * <p>
         * Matches are based on Store, Customer, Building, and Requested Start Time.
         *
         * @param store The store.
         * @param key   The grouping key identifying the context (customer, building, time).
         * @return The found or newly created GroupingOrder.
         */
        private GroupingOrder findOrCreateGroupingOrder(Store store, GroupKey key) {
            EntityManager em = Panache.getEntityManager();

            // Check for existing OPEN handler for this specific criteria including Building
            String jpql = """
                SELECT gh
                FROM GroupingOrder gh
                WHERE gh.store = :store
                  AND gh.status = 'OPEN'
                  AND gh.customer = :customer
                  AND gh.buffer = :buffer
                  AND gh.requestedStart = :requestedStart
                """;

            // Resolve entities from IDs for the query
            Customer customer = (key.customerId != null) ? Customer.findById(key.customerId) : null;

            List<GroupingOrder> existing = em.createQuery(jpql, GroupingOrder.class)
                    .setParameter("store", store)
                    .setParameter("customer", customer)
                    .setParameter("buffer", key.buffer)
                    .setParameter("requestedStart", key.slotStart)
                    .getResultList();

            if (!existing.isEmpty()) {
                LOGGER.debugf("Found existing GroupingOrder ID %s for GroupKey %s", existing.get(0).id, key);
                return existing.get(0);
            }

            // Create new handler
            LOGGER.debugf("Creating new GroupingOrder for GroupKey %s", key);
            GroupingOrder newOrder = new GroupingOrder();
            newOrder.store = store;
            newOrder.customer = customer;
            newOrder.requestedStart = key.slotStart;
            newOrder.buffer = key.buffer;
            newOrder.code = HandlingOrderSequence.getNextCode(newOrder.store, newOrder.getPrefix());
            // Calculate end time based on grouping window configuration
            if (key.slotStart != null) {
                newOrder.requestedEnd = key.slotStart.plusMinutes(this.config.groupingWindowMinutes);
            }
            newOrder.status = BaseHandlingOrder.HandlingOrderStatus.OPEN;
            return newOrder;
        }
    }

    /**
     * Value object used as a key for grouping lots together.
     * <p>
     * Two lots should be in the same grouping order if they share these attributes.
     */
    public static class GroupKey {
        final Long customerId;
        final Buffer buffer; // Specific to GroupingOrder: grouping happens per building
        final LocalDateTime slotStart;

        /**
         * Constructs a GroupKey.
         *
         * @param customerId The ID of the customer.
         * @param buffer   The buffer where the lots are located.
         * @param slotStart  The start time of the delivery slot.
         */
        public GroupKey(Long customerId, Buffer buffer, LocalDateTime slotStart) {
            this.customerId = customerId;
            this.buffer = buffer;
            this.slotStart = slotStart;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            GroupKey groupKey = (GroupKey) o;
            return Objects.equals(customerId, groupKey.customerId) &&
                    Objects.equals(buffer, groupKey.buffer) &&
                    Objects.equals(slotStart, groupKey.slotStart);
        }

        /**
         * Generates a hash code based on the fields used for grouping.
         */
        @Override
        public int hashCode() {
            return Objects.hash(customerId, buffer.id, slotStart);
        }

        /**
         * Provides a string representation of the GroupKey for logging and debugging purposes.
         */
        @Override
        public String toString() {
            return "GroupKey{" +
                    "customerId=" + customerId +
                    ", buildingId=" + buffer.id +
                    ", slotStart=" + slotStart +
                    '}';
        }
    }
}