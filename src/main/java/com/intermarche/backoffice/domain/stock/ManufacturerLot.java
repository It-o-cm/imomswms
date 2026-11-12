package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="manufacturer_lots",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","product_id","code"}))
public class ManufacturerLot extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.order.Product product;
 @Column(nullable=false,length=100) public String code;
 public java.time.LocalDate manufacturedOn,bestBefore,useBy;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
