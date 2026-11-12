package com.intermarche.backoffice.domain.operator;
import com.intermarche.backoffice.domain.BaseEntity;import com.intermarche.backoffice.domain.store.Store;import jakarta.persistence.*;
@Entity @Table(name="operator_shifts") public class OperatorShift extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @Column(nullable=false,length=100) public String principal;
 @Column(nullable=false) public java.time.LocalDateTime startedAt;
 public java.time.LocalDateTime endedAt;
 public Integer goalLines;
 @Override public int getChecksum(){return java.util.Objects.hash(id,goalLines,endedAt);}
}
