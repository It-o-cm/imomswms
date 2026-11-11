package com.intermarche.backoffice.domain.catering;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Idempotent receipt of a received amendment or cancellation (§ 5, F18, F19).
 * <p>
 * {@code requestId} is unique per principal; {@code fingerprint} lets a replay be
 * recognised as identical or conflicting.
 */
@Entity
@Table(name = "catering_amendments",
        uniqueConstraints = @UniqueConstraint(name = "uk_amendment_principal_request",
                columnNames = {"principal", "requestId"}))
public class CateringAmendment extends BaseEntity {

    /** Kind of received request. */
    public enum CateringAmendmentKind {
        /** A change request (F18). */
        AMENDMENT,
        /** A cancellation request (F19). */
        CANCELLATION
    }

    /** The store the request targets. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "store_id")
    public Store store;

    /** The order the request targets. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "order_id")
    public Order order;

    /** Calling principal (idempotency scope). */
    @Column(nullable = false, length = 100)
    public String principal;

    /** Request identifier, unique per principal. */
    @Column(nullable = false, length = 100)
    public String requestId;

    /** Canonical fingerprint of the request payload. */
    @Column(length = 64)
    public String fingerprint;

    /** Kind of request. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public CateringAmendmentKind kind;

    /** Raw request payload JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    public String payload;

    /** Outcome of the request. */
    @Column(length = 40)
    public String outcome;

    /** Result code of the request. */
    @Column(length = 60)
    public String code;

    /** Instant the request was applied. */
    @Column(name = "appliedAt")
    public LocalDateTime appliedAt;

    /**
     * Computes the checksum from the receipt's business fields.
     *
     * @return an integer checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                store != null ? store.id : null,
                order != null ? order.id : null,
                principal, requestId, fingerprint, kind, payload, outcome, code, appliedAt);
    }
}
