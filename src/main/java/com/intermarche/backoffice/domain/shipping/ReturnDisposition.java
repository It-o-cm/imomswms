package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.executing.PickedItem;
import jakarta.persistence.*;import java.math.BigDecimal;import java.time.LocalDateTime;
@Entity @Table(name="return_dispositions")
public class ReturnDisposition extends BaseEntity {
@ManyToOne(optional=false,fetch=FetchType.LAZY) public ProductReturnLine line;
@ManyToOne(fetch=FetchType.LAZY) public BaseLocation destination;
@ManyToOne(fetch=FetchType.LAZY) public StockSegment transitSegment;
@Column(precision=19,scale=6,nullable=false) public BigDecimal quantity;
public String decision,state="PLANNED",decidedBy,performedBy; @Column(length=2000) public String reason;
public LocalDateTime pickedAt,completedAt; public long revisionNumber;
@Override public int getChecksum(){return java.util.Objects.hash(id);}
}
