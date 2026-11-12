package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.PickingToteLocation;
import jakarta.persistence.*;
import java.math.BigDecimal;
/** Audits partial placements across several totes without changing the planned order line. */
@Entity @Table(name="stock_protection_placements",uniqueConstraints=@UniqueConstraint(columnNames={"supply_id","event_key"}))
public class StockProtectionPlacement extends BaseEntity {
    @ManyToOne(optional=false) public ExpectedSupply supply;
    @ManyToOne(optional=false) public PickingToteLocation destination;
    @Column(name="event_key",nullable=false,length=100) public String eventKey;
    @Column(nullable=false,precision=19,scale=6) public BigDecimal quantity;
    @Override public int getChecksum(){return java.util.Objects.hash(supply,destination,eventKey,quantity);}
}
