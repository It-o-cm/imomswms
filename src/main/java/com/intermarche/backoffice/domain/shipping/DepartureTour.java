package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

/** A carrier collection grouping the departures of several orders at one dock, sealed then handed over at once. */
@Entity @Table(name="departure_tours")
public class DepartureTour extends BaseEntity {
 /** OPEN while departures are attached and loaded, SEALED once the vehicle is closed, then DEPARTED or CANCELLED. */
 public static final String OPEN="OPEN",SEALED="SEALED",DEPARTED="DEPARTED",CANCELLED="CANCELLED";
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public CarrierServiceLevel serviceLevel;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public ShippingDock dock;
 @Column(nullable=false,unique=true,length=40) public String code;
 @Column(nullable=false,length=20) public String state=OPEN;
 @Column(nullable=false) public LocalDateTime plannedDeparture;
 @Column(length=40) public String vehicleReference;
 @Column(length=30) public String sealNumber;
 @Column(nullable=false,length=100) public String createdBy;
 @Column(length=100) public String sealedBy,departedBy;
 public LocalDateTime sealedAt,departedAt;
 @Column(length=255) public String closingReason;
 @Column(nullable=false) public long revisionNumber=1;
 /** Road itinerary of the current delivery order computed by Google Maps (JSON), or null when only estimated. */
 @Lob public String roadRoute;
 /**
  * Returns the checksum of the identity of this tour.
  *
  * @return the checksum
  */
 @Override public int getChecksum(){return Objects.hash(id);}
}
