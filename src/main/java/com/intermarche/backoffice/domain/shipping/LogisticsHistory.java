package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.executing.PickedItem;
import jakarta.persistence.*;import java.math.BigDecimal;import java.time.LocalDateTime;
@Entity @Table(name="logistics_history")
public class LogisticsHistory extends BaseEntity {
@ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
public String subjectType,action,actor; public Long subjectId;
@Column(length=2000) public String reason;
@Lob public String snapshot;
@Override public int getChecksum(){return java.util.Objects.hash(id);}
}
