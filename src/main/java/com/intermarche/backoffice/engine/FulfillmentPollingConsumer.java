package com.intermarche.backoffice.engine;

import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.ReservationRecord;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Polling Consumer using Quarkus Scheduler for Fulfillments.
 * <p>
 * Orchestrates the processing of {@link Fulfillment} entities.
 * Delegates the reservation logic entirely to {@link ReservationRecord#reserveBatch}.
 */
@Singleton
public class FulfillmentPollingConsumer {

    private static final Logger LOGGER = Logger.getLogger(FulfillmentPollingConsumer.class);
    private static final int BATCH_SIZE = 10;
    // Lease duration in seconds (e.g., 5 minutes)
    private static final long LEASE_DURATION_SECONDS = 300;

    @Inject
    EntityManager em;

    @Inject
    FulfillmentEngine fulfillmentEngine;

    /**
     * Scheduled method executed every 10 seconds.
     * <p>
     * Annotated with {@code @RunOnVirtualThread} to ensure that IO operations
     * do not block the scheduler thread pool.
     */
    @Scheduled(every = "1s")
    @RunOnVirtualThread
    public void pollAndProcess() {
        try {
            List<Long> reservedIds;
            do {
                // 1. Reserve a batch of tasks
                reservedIds = reserveBatch();
                if (!reservedIds.isEmpty()) {
                    // 2. Process the reserved tasks
                    processBatch(reservedIds);
                }
            } while(reservedIds.size() == BATCH_SIZE); // Continue polling if we got a full batch, to reduce latency
        } catch (Exception e) {
            LOGGER.error("Error during scheduled poll", e);
        }
    }

    /**
     * Reserves a batch of fulfillments.
     * <p>
     * Delegates to {@link ReservationRecord#reserveBatch}, passing all entity-specific
     * context parameters (table name, column names, record accessor).
     * <p>
     * This method is transactional to commit the status change and lease update immediately.
     *
     * @return A list of IDs of the reserved Fulfillments.
     */
    @Transactional
    public List<Long> reserveBatch() {
        return Fulfillment.reserveBatch(    // Specific Accessor to the embedded record
                LEASE_DURATION_SECONDS,
                BATCH_SIZE,
                ""
        );
    }

    /**
     * Processes the reserved fulfillments and marks them as DONE or FAILED.
     *
     * @param ids The list of Fulfillment IDs to process.
     */
    @Transactional
    public void processBatch(List<Long> ids) {
        // Fetch entities to process
        List<Fulfillment> fulfillments = em.createQuery("SELECT f FROM Fulfillment f WHERE f.id IN :ids", Fulfillment.class)
                .setParameter("ids", ids)
                .getResultList();

        for (Fulfillment fulfillment : fulfillments) {
            processSingleFulfillment(fulfillment);
        }
    }

    /**
     * Processes a single Fulfillment.
     * <p>
     * Delegates the actual processing logic to {@link FulfillmentEngine}.
     *
     * @param fulfillment The entity to process.
     * @throws Exception If processing fails.
     */
    public void processSingleFulfillment(Fulfillment fulfillment) {
        try {
            fulfillment = Fulfillment.findById(fulfillment.id); // Refresh to get the latest state after each fulfiller
            fulfillmentEngine.process(fulfillment);
            LOGGER.infof("After Processing Single Fulfillment: %s with chunk size: %d", fulfillment, fulfillment.chunks.size());
        } catch (Exception e) {
            // Last line of defense to catch any processing errors and ensure that the whole batch is not left in a
            // limbo state. The engine should ideally handle exceptions and mark the fulfillment as FAILED itself, but
            // we add this catch here as a safeguard.
            LOGGER.errorf(e, "Error processing Fulfillment ID %d", fulfillment.id);
            // Mark as FAILED on error
            fulfillment.reservationRecord.status = ReservationRecord.ReservationStatus.FAILED;
            em.merge(fulfillment);
        }
    }

}