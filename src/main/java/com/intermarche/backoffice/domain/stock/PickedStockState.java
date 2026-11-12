package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="picked_stock_states",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","itemId"}))
public class PickedStockState extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.Store store;
 public Long itemId,lotLineId;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.order.Product product;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.executing.LoadedTote legacyTote;
 @Column(nullable=false,precision=19,scale=6) public java.math.BigDecimal legacyQuantity=java.math.BigDecimal.ZERO;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
