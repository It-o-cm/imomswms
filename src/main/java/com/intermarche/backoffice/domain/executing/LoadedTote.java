package com.intermarche.backoffice.domain.executing;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.domain.store.Tote;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Entity representing the active usage session of a physical Tote.
 * <p>
 * A {@link LoadedTote} acts as a "Session" or "Instance" of a physical {@link Tote} during a specific operational
 * workflow (such as Picking, Grouping, or Delivery). It decouples the physical object (the bin)
 * from the state of its contents and its current status within a process.
 * <p>
 * <b>Key Concepts:</b>
 * <ul>
 *   <li><b>Exclusivity:</b> A physical {@link Tote} can only be associated with one {@link LoadedTote}
 *       in {@link Status#IN_USE} at any given time. This prevents two operators from picking into the same
 *       physical bin simultaneously.</li>
 *   <li><b>Lifecycle:</b> Starts as {@link Status#IN_USE} when created and transitions to
 *       {@link Status#DELIVERED} once the operation is complete.</li>
 *   <li><b>Buffering:</b> May be associated with a {@link Buffer} (staging area), though this is optional.</li>
 * </ul>
 * <p>
 * This class extends {@link BaseEntity} to inherit ID, versioning, and audit fields.
 */
@Entity
@Table(name = "loaded_totes",
        indexes = {
                @Index(name = "idx_loaded_tote_tote", columnList = "tote_id"),
                @Index(name = "idx_loaded_tote_buffer", columnList = "buffer_id"),
                @Index(name = "idx_loaded_tote_type", columnList = "storage_type")
        }
)
public class LoadedTote extends BaseEntity {

    /**
     * Enumeration representing the current lifecycle state of a LoadedTote.
     */
    public enum Status {
        /**
         * The tote is currently active and in use by an operational process (e.g., Picking or Grouping).
         * <p>
         * While in this state, the physical Tote is considered "locked" or "reserved". No other
         * process should attempt to create a new LoadedTote for the same physical Tote.
         */
        IN_USE,

        /**
         * The tote has been finalized, handed over, or delivered.
         * <p>
         * This state indicates that the operational session is closed. The physical Tote is now
         * available to be used for a new cycle (i.e., a new LoadedTote can be created).
         */
        DELIVERED
    }

    // --------------------------------------------------
    // Relationships
    // --------------------------------------------------

    /**
     * The physical Tote (the plastic bin) associated with this usage session.
     * <p>
     * This is a mandatory reference to the tangible object.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tote_id", nullable = false, foreignKey = @ForeignKey(name = "fk_loadedtote_tote"))
    @NotNull(message = "Tote is mandatory")
    public Tote tote;

    /**
     * The Buffer (staging area) where the tote is currently located or staged.
     * <p>
     * This field is optional. A LoadedTote may exist without a specific buffer assignment,
     * for instance, when it is first created or when it is in transit.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buffer_id", foreignKey = @ForeignKey(name = "fk_loadedtote_buffer"))
    public Buffer buffer;

    /**
     * The collection of {@link PickedItem}s currently contained within this tote.
     * <p>
     * <b>Bidirectional:</b> The inverse side of this relationship is {@link PickedItem#loadedTote}.
     * <p>
     * <b>Performance:</b> Uses a {@link Set} to allow deep fetching without triggering
     * Hibernate's {@code MultipleBagFetchException}.
     * <p>
     * <b>Cascading:</b> Changes to this collection (add/remove) are cascaded to the database.
     */
    @OneToMany(mappedBy = "loadedTote", cascade = CascadeType.ALL, orphanRemoval = true)
    public Set<PickedItem> items = new HashSet<>();

    // --------------------------------------------------
    // Details
    // --------------------------------------------------

    /**
     * The current status of this loaded tote session.
     * <p>
     * Defaults to {@link Status#IN_USE} upon creation to indicate active processing.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    public Status status = Status.IN_USE;

    /**
     * The temperature zone classification for the contents of this tote.
     * <p>
     * Determines the logistical handling requirements (e.g., ambient, chilled, or frozen).
     * This is a mandatory attribute to ensure proper storage and transport.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "storage_type", nullable = false, length = 20)
    @NotNull(message = "Storage type is mandatory")
    public TemperatureZone storageType;

    // --------------------------------------------------
    // Factory Method
    // --------------------------------------------------

    /**
     * Factory method to initialize the usage of a physical Tote for a new operation.
     * <p>
     * This method encapsulates the business rule ensuring the exclusivity of the physical Tote.
     * It verifies that the specified physical Tote is not currently associated with an
     * existing {@link LoadedTote} in the {@link Status#IN_USE} state.
     * <p>
     * If the Tote is available, this method creates a new {@link LoadedTote} instance,
     * sets its status to {@link Status#IN_USE}, and persists it to the database.
     * <p>
     * If the Tote is already in use, an exception is thrown to prevent conflicts.
     *
     * @param physicalTote The physical Tote entity to be used. Cannot be null.
     * @return The newly created and persisted {@link LoadedTote} entity.
     * @throws IllegalStateException if the physical Tote is already {@link Status#IN_USE} by another process.
     */
    public static LoadedTote useAnEmptyTote(Tote physicalTote) {
        // Verify that the physical Tote is not currently IN_USE by another active LoadedTote
        LoadedTote existing = find("tote = ?1 and status = ?2", physicalTote, Status.IN_USE).firstResult();
        // The Tote is available; proceed with creating a new usage session
        LoadedTote newLoad = new LoadedTote();
        newLoad.tote = physicalTote;
        newLoad.status = Status.IN_USE;
        newLoad.persist();
        return newLoad;
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Helper method to add a {@link PickedItem} to this loaded tote.
     * <p>
     * Maintains the integrity of the bidirectional relationship by setting the
     * {@code loadedTote} reference on the item as well as adding it to this collection.
     *
     * @param item The {@link PickedItem} to be added to this tote.
     */
    public void addItem(PickedItem item) {
        this.items.add(item);
        item.loadedTote = this;
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum representing the current state of the loaded tote.
     * <p>
     * The checksum is derived from the physical tote ID, buffer ID, status, storage type,
     * and the set of contained items. This is used for change detection and data integrity.
     *
     * @return An integer hash representing the loaded tote's state.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                tote != null ? tote.id : null,
                buffer != null ? buffer.id : null,
                status,
                storageType,
                items
        );
    }
}