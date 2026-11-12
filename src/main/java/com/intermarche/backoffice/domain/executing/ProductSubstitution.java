package com.intermarche.backoffice.domain.executing;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.*;
@Entity @Table(name="product_substitutions",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","requested_id","replacement_id"}))
public class ProductSubstitution extends BaseEntity {
 @ManyToOne(optional=false) public Store store;
 @ManyToOne(optional=false) public Product requested;
 @ManyToOne(optional=false) public Product replacement;
 public boolean active=true;
 @Override public int getChecksum(){return java.util.Objects.hash(store,requested,replacement,active);}
}
