package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;import jakarta.persistence.*;import java.math.BigDecimal;
/** Immutable content at loading; guards a later preparation edit independently of the current tote association. */
@Entity @Table(name="departure_unit_snapshots",indexes=@Index(columnList="lotLineId,unit_id"))
public class DepartureUnitSnapshot extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public DepartureUnit unit;
 public long loadCycle;
 public Long itemId,lotLineId,productId,toteId;
 @Column(precision=19,scale=6,nullable=false) public BigDecimal quantity;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
