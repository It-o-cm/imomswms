package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
@Entity @Table(name="support_types",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","code"}))
public class SupportType extends BaseEntity {
 @Column(nullable=false) public long stockQuantity=0,reorderThreshold=0,reorderTarget=0;
 public enum Kind {TOTE,CARTON,PALLET,CART}
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @Column(nullable=false,length=30) public String code;
 @Column(nullable=false,length=100) public String label;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) public Kind kind;
 @Column(precision=12,scale=3) public java.math.BigDecimal maxWeightKg,maxVolumeLiters;
 @Column(nullable=false,length=80) public String allowedZones="AMBIENT,HOUSEHOLD";
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
