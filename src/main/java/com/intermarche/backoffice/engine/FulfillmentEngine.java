package com.intermarche.backoffice.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.*;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.engine.fulfillers.BaseFulfillerFactory;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Engine responsible for processing {@link Fulfillment} entities.
 * <p>
 * Uses a thread-safe {@link java.util.concurrent.ConcurrentHashMap} to cache
 * built Fulfillers per Store.
 * <p>
 * The {@link ConcurrentHashMap#computeIfAbsent(Object, java.util.function.Function)}
 * method ensures that under massive concurrent load, the loading logic
 * (DB + JSON parsing) for a specific Store is executed only once,
 * preventing database thundering herds.
 * <p>
 * Emits {@link Event} instances if fulfillers cannot be initialized or if the
 * fulfillment cannot be transitioned to the READY state.
 */
@ApplicationScoped
public class FulfillmentEngine {

    private static final Logger LOGGER = Logger.getLogger(FulfillmentEngine.class);

    @Inject
    Instance<BaseFulfillerFactory> factories;

    @Inject
    ObjectMapper objectMapper;

    Map<String, BaseFulfillerFactory> factoryMap;

    /**
     * Thread-safe cache using standard JDK ConcurrentHashMap.
     * Key: Store ID (Long)
     * Value: List of BaseFulfiller
     */
    private final ConcurrentHashMap<Long, List<BaseFulfillerFactory.BaseFulfiller>> fulfillersCache = new ConcurrentHashMap<>();

    @PostConstruct
    void initializeFactories() {
        // 1. Initialize Factory Map
        factoryMap = new ConcurrentHashMap<>();
        for (BaseFulfillerFactory factory : factories) {
            String name = factory.getName();
            LOGGER.infof("Registered Fulfiller Factory: %s", name);
            factoryMap.put(name, factory);
        }
    }

    /**
     * Builds the list of fulfillers from the database configuration.
     * <p>
     * The configuration specification is expected to be a JSON Array.
     * Each element in the array represents a fulfiller configuration and must contain a "type" field.
     * <p>
     * Example JSON structure:
     * <pre>
     * [
     *   {
     *     "type": "StorePicking",
     *     "param1": "value1"
     *   },
     *   {
     *     "type": "ZoneGrouping",
     *     "zoneCriteria": { ... }
     *   }
     * ]
     * </pre>
     *
     * @param store The store for which to build the fulfillers.
     * @return A list of initialized fulfillers.
     * @throws IllegalStateException if the configuration is missing or structurally invalid.
     * @throws RuntimeException if a specific fulfiller cannot be built.
     */
    private List<BaseFulfillerFactory.BaseFulfiller> buildFulfillersList(Store store) {
        Configuration configuration = Configuration.findHighestPriorityForStore(store, BaseFulfillerFactory.CONFIGURATION);
        if (configuration == null) {
            // Critical error: We cannot process anything without a configuration
            throw new IllegalStateException("No Fulfiller Configuration found for store " + store.code);
        }
        try {
            JsonNode specRoot = objectMapper.readTree(configuration.specification);
            List<BaseFulfillerFactory.BaseFulfiller> fulfillers = new ArrayList<>();
            if (specRoot.isArray()) {
                for (JsonNode element : specRoot) {
                    // Extract the factory type from the "type" field
                    if (!element.has("type")) {
                        throw new IllegalArgumentException("Configuration element is missing 'type' field.");
                    }
                    String factoryName = element.get("type").asText();
                    // The entire element node is passed as the specification to the factory
                    JsonNode factorySpec = element;
                    BaseFulfillerFactory factory = factoryMap.get(factoryName);
                    if (factory != null) {
                        try {
                            BaseFulfillerFactory.BaseFulfiller fulfiller = factory.build(factorySpec);
                            fulfillers.add(fulfiller);
                        } catch (Exception e) {
                            throw new RuntimeException("Error building fulfiller " + factoryName + ": " + e.getMessage(), e);
                        }
                    } else {
                        throw new IllegalArgumentException("Fulfiller Factory " + factoryName + " not found in registry");
                    }
                }
            } else {
                throw new IllegalArgumentException("Configuration specification for store " + store.code + " is not a valid JSON array.");
            }
            return fulfillers;
        } catch (IllegalStateException | IllegalArgumentException e) {
            // Re-throw business/configuration errors directly
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error parsing configuration for store " + store.code, e);
        }
    }

    /**
     * Public method to get fulfillers.
     * <p>
     * Uses {@link ConcurrentHashMap#computeIfAbsent(Object, java.util.function.Function)}.
     * If the store ID exists in the cache, the value is returned immediately.
     * If not, the lambda is executed atomically to load and build the list.
     * Other threads requesting the same store ID will block until the first thread finishes,
     * then receive the cached result.
     *
     * @param store The store.
     * @return The list of fulfillers.
     */
    public List<BaseFulfillerFactory.BaseFulfiller> getFulfillersForStore(Store store) {
        if (store == null || store.id == null) {
            return Collections.emptyList();
        }
        // ATOMIC OPERATION:
        // Only one thread will execute the lambda for a specific store.id at a time.
        return fulfillersCache.computeIfAbsent(store.id, id -> {
            LOGGER.debugf("Cache MISS for Store ID %s. Loading fulfillers...", id);
            // We must fetch the Store entity here as we only have the ID in the cache map key.
            // Ideally, we should also check if the store still exists.
            Store s = Store.findById(id);
            if (s == null) {
                return Collections.emptyList();
            }
            return buildFulfillersList(s);
        });
    }

    /**
     * Helper method to manually invalidate the cache for a specific store.
     * Useful if an admin updates the configuration.
     *
     * @param storeId The ID of the store to invalidate.
     */
    public void evictCacheForStore(Long storeId) {
        fulfillersCache.remove(storeId);
        LOGGER.infof("Evicted Fulfiller cache for Store ID %s", storeId);
    }

    /**
     * Helper method to clear the entire cache.
     */
    public void evictAll() {
        fulfillersCache.clear();
        LOGGER.info("Evicted all Fulfiller caches");
    }

    /**
     * Processes the given Fulfillment.
     * <p>
     * Retrieves the cached list of fulfillers and executes them.
     * <p>
     * If fulfillers cannot be initialized (missing/invalid configuration), emits a CRITICAL event
     * in an independent transaction to ensure traceability even if the caller rolls back, then rethrows.
     *
     * @param fulfillment The fulfillment entity to process.
     * @throws Exception if fulfillers cannot be built or if any fulfiller fails during processing.
     */
    public void process(Fulfillment fulfillment) throws Exception {
        List<BaseFulfillerFactory.BaseFulfiller> fulfillers;

        try {
            // Fetch the list from the cache (may throw if configuration is invalid)
            fulfillers = getFulfillersForStore(fulfillment.order.store);
        } catch (Exception e) {
            // Emit explicit anomaly event in an INDEPENDENT transaction
            // because the fulfillment processing is aborting here.
            Event.publishInNewTx(
                    "Erreur Critique de Fulfillment",
                    "Impossible de construire les fulfillers pour le magasin " + fulfillment.order.store.code +
                            ". La configuration est manquante, corrompue, ou un fulfiller est introuvable. Cause technique: " + e.getMessage(),
                    Event.UrgencyLevel.CRITICAL,
                    fulfillment.order.store,
                    fulfillment,
                    "FULFILLMENT-" + fulfillment.id
            );
            // Rethrow to let the caller (e.g., reservation worker) handle the failure appropriately
            throw e;
        }

        // Execute the valid fulfillers
        for (BaseFulfillerFactory.BaseFulfiller fulfiller : fulfillers) {
            LOGGER.infof("Applying fulfiller %s to Fulfillment ID %s", fulfiller.getClass().getSimpleName(), fulfillment.id);
            fulfiller.process(fulfillment);
        }

        checkAndMarkFulfillmentAsDone(fulfillment);
    }

    /**
     * Checks if all chunks within the fulfillment are in the "READY" state.
     * <p>
     * If every chunk is "READY", this method updates the Fulfillment's
     * reservation status to {@link ReservationRecord.ReservationStatus#DONE}.
     * Additionally, it creates a {@link Lot} for each {@link FulfillmentChunk}.
     * <p>
     * If any chunk remains "PENDING", it means products could not be routed by the configured fulfillers.
     * In this case, it keeps the status PENDING, emits a HIGH urgency event to alert operators
     * about the blocking chunks (including their IDs and line counts for easy diagnosis), and skips Lot creation.
     *
     * @param fulfillment The fulfillment entity to check and potentially complete.
     */
    protected void checkAndMarkFulfillmentAsDone(Fulfillment fulfillment) {
        boolean allReady = fulfillment.chunks.stream()
                .allMatch(chunk -> FulfillmentChunk.READY.equals(chunk.type));

        if (allReady) {
            // 1. Mark the reservation as DONE
            fulfillment.reservationRecord.status = ReservationRecord.ReservationStatus.DONE;
            // 2. Create a Lot for each Chunk
            for (FulfillmentChunk chunk : fulfillment.chunks) {
                // Pass 'fulfillment' to access the order and customer
                createLotForChunk(fulfillment, chunk);
            }
        }
        else {
            // Blockers found: Keep status PENDING and emit an explicit event
            fulfillment.reservationRecord.status = ReservationRecord.ReservationStatus.PENDING;

            // Extract precise details about the blocking chunks to help operators find the anomaly
            String pendingChunksDetails = fulfillment.chunks.stream()
                    .filter(chunk -> FulfillmentChunk.PENDING.equals(chunk.type))
                    .map(chunk -> String.format("Chunk ID %d (%d lignes restantes)", chunk.id, chunk.lines.size()))
                    .collect(Collectors.joining("; "));

            String description = "Impossible de rendre le fulfillment READY. Certains produits n'ont pas pu être " +
                    "déplacés par les fulfillers configurés (Localisation introuvable ?). " +
                    "Détails des chunks bloquants : [" + pendingChunksDetails + "]";

            // Emit in the CURRENT transaction (the fulfillment IS being saved in its PENDING state)
            Event.publishInCurrentTx(
                    "Fulfillment Incomplet (Non READY)",
                    description,
                    Event.UrgencyLevel.HIGH,
                    fulfillment.order.store,
                    fulfillment,
                    "FULFILLMENT-" + fulfillment.id
            );
        }
    }

    /**
     * Helper method to create a Lot and its associated LotLines for a specific Chunk.
     *
     * @param fulfillment The parent Fulfillment (used to retrieve the Customer).
     * @param chunk       The FulfillmentChunk to transform into a Lot.
     */
    private void createLotForChunk(Fulfillment fulfillment, FulfillmentChunk chunk) {
        Lot lot = new Lot();
        lot.status = Lot.LotStatus.PENDING;
        lot.store = fulfillment.order.store;
        lot.chunk = chunk;
        lot.fulfillment = fulfillment;
        lot.customer = fulfillment.order.customer;
        lot.persist();
        // Iterate over the lines in the Chunk to create LotLines
        for (FulfillmentLine line : chunk.lines) {
            LotLine lotLine = new LotLine();
            lotLine.setFulfillmentLine(line);
            lotLine.product = line.product;
            lotLine.quantity = line.requestedQuantity;
            lotLine.persist();
            lot.addLine(lotLine);
        }
    }

}