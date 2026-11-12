package com.intermarche.backoffice.domain.policy;
import com.intermarche.backoffice.domain.BaseEntity;import com.intermarche.backoffice.domain.store.Store;import jakarta.persistence.*;
@Entity @Table(name="operational_policy_versions",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","scopeKey","revisionNumber"}))
public class OperationalPolicyVersion extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @Column(nullable=false,length=100) public String scopeKey;
 @Column(nullable=false,length=40) public String kind;
 @Column(nullable=false) public int revisionNumber;
 public boolean active=true;
 @Column(nullable=false,length=100) public String activatedBy;
 @Column(length=500) public String reason;
 @Lob @Column(nullable=false) public String specification;
 @Lob public String simulation;
 @Override public int getChecksum(){return java.util.Objects.hash(id,scopeKey,revisionNumber);}
}
