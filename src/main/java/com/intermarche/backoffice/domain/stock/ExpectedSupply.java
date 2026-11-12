package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
@Entity
@Table(name="stock_expected_supplies", uniqueConstraints=@UniqueConstraint(columnNames={"account_id","reference"}))
public class ExpectedSupply extends BaseEntity {
    public enum SupplyStatus { EXPECTED, RECEIVED, CANCELLED }
    @ManyToOne(optional=false) public StockAccount account;
    @Column(nullable=false,length=120) public String reference;
    // Null source means an external delivery (truck); otherwise this is an internal transfer.
    @ManyToOne public BaseLocation source;
    @ManyToOne(optional=false) public BaseLocation destination;
    @Column(nullable=false) public LocalDateTime availableAt;
    @Column(nullable=false,precision=19,scale=6) public BigDecimal quantity;
    @Column(nullable=false,precision=19,scale=6) public BigDecimal received=BigDecimal.ZERO;
    @Column(precision=19,scale=6) public BigDecimal picked=BigDecimal.ZERO;
    public BigDecimal pickedQuantity() { return (picked==null?BigDecimal.ZERO:picked).max(received==null?BigDecimal.ZERO:received); }
    @Enumerated(EnumType.STRING) @Column(nullable=false) public SupplyStatus status=SupplyStatus.EXPECTED;
    @Override public int getChecksum() { return Objects.hash(account,reference,source,destination,availableAt,quantity,received,status); }
}
