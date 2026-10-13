package com.intermarche.backoffice.engine;

import com.intermarche.backoffice.domain.*;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.WMSStoreHandle;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Polling Consumer responsible for initializing WMS Store Handles.
 * <p>
 * Runs periodically (every 1 minute) via {@link #pollAndProcess()}.
 * Delegates to {@link #createMissingStoreHandles()} to find Stores that do not yet have
 * a {@link WMSStoreHandle} and creates them.
 * <p>
 * Uses a loop to process all pending stores in batches of 100 within the same transaction window.
 * Uses "FOR UPDATE SKIP LOCKED" to safely handle concurrent execution across multiple cluster nodes.
 */
@Singleton
public class StoreActivityPollingConsumer {

    private static final Logger LOGGER = Logger.getLogger(StoreActivityPollingConsumer.class);

    /**
     * The number of stores to process in a single batch.
     */
    private static final int BATCH_SIZE = 100;

    /**
     * The lease duration in seconds for which a store handle is reserved for processing.
     */
    private static final long LEASE_DURATION_SECONDS = 300;

    @Inject
    StoreActivityEngine storeActivityEngine;

    @Inject
    EntityManager em;

    /**
     * Scheduled entry point.
     * <p>
     * Annotated with {@code @Scheduled} to run every minute.
     * Annotated with {@code @RunOnVirtualThread} for non-blocking execution.
     * <p>
     * Simply delegates to the initialization logic and the processing logic.
     */
    @Scheduled(every = "5s")
    @RunOnVirtualThread
    public void pollAndProcess() {
        createMissingStoreHandles();
        pollAndProcessActivities();
    }

    /**
     * Polls for and processes existing WMS Store Handles.
     * <p>
     * Reserves batches of handles using {@link #reserveStores()} and processes them
     * sequentially until no more handles are available in the current batch.
     */
    void pollAndProcessActivities() {
        try {
            List<Long> reservedIds;
            do {
                // 1. Reserve a batch of tasks
                reservedIds = reserveStores();
                if (!reservedIds.isEmpty()) {
                    // 2. Process the reserved tasks
                    processStores(reservedIds);
                }
            } while(reservedIds.size() == BATCH_SIZE); // Continue polling if we got a full batch, to reduce latency
        } catch (Exception e) {
            LOGGER.error("Error during scheduled poll", e);
        }
    }

    /**
     * Reserves a batch of WMS Store Handles for processing.
     * <p>
     * Delegates to {@link WMSStoreHandle#reserveBatch(long, int, String)} to lock
     * records in the database using "FOR UPDATE SKIP LOCKED".
     *
     * @return A list of IDs of the reserved handles.
     */
    @Transactional
    public List<Long> reserveStores() {
        // Pass the ordering clause to ensure FIFO (First In, First Out) behavior based on consideration
        return WMSStoreHandle.reserveBatch(
                LEASE_DURATION_SECONDS,
                BATCH_SIZE,
                "ORDER BY reservation_consideration ASC"
        );
    }

    /**
     * Processes a list of reserved Store Handles.
     * <p>
     * Fetches the handles by their IDs and delegates to {@link #processSingleStore(WMSStoreHandle)}.
     *
     * @param ids The list of WMSStoreHandle IDs to process.
     */
    @Transactional
    public void processStores(List<Long> ids) {
        // Fetch entities to process
        List<WMSStoreHandle> handles = em.createQuery("SELECT h FROM WMSStoreHandle h WHERE h.id IN :ids", WMSStoreHandle.class)
                .setParameter("ids", ids)
                .getResultList();
        for (WMSStoreHandle storeHandle : handles) {
            processSingleStore(storeHandle);
        }
    }

    /**
     * Processes a single WMS Store Handle.
     * <p>
     * Delegates the actual business logic to the {@link StoreActivityEngine}.
     * Regardless of success or failure, the reservation status is reset to {@code PENDING}
     * and the {@code lastReservationDate} is updated.
     *
     * @param handle The WMSStoreHandle to process.
     */
    public void processSingleStore(WMSStoreHandle handle) {
        try {
            handle = WMSStoreHandle.findById(handle.id); // Refresh to get the latest state after each fulfiller
            storeActivityEngine.process(handle.store);
            LOGGER.infof("After Processing Single Activities of Store : %s", handle);
        } catch (Exception e) {
            LOGGER.errorf(e, "Error processing Activities for Store ID %d", handle.store.id);
        }
        finally {
            handle.reservationRecord.status = ReservationRecord.ReservationStatus.PENDING;
            handle.lastReservationDate = DateTimeProvider.now();
            em.merge(handle);
        }
    }

    /**
     * Core processing logic for initializing missing handles.
     * <p>
     * Annotated with {@code @Transactional} to manage the database transaction.
     * Loops continuously to fetch and process stores without handles until none are left.
     */
    @Transactional
    public void createMissingStoreHandles() {
        LOGGER.debug("Starting StoreActivityPollingConsumer cycle to check for missing WMS Store Handles...");
        int totalCreated = 0;
        int iteration = 0;
        List<Long> storeIds;
        // Loop as long as we find stores needing a handle
        do {
            iteration++;
            storeIds = findStoreIdsWithoutHandle(BATCH_SIZE);
            // If no stores found, exit the loop
            if (storeIds.isEmpty()) {
                if (iteration == 1) {
                    LOGGER.debug("No stores found requiring a WMS Handle.");
                }
                break;
            }
            LOGGER.infof("StoreActivityPollingConsumer Iteration %d: Found %d stores without WMS Handle. Processing batch...", iteration, storeIds.size());
            int batchCreated = 0;
            for (Long storeId : storeIds) {
                if (createHandleForStore(storeId)) {
                    batchCreated++;
                }
            }
            // Flush the batch to free memory and clear the persistence context before the next iteration
            em.flush();
            em.clear();
            totalCreated += batchCreated;
            LOGGER.infof("StoreActivityPollingConsumer Iteration %d: Created %d handles in this batch.", iteration, batchCreated);
        } while (true);
        if (totalCreated > 0) {
            LOGGER.infof("StoreActivityPollingConsumer cycle completed. Total created: %d WMS Store Handles.", totalCreated);
        }
    }

    /**
     * Executes the SQL query to find Store IDs that do not have an associated WMSStoreHandle.
     * Uses FOR UPDATE SKIP LOCKED to allow concurrent processing.
     *
     * @param limit The maximum number of IDs to fetch.
     * @return A list of Store IDs.
     */
    List<Long> findStoreIdsWithoutHandle(int limit) {
        String sql = """
                SELECT s.id
                FROM stores s
                LEFT JOIN wms_store_handles w ON w.store_id = s.id
                WHERE w.id IS NULL
                LIMIT ?1
                FOR UPDATE SKIP LOCKED
                """;
        @SuppressWarnings("unchecked")
        List<Long> storeIds = em.createNativeQuery(sql)
                .setParameter(1, limit)
                .getResultList();
        return storeIds;
    }

    /**
     * Creates and persists a WMSStoreHandle for a specific Store ID.
     *
     * @param storeId The ID of the store.
     * @return true if creation succeeded, false otherwise.
     */
    boolean createHandleForStore(Long storeId) {
        try {
            // Get a reference to the store (efficient, doesn't load all data)
            Store store = em.getReference(Store.class, storeId);
            // Create the Handle
            WMSStoreHandle handle = new WMSStoreHandle();
            handle.store = store;
            // ReservationRecord is initialized to PENDING by default in the entity
            handle.persist();
            return true;
        } catch (Exception e) {
            // Log error for this specific store but continue with others
            LOGGER.errorf("Failed to create WMSStoreHandle for Store ID %d: %s", storeId, e.getMessage());
            return false;
        }
    }

}