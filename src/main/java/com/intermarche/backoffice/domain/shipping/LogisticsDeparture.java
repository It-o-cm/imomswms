package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;import com.intermarche.backoffice.domain.store.*;import jakarta.persistence.*;import java.time.LocalDateTime;
@Entity @Table(name="logistics_departures")
public class LogisticsDeparture extends BaseEntity {
@ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
@ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.order.Order order;
@ManyToOne(optional=false,fetch=FetchType.LAZY) public Employee owner;
/** Carrier tour this departure travels with, if any. */
@ManyToOne(fetch=FetchType.LAZY) public DepartureTour tour;
public String code,state="LOADING",mode,recipient,transportReference,destinationCode;
public long revisionNumber;public LocalDateTime lastHandoverAt;
/** Position of this departure in the delivery order of its tour, from 1; null while the order is not set. */
public Integer stopSequence;
/**
 * Returns the checksum of the identity of this departure.
 *
 * @return the checksum
 */
@Override public int getChecksum(){return java.util.Objects.hash(id);}
}
