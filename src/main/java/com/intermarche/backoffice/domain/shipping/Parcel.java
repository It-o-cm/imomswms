package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.executing.PickedItem;
import jakarta.persistence.*;import java.math.BigDecimal;import java.time.LocalDateTime;
@Entity @Table(name="parcels")
public class Parcel extends BaseEntity {
@ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
@ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.order.Order order;
@Column(nullable=false,unique=true) public String code;
public String state="OPEN",packagingType="CARTON",createdBy,closedBy;
@Column(precision=19,scale=6) public BigDecimal weightKg,lengthCm,widthCm,heightCm;
@ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.fieldops.PhysicalSupport packagingSupport;
public boolean materialized=false;
@ManyToOne(fetch=FetchType.LAZY) public Buffer currentBuffer;
@Column(length=300) public String currentAddress;
public LocalDateTime closedAt; public long revisionNumber;
@Lob public String closedSnapshot;
@Override public int getChecksum(){return java.util.Objects.hash(id);}
}
