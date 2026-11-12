package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import jakarta.persistence.*;
@Entity @Table(name="mission_preparation_contexts",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","handlingOrder_id"}))
public class MissionPreparationContext extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public BaseHandlingOrder handlingOrder;
 @Column(length=30,nullable=false) public String mode;
 @Column(length=80) public String groupCode;
 public boolean requireFinalScan;
 @Override public int getChecksum(){return java.util.Objects.hash(mode,groupCode);}
}
