package com.intermarche.backoffice.engine.handling;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.wms.HandlingOrderSequence;
import com.intermarche.backoffice.domain.wms.handling.PackingOrder;
import com.intermarche.backoffice.domain.order.Customer;
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
 * Factory for creating the Packing Order Manager.
 * <p>
 * This builder is responsible for generating packing handlers by grouping orphan lots
 * based on customer, location, and delivery time slots.
 */
@ApplicationScoped
public class PackingOrderManagerFactory extends BaseOrderHandlerBuilderFactory {

    /** Logger instance for this factory class. */
    private static final Logger LOGGER = Logger.getLogger(PackingOrderManagerFactory.class);

    // --------------------------------------------------
    // JSON Schema for Configuration Validation
    // --------------------------------------------------

    /**
     * The JSON schema definition used to validate the factory configuration.
     * <p>
     * This schema enforces the presence and types of parameters like validity delay,
     * grouping window, and deadline delay.
     */
    public static final String PACKING_MANAGER_SCHEMA = """
        {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "title": "PackingOrderManagerConfiguration",
          "type": "object",
          "description": "Configuration for the Packing Order Manager.",
          "properties": {
            "name": {
              "type": "string",
              "description": "Optional: Name to identify or document the handler manager. "
            },
            "validityDelayMinutes": {
              "type": "integer",
              "description": "The delay in minutes added to the current time to determine the cutoff date for packing.",
              "minimum": 0
            },
            "groupingWindowMinutes": {
              "type": "integer",
              "description": "The duration of the grouping window in minutes (e.g., 60 for a 1-hour window).",
              "minimum": 1
            },
            "deadlineDelayMinutes": {
              "type": "integer",
              "description": "The delay in minutes subtracted from the delivery date to determine the preparation deadline. If 0, the deadline is the delivery date itself.",
              "minimum": 0
            }
          }
        }
        """;

    /** The compiled JSON schema validator instance. */
    private final JsonSchema schema;

    /**
     * Default constructor initializing the JSON schema validator.
     */
    public PackingOrderManagerFactory() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        this.schema = factory.getSchema(PACKING_MANAGER_SCHEMA);
    }

    /**
     * Returns the unique name of this factory.
     *
     * @return The factory name.
     */
    @Override
    public String getName() {
        return "Packing";
    }

    /**
     * Builds a new instance of the PackingOrderManager based on the provided JSON specification.
     *
     * @param spec The JSON specification containing configuration parameters.
     * @return A configured PackingOrderManager instance.
     */
    @Override
    public BaseOrderHandlerBuilder build(JsonNode spec) {
        validateConfiguration(spec);
        PackingOrderManagerConfig config = parseConfiguration(spec);
        return new PackingOrderManager(config);
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
            LOGGER.errorf("Invalid PackingHandlingManager configuration: %s", errorMsg);
            throw new IllegalArgumentException("Invalid PackingHandlingManager configuration: " + errorMsg);
        }
    }

    /**
     * Parses the JSON specification into a typed configuration object.
     *
     * @param spec The validated JSON node.
     * @return The parsed configuration.
     */
    private PackingOrderManagerConfig parseConfiguration(JsonNode spec) {
        int validityDelay = 0;
        int groupingWindow = 60;
        int deadlineDelay = 0;

        if (spec.has("validityDelayMinutes")) {
            validityDelay = spec.get("validityDelayMinutes").asInt();
        }
        if (spec.has("groupingWindowMinutes")) {
            groupingWindow = spec.get("groupingWindowMinutes").asInt();
        }
        if (spec.has("deadlineDelayMinutes")) {
            deadlineDelay = spec.get("deadlineDelayMinutes").asInt();
        }

        return new PackingOrderManagerConfig(validityDelay, groupingWindow, deadlineDelay);
    }

    /**
     * Immutable configuration data holder for the Packing Order Manager.
     *
     * @param validityDelayMinutes  The delay in minutes added to the current time to determine the cutoff date for picking lots.
     * @param groupingWindowMinutes The duration in minutes of the time slot used for grouping lots (e.g., 60 for a 1-hour window).
     * @param deadlineDelayMinutes  The delay in minutes subtracted from the delivery slot start time to calculate the absolute
     *                              deadline by which the packing preparation must be completely finished.
     */
    private record PackingOrderManagerConfig(int validityDelayMinutes, int groupingWindowMinutes, int deadlineDelayMinutes) {
    }

    /**
     * The worker implementation that processes a Store to create or update Packing Orders.
     */
    public static class PackingOrderManager extends BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder {

        /** Logger instance for this worker class. */
        private static final Logger LOGGER = Logger.getLogger(PackingOrderManager.class);

        /** The parsed configuration parameters controlling the behavior of this manager. */
        final PackingOrderManagerConfig config;

        /**
         * Constructor initializing the manager with its specific configuration.
         *
         * @param config The configuration parameters.
         */
        public PackingOrderManager(PackingOrderManagerConfig config) {
            this.config = config;
        }

        /**
         * Main processing method for the store.
         * <p>
         * Finds orphan lots, groups them by key, and creates or updates the corresponding
         * Packing Handler Orders.
         *
         * @param store The store to process.
         * @throws Exception if processing fails.
         */
        @Override
        public void process(Store store) throws Exception {
            LOGGER.infof("Starting Packing Order Manager for Store ID %s (Store %s)", store.id, store.code);
            List<Lot> orphanLots = findOrphanLots(store);
            if (orphanLots.isEmpty()) {
                LOGGER.info("No orphan lots found for this store.");
                return;
            }
            Map<GroupKey, List<Lot>> groups = groupLots(orphanLots);
            for (Map.Entry<GroupKey, List<Lot>> entry : groups.entrySet()) {
                GroupKey key = entry.getKey();
                List<Lot> lots = entry.getValue();
                setStatusToLot(lots, Lot.LotStatus.PLANNED_TO_BE_PACKED);
                PackingOrder handler = findOrCreatePackingOrderHandler(store, key);
                if(handler.building==null)handler.building=lots.stream().filter(l->l.chunk!=null && l.chunk.building!=null).map(l->l.chunk.building).findFirst().orElse(null);
                handler.lots.addAll(lots);
                handler.persist();
            }
        }

        /**
         * Retrieves lots that are eligible for Packing but not yet assigned to an order.
         * <p>
         * Criteria:
         * <ul>
         *   <li>Lot must belong to the given store.</li>
         *   <li>Lot status must be PENDING.</li>
         *   <li>Lot delivery date must be less than or equal to (Current Time + Validity Delay).</li>
         *   <li>Lot must not be referenced by any PackingOrder in OPEN, SCHEDULED, or PROCESSING status.</li>
         * </ul>
         *
         * @param store The store to search in.
         * @return A list of orphan lots.
         */
        private List<Lot> findOrphanLots(Store store) {
            LocalDateTime cutoffDate = DateTimeProvider.now().plusMinutes(config.validityDelayMinutes);
            LOGGER.debugf("Scanning for orphan lots in Store %s with delivery date <= %s", store.code, cutoffDate);
            String jpql = """
                SELECT DISTINCT l
                FROM Lot l
                WHERE (l.status = LotStatus.PENDING OR l.status = LotStatus.PLANNED_TO_BE_COLLECTED)
                  AND l.store = :store
                  AND l.chunk.deliveryDate IS NOT NULL
                  AND l.chunk.deliveryDate <= :cutoffDate
                  AND NOT EXISTS (
                     SELECT 1
                     FROM PackingOrder po
                     JOIN po.lots pl
                     WHERE pl.id = l.id
                        AND po.store = :store
                  )
                ORDER BY l.id
                """;
            EntityManager em = Panache.getEntityManager();
            return em.createQuery(jpql, Lot.class)
                .setParameter("store", store)
                .setParameter("cutoffDate", cutoffDate)
                .getResultList();
        }

        /**
         * Groups a list of lots based on business criteria.
         * <p>
         * Lots are grouped by Customer, Store, and Time Slot (truncated to the hour).
         *
         * @param lots The list of lots to group.
         * @return A map of GroupKey to List of Lots.
         */
        private Map<GroupKey, List<Lot>> groupLots(List<Lot> lots) {
            return lots.stream()
                .collect(Collectors.groupingBy(lot -> {
                    LocalDateTime slotStart = (lot.chunk.deliveryDate != null)
                        ? lot.chunk.deliveryDate.withMinute(0).withSecond(0).withNano(0)
                        : null;
                    return new GroupKey(lot.customer.id, lot.store, slotStart);
                }));
        }

        /**
         * Finds an existing Packing Handler Order matching the group key or creates a new one.
         * <p>
         * Matches are based on Store, Customer, and Requested Start Time.
         * When creating a new order, the {@code deadLine} is calculated by subtracting the
         * configured {@code deadlineDelayMinutes} from the delivery slot start time.
         *
         * @param store The store.
         * @param key   The grouping key identifying the context (customer, store, time).
         * @return The found or newly created PackingOrder.
         */
        private PackingOrder findOrCreatePackingOrderHandler(Store store, GroupKey key) {
            EntityManager em = Panache.getEntityManager();
            String jpql = """
                SELECT poh
                FROM PackingOrder poh
                WHERE poh.store = :store
                  AND poh.status = HandlingOrderStatus.OPEN
                  AND poh.customer = :customer
                  AND poh.requestedStart = :requestedStart
                """;
            Customer customer = (key.customerId != null) ? Customer.findById(key.customerId) : null;
            List<PackingOrder> existing = em.createQuery(jpql, PackingOrder.class)
                    .setParameter("store", store)
                    .setParameter("customer", customer)
                    .setParameter("requestedStart", key.slotStart)
                    .getResultList();
            if (!existing.isEmpty()) {
                LOGGER.debugf("Found existing PackingOrder ID %s for GroupKey %s", existing.get(0).id, key);
                return existing.get(0);
            }
            LOGGER.debugf("Creating new PackingOrder for GroupKey %s", key);
            PackingOrder newOrder = new PackingOrder();
            newOrder.store = store;
            newOrder.customer = customer;
            newOrder.requestedStart = key.slotStart;
            newOrder.code = HandlingOrderSequence.getNextCode(newOrder.store, newOrder.getPrefix());
            if (key.slotStart != null) {
                newOrder.requestedEnd = key.slotStart.plusMinutes(this.config.groupingWindowMinutes);
                // Calculate the absolute deadline for preparation completion.
                // It is set strictly before the delivery date based on the configured delay.
                newOrder.deadLine = key.slotStart.minusMinutes(this.config.deadlineDelayMinutes);
            }
            newOrder.status = BaseHandlingOrder.HandlingOrderStatus.OPEN;
            return newOrder;
        }
    }

    /**
     * Value object used as a key for grouping lots together.
     * <p>
     * Two lots should be in the same packing order if they share these exact attributes.
     */
    public static class GroupKey {

        /** The unique identifier of the customer associated with the lots. */
        final Long customerId;

        /** The physical store entity where the lots are located. */
        final Store store;

        /** The truncated start time of the delivery time slot. */
        final LocalDateTime slotStart;

        /**
         * Constructs a GroupKey.
         *
         * @param customerId The ID of the customer.
         * @param store      The physical location entity.
         * @param slotStart  The start time of the delivery slot.
         */
        public GroupKey(Long customerId, Store store, LocalDateTime slotStart) {
            this.customerId = customerId;
            this.store = store;
            this.slotStart = slotStart;
        }

        /**
         * Evaluates whether another object is equal to this GroupKey.
         *
         * @param o The object to compare against.
         * @return {@code true} if the objects share the same customer ID, store, and slot start time.
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            GroupKey groupKey = (GroupKey) o;
            return Objects.equals(customerId, groupKey.customerId) &&
                    Objects.equals(store, groupKey.store) &&
                    Objects.equals(slotStart, groupKey.slotStart);
        }

        /**
         * Generates a hash code based on the fields used for grouping.
         *
         * @return The integer hash code.
         */
        @Override
        public int hashCode() {
            return Objects.hash(customerId, store.id, slotStart);
        }

        /**
         * Provides a string representation of the GroupKey for logging and debugging purposes.
         *
         * @return A formatted string containing the key identifiers.
         */
        @Override
        public String toString() {
            return "GroupKey{" +
                    "customerId=" + customerId +
                    ", storeId=" + store.id +
                    ", slotStart=" + slotStart +
                    '}';
        }
    }
}