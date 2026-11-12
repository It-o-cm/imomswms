package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.executing.PickedItem;
import jakarta.persistence.*;import java.math.BigDecimal;import java.time.LocalDateTime;
@Entity @Table(name="product_return_lines")
public class ProductReturnLine extends BaseEntity {
@ManyToOne(optional=false,fetch=FetchType.LAZY) public ProductReturn productReturn;
@ManyToOne(optional=false,fetch=FetchType.LAZY) public Product product;
@ManyToOne(optional=false,fetch=FetchType.LAZY) public StockSegment segment;
@ManyToOne(fetch=FetchType.LAZY) public ShipmentLine shipmentLine;
@Column(precision=19,scale=6,nullable=false) public BigDecimal quantity;
public String inspection="PENDING",inspectedBy; @Column(length=2000) public String inspectionReason; public LocalDateTime inspectedAt;
@Override public int getChecksum(){return java.util.Objects.hash(id);}
}
