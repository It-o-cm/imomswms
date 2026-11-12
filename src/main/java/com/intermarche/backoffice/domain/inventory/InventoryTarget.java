package com.intermarche.backoffice.domain.inventory;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="inventory_targets")
public class InventoryTarget extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.wms.handling.InventoryOrder handlingOrder;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.BaseLocation location;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.Buffer buffer;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.BufferCabinet cabinet;
 public Integer cellRow,cellColumn;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.stock.StockSegment segment;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.order.Product product;
 @Column(precision=19,scale=6) public java.math.BigDecimal expectedQuantity;
 @Column(nullable=false,length=200) public String address;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
