package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Objects;

/** A service sold by a carrier (next day, chilled…): its daily cut-off and the capacity of one collection. */
@Entity @Table(name="carrier_service_levels")
public class CarrierServiceLevel extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Carrier carrier;
 @Column(nullable=false,length=30) public String code;
 @Column(nullable=false,length=120) public String label;
 /** Latest collection time of the day, HH:mm. */
 @Column(nullable=false,length=5) public String cutoff;
 public Integer maxUnits;
 @Column(precision=19,scale=6) public BigDecimal maxWeightKg;
 @Column(nullable=false) public boolean active=true;
 @Column(nullable=false) public long revisionNumber=1;
 /**
  * Returns the checksum of the identity of this service.
  *
  * @return the checksum
  */
 @Override public int getChecksum(){return Objects.hash(id);}
}
