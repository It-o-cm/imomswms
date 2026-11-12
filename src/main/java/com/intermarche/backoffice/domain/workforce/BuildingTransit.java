package com.intermarche.backoffice.domain.workforce;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import jakarta.persistence.*;
import java.util.Objects;
/** Directed walking time, configured explicitly; a missing edge is not a zero-minute trip. */
@Entity @Table(name="building_transits",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","origin_id","destination_id"}))
public class BuildingTransit extends BaseEntity {
 @ManyToOne(optional=false) public Store store;
 @ManyToOne(optional=false) public Building origin;
 @ManyToOne(optional=false) public Building destination;
 public int minutes;
 @Override public int getChecksum(){return Objects.hash(store,origin,destination,minutes);}
}
