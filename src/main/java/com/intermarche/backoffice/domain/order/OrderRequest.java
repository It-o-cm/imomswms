package com.intermarche.backoffice.domain.order;

// --------------------------------------------------
// Entity: Order Request
// --------------------------------------------------

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.ReservationRecord;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Objects;

/**
 * Entity representing an incoming Order Request.
 * <p>
 * Stores all valid requests (history allowed). The status and reservation details
 * are encapsulated within the {@link ReservationRecord}.
 */
@Entity
@Table(name = "order_requests",
        indexes = {
                @Index(name = "idx_req_order_number", columnList = "order_number"),
                @Index(name = "idx_req_status", columnList = "reservation_status"),
                @Index(name = "idx_req_store_code", columnList = "store_code")
        }
)
public class OrderRequest extends BaseEntity {

    // --------------------------------------------------
    // Request Details
    // --------------------------------------------------

    /**
     * The business identifier for the order.
     * NOT unique: allows storing a history of all valid requests for the same order.
     */
    @Column(name = "order_number", nullable = false, length = 50)
    @NotBlank(message = "Order number is mandatory")
    public String orderNumber;

    /**
     * The code of the target store.
     */
    @Column(name = "store_code", nullable = false, length = 20)
    @NotBlank(message = "Store code is mandatory")
    public String storeCode;

    // --------------------------------------------------
    // Embedded Reservation Record (includes Status)
    // --------------------------------------------------

    /**
     * The reservation record containing the status and details about the worker.
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "status", column = @Column(name = "reservation_status")),
            @AttributeOverride(name = "leasing", column = @Column(name = "reservation_leasing")),
            @AttributeOverride(name = "consideration", column = @Column(name = "reservation_consideration"))
    })
    public ReservationRecord reservationRecord;

    // --------------------------------------------------
    // Data Payload
    // --------------------------------------------------

    /**
     * Raw JSON representation of the order request.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "specification", columnDefinition = "json", nullable = false)
    @NotBlank(message = "Specification is mandatory")
    public String specification;

    // --------------------------------------------------
    // Constructors
    // --------------------------------------------------

    /**
     * Default constructor required by JPA.
     * Initializes the reservation record with PENDING status.
     */
    public OrderRequest() {
        this.reservationRecord = new ReservationRecord();
    }

    /**
     * Convenience constructor to create a new request entity.
     *
     * @param orderNumber   The order ID
     * @param storeCode     The store code
     * @param specification The JSON payload
     */
    public OrderRequest(String orderNumber, String storeCode, String specification) {
        this.orderNumber = orderNumber;
        this.storeCode = storeCode;
        this.specification = specification;
        // Initialize the reservation record with default PENDING status
        this.reservationRecord = new ReservationRecord();
    }

    // --------------------------------------------------
    // Helpers / Panache
    // --------------------------------------------------

    /**
     * Finds pending requests for a specific store.
     *
     * @param storeCode The store code to filter by.
     * @return A list of pending OrderRequests.
     */
    public static List<OrderRequest> findPendingByStore(String storeCode) {
        return list("storeCode = ?1 and reservationRecord.status = ?2", storeCode, ReservationRecord.ReservationStatus.PENDING);
    }

    /**
     * Assigns a worker to this request by updating the reservation record.
     *
     * @param workerId  The ID of the worker taking the task.
     * @param hostName  The hostname of the worker.
     * @param processId The process ID of the worker.
     */
    public void startProcessing(String workerId, String hostName, String processId) {
        if (this.reservationRecord == null) {
            this.reservationRecord = new ReservationRecord();
        }
        this.reservationRecord.status = ReservationRecord.ReservationStatus.PROCESSING;
    }

    /**
     * Reserves a batch of order requests for processing.
     * <p>
     * Delegates to {@link ReservationRecord#reserveBatch}, passing all entity-specific
     * context parameters (table name, column names, record accessor).
     *
     * @param extraWhereClause An additional SQL WHERE clause to filter the order requests to reserve.
     *                         This allows for flexible reservation strategies (e.g., by store, priority).
     * @return A list of IDs of the reserved order requests.
     */
    public static List<Long> reserveBatch(long duration, int batchSize, String extraWhereClause) {
        return ReservationRecord.reserveBatch(
                Panache.getEntityManager(),
                OrderRequest.class,
                "order_requests",                 // Specific Table Name
                "reservation_status",                     // Specific Status Column
                "reservation_leasing",                  // Specific Leasing Column
                "reservation_consideration",          // Specific Consideration Instant Column
                (f) -> f.reservationRecord,    // Specific Accessor to the embedded record
                duration,
                batchSize,
                extraWhereClause
        );
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum based on the request's business fields.
     * Used by {@link BaseEntity} to detect data changes.
     *
     * @return An integer hash representing the request's state.
     */
    @Override
    public int getChecksum() {
        int recordHash = (reservationRecord == null) ? 0 : reservationRecord.hashCode();
        return Objects.hash(
                orderNumber,
                storeCode,
                recordHash, // Includes status and worker info
                specification
        );
    }
}