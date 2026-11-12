package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="stock_segments",uniqueConstraints=@UniqueConstraint(columnNames={"account_id","segmentKey"}),indexes=@Index(columnList="account_id,location_id"))
public class StockSegment extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public StockAccount account;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.BaseLocation location;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.fieldops.PhysicalSupport support;
 @ManyToOne(fetch=FetchType.LAZY) public ManufacturerLot lot;
 @ManyToOne(fetch=FetchType.LAZY) public ProductSerial serial;
 @Column(nullable=false,length=30) public String quality="UNASSESSED";
 @Column(nullable=false,length=64) public String segmentKey;
 public java.time.LocalDateTime receivedAt;
 @Override @PrePersist public void onCreate(){super.onCreate();if(receivedAt==null)receivedAt=com.intermarche.backoffice.domain.util.DateTimeProvider.now();}
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
