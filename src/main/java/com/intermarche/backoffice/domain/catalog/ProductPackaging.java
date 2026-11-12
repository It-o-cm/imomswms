package com.intermarche.backoffice.domain.catalog;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
@Entity @Table(name="product_packagings",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","product_id","code"}),indexes=@Index(columnList="store_id,product_id"))
public class ProductPackaging extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Product product;
 @Column(nullable=false,length=40) public String code;
 @Column(nullable=false,length=80) public String label;
 @Column(nullable=false,length=12) public String level;
 @Column(nullable=false,length=40) public String parentCode="BASE";
 @Column(nullable=false,length=6) public String unit;
 @Column(nullable=false,precision=19,scale=6) public java.math.BigDecimal factor;
 public boolean active=true;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
