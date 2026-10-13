package com.intermarche.backoffice.domain.wms;

import com.intermarche.backoffice.domain.store.Store;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Entity representing a sequence counter for Handling Orders.
 * <p>
 * This entity manages the incremental numbering of order codes in a thread-safe manner.
 * It ensures that each combination of {@link Store}, order label (type), and date
 * has a dedicated counter that persists across transactions.
 * <p>
 * The composite primary key ({@link Store} + Label + Date) guarantees uniqueness of the counter
 * and supports daily reset functionality.
 */
@Entity
@Table(name = "order_sequences",
        uniqueConstraints = @UniqueConstraint(columnNames = {"store_id", "label", "seq_date"})
)
@IdClass(HandlingOrderSequence.SequenceId.class)
public class HandlingOrderSequence extends PanacheEntityBase {

    /**
     * The store to which this sequence belongs.
     * Part of the composite primary key.
     */
    @Id
    @ManyToOne
    @JoinColumn(name = "store_id")
    public Store store;

    /**
     * The label or type of the order (e.g., "GRP", "PAK").
     * Part of the composite primary key.
     */
    @Id
    @Column(name = "label")
    public String label;

    /**
     * The date of the sequence.
     * Allows for daily reset of the counter.
     * Part of the composite primary key.
     */
    @Id
    @Column(name = "seq_date")
    public LocalDate date;

    /**
     * The next value to be used for generating a code.
     * This value is incremented atomically every time a new order code is generated.
     */
    @Column(name = "next_val")
    public Long nextVal = 1L;

    /**
     * Default constructor required by JPA.
     */
    public HandlingOrderSequence() {
    }

    /**
     * Constructs a new sequence entry for a specific store, label, and date.
     *
     * @param store The store entity.
     * @param label The order type label.
     * @param date  The date for this sequence.
     */
    public HandlingOrderSequence(Store store, String label, LocalDate date) {
        this.store = store;
        this.label = label;
        this.date = date;
        this.nextVal = 1L;
    }

    // -------------------------------------------------------
    // BUSINESS LOGIC
    // -------------------------------------------------------

    /**
     * Generates the next unique human-readable business code for a handling order.
     * <p>
     * This method is designed to handle high volumes (e.g., 1000 orders/day per store).
     * It retrieves or creates the sequence for the current date and store,
     * increments the counter, and formats the result.
     * <p>
     * Format: {@code DDMM-PREFIX-####} (e.g., 2710-PAK-0042).
     * <p>
     * <b>Transactionality:</b> This method must be called within an active transaction
     * to ensure atomicity of the read-increment-write operation.
     *
     * @param store  The store for which the code is generated. Must not be null.
     * @param prefix The prefix identifying the order type (e.g., "PAK", "GRP"). Must not be null.
     * @return A unique, formatted business code string.
     * @throws IllegalArgumentException if store or prefix is null.
     */
    public static String getNextCode(Store store, String prefix) {
        if (store == null || prefix == null) {
            throw new IllegalArgumentException("Store and Prefix cannot be null");
        }

        LocalDate today = LocalDate.now();

        // Retrieve existing sequence for today
        HandlingOrderSequence seq = HandlingOrderSequence.find(
                "store = ?1 and label = ?2 and date = ?3",
                store, prefix, today
        ).firstResult();

        long currentVal;

        if (seq == null) {
            // No sequence for today, create a new one starting at 1
            seq = new HandlingOrderSequence(store, prefix, today);
            seq.persist();
            currentVal = 1L;
        } else {
            // Sequence exists, increment the value
            seq.nextVal++;
            currentVal = seq.nextVal;
        }

        // Format: DDMM-PREFIX-0000
        String datePart = today.format(DateTimeFormatter.ofPattern("ddMM"));
        return String.format("%s-%s-%04d", datePart, prefix.toUpperCase(), currentVal);
    }

    // -------------------------------------------------------
    // COMPOSITE KEY CLASS
    // -------------------------------------------------------

    /**
     * Composite ID class for {@link HandlingOrderSequence}.
     * <p>
     * Defines the structure of the primary key composed of Store ID, Label, and Date.
     */
    public static class SequenceId implements Serializable {

        /**
         * The ID of the store.
         */
        public Long store;

        /**
         * The label of the order type.
         */
        public String label;

        /**
         * The date of the sequence.
         */
        public LocalDate date;

        /**
         * Default constructor required by JPA.
         */
        public SequenceId() {
        }

        /**
         * Constructs a new Sequence ID.
         *
         * @param store The ID of the store.
         * @param label The label of the order type.
         * @param date  The date of the sequence.
         */
        public SequenceId(Long store, String label, LocalDate date) {
            this.store = store;
            this.label = label;
            this.date = date;
        }

        /**
         * Compares this {@code SequenceId} object to another object for equality.
         * Two {@code SequenceId} objects are considered equal if their {@code store}, {@code label},
         * and {@code date} fields are all equal.
         *
         * @param o The object to compare with this {@code SequenceId} for equality.
         *          It could be another {@code SequenceId} or any other object.
         * @return {@code true} if the specified object is equal to this {@code SequenceId};
         *         {@code false} otherwise.
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SequenceId that = (SequenceId) o;
            return Objects.equals(store, that.store)
                    && Objects.equals(label, that.label)
                    && Objects.equals(date, that.date);
        }

        /**
         * Computes the hash code for this {@code SequenceId} object based on its {@code store}, {@code label}, and {@code date}.
         *
         * @return An integer hash code value derived from the fields {@code store}, {@code label}, and {@code date}.
         */
        @Override
        public int hashCode() {
            return Objects.hash(store, label, date);
        }
    }
}