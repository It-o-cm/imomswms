package com.intermarche.backoffice.domain.operator;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
@Entity @Table(name="operator_diagnostics",uniqueConstraints=@UniqueConstraint(columnNames={"trackingId"}))
public class OperatorDiagnostic extends BaseEntity {
 @ManyToOne(optional=false) public Store store;
 @Column(nullable=false,length=100) public String principal;
 @Column(nullable=false,length=36) public String trackingId;
 @Column(nullable=false,length=36) public String requestKey;
 @Column(nullable=false,length=64) public String fingerprint;
 @Column(nullable=false,length=4000) public String payload;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
