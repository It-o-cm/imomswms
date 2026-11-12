package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
import java.util.Objects;

/** A loading dock; its code is posted on the dock and scanned when a tour is sealed. */
@Entity @Table(name="shipping_docks")
public class ShippingDock extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @Column(nullable=false,length=30) public String code;
 @Column(nullable=false,length=120) public String label;
 @Column(nullable=false) public boolean active=true;
 @Column(nullable=false) public long revisionNumber=1;
 /**
  * Returns the checksum of the identity of this dock.
  *
  * @return the checksum
  */
 @Override public int getChecksum(){return Objects.hash(id);}
}
