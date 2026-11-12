package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="support_operations",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","actor","requestKey"}))
public class SupportOperation extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.Store store;
 @Column(nullable=false,length=100) public String actor;
 @Column(nullable=false,length=64) public String requestKey,fingerprint;
 @Column(nullable=false,length=40) public String action;
 @Column(nullable=false,columnDefinition="text") public String result;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
