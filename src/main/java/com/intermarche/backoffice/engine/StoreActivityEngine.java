package com.intermarche.backoffice.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.Configuration;
import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.engine.handling.BaseOrderHandlerBuilderFactory;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This engine is responsible for processing store activities based on a configurable sequence of builders. Generally,
 * these builders create Order Handlers asking employees to perform various operations on the store, such as creating
 * picking/packing orders, collect/dispatch goods and so on.
 * <p>
 * This engine manages the instantiation and execution of a sequence of
 * {@link BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder} instances.
 * The list of builders is determined by a database {@link Configuration} specific to the store.
 * <p>
 * Uses a thread-safe {@link java.util.concurrent.ConcurrentHashMap} to cache
 * built builders per Store ID.
 * <p>
 * The {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent(Object, java.util.function.Function)}
 * method ensures that under massive concurrent load, the loading logic
 * (DB + JSON parsing) for a specific store is executed only once,
 * preventing database thundering herds.
 * <p>
 * Furthermore, this engine includes a delay detection mechanism that checks if any active
 * {@link BaseHandlingOrder} has exceeded its requested end time. If a delay is detected,
 * it ensures a warning {@link Event} is emitted exactly once per delayed order.
 * <p>
 * Emits {@link Event} instances in independent transactions if the initialization or execution
 * of order handlers fails, ensuring anomalies are tracked even if the processing transaction rolls back.
 */
@ApplicationScoped
public class StoreActivityEngine {

    private static final Logger LOGGER = Logger.getLogger(StoreActivityEngine.class);

    /**
     * Constant label used to identify delay events in the database.
     * This ensures that only one delay event is emitted per Handling Order,
     * preventing duplicate alerts for the same overdue task across multiple processing cycles.
     */
    private static final String LATE_HANDLING_ORDER_EVENT_LABEL = "Retard Handling Order";

    /**
     * CDI Instance to inject all available implementations of {@link BaseOrderHandlerBuilderFactory}.
     */
    @Inject
    Instance<BaseOrderHandlerBuilderFactory> factories;

    @Inject
    OrderScheduler orderScheduler;

    /**
     * Jackson ObjectMapper for parsing and processing JSON payloads from the database configuration.
     */
    @Inject
    ObjectMapper objectMapper;

    /**
     * A map indexing the available factories by their unique name.
     * Allows for quick lookup during configuration parsing.
     */
    Map<String, BaseOrderHandlerBuilderFactory> factoryMap;

    /**
     * Thread-safe cache using standard JDK ConcurrentHashMap.
     * Key: Store ID (Long)
     * Value: List of BaseOrderHandlerBuilder
     */
    private final ConcurrentHashMap<Long, List<BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder>> buildersCache = new ConcurrentHashMap<>();

    /**
     * Initializes the engine by populating the factory registry.
     * <p>
     * This method runs at application startup to discover all available factories
     * and map them by name.
     */
    @PostConstruct
    void initializeFactories() {
        // 1. Initialize Factory Map
        factoryMap = new ConcurrentHashMap<>();
        for (BaseOrderHandlerBuilderFactory factory : factories) {
            String name = factory.getName();
            LOGGER.infof("Registered Order Handler Builder Factory: %s", name);
            factoryMap.put(name, factory);
        }
    }

    /**
     * Builds the list of order handler builders from the database configuration.
     * <p>
     * This method contains the "expensive" logic (DB + JSON).
     * It retrieves the configuration for the store, parses the JSON array,
     * and instantiates the corresponding builders using the factory map.
     * <p>
     * Throws {@link IllegalStateException} if the configuration is missing, structurally invalid,
     * or if a specific builder cannot be instantiated.
     *
     * @param store The store entity for which to build the handlers.
     * @return A list of instantiated {@link BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder}.
     * @throws IllegalStateException if the global configuration for the store is missing.
     * @throws RuntimeException if a specific builder fails to build.
     */
    private List<BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder> buildBuilderList(Store store) {
        Configuration configuration = Configuration.findHighestPriorityForStore(store, BaseOrderHandlerBuilderFactory.CONFIGURATION);
        if (configuration == null) {
            // If there is no configuration at all, it's a critical system state for this store.
            throw new IllegalStateException("No Order Handler Builder Configuration found for store " + store.code);
        }
        try {
            JsonNode specRoot = objectMapper.readTree(configuration.specification);
            List<BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder> builders = new ArrayList<>();
            if (specRoot.isArray()) {
                for (JsonNode element : specRoot) {
                    // Extract the factory type from the "type" field
                    if (!element.has("type")) {
                        throw new IllegalArgumentException("Configuration element is missing 'type' field.");
                    }
                    String factoryName = element.get("type").asText();
                    // The entire element node is passed as the specification to the factory
                    JsonNode factorySpec = element;
                    BaseOrderHandlerBuilderFactory factory = factoryMap.get(factoryName);
                    if (factory != null) {
                        try {
                            BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder builder = factory.build(factorySpec);
                            builders.add(builder);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to build order handler builder " + factoryName + ": " + e.getMessage(), e);
                        }
                    } else {
                        throw new IllegalArgumentException("Order Handler Builder Factory " + factoryName + " not found in registry");
                    }
                }
            } else {
                throw new IllegalArgumentException("Configuration specification for store " + store.code + " is not a valid JSON array.");
            }
            return builders;
        } catch (IllegalStateException | IllegalArgumentException e) {
            // Re-throw business/configuration errors directly
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error parsing configuration for store " + store.code, e);
        }
    }

    /**
     * Public method to get the list of order handler builders for a store.
     * <p>
     * Uses {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent(Object, java.util.function.Function)}.
     * If the store ID exists in the cache, the value is returned immediately.
     * If not, the lambda is executed atomically to load and build the list.
     * Other threads requesting the same store ID will block until the first thread finishes,
     * then receive the cached result.
     *
     * @param store The store.
     * @return The list of order handler builders.
     */
    public List<BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder> getOrderHandlerBuildersForStore(Store store) {
        if (store == null || store.id == null) {
            return Collections.emptyList();
        }
        // ATOMIC OPERATION:
        // Only one thread will execute the lambda for a specific store.id at a time.
        return buildersCache.computeIfAbsent(store.id, id -> {
            LOGGER.debugf("Cache MISS for Store ID %s. Loading order handler builders...", id);
            // We must fetch the Store entity here as we only have the ID in the cache map key.
            // Ideally, we should also check if the store still exists.
            Store s = Store.findById(id);
            if (s == null) {
                return Collections.emptyList();
            }
            return buildBuilderList(s);
        });
    }

    /**
     * Helper method to manually invalidate the cache for a specific store.
     * Useful if an admin updates the configuration.
     *
     * @param storeId The ID of the store to invalidate.
     */
    public void evictCacheForStore(Long storeId) {
        buildersCache.remove(storeId);
        LOGGER.infof("Evicted Order Handler cache for Store ID %s", storeId);
    }

    /**
     * Helper method to clear the entire cache.
     */
    public void evictAll() {
        buildersCache.clear();
        LOGGER.info("Evicted all Order Handler caches");
    }

    /**
     * Processes the given Store.
     * <p>
     * Retrieves the cached list of builders and executes them in order.
     * Once the execution is complete (or if there were no builders to execute), it triggers
     * a delay verification mechanism for active Handling Orders.
     * <p>
     * If an error occurs (missing configuration, parsing error, or builder execution failure),
     * emits a {@link Event} with CRITICAL urgency in an independent transaction. This ensures
     * that the anomaly is tracked for technical support, even if the caller's transaction rolls back.
     *
     * @param store The store entity to process.
     * @throws Exception if builders cannot be initialized or if any builder fails during processing.
     */
    public void process(Store store) throws Exception {
        try {
            // Fetch the list from the cache
            List<BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder> builders = getOrderHandlerBuildersForStore(store);
            if (!builders.isEmpty()) {
                for (BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder builder : builders) {
                    LOGGER.infof("Applying order handler builder %s to store ID %s", builder.getClass().getSimpleName(), store.id);
                    builder.process(store);
                }
            }
            orderScheduler.scheduleOpenedOrders(store);
            // Check for delays AFTER the standard builder processing is complete
            checkAndEmitLateHandlingOrders(store);
        } catch (Exception e) {
            LOGGER.errorf("Failed processing Order Handler Builders for Store %s", store.code, e);
            // Emit explicit anomaly event in an INDEPENDENT transaction
            Event.publishInNewTx(
                    "Erreur Critique Création Handlers (Store)",
                    "Impossible d'initialiser ou d'exécuter les Order Handlers pour le magasin " + store.code +
                            ". Le processus est stoppé pour éviter des incohérences. Cause technique: " + e.getMessage(),
                    Event.UrgencyLevel.CRITICAL,
                    store, // <-- LE VRAI MAGASIN EST ICI (pour la contrainte store_id NOT NULL)
                    createFallbackStore(store.code, store.id), // <-- L'OBJET FANTOME EST ICI (pour l'ID technique et le code source)
                    "STORE-" + store.id
            );
            throw e;
        }
    }

    /**
     * Checks for active Handling Orders that have exceeded their requested end time
     * and emits a warning event if one has not been emitted already.
     * <p>
     * This method performs a database query to find orders associated with the given store
     * that are in {@link BaseHandlingOrder.HandlingOrderStatus#OPEN} or
     * {@link BaseHandlingOrder.HandlingOrderStatus#PROCESSING} state, and whose
     * {@code requestedEnd} is strictly in the past.
     * <p>
     * To ensure idempotency, it checks the {@link Event} table to see if an event with the
     * specific {@link #LATE_HANDLING_ORDER_EVENT_LABEL} already exists for the target entity ID.
     * If no such event exists, a new {@link Event} with {@link Event.UrgencyLevel#HIGH} urgency
     * is published in the current transaction.
     * <p>
     * <b>Note:</b> This method is wrapped in a try-catch block to prevent unexpected exceptions
     * during the delay check from breaking the main processing flow.
     *
     * @param store The store for which to check handling order delays.
     */
    private void checkAndEmitLateHandlingOrders(Store store) {
        try {
            LocalDateTime now = LocalDateTime.now();

            // 1. Find active orders (not finished) whose requested end date is in the past
            List<BaseHandlingOrder> lateOrders = BaseHandlingOrder.list(
                    "store = ?1 AND requestedEnd < ?2 AND requestedEnd IS NOT NULL AND status IN ?3",
                    store,
                    now,
                    List.of(BaseHandlingOrder.HandlingOrderStatus.OPEN, BaseHandlingOrder.HandlingOrderStatus.PROCESSING)
            );
            for (BaseHandlingOrder order : lateOrders) {
                // 2. Check if a "Delay" event has already been emitted for this specific orderId.
                // Using the technical ID ensures global uniqueness and prevents false positives across stores.
                boolean alreadyNotified = Event.count(
                        "targetEntityId = ?1 AND label = ?2",
                        order.id,
                        LATE_HANDLING_ORDER_EVENT_LABEL
                ) > 0;
                // 3. If never notified, emit the event in the current transaction
                if (!alreadyNotified) {
                    LOGGER.warnf("Handling Order %s is LATE for store %s. Emitting delay event.", order.code, store.code);
                    Event.publishInCurrentTx(
                            LATE_HANDLING_ORDER_EVENT_LABEL,
                            "Le Handling Order " + order.code + " est en retard. " +
                                    "Date de fin demandée: " + order.requestedEnd + ". Statut actuel: " + order.status,
                            Event.UrgencyLevel.HIGH,
                            store,
                            order,
                            order.code
                    );
                }
            }
        } catch (Exception e) {
            // We catch the exception to ensure that a failure in the delay checking logic
            // does not crash the main processing engine unexpectedly.
            LOGGER.errorf(e, "An error occurred while checking late handling orders for store %s", store.code);
        }
    }

    /**
     * Creates a fallback Store-like object for event targeting when the actual store processing fails.
     * <p>
     * Because the {@link Event} entity requires a non-null technical ID for its target, and passing the real
     * Store entity here might cause issues within the dying transaction context, we assign a dummy ID
     * to satisfy constraints without querying the database.
     *
     * @param storeCode The business code of the store (for readability in the event UI).
     * @param storeId   The technical ID of the store (for the Event targetEntityId).
     * @return A dummy Store instance.
     */
    private Store createFallbackStore(String storeCode, Long storeId) {
        Store fallback = new Store();
        fallback.code = storeCode;
        fallback.id = storeId;
        return fallback;
    }

}