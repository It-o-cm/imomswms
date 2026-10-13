package com.intermarche.backoffice.domain;

import com.intermarche.backoffice.domain.util.DateTimeProvider;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.eclipse.microprofile.graphql.Name;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Value Object representing the reservation record of a request by a worker.
 * <p>
 * Embedded into entities (e.g., OrderRequest) to store the context of the worker
 * processing this specific request, as well as the current status, lease information,
 * and the valid start instant for processing.
 */
@Embeddable
public class ReservationRecord {

    /**
     * Enumeration representing the processing status of an order request.
     */
    @Name("ReservationStatus")
    public enum ReservationStatus {
        /**
         * The request has been received and is waiting to be picked up by a worker.
         */
        PENDING,

        /**
         * A worker is currently processing the request.
         */
        PROCESSING,

        /**
         * The request has been blocked.
         */
        BLOCKED,

        /**
         * The request has been successfully processed (but not necessarily completed).
         */
        DONE,

        /**
         * The request processing has failed due to an error.
         */
        FAILED,

        /**
         * The request processing is completed.
         */
        CLOSED
    }

    /**
     * Current status of the request processing.
     * Defaults to PENDING upon creation.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public ReservationStatus status = ReservationStatus.PENDING;

    /**
     * Timestamp indicating the expiration of the lease for the PROCESSING status.
     * <p>
     * If the current time is after this timestamp, the record should be considered
     * as expired (effectively reverting to PENDING for a new worker to pick it up).
     * Null means no active lease or no expiration.
     */
    @Column(name = "leasing")
    public Instant leasing;

    /**
     * The instant from which this request can be taken into account (processed).
     * <p>
     * A reservation cannot be made before this timestamp.
     * If null, the request is considered immediately processable.
     * <p>
     * Upon successful reservation, this field is automatically updated to {@code now + 1 second}
     * to prevent immediate re-processing by other workers.
     */
    @Column(name = "consideration")
    public Instant consideration;

    /**
     * Default constructor required by JPA.
     */
    public ReservationRecord() {
    }

    /**
     * Constructor to initialize status and leasing.
     *
     * @param status  The current status.
     * @param leasing The timestamp until which the reservation is valid.
     */
    public ReservationRecord(ReservationStatus status, Instant leasing) {
        this.status = status;
        this.leasing = leasing;
    }

    /**
     * Generates the SQL WHERE clause condition for finding reservable items.
     * <p>
     * This separates the business logic (how status/leasing works) from the specific
     * table context (column names). It now also ensures that the {@code considerationInstant}
     * has passed or is null.
     *
     * @param statusColumn            The actual column name for status in the specific table.
     * @param leasingColumn           The actual column name for leasing in the specific table.
     * @param considerationInstantColumn The actual column name for the consideration instant.
     * @return A SQL string fragment for the WHERE clause.
     */
    public static String getReservableSqlCondition(String statusColumn, String leasingColumn, String considerationInstantColumn) {
        // 1. Logic for Status and Lease
        String statusLeaseClause = String.format(
            "(%s = 'PENDING' OR (%s = 'PROCESSING' AND %s IS NOT NULL AND %s < CURRENT_TIMESTAMP))",
            statusColumn, statusColumn, leasingColumn, leasingColumn
        );
        // 2. Logic for Consideration Instant (Valid From)
        // The record is reservable if the instant is NULL (immediate) OR if the instant is in the past.
        String considerationClause = String.format(
            "(%s IS NULL OR %s <= CURRENT_TIMESTAMP)",
            considerationInstantColumn, considerationInstantColumn
        );
        return statusLeaseClause + " AND " + considerationClause;
    }

    /**
     * Generic method to reserve a batch of entities containing a ReservationRecord.
     * <p>
     * This method centralizes the logic for finding PENDING or expired items, respecting the
     * consideration instant, locking them, and applying the lease using the embedded object's
     * {@link #startLease(long)} method.
     * <p>
     * At the end of the reservation, the {@code considerationInstant} is set to {@code now + 1 second}.
     *
     * @param em                       The EntityManager to execute queries.
     * @param entityClass              The class of the entity (e.g., OrderRequest.class).
     * @param tableName                The database table name.
     * @param statusColumn             The database column name for the status.
     * @param leasingColumn            The database column name for the leasing timestamp.
     * @param considerationInstantColumn The database column name for the consideration instant.
     * @param recordAccessor          A function to extract the {@link ReservationRecord} from the entity instance.
     * @param leaseDurationSec        The duration of the lease in seconds.
     * @param batchSize               The maximum number of items to reserve.
     * @param extraWhereCondition     Optional. An additional SQL fragment to append to the WHERE clause
     *                                 (e.g., "store_id = 1"). The text is appended directly, so
     *                                 ensure it is valid SQL and safe from injection if dynamic.
     * @param <T>                     The type of the entity.
     * @return A list of IDs of the reserved entities.
     */
    public static <T> List<Long> reserveBatch(
            EntityManager em,
            Class<T> entityClass,
            String tableName,
            String statusColumn,
            String leasingColumn,
            String considerationInstantColumn,
            Function<T, ReservationRecord> recordAccessor,
            long leaseDurationSec,
            int batchSize,
            String extraWhereCondition) {

        // 1. Build the standard WHERE clause
        String whereClause = getReservableSqlCondition(statusColumn, leasingColumn, considerationInstantColumn);
        // 2. Append extra condition if provided
        if (extraWhereCondition != null && !extraWhereCondition.isBlank()) {
            whereClause += extraWhereCondition;
        }
        // 3. Build Native Query to fetch entities directly
        String sql = String.format(
                "SELECT e.* FROM %s e WHERE %s LIMIT ?1 FOR UPDATE SKIP LOCKED",
                tableName, whereClause
        );
        // 4. Fetch and Lock
        @SuppressWarnings("unchecked")
        List<T> entities = em.createNativeQuery(sql, entityClass)
                .setParameter(1, batchSize)
                .getResultList();
        if (entities.isEmpty()) {
            return List.of();
        }
        List<Long> reservedIds = new ArrayList<>(entities.size());
        // 5. Apply Lease Logic
        for (T entity : entities) {
            ReservationRecord record = recordAccessor.apply(entity);
            if (record != null) {
                record.startLease(leaseDurationSec);
                // Set considerationInstant to now + 1s using DateTimeProvider
                Instant now = DateTimeProvider.now().atZone(ZoneId.systemDefault()).toInstant();
                record.consideration = now.plusSeconds(1);
                // Extract ID
                try {
                    java.lang.reflect.Field idField = entityClass.getField("id");
                    Object idValue = idField.get(entity);
                    if (idValue instanceof Long) {
                        reservedIds.add((Long) idValue);
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to extract ID from entity", e);
                }
            }
        }
        return reservedIds;
    }

    /**
     * Updates the status to PROCESSING and sets a new lease expiration date.
     * <p>
     * Uses {@link DateTimeProvider} to determine the current time.
     *
     * @param durationSeconds The duration of the lease in seconds from now.
     */
    public void startLease(long durationSeconds) {
        this.status = ReservationStatus.PROCESSING;
        // Convert LocalDateTime from provider to Instant
        Instant now = DateTimeProvider.now().atZone(ZoneId.systemDefault()).toInstant();
        this.leasing = now.plusSeconds(durationSeconds);
    }

    /**
     * Checks if the current lease has expired.
     *
     * @return true if the lease is expired, false otherwise.
     */
    public boolean isLeaseExpired() {
        return this.status == ReservationStatus.PROCESSING
                && this.leasing != null
                && Instant.now().isAfter(this.leasing);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReservationRecord that = (ReservationRecord) o;
        return status == that.status && Objects.equals(leasing, that.leasing);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, leasing);
    }
}