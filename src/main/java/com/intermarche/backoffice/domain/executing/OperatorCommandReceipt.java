package com.intermarche.backoffice.domain.executing;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;

/** Acquittement atomique d'une action opérateur. Aucun nouvel état métier de manutention. */
@Entity
@Table(name="operator_command_receipts",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","performedBy","requestKey"}))
public class OperatorCommandReceipt extends BaseEntity {
    @ManyToOne(optional=false) public Store store;
    @Column(nullable=false,length=100) public String performedBy;
    @Column(nullable=false,length=64) public String requestKey;
    @Column(nullable=false,length=40) public String action;
    @Column(nullable=false,length=64) public String fingerprint;
    @Column(name="activity_quantity",precision=19,scale=6) public java.math.BigDecimal activityQuantity;
    @Column(name="activity_unit",length=8) public String activityUnit;
    @Column(name="activity_line_id") public Long activityLineId;
    @Column(name="activity_product_id") public Long activityProductId;
    public Long executionId;
    public Long orderId;
    @Lob public String payload;
    @Lob @Column(nullable=false) public String result;
    @Column(name="quantity_projection_version") public Integer quantityProjectionVersion;
    @Override public int getChecksum(){return java.util.Objects.hash(store,performedBy,requestKey,fingerprint);}
}
