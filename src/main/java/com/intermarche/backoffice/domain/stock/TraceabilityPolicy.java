package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="traceability_policies",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","product_id","context"}))
public class TraceabilityPolicy extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.order.Product product;
 @Column(nullable=false,length=30) public String context;
 public boolean lotRequired,serialRequired,dateRequired;
 @Column(nullable=false,length=10) public String allocationMethod="FEFO";
 public int minimumRemainingDays;
 public boolean bestBeforeStrict;
 @Column(length=60) public String hazardClass,separationGroup;
 @Column(length=600) public String incompatibleHazards;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
