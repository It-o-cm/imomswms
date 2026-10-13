package com.intermarche.backoffice.domain.handling;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;

/**
 * Abstract base class representing the execution of a {@link BaseHandlingOrder}.
 * <p>
 * Defines the common link between an Order and the {@link Employee} performing it.
 * Specific details (like the list of totes) are defined in subclasses.
 * <p>
 * This class extends {@link BaseEntity} to inherit ID, versioning, and audit fields.
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "execution_type", discriminatorType = DiscriminatorType.STRING)
@Table(name = "handling_executions",
        indexes = {
                @Index(name = "idx_exec_order", columnList = "handling_order_id"),
                @Index(name = "idx_exec_employee", columnList = "employee_id")
        }
)
public abstract class HandlingExecution extends BaseEntity {

    /**
     * Enumeration representing the lifecycle status of the execution.
     */
    public enum ExecutionStatus {
        /**
         * The execution has been created but not yet started.
         */
        PENDING,
        /**
         * The execution is currently in progress (default state at initialization).
         */
        IN_PROGRESS,
        /**
         * The execution has been successfully completed.
         */
        DONE,
        /**
         * The execution has been cancelled.
         */
        CANCELLED
    }

    // --------------------------------------------------
    // Relationships
    // --------------------------------------------------

    /**
     * The Handling Order being executed.
     * <p>
     * Links the execution back to its source definition.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handling_order_id", nullable = false, foreignKey = @ForeignKey(name = "fk_exec_order"))
    @NotNull(message = "Handling Order is mandatory")
    public BaseHandlingOrder handlingOrder;

    /**
     * The Employee assigned to this execution.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false, foreignKey = @ForeignKey(name = "fk_exec_employee"))
    @NotNull(message = "Employee is mandatory")
    public Employee employee;

    // --------------------------------------------------
    // Status
    // --------------------------------------------------

    /**
     * The current status of the execution.
     * Defaults to IN_PROGRESS upon creation.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", nullable = false, length = 20)
    public ExecutionStatus status = ExecutionStatus.IN_PROGRESS;

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum based on the common execution attributes.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                handlingOrder != null ? handlingOrder.id : null,
                employee != null ? employee.id : null,
                status
        );
    }
}