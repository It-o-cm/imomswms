package com.intermarche.backoffice.domain.printing;
import com.intermarche.backoffice.domain.BaseEntity;import com.intermarche.backoffice.domain.store.Store;import jakarta.persistence.*;import java.time.LocalDateTime;
@Entity @Table(name="print_jobs")
public class PrintJob extends BaseEntity {
@ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
@ManyToOne(optional=false,fetch=FetchType.LAZY) public StorePrinter printer;
@ManyToOne(fetch=FetchType.LAZY) public LabelTemplate template;
@ManyToOne(fetch=FetchType.LAZY) public PrintJob reprintOf;
public Long subjectId;public String subjectType,logisticsCode,state="QUEUED",requestedBy,reason,payloadSha256,lastError;
public int templateVersion=1,attemptCount;public long revisionNumber;
public LocalDateTime nextAttemptAt,sendingAt,sentAt;
@Lob public String frozenDestination;
@Lob public String frozenData;
@Lob public String frozenPayload;
@Lob public String frozenPreview;
@Override public int getChecksum(){return java.util.Objects.hash(id);}
}
