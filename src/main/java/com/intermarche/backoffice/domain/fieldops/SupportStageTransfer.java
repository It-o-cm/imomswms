package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
@Entity @Table(name="support_stage_transfers")
public class SupportStageTransfer extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 public Long loadedToteId,parcelId;
 @Column(nullable=false,length=100) public String sender,recipient;
 @Column(nullable=false,length=30) public String step,state="PENDING";
 @Column(nullable=false,length=100) public String code;
 @Column(nullable=false,length=64) public String snapshot;
 @Column(nullable=false,length=500) public String reason;
 public java.time.LocalDateTime acceptedAt;
 @Override public int getChecksum(){return java.util.Objects.hash(id,state,recipient);}
}
