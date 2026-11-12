package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="product_serials",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","product_id","code"}))
public class ProductSerial extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.order.Product product;
 @ManyToOne(fetch=FetchType.LAZY) public ManufacturerLot lot;
 @Column(nullable=false,length=100) public String code;
 @Column(nullable=false,length=160) public String claimKey;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
