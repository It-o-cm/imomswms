package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="picked_stock_allocations")
public class PickedStockAllocation extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public PickedStockState state;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public StockSegment origin;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public StockSegment holding;
 @Column(nullable=false,precision=19,scale=6) public java.math.BigDecimal quantity;
 public boolean shipped;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
