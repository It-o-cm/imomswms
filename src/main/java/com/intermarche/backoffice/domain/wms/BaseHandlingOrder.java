package com.intermarche.backoffice.domain.wms;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Abstract base class for all Handling Orders (e.g., Picking, Packing, Dispatch).
 * <p>
 * This entity defines the common structure for operational orders assigned to stores.
 * It uses a SINGLE_TABLE inheritance strategy.
 * <p>
 * <b>Constraints:</b>
 * <ul>
 *   <li>Parent {@link Store} is Mandatory (Not Null).</li>
 *   <li>{@link HandlingOrderStatus} defaults to OPEN.</li>
 *   <li>{@link Employee} (Assignee) is Optional.</li>
 * </ul>
 */
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "type", discriminatorType = DiscriminatorType.STRING)
@Table(name = "handling_orders",
        indexes = {
                @Index(name = "idx_handling_store", columnList = "store_id"),
                @Index(name = "idx_handling_status", columnList = "status"),
                @Index(name = "idx_handling_start", columnList = "requested_start"),
                @Index(name = "idx_handling_code", columnList = "code", unique = true)
        }
)
public abstract class BaseHandlingOrder extends BaseEntity {

    /**
     * Enumeration representing the lifecycle status of a Handling Order.
     */
    public enum HandlingOrderStatus {
        OPEN, SCHEDULED, PROCESSING, DONE, CANCELLED, FAILED
    }

    /**
     * Current status of the handling order.
     * Defaults to {@link HandlingOrderStatus#OPEN} upon creation.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public HandlingOrderStatus status = HandlingOrderStatus.OPEN;

    /**
     * The Store where this handling operation is taking place.
     * <b>Mandatory:</b> A Handling Order cannot exist without being associated with a Store.
     */
    @ManyToOne
    @JoinColumn(name = "store_id", nullable = false)
    public Store store;

    /**
     * The employee assigned to execute this order.
     * <p>
     * This field is optional; an order can be unassigned (Open status) before being claimed.
     */
    @ManyToOne
    @JoinColumn(name = "assignee_id")
    public Employee assignee;

    /**
     * The requested start time for the handling operation.
     */
    @Column(name = "requested_start")
    public LocalDateTime requestedStart;

    /**
     * The requested end time for the handling operation.
     */
    @Column(name = "requested_end")
    public LocalDateTime requestedEnd;

    /**
     * Human-readable unique business code for this order.
     * <p>
     * Format: {@code DDMM-PREFIX-####} (e.g., 2710-PAK-0042).
     * This code is generated upon creation and is designed for easy communication
     * between operators and tracking in high-volume environments.
     */
    @Column(name = "code", unique = true, length = 15)
    public String code;

    /**
     * Returns the prefix used for generating the business code.
     * <p>
     * Each subclass must define its specific prefix (e.g., "PAK" for PackingOrder).
     *
     * @return The string prefix for the order type.
     */
    public abstract String getPrefix();

    /**
     * Creates a new {@link HandlingExecution} for this handling order.
     * <p>
     * Subclasses may override this method to provide specific execution types.
     *
     * @param employee the {@link Employee} assigned to execute this order.
     * @return a new {@link HandlingExecution} instance linked to this order and the employee.
     */
    public abstract HandlingExecution createExecution(Employee employee);

    /**
     * Computes a checksum for this HandlingOrder based on its key attributes.
     *
     * @return An integer hash representing the current state of the order.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                status,
                store,
                requestedStart,
                requestedEnd,
                assignee != null ? assignee.badgeId : null
        );
    }
}