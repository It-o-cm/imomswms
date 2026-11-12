package com.intermarche.backoffice.domain.executing;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
@Entity @Table(name="tote_content_corrections",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","requestKey"}))
public class ToteContentCorrection extends BaseEntity {
 @ManyToOne(optional=false) public Store store;
 @ManyToOne(optional=false) public LoadedTote loadedTote;
 @Column(nullable=false,length=100) public String requestKey;
 @Column(nullable=false,length=200) public String reason;
 @Column(nullable=false,length=100) public String performedBy;
 @Lob @Column(nullable=false) public String payload;
 @Override public int getChecksum(){return java.util.Objects.hash(loadedTote,requestKey,payload);}
}
