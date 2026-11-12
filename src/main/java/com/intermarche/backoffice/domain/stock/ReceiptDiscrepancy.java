package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;import jakarta.persistence.*;import java.math.BigDecimal;
@Entity @Table(name="receipt_discrepancies")
public class ReceiptDiscrepancy extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder handlingOrder;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.order.Product product;
 @ManyToOne(fetch=FetchType.LAZY) public ExpectedSupply expectedSupply;
 @ManyToOne(fetch=FetchType.LAZY) public ExpectedSupply acceptedSupply;
 @Column(nullable=false,length=20) public String kind;
 @Column(nullable=false,length=20) public String state="REPORTED";
 @Column(nullable=false,precision=19,scale=6) public BigDecimal quantity;
 @Column(length=300) public String documentReference;
 @Column(nullable=false,length=2000) public String comment;
 @Column(nullable=false,length=100) public String reportedBy;
 @Column(length=100) public String decidedBy;
 @Column(length=2000) public String decisionReason;
 public java.time.LocalDateTime decidedAt;
 public long revisionNumber;
 @Override public int getChecksum(){return java.util.Objects.hash(id,state,revisionNumber);}
}
