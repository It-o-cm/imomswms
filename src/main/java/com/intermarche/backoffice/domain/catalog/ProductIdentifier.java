package com.intermarche.backoffice.domain.catalog;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
@Entity @Table(name="product_identifiers",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","kind","context","normalized"}),indexes=@Index(columnList="store_id,product_id"))
public class ProductIdentifier extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Product product;
 @Column(nullable=false,length=12) public String kind;
 @Column(name="identifier_value",nullable=false,length=120) public String value;
 @Column(nullable=false,length=120) public String normalized;
 @Column(nullable=false,length=100) public String context="";
 @Column(nullable=false,length=40) public String packaging="BASE";
 public boolean active=true;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
