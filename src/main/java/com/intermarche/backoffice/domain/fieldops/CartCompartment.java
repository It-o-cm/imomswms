package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="cart_compartments",uniqueConstraints=@UniqueConstraint(columnNames={"cart_id","rowNumber","columnNumber"}))
public class CartCompartment extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public PhysicalSupport cart;
 public int rowNumber,columnNumber;
 @Column(nullable=false,length=60) public String label;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(unique=true) public PhysicalSupport occupant;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.wms.BaseHandlingOrder handlingOrder;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.order.Order customerOrder;
 @Column(length=150) public String destination;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
