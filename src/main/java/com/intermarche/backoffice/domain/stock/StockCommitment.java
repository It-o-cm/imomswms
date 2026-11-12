package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
@Entity
@Table(name="stock_commitments", uniqueConstraints=@UniqueConstraint(columnNames={"account_id","reference"}))
public class StockCommitment extends BaseEntity {
    public enum CommitmentStatus { ACTIVE, PREPARED, CANCELLED }
    @ManyToOne(optional=false) public StockAccount account;
    // Stable order-line reference; partial preparations reduce remainingQuantity.
    @Column(nullable=false,length=120) public String reference;
    @ManyToOne(optional=false) public Building building;
    @Column(nullable=false) public LocalDateTime preparationAt;
    @Column(nullable=false,precision=19,scale=6) public BigDecimal quantity;
    @Column(nullable=false,precision=19,scale=6) public BigDecimal remainingQuantity;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public CommitmentStatus status=CommitmentStatus.ACTIVE;
    @Override public int getChecksum() { return Objects.hash(account,reference,building,preparationAt,quantity,remainingQuantity,status); }
}
