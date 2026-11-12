package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import jakarta.persistence.*;
@Entity @Table(name="mission_handovers",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","requester","requestKey"}))
public class MissionHandover extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public BaseHandlingOrder handlingOrder;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Employee source;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Employee target;
 @Column(nullable=false,length=100) public String requester;
 @Column(nullable=false,length=64) public String requestKey,fingerprint,orderRevision;
 @Column(nullable=false,length=500) public String reason;
 @Column(nullable=false,length=20) public String state="OFFERED";
 @Column(nullable=false) public java.time.LocalDateTime expiresAt;
 @Column(nullable=false) public java.time.LocalDateTime readyAt;
 @Column(columnDefinition="text") public String result;
 public Long cartId;
 @Column(columnDefinition="text") public String orderSnapshots;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
