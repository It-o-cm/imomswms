package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="support_replacements",uniqueConstraints=@UniqueConstraint(columnNames={"source_id"}))
public class SupportReplacement extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.executing.LoadedTote source;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.executing.LoadedTote replacement;
 @Column(nullable=false,length=500) public String reason;
 @Column(nullable=false,length=100) public String actor;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
