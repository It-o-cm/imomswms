package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="line_directive_changes",uniqueConstraints=@UniqueConstraint(columnNames={"directive_id","actor","requestKey"}))
public class LineDirectiveChange extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public LineDirective directive;
 @Column(nullable=false,length=100) public String actor;
 @Column(nullable=false,length=64) public String requestKey;
 @Column(nullable=false,length=64) public String fingerprint;
 @Column(nullable=false,columnDefinition="text") public String result;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
