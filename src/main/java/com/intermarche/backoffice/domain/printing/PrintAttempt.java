package com.intermarche.backoffice.domain.printing;
import com.intermarche.backoffice.domain.BaseEntity;import com.intermarche.backoffice.domain.store.Store;import jakarta.persistence.*;import java.time.LocalDateTime;
@Entity @Table(name="print_attempts")
public class PrintAttempt extends BaseEntity {
@ManyToOne(optional=false,fetch=FetchType.LAZY) public PrintJob job;
public int attemptNumber;public String state,errorCode;public LocalDateTime startedAt,completedAt;
@Override public int getChecksum(){return java.util.Objects.hash(id);}
}
