package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="packaging_movements")
public class PackagingMovement extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public SupportType type;
 @ManyToOne(fetch=FetchType.LAZY) public PhysicalSupport support;
 @Column(nullable=false) public long quantity,balance;
 @Column(nullable=false,length=100) public String actor;
 @Column(nullable=false,length=500) public String reason;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
