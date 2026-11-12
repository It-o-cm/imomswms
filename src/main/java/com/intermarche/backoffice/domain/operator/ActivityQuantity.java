package com.intermarche.backoffice.domain.operator;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
import java.math.BigDecimal;
/** Immutable quantities of a confirmed operation, not a running stock balance. */
@Entity @org.hibernate.annotations.Immutable
@Table(name="activity_quantities",uniqueConstraints=@UniqueConstraint(columnNames={"source","receipt_id","ordinal"}),indexes=@Index(columnList="store_id,actor,created_at"))
public class ActivityQuantity extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @Column(nullable=false,length=100) public String actor;
 @Column(nullable=false,length=12) public String source;
 @Column(name="receipt_id",nullable=false) public Long receiptId;
 @Column(nullable=false) public int ordinal;
 @Column(nullable=false,length=40) public String action;
 @Column(nullable=false,length=40) public String metric;
 @Column(nullable=false,length=8) public String unit;
 @Column(nullable=false,precision=19,scale=6) public BigDecimal quantity;
 public Long productId,lineId;
 @Override public int getChecksum(){return java.util.Objects.hash(source,receiptId,ordinal,quantity,unit,metric);}
}
