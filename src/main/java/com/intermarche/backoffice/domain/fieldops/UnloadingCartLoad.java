package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;

/** One cart loaded from a truck delivery and put away along its own route; it keeps the cart busy until closed. */
@Entity @Table(name="unloading_cart_loads")
public class UnloadingCartLoad extends BaseEntity {
 /** CONFIRMED while goods remain on the cart, DONE once everything is put away, RELEASED when the picker unloads it otherwise. */
 public static final String CONFIRMED="CONFIRMED",DONE="DONE",RELEASED="RELEASED";
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public ReplenishmentOrder handlingOrder;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public PhysicalSupport cart;
 @Column(nullable=false,length=20) public String zone;
 @Column(nullable=false,length=20) public String state=CONFIRMED;
 @Column(nullable=false,length=64) public String requestKey,fingerprint;
 @Column(nullable=false,length=100) public String confirmedBy;
 @Column(nullable=false) public LocalDateTime confirmedAt;
 public LocalDateTime closedAt;
 @OneToMany(mappedBy="load",cascade=CascadeType.ALL) @OrderBy("id") public List<UnloadingCartLine> lines=new ArrayList<>();
 /**
  * Returns the checksum of the identity of this load.
  *
  * @return the checksum
  */
 @Override public int getChecksum(){return Objects.hash(id);}
}
