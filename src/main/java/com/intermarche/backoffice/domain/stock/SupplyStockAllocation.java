package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="supply_stock_allocations")
public class SupplyStockAllocation extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public ExpectedSupply supply;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public StockSegment origin;
 @Column(nullable=false,precision=19,scale=6) public java.math.BigDecimal quantity,received=java.math.BigDecimal.ZERO;
 @Column(nullable=false,length=160) public String eventKey;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
