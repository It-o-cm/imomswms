package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;import com.intermarche.backoffice.domain.store.*;import jakarta.persistence.*;import java.time.LocalDateTime;
@Entity @Table(name="departure_units")
public class DepartureUnit extends BaseEntity {
@ManyToOne(optional=false,fetch=FetchType.LAZY) public LogisticsDeparture departure;
@ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.executing.LoadedTote loadedTote;
@ManyToOne(fetch=FetchType.LAZY) public Parcel parcel;
@ManyToOne(fetch=FetchType.LAZY) public Buffer originalBuffer;
@ManyToOne(fetch=FetchType.LAZY) public BufferCabinet originalCabinet;
public Integer originalRow,originalColumn;
@ManyToOne(fetch=FetchType.LAZY) public DepartureHandover handover;
public long loadCycle;
public String state="SELECTED",loadedBy;public LocalDateTime loadedAt;
@Override public int getChecksum(){return java.util.Objects.hash(id);}
}
