package com.intermarche.backoffice.domain.workforce;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
import java.util.Objects;
@Entity @Table(name="planning_revisions")
public class PlanningRevision extends BaseEntity {
 @ManyToOne(optional=false) public Store store;
 @Column(unique=true,nullable=false) public String previewId;
 public int solutionIndex;
 public String actor;
 public String snapshotHash;
 @Column(columnDefinition="text") public String solutionJson;
 @Override public int getChecksum(){return Objects.hash(store,previewId,solutionIndex,snapshotHash,solutionJson);}
}
