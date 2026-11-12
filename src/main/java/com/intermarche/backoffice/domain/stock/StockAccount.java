package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
@Entity
@Table(name="stock_accounts", uniqueConstraints=@UniqueConstraint(columnNames={"store_id","product_id"}))
public class StockAccount extends BaseEntity {
    @ManyToOne(optional=false) public Store store;
    @ManyToOne(optional=false) public Product product;
    @Column(nullable=false,precision=19,scale=6) public BigDecimal safetyMargin=BigDecimal.ZERO;
    public String alertStatus;
    public LocalDateTime alertCheckedAt,alertNextCheckAt;
    @org.hibernate.annotations.ColumnDefault("true")
    public boolean alertDirty=true;
    @Override public int getChecksum() { return Objects.hash(store,product,safetyMargin); }
}
