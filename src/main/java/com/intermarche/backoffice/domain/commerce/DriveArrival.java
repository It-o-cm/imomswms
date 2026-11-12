package com.intermarche.backoffice.domain.commerce;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

/** A customer announcing their arrival at the Drive, received from a commerce system and handled by the store team. */
@Entity @Table(name="drive_arrivals")
public class DriveArrival extends BaseEntity {
 /** Received from commerce, taken in charge by an operator, handed over, or cancelled. */
 public static final String RECEIVED="RECEIVED",ACKNOWLEDGED="ACKNOWLEDGED",COMPLETED="COMPLETED",CANCELLED="CANCELLED";
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Order order;
 @Column(nullable=false,length=20) public String sourceSystem;
 /** Technical identity that sent the arrival. */
 @Column(nullable=false,length=100) public String principal;
 @Column(nullable=false,length=36) public String requestId;
 /** Opaque, stable identifier given to commerce. */
 @Column(nullable=false,unique=true,length=40) public String arrivalId;
 /** Canonical hash of the accepted target and content, to tell a replay from a conflicting reuse of the key. */
 @Column(nullable=false,length=64) public String fingerprint;
 @Column(nullable=false) public long expectedRevision;
 @Column(nullable=false,length=20) public String parkingKind;
 @Column(length=12) public String parkingLabel;
 @Column(nullable=false,length=20) public String state=RECEIVED;
 @Column(nullable=false) public LocalDateTime receivedAt;
 public LocalDateTime acknowledgedAt,completedAt,cancelledAt;
 @Column(length=100) public String acknowledgedBy,completedBy,cancelledBy;
 @Column(length=255) public String cancelReason;
 /** Revision of the operator-side record, checked by store actions. */
 @Column(nullable=false) public long revisionNumber=1;
 /**
  * Returns the checksum of the identity of this arrival.
  *
  * @return the checksum
  */
 @Override public int getChecksum(){return Objects.hash(id);}
 /**
  * Tells whether the arrival still awaits its handover.
  *
  * @return true while received or acknowledged
  */
 public boolean active(){return RECEIVED.equals(state)||ACKNOWLEDGED.equals(state);}
}
