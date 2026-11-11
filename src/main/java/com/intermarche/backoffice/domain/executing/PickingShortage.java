package com.intermarche.backoffice.domain.executing;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.wms.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
/** Keeps the customer demand unchanged while recording what the picker could actually provide. */
@Entity @Table(name="picking_shortages")
public class PickingShortage extends BaseEntity {
 // Optional: a shortage may be declared by the manager outside any handling order (catering F3).
 @ManyToOne public BaseHandlingOrder handlingOrder;
 @ManyToOne(optional=false) public LotLine lotLine;
 @ManyToOne(optional=false) public Product requestedProduct;
 @ManyToOne public Product replacementProduct;
 @ManyToOne(optional=false) public Employee employee;
 @Column(nullable=false,precision=10,scale=3) public BigDecimal quantity;
 @Column(nullable=false,length=300) public String reason;
 public BigDecimal effectiveQuantity(){return quantity.subtract(PickingShortageCorrection.<PickingShortageCorrection>list("shortage",this).stream().map(c->c.quantity).reduce(BigDecimal.ZERO,BigDecimal::add));}
 @Override public int getChecksum(){return java.util.Objects.hash(handlingOrder,lotLine,requestedProduct,replacementProduct,employee,quantity,reason);}
}
