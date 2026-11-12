package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
import java.util.Objects;

/** A transport company collecting the store's departures. */
@Entity @Table(name="carriers")
public class Carrier extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @Column(nullable=false,length=30) public String code;
 @Column(nullable=false,length=120) public String name;
 @Column(nullable=false) public boolean active=true;
 @Column(nullable=false) public long revisionNumber=1;
 /**
  * Returns the checksum of the identity of this carrier.
  *
  * @return the checksum
  */
 @Override public int getChecksum(){return Objects.hash(id);}
}
