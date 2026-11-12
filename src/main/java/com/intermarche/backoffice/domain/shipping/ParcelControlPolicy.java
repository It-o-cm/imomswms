package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.executing.PickedItem;
import jakarta.persistence.*;import java.math.BigDecimal;import java.time.LocalDateTime;
@Entity @Table(name="parcel_control_policies",uniqueConstraints=@UniqueConstraint(columnNames="store_id"))
public class ParcelControlPolicy extends BaseEntity {
@ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
public boolean requireWeight=true,requireDimensions=false,requireContentConfirmation=true,requireScan=false;
public int revisionNumber=1; public String updatedBy;
@Override public int getChecksum(){return java.util.Objects.hash(id);}
}
