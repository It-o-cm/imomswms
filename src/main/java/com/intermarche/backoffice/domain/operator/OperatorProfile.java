package com.intermarche.backoffice.domain.operator;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
@Entity @Table(name="operator_profiles",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","principal"}))
public class OperatorProfile extends BaseEntity {
 @ManyToOne(optional=false) public Store store;
 @Column(nullable=false,length=100) public String principal;
 @Column(nullable=false,length=12) public String state="AVAILABLE";
 @Column(nullable=false,length=8) public String language="fr";
 @Column(nullable=false) public int textPercent=100;
 @Column(nullable=false) public boolean sound=true;
 @Column(nullable=false) public boolean vibration=true;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
