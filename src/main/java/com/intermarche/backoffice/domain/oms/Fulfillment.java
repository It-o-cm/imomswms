package com.intermarche.backoffice.domain.oms;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.order.Order;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Entity representing the fulfillment process for an Order.
 * <p>
 * Acts as the aggregate root for the fulfillment operation. It tracks the status
 * of the preparation (e.g., picking, packing, ready) and groups the work into
 * {@link FulfillmentChunk}s.
 * <p>
 * <b>Relationships:</b>
 * <ul>
 *   <li>Maintains a direct link to the {@link Order}.</li>
 *   <li>Contains a unidirectional One-to-Many relationship with {@link FulfillmentChunk}.
 *       The chunks do not hold a reference back to this fulfillment.</li>
 * </ul>
 * <p>
 * <b>Performance Optimization:</b>
 * This entity uses {@link Set} for the {@code chunks} collection instead of {@link java.util.List}.
 * This is necessary to prevent Hibernate's {@code MultipleBagFetchException} when performing
 * optimized queries that fetch nested relationships (Fulfillment -> Chunk -> Line) in a single SQL round-trip.
 * <p>
 * Extends {@link BaseEntity} for ID generation, versioning, and auditing.
 */
@Entity
@Table(name = "fulfillments",
        indexes = {
                @Index(name = "idx_fulfillment_order", columnList = "order_id", unique = true),
                @Index(name = "idx_fulfillment_reservation_status", columnList = "reservation_status")
        }
)
public class Fulfillment extends BaseEntity {


    // --------------------------------------------------
    // Relationships
    // --------------------------------------------------

    /**
     * The Order being fulfilled.
     * <p>
     * This represents the link between the operational fulfillment data and the
     * commercial order data. The relationship is typically 1-to-1 (one order results
     * in one fulfillment process), enforced by the unique constraint on the index.
     */
    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false, unique = true, foreignKey = @ForeignKey(name = "fk_fulfillment_order"))
    public Order order;

    /**
     * The list of chunks that make up this fulfillment.
     * <p>
     * <b>Unidirectional:</b> This entity holds the list, but {@link FulfillmentChunk} does not
     * contain a reference to this fulfillment.
     * <p>
     * <b>Data Structure:</b> Uses a {@link Set} instead of a {@link java.util.List}.
     * This allows Hibernate to fetch this collection along with nested collections (like lines)
     * using a JOIN FETCH strategy without throwing a {@code MultipleBagFetchException}.
     * <p>
     * <b>Persistence:</b> A {@link JoinTable} is used to map the association in the database.
     * Cascade ALL ensures that persisting a fulfillment also persists its chunks.
     * Orphan removal ensures that removing a chunk from this set deletes it from the database.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "fulfillment_id", foreignKey = @ForeignKey(name = "fk_fulfillment_chunk"))
    public Set<FulfillmentChunk> chunks = new HashSet<>();

    // --------------------------------------------------
    // Reservation & Status
    // --------------------------------------------------

    /**
     * The reservation record containing the processing status, lease information,
     * and worker context.
     * <p>
     * This replaces the explicit status field previously managed at the entity level.
     * The status is now handled within this embedded object to support leasing and worker tracking.
     * <p>
     * Defaults to PENDING status upon instantiation.
     */
    @Embedded
    @AttributeOverrides({
            // Explicitly map columns to ensure clarity, though defaults match field names
            @AttributeOverride(name = "status", column = @Column(name = "reservation_status")),
            @AttributeOverride(name = "leasing", column = @Column(name = "reservation_leasing")),
            @AttributeOverride(name = "consideration", column = @Column(name = "reservation_consideration"))
    })
    public ReservationRecord reservationRecord = new ReservationRecord();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Helper method to add a {@link FulfillmentChunk} to this fulfillment.
     * <p>
     * Maintains the collection integrity. Note that because the relationship
     * is unidirectional, we do not set a parent reference on the chunk.
     *
     * @param chunk The FulfillmentChunk to add.
     */
    public void addChunk(FulfillmentChunk chunk) {
        this.chunks.add(chunk);
    }

    /**
     * Reserves a batch of fulfillments for processing.
     * <p>
     * Delegates to {@link ReservationRecord#reserveBatch}, passing all entity-specific
     * context parameters (table name, column names, record accessor).
     *
     * @param extraWhereClause An additional SQL WHERE clause to filter the fulfillments to reserve.
     *                         This allows for flexible reservation strategies (e.g., by store, priority).
     * @return A list of IDs of the reserved fulfillments.
     */
    public static List<Long> reserveBatch(long duration, int batchSize, String extraWhereClause) {
        return ReservationRecord.reserveBatch(
                Panache.getEntityManager(),
                Fulfillment.class,
                "fulfillments",                 // Specific Table Name
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
     * Calculates a checksum representing the current state of the fulfillment.
     * <p>
     * The checksum is based on the reservation record (status, leasing), the ID of the linked Order,
     * and the list of chunks. This allows for quick detection of changes in the fulfillment structure.
     *
     * @return An integer hash representing the fulfillment's state.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                reservationRecord,
                order != null ? order.id : null,
                chunks
        );
    }
}