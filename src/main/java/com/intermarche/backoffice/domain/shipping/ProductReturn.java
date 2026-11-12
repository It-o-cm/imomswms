package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.executing.PickedItem;
import jakarta.persistence.*;import java.math.BigDecimal;import java.time.LocalDateTime;
@Entity @Table(name="product_returns")
public class ProductReturn extends BaseEntity {
@ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
@ManyToOne(fetch=FetchType.LAZY) public Shipment shipment;
public String code,reference,reportedBy; @Column(length=2000) public String reason;
public String state="RECEIVED"; public long revisionNumber; public LocalDateTime receivedAt;
@Override public int getChecksum(){return java.util.Objects.hash(id);}
}
