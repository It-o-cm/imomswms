package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import jakarta.persistence.*;
@Entity @Table(name="mission_state_changes",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","principal","requestKey"}))
public class MissionStateChange extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public BaseHandlingOrder handlingOrder;
 @Column(length=100,nullable=false) public String principal;
 @Column(length=64,nullable=false) public String requestKey;
 @Column(length=64,nullable=false) public String fingerprint;
 @Column(columnDefinition="TEXT",nullable=false) public String result;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
