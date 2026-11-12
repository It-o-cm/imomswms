package com.intermarche.backoffice.domain.operator;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
@Entity @Table(name="operator_notice_recipients",uniqueConstraints=@UniqueConstraint(columnNames={"notice_id","principal"}))
public class OperatorNoticeRecipient extends BaseEntity {
 @ManyToOne(optional=false) public OperatorNotice notice;
 @Column(nullable=false,length=100) public String principal;
 public java.time.LocalDateTime receivedAt;
 public java.time.LocalDateTime acknowledgedAt;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
