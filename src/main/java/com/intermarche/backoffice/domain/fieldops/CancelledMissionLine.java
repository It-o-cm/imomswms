package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.LotLine;
import jakarta.persistence.*;
@Entity @Table(name="cancelled_mission_lines",uniqueConstraints=@UniqueConstraint(columnNames={"line_id"}))
public class CancelledMissionLine extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @OneToOne(optional=false,fetch=FetchType.LAZY) public LotLine line;
 @Column(nullable=false,length=500) public String reason;
 @Column(nullable=false,length=100) public String actor;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
