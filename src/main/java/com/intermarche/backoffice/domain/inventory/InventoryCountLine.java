package com.intermarche.backoffice.domain.inventory;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Entity @Table(name="inventory_count_lines",uniqueConstraints=@UniqueConstraint(columnNames={"session_id","lineKey"}))
public class InventoryCountLine extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public InventoryCountSession session;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.BaseLocation location;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.order.Product product;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.stock.ManufacturerLot lot;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.stock.ProductSerial serial;
 @Column(nullable=false,length=64) public String lineKey;
 @Column(nullable=false,length=300) public String address;
 @Column(nullable=false,columnDefinition="text") public String sourceLocationIds="[]";
 @Column(nullable=false,precision=19,scale=6) public BigDecimal referenceQuantity=BigDecimal.ZERO;
 @Column(precision=19,scale=6) public BigDecimal observedQuantity;
 @Column(nullable=false,length=20) public String state="UNCOUNTED";
 @Column(length=20) public String observedToteCode;
 public LocalDateTime countedAt;
 @Column(length=2000) public String comment;
 @Override public int getChecksum(){return java.util.Objects.hash(id,state,observedQuantity,countedAt);}
}
