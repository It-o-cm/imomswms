package com.intermarche.backoffice.domain.wms.handling;

import com.intermarche.backoffice.domain.catering.FabricationStation;
import com.intermarche.backoffice.domain.executing.FabricationExecution;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A fabrication handling order (§ 5, F6, F8): one station, one day, one or more
 * fabrication lines.
 * <p>
 * Discriminator value {@code FABRICATION}, code prefix {@code FAB}. A fabrication
 * order is first a proposal ({@code proposalState = PROPOSED}); it engages nothing
 * and is invisible to operators until the manager validates it ({@code VALIDATED}).
 */
@Entity
@DiscriminatorValue("FABRICATION")
public class FabricationOrder extends BaseHandlingOrder {

    /** Proposal state of a fabrication order. */
    public enum FabricationProposalState {
        /** Proposed by the server; recomputed freely, engages no stock. */
        PROPOSED,
        /** Validated by the manager (or by the server under autoValidateProposals). */
        VALIDATED
    }

    /** The station this order is fabricated at. */
    @ManyToOne
    @JoinColumn(name = "station_id")
    public FabricationStation station;

    /** The production day. */
    @Column(name = "productionDate")
    public LocalDate productionDate;

    /** Proposal state. */
    @Enumerated(EnumType.STRING)
    @Column(name = "proposalState", length = 20)
    public FabricationProposalState proposalState = FabricationProposalState.PROPOSED;

    /** Employee proposed for this order (before validation). */
    @ManyToOne
    @JoinColumn(name = "proposedEmployee_id")
    public Employee proposedEmployee;

    /** Total planned minutes, rounded up to the minute. */
    @Column(name = "plannedMinutes")
    public Integer plannedMinutes;

    /**
     * Returns the code prefix for fabrication orders.
     *
     * @return the string {@code "FAB"}.
     */
    @Override
    public String getPrefix() {
        return "FAB";
    }

    /**
     * Creates the fabrication execution for this order.
     *
     * @param employee the employee performing the execution.
     * @return a new {@link FabricationExecution} linked to this order.
     */
    @Override
    public HandlingExecution createExecution(Employee employee) {
        return new FabricationExecution(this, employee);
    }

    /**
     * Computes the checksum, chaining the base order fields with the fabrication fields.
     *
     * @return an integer checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                super.getChecksum(),
                station != null ? station.id : null,
                productionDate,
                proposalState,
                proposedEmployee != null ? proposedEmployee.badgeId : null,
                plannedMinutes);
    }
}
