package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.*;
@Entity @Table(name="field_approvals",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","requester","requestKey"}))
public class SensitiveApproval extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public FieldIncident incident;
 @Column(length=100,nullable=false) public String requester;
 @Column(length=64,nullable=false) public String requestKey;
 @Column(length=64,nullable=false) public String commandHash;
 @Column(columnDefinition="TEXT",nullable=false) public String commandPayload;
 @Column(length=64,nullable=false) public String orderRevision;
 @Column(length=20,nullable=false) public String state="PENDING";
 @Column(length=100) public String decidedBy;
 @Column(length=500) public String decisionReason;
 @Column(nullable=false) public java.time.LocalDateTime expiresAt;
 @Column(length=100) public String appliedRequestKey;
 @Column(columnDefinition="TEXT") public String result;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
