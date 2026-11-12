package com.intermarche.backoffice.domain.operator;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
@Entity @Table(name="operator_notices",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","eventKey"}))
public class OperatorNotice extends BaseEntity {
 @ManyToOne(optional=false) public Store store;
 @Column(nullable=false) public long cursor;
 @Column(nullable=false,length=200) public String eventKey;
 @Column(nullable=false,length=64) public String fingerprint;
 @Column(nullable=false,length=100) public String author;
 @Column(nullable=false,length=32) public String category;
 @Column(nullable=false,length=120) public String label;
 @Column(nullable=false,length=1000) public String message;
 @Column(nullable=false,length=12) public String urgency="NORMAL";
 @Column(nullable=false) public boolean mandatory;
 public Long orderId;
 public Long fieldCaseId;
 @Column(length=40) public String eventType;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
