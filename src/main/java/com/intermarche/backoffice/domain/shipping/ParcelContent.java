package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.executing.PickedItem;
import jakarta.persistence.*;import java.math.BigDecimal;import java.time.LocalDateTime;
@Entity @Table(name="parcel_contents")
public class ParcelContent extends BaseEntity {
@ManyToOne(optional=false,fetch=FetchType.LAZY) public Parcel parcel;
@ManyToOne(optional=false,fetch=FetchType.LAZY) public PickedItem pickedItem;
@Column(precision=19,scale=6,nullable=false) public BigDecimal quantity;
public Long sourceToteId,sourceProductId;
public String sourceToteCode;
@Column(length=500) public String sourceAddress;
@Column(precision=19,scale=6) public BigDecimal sourceQuantity;
@Lob public String traceSnapshot;
@Override public int getChecksum(){return java.util.Objects.hash(id);}
}
