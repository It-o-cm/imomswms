package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.*;
@Entity @Table(name="field_incident_history",uniqueConstraints=@UniqueConstraint(columnNames={"incident_id","eventKey"}))
public class IncidentHistory extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public FieldIncident incident;
 @Column(length=64,nullable=false) public String eventKey;
 @Column(length=64,nullable=false) public String fingerprint;
 @Column(length=100,nullable=false) public String actor;
 @Column(length=40,nullable=false) public String action;
 @Column(length=2000,nullable=false) public String detail;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
