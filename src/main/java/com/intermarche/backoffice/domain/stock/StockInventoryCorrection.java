package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.BaseLocation;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Objects;
@Entity
@Table(name="stock_inventory_corrections",uniqueConstraints=@UniqueConstraint(columnNames={"account_id","eventKey"}))
public class StockInventoryCorrection extends BaseEntity {
    @ManyToOne(optional=false) public StockAccount account;
    @ManyToOne(optional=false) public BaseLocation location;
    @Column(nullable=false,length=100) public String eventKey;
    @Column(nullable=false,precision=19,scale=6) public BigDecimal previousQuantity;
    @Column(nullable=false,precision=19,scale=6) public BigDecimal quantity;
    @Column(nullable=false,length=200) public String reason;
    @Column(length=100) public String performedBy;
    @Override public int getChecksum(){return Objects.hash(account,location,eventKey,previousQuantity,quantity,reason,performedBy);}
}
