package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.order.Order;
import jakarta.persistence.*;
import java.util.Objects;

/** A transport document frozen when its tour is sealed: reprints are identical, a broken seal voids it. */
@Entity @Table(name="tour_documents")
public class TourDocument extends BaseEntity {
 /** Load manifest, delivery note of one order, consignment note handed to the driver. */
 public static final String MANIFEST="MANIFEST",DELIVERY_NOTE="DELIVERY_NOTE",CONSIGNMENT_NOTE="CONSIGNMENT_NOTE";
 /** ACTIVE until the seal is broken, then VOIDED. */
 public static final String ACTIVE="ACTIVE",VOIDED="VOIDED";
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public DepartureTour tour;
 @ManyToOne(fetch=FetchType.LAZY) public Order order;
 @Column(nullable=false,length=30) public String kind;
 @Column(nullable=false,length=80) public String number;
 @Column(nullable=false,length=20) public String state=ACTIVE;
 @Lob @Column(nullable=false) public String content;
 @Column(nullable=false,length=64) public String sha256;
 @Column(length=255) public String voidedReason;
 /**
  * Returns the checksum of the identity of this document.
  *
  * @return the checksum
  */
 @Override public int getChecksum(){return Objects.hash(id);}
}
