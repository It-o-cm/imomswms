package com.intermarche.backoffice.domain.catering;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Employee;
import jakarta.persistence.*;
import java.util.Objects;

/**
 * An employee qualified to work at a {@link FabricationStation} (§ 5).
 */
@Entity
@Table(name = "fabrication_station_employees",
        uniqueConstraints = @UniqueConstraint(name = "uk_station_employee", columnNames = {"station_id", "employee_id"}))
public class FabricationStationEmployee extends BaseEntity {

    /** The station the employee is qualified for. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "station_id")
    public FabricationStation station;

    /** The qualified employee. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "employee_id")
    public Employee employee;

    /**
     * Computes the checksum from the station and employee identities.
     *
     * @return an integer checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                station != null ? station.id : null,
                employee != null ? employee.id : null);
    }
}
