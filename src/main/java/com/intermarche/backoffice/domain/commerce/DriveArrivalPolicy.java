package com.intermarche.backoffice.domain.commerce;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
import java.util.Objects;

/** Window, around the pickup slot, in which a store accepts arrivals. */
@Entity @Table(name="drive_arrival_policies")
public class DriveArrivalPolicy extends BaseEntity {
 /** Window applied while the store has not set its own. */
 public static final int DEFAULT_BEFORE=30,DEFAULT_AFTER=60;
 @OneToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @Column(nullable=false) public int minutesBefore=DEFAULT_BEFORE;
 @Column(nullable=false) public int minutesAfter=DEFAULT_AFTER;
 @Column(nullable=false) public long revisionNumber=1;
 @Column(nullable=false,length=100) public String updatedBy;
 /**
  * Returns the checksum of the identity of this policy.
  *
  * @return the checksum
  */
 @Override public int getChecksum(){return Objects.hash(id);}
}
