package com.intermarche.backoffice.domain.operator;
import com.intermarche.backoffice.domain.BaseEntity;import jakarta.persistence.*;
@Entity @Table(name="operator_shift_intervals") public class OperatorShiftInterval extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public OperatorShift shift;
 @Column(nullable=false,length=30) public String state;
 @Column(length=500) public String reason;
 @Column(nullable=false) public java.time.LocalDateTime startedAt;
 public java.time.LocalDateTime endedAt;
 @Override public int getChecksum(){return java.util.Objects.hash(id,state,endedAt);}
}
