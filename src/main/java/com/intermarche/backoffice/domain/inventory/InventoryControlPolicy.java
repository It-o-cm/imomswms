package com.intermarche.backoffice.domain.inventory;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
@Entity @Table(name="inventory_control_policies",uniqueConstraints=@UniqueConstraint(columnNames="store_id"))
public class InventoryControlPolicy extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.Store store;
 @Column(nullable=false,precision=19,scale=6) public BigDecimal absoluteTolerance=BigDecimal.ZERO;
 @Column(precision=8,scale=4) public BigDecimal relativeTolerancePercent;
 @Column(nullable=false) public int maximumRounds=2;
 public boolean differentCounterRequired;
 @Column(nullable=false,length=30) public String bayMovementMode="RECONCILE_MOVEMENTS";
 @Override public int getChecksum(){return java.util.Objects.hash(id,absoluteTolerance,relativeTolerancePercent,maximumRounds,differentCounterRequired,bayMovementMode);}
}
