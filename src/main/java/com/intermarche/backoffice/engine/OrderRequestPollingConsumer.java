package com.intermarche.backoffice.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.order.OrderRequest;
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
 * Polling Consumer using Quarkus Scheduler.
 * <p>
 * Orchestrates the processing of {@link OrderRequest} entities.
 * Delegates the reservation logic entirely to {@link ReservationRecord}.
 */
@Singleton
public class OrderRequestPollingConsumer {

    private static final Logger LOGGER = Logger.getLogger(OrderRequestPollingConsumer.class);
    private static final int BATCH_SIZE = 10;
    // Lease duration in seconds (e.g., 5 minutes)
    private static final long LEASE_DURATION_SECONDS = 300;

    @Inject
    EntityManager em;

    @Inject
    OrderRecorder orderRecorder;

    @Inject
    ObjectMapper objectMapper;

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
            } while (!reservedIds.isEmpty()); // Continue polling until no more tasks are available
        } catch (Exception e) {
            LOGGER.error("Error during scheduled poll", e);
        }
    }

    /**
     * Reserves a batch of tasks.
     * <p>
     * Delegates to {@link ReservationRecord#reserveBatch}, passing all entity-specific
     * context parameters (table name, column names, record accessor).
     * <p>
     * This method is transactional to commit the status change and lease update immediately.
     *
     * @return A list of IDs of the reserved OrderRequests.
     */
    @Transactional
    public List<Long> reserveBatch() {
        return OrderRequest.reserveBatch(
                LEASE_DURATION_SECONDS,
                BATCH_SIZE,
                ""
        );
    }

    /**
     * Processes the reserved requests and marks them as DONE or FAILED.
     *
     * @param ids The list of OrderRequest IDs to process.
     */
    @Transactional
    public void processBatch(List<Long> ids) {
        // Fetch entities to process
        List<OrderRequest> requests = em.createQuery("SELECT o FROM OrderRequest o WHERE o.id IN :ids", OrderRequest.class)
                .setParameter("ids", ids)
                .getResultList();
        for (OrderRequest request : requests) {
            processSingleRequest(request);
        }
    }

    /**
     * Processes a single OrderRequest.
     * <p>
     * Parses the specification and persists the order and fulfillment.
     *
     * @param request The entity to process.
     * @throws Exception If parsing or persistence fails.
     */
    @Transactional
    public void processSingleRequest(OrderRequest request) {
        if (request == null) {
            LOGGER.warn("Skipping null OrderRequest.");
            return;
        }
        LOGGER.infof("Processing Order Request ID %s", request.id);
        try {
            JsonNode json = objectMapper.readTree(request.specification);
            orderRecorder.persistOrderAndFulfillmentFromJson(json);
            // Success: mark as DONE
            LOGGER.info("Succeeded processing Order Request ID %s" + request.id);
            request.reservationRecord.status = ReservationRecord.ReservationStatus.DONE;
        } catch (Exception e) {
            LOGGER.errorf("Failed processing Order Request ID %s", request.id, e);
            // Failure: mark as FAILED to avoid infinite retry loops
            request.reservationRecord.status = ReservationRecord.ReservationStatus.FAILED;
        }
    }

}