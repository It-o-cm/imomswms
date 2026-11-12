package com.intermarche.backoffice.domain.catalog;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
@Entity @Table(name="product_instructions",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","product_id","revision"}),indexes=@Index(columnList="store_id,product_id"))
public class ProductInstruction extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Product product;
 @Column(nullable=false) public int revision;
 @Column(nullable=false,length=5000) public String text;
 @Column(nullable=false,length=100) public String author;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
