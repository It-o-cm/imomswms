package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.*;
@Entity @Table(name="field_incidents",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","reporter","requestKey"}))
public class FieldIncident extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @ManyToOne(fetch=FetchType.LAZY) public BaseHandlingOrder handlingOrder;
 @ManyToOne(fetch=FetchType.LAZY) public Product product;
 @ManyToOne(fetch=FetchType.LAZY) public BaseLocation location;
 @ManyToOne(fetch=FetchType.LAZY) public Buffer buffer;
 @Column(length=100,nullable=false) public String reporter;
 @Column(length=64,nullable=false) public String requestKey;
 @Column(length=64,nullable=false) public String fingerprint;
 @Column(length=30,nullable=false) public String kind;
 @Column(length=20,nullable=false) public String state="OPEN";
 @Column(length=120,nullable=false) public String title;
 @Column(length=2000,nullable=false) public String comment;
 @Column(length=300) public String observedAddress;
 @Column(length=100) public String owner;
 public Long shortageId;
 public Long replenishmentId;
 public Long linkedHandlingOrderId;
 @Column(precision=19,scale=6) public java.math.BigDecimal requestedQuantity;
 @Column(precision=19,scale=6) public java.math.BigDecimal announcedQuantity,observedQuantity;
 @Column(length=30) public String outcome;
 @Column(length=1000) public String nextAction;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.order.Product replacementProduct;
 @Column(precision=19,scale=6) public java.math.BigDecimal remainingQuantity;
 public java.time.LocalDateTime resolvedAt;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
