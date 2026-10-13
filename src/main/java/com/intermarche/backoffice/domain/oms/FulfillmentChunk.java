package com.intermarche.backoffice.domain.oms;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Entity representing a logical grouping of items within a Fulfillment.
 * <p>
 * Chunks are used to organize the picking or packing process (e.g., by temperature zone
 * such as "FRESH", "DRY", "FROZEN", or by physical location).
 * <p>
 * <b>Relationships:</b>
 * <ul>
 *   <li>Does NOT hold a reference to the parent {@link Fulfillment} (Unidirectional).</li>
 *   <li>Contains a unidirectional One-to-Many relationship with {@link FulfillmentLine}.
 *       The lines do not hold a reference to this chunk.</li>
 * </ul>
 * <p>
 * <b>Performance Optimization:</b>
 * This entity uses {@link Set} for the {@code lines} collection.
 * This is required to support deep fetching (Fulfillment -> Chunk -> Line) via JOIN FETCH
 * in a single query, avoiding Hibernate's {@code MultipleBagFetchException}.
 * <p>
 * Extends {@link BaseEntity} for ID generation, versioning, and auditing.
 */
@Entity
@Table(name = "fulfillment_chunks",
        indexes = {
                @Index(name = "idx_chunk_type", columnList = "type"),
                @Index(name = "idx_chunk_delivery_date", columnList = "delivery_date")
        }
)
public class FulfillmentChunk extends BaseEntity {

    /**
     * Indicates that the chunk is to be processed.
     */
    public static final String PENDING = "PENDING";

    /**
     * Indicates that the chunk is ready for the picking process.
     */
    public static final String READY = "READY";

    // --------------------------------------------------
    // Chunk Details
    // --------------------------------------------------

    /**
     * The type or category of the chunk (e.g., "PENDING", "READY", "WAITING").
     */
    @Column(name = "type", nullable = false, length = 50)
    @NotBlank(message = "Chunk type is mandatory")
    public String type;

    /**
     * Label indicating the origin of the chunk (e.g., "Default", "ProductGrouping", "ZoneGrouping").
     * <p>
     * Set by the FulfillerFactory that created this chunk.
     */
    @Column(name = "label", length = 50)
    public String label;

    /**
     * The scheduled delivery date and time for the items in this chunk.
     * <p>
     * This field is used to prioritize chunks for preparation or to group deliveries
     * by time slots. Can be null if the delivery is not scheduled.
     */
    @Column(name = "delivery_date")
    public LocalDateTime deliveryDate;

    /**
     * The building (shop or warehouse) associated with this chunk.
     * <p>
     * This is a reference to the physical location where the items in this chunk are stored
     * or from which they will be picked. It can be null if the chunk is not yet assigned
     * to a specific location.
     */
    @ManyToOne
    @JoinColumn(name = "building_id")
    public Building building = null;

    // --------------------------------------------------
    // Relations
    // --------------------------------------------------

    /**
     * Read-only reference to the parent Fulfillment.
     * <p>
     * Mapped to the same foreign key column used by the owning side ({@link Fulfillment#chunks}).
     * This field is strictly for querying purposes (JPQL) to filter chunks by their parent context.
     * It is not used for persistence (insertable=false, updatable=false).
     */
    @ManyToOne
    @JoinColumn(name = "fulfillment_id", insertable = false, updatable = false)
    public Fulfillment fulfillment;

    /**
     * The list of lines contained in this chunk.
     * <p>
     * <b>Unidirectional:</b> This entity holds the list, but {@link FulfillmentLine} does not
     * contain a reference to this chunk.
     * <p>
     * <b>Data Structure:</b> Uses a {@link Set}.
     * This allows Hibernate to fetch this collection alongside the parent Fulfillment's collection
     * without encountering {@code MultipleBagFetchException}.
     * <p>
     * <b>Persistence:</b> A {@link JoinTable} is used to map the association because the child
     * entity does not define a back-reference.
     * Cascade ALL ensures that persisting a chunk also persists its lines.
     * Orphan removal ensures that removing a line from this set deletes it from the database.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "chunk_id", foreignKey = @ForeignKey(name = "fk_chunk_fulfillmentline"))
    public Set<FulfillmentLine> lines = new HashSet<>();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Helper method to add a {@link FulfillmentLine} to this chunk.
     * <p>
     * Maintains the collection integrity. Note that because the relationship
     * is unidirectional, we do not set a parent reference on the line.
     *
     * @param line The FulfillmentLine to add.
     */
    public void addLine(FulfillmentLine line) {
        this.lines.add(line);
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum representing the current state of the chunk.
     * <p>
     * The checksum is based on the chunk type, label, delivery date, and the set of lines.
     * Including these fields ensures that any modification invalidates this chunk's checksum.
     *
     * @return An integer hash representing the chunk's state.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                type,
                label,
                deliveryDate,
                building != null ? building.id : null);
    }

    /**
     * Retrieves the Store associated with a specific FulfillmentChunk.
     * <p>
     * Navigates the graph: Chunk -> Fulfillment -> Order -> Store.
     *
     * @param chunkId The ID of the chunk.
     * @return A list containing the associated Store (0 or 1 element), or an empty list if not found.
     */
    public static Store findStoreByChunkId(Long chunkId) {
        if (chunkId == null) {
            return null;
        }
        String jpql = "SELECT f.order.store FROM FulfillmentChunk c " +
                "JOIN c.fulfillment f " +
                "WHERE c.id = ?1";
        return find(jpql, chunkId).firstResult();
    }

    /**
     * Retrieves the Building associated with a specific FulfillmentChunk.
     * <p>
     * Uses the direct relationship defined on the chunk.
     *
     * @param chunkId The ID of the chunk.
     * @return A list containing the associated Building (0 or 1 element), or an empty list if not found.
     */
    public static Building findBuildingByChunkId(Long chunkId) {
        if (chunkId == null) {
            return null;
        }
        String jpql = "SELECT c.building FROM FulfillmentChunk c WHERE c.id = ?1";
        return find(jpql, chunkId).firstResult();
    }

    /**
     * Determines the common location specification for all the lines in a given {@link FulfillmentChunk}.
     * This is achieved by analyzing each line's associated location and identifying a shared specification,
     * if one exists.
     *
     * @param chunk the {@link FulfillmentChunk} whose lines' locations will be analyzed
     * @return the common {@link BaseLocation.LocationSpecification} shared by all lines in the chunk,
     *         or null if no common specification exists
     */
    public static BaseLocation.LocationSpecification findCommonSpecification(FulfillmentChunk chunk) {
        List<? extends BaseLocation> locations = chunk.lines.stream().map(l -> l.location).toList();
        return ShelfLocation.findCommonSpecification(locations);
    }
}