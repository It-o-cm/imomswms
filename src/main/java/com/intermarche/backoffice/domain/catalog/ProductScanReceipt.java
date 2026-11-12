package com.intermarche.backoffice.domain.catalog;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
@Entity @Table(name="product_scan_receipts",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","principal","scanId"}),indexes=@Index(columnList="store_id,principal"))
public class ProductScanReceipt extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @Column(nullable=false,length=100) public String principal;
 @Column(nullable=false,length=36) public String scanId;
 @Column(nullable=false,length=64) public String fingerprint;
 @Column(nullable=false,columnDefinition="TEXT") public String result;
 @Column(length=100) public String usedRequestKey;
 @Column(precision=19,scale=6) public java.math.BigDecimal consumedQuantity;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
