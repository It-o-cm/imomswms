package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="quality_cases")
public class QualityCase extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.order.Product product;
 @ManyToOne(fetch=FetchType.LAZY) public ManufacturerLot lot;
 @ManyToOne(fetch=FetchType.LAZY) public ProductSerial serial;
 @Column(nullable=false,length=20) public String kind,state="ACTIVE";
 @Column(nullable=false,length=2000) public String reason;
 @Column(nullable=false,length=100) public String createdBy;
 @Column(length=100) public String releasedBy;
 @Column(length=2000) public String releaseReason;
 public boolean includeUnknownLots=true;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
