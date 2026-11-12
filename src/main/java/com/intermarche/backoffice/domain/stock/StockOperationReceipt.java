package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="stock_operation_receipts",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","actor","requestKey"}))
public class StockOperationReceipt extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.Store store;
 @Column(nullable=false,length=100) public String actor;
 @Column(nullable=false,length=64) public String requestKey,fingerprint;
 @Column(nullable=false,length=40) public String operation;
 @Column(nullable=false,columnDefinition="text") public String result;
 @Column(name="activity_state",length=20) public String activityState;
 @Column(name="activity_reason",length=300) public String activityReason;
 @Column(name="quantity_projection_version") public Integer quantityProjectionVersion;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
