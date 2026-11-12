package com.intermarche.backoffice.domain.workforce;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Employee;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;
@Entity @Table(name="employee_planning_entries",indexes=@Index(columnList="employee_id,startsAt,endsAt"))
public class PlanningEntry extends BaseEntity {
    @ManyToOne(optional=false) public Employee employee;
    public String kind,title,requiredRole;
    @ManyToOne public com.intermarche.backoffice.domain.store.Building building;
    public boolean cancelled=false;
    @Column(nullable=false) public LocalDateTime startsAt,endsAt;
    @Override public int getChecksum(){return Objects.hash(employee,kind,title,requiredRole,building,startsAt,endsAt);}
}
