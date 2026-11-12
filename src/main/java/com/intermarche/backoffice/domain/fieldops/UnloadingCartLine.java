package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.stock.ExpectedSupply;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Objects;

/** Quantity of one expected delivery line placed on a cart, and how much of it has already been put away. */
@Entity @Table(name="unloading_cart_lines",uniqueConstraints=@UniqueConstraint(columnNames={"load_id","supply_id"}))
public class UnloadingCartLine extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public UnloadingCartLoad load;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public ExpectedSupply supply;
 @Column(nullable=false,precision=19,scale=6) public BigDecimal quantity;
 @Column(nullable=false,precision=19,scale=6) public BigDecimal putAway=BigDecimal.ZERO;
 /**
  * Returns the quantity still on the cart.
  *
  * @return the loaded quantity not yet put away
  */
 public BigDecimal remaining(){return quantity.subtract(putAway).max(BigDecimal.ZERO);}
 /**
  * Returns the checksum of the identity of this line.
  *
  * @return the checksum
  */
 @Override public int getChecksum(){return Objects.hash(id);}
}
