package com.intermarche.backoffice.domain.workforce;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;
@Entity @Table(name="employee_positions",uniqueConstraints=@UniqueConstraint(columnNames="employee_id"))
public class EmployeePosition extends BaseEntity {
 @ManyToOne(optional=false) public Employee employee;
 @ManyToOne(optional=false) public Building building;
 @ManyToOne public Building origin;
 public LocalDateTime availableAt;
 @Override public int getChecksum(){return Objects.hash(employee,building,origin,availableAt);}
}
