package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;import com.intermarche.backoffice.domain.store.*;import jakarta.persistence.*;import java.time.LocalDateTime;
@Entity @Table(name="departure_custody_transfers")
public class DepartureCustodyTransfer extends BaseEntity {
@ManyToOne(optional=false,fetch=FetchType.LAZY) public LogisticsDeparture departure;
@ManyToOne(optional=false,fetch=FetchType.LAZY) public Employee sender;
@ManyToOne(optional=false,fetch=FetchType.LAZY) public Employee recipient;
public String state="PENDING",reason;public long cargoRevision;public LocalDateTime acceptedAt;
@Lob public String cargoSnapshot;
@Override public int getChecksum(){return java.util.Objects.hash(id);}
}
