package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="line_instruction_acks",uniqueConstraints=@UniqueConstraint(columnNames={"directive_id","actor","instructionVersion"}))
public class LineInstructionAck extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public LineDirective directive;
 @Column(nullable=false,length=100) public String actor;
 public long instructionVersion;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
