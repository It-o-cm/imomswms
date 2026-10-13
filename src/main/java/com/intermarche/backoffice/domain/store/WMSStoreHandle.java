package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.ReservationRecord;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Entity representing a handle or lock for a Store within the WMS (Warehouse Management System).
 * <p>
 * This entity associates a specific {@link Store} with a {@link ReservationRecord}.
 * It is typically used to manage the processing status of a store (e.g., PENDING, PROCESSING)
 * for background tasks such as stock synchronization or batch exports, ensuring that
 * only one worker process handles the store at a time.
 * <p>
 * <b>Constraints:</b>
 * <ul>
 *   <li>Unidirectional relationship to {@link Store}.</li>
 *   <li>Unique relationship: One active WMS handle per Store.</li>
 *   <li>Contains an embedded {@link ReservationRecord} for status and leasing management.</li>
 * </ul>
 */
@Entity
@Table(name = "wms_store_handles",
        uniqueConstraints = @UniqueConstraint(columnNames = "store_id"),
        indexes = {
                @Index(name = "idx_wms_handle_store", columnList = "store_id"),
                @Index(name = "idx_wms_handle_status", columnList = "reservation_status")
        }
)
public class WMSStoreHandle extends BaseEntity {

    // --------------------------------------------------
    // Relationships
    // --------------------------------------------------

    /**
     * The Store associated with this WMS Handle.
     * <b>Mandatory & Unique:</b> Ensures one handle per store.
     */
    @ManyToOne
    @JoinColumn(name = "store_id", nullable = false, unique = true, foreignKey = @ForeignKey(name = "fk_wms_handle_store"))
    public Store store;

    // --------------------------------------------------
    // Embedded State
    // --------------------------------------------------

    /**
     * The reservation record containing the processing status (PENDING, PROCESSING, etc.)
     * and the leasing information (timestamp expiration).
     * <p>
     * Embedded to reuse the standard polling/consumer logic (Status + Lease).
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "status", column = @Column(name = "reservation_status")),
            @AttributeOverride(name = "leasing", column = @Column(name = "reservation_leasing")),
            @AttributeOverride(name = "consideration", column = @Column(name = "reservation_consideration"))
    })
    public ReservationRecord reservationRecord = new ReservationRecord();

    // --------------------------------------------------
    // Audit Trail
    // --------------------------------------------------

    /**
     * The date and time of the last successful reservation/lease acquisition for this store.
     */
    @Column(name = "last_reservation_date")
    public LocalDateTime lastReservationDate;

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Reserves a batch of store handles for processing.
     * <p>
     * Delegates to {@link ReservationRecord#reserveBatch}, passing all entity-specific
     * context parameters (table name, column names, record accessor).
     *
     * @param extraWhereClause An additional SQL WHERE clause to filter the handles to reserve.
     *                         This allows for flexible reservation strategies (e.g., by store, priority).
     * @return A list of IDs of the reserved handles.
     */
    public static List<Long> reserveBatch(long duration, int batchSize, String extraWhereClause) {
        return ReservationRecord.reserveBatch(
                Panache.getEntityManager(),
                WMSStoreHandle.class,
                "wms_store_handles",                 // Specific Table Name
                "reservation_status",                     // Specific Status Column
                "reservation_leasing",                  // Specific Leasing Column
                "reservation_consideration",          // Specific Consideration Instant Column
                (f) -> f.reservationRecord,    // Specific Accessor to the embedded record
                duration,
                batchSize,
                extraWhereClause
        );
    }

    /**
     * Helper method to start processing (acquire lease).
     * Updates the reservation record status and leasing, as well as the last reservation date.
     *
     * @param durationSeconds The duration of the lease.
     */
    public void startProcessing(long durationSeconds) {
        if (this.reservationRecord == null) {
            this.reservationRecord = new ReservationRecord();
        }
        this.reservationRecord.startLease(durationSeconds);
        // Update the timestamp of the last reservation
        this.lastReservationDate = LocalDateTime.now();
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    @Override
    public int getChecksum() {
        return Objects.hash(
                store != null ? store.id : null,
                reservationRecord,
                lastReservationDate
        );
    }
}