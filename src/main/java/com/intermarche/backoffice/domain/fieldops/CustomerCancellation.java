package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
@Entity @Table(name="customer_cancellations",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","requestKey"}))
public class CustomerCancellation extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 public Long customerOrderId;
 @Column(nullable=false,length=64) public String requestKey,fingerprint;
 @Column(nullable=false,length=100) public String actor;
 @Column(nullable=false,columnDefinition="text") public String result;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
