package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="quality_case_history")
public class QualityCaseHistory extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public QualityCase qualityCase;
 @Column(nullable=false,length=100) public String actor;
 @Column(nullable=false,length=30) public String action;
 @Column(nullable=false,length=2000) public String reason;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
