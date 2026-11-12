package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="line_directives",uniqueConstraints=@UniqueConstraint(columnNames={"handlingOrder_id","lineKind","lineId"}))
public class LineDirective extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.wms.BaseHandlingOrder handlingOrder;
 @Column(nullable=false,length=30) public String lineKind;
 @Column(nullable=false) public Long lineId;
 @Column(nullable=false,length=20) public String state="READY";
 public int priority=0;
 @Column(nullable=false,length=2000) public String instruction="";
 @Column(length=500) public String deferReason;
 public long instructionVersion=0;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
