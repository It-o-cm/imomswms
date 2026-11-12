package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;import com.intermarche.backoffice.domain.store.*;import jakarta.persistence.*;import java.time.LocalDateTime;
@Entity @Table(name="departure_handovers")
public class DepartureHandover extends BaseEntity {
@ManyToOne(optional=false,fetch=FetchType.LAZY) public LogisticsDeparture departure;
@ManyToOne(optional=false,fetch=FetchType.LAZY) public Shipment shipment;
public String performedBy,recipient,reference;public LocalDateTime handedOverAt;
@Override public int getChecksum(){return java.util.Objects.hash(id);}
}
