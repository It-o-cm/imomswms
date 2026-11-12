package com.intermarche.backoffice.domain.inventory;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.handling.InventoryOrder;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity @Table(name="inventory_count_sessions",uniqueConstraints=@UniqueConstraint(columnNames={"handlingOrder_id","roundNumber"}))
public class InventoryCountSession extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public InventoryOrder handlingOrder;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Employee employee;
 @ManyToOne(fetch=FetchType.LAZY) public InventoryCountSession previousRound;
 @Column(nullable=false) public int roundNumber=1;
 public long revisionNumber;
 @Column(nullable=false,length=30) public String state="COUNTING";
 @Column(nullable=false,length=30) public String movementMode="RECONCILE_MOVEMENTS";
 @Column(nullable=false) public LocalDateTime referenceAt;
 public LocalDateTime submittedAt,approvedAt,appliedAt;
 @Column(length=100) public String approvedBy;
 @Column(length=2000) public String approvalReason;
 @Column(nullable=false,columnDefinition="text") public String policySnapshot;
 @Column(columnDefinition="text") public String approvedSnapshot;
 @Override public int getChecksum(){return java.util.Objects.hash(id,state,roundNumber);}
}
