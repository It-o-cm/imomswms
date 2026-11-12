package com.intermarche.backoffice.domain.inventory;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Entity @Table(name="inventory_count_observations")
public class InventoryCountObservation extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public InventoryCountLine line;
 @Column(nullable=false,length=100) public String actor;
 @Column(nullable=false,length=64) public String requestKey;
 @Column(nullable=false,length=20) public String state;
 @Column(precision=19,scale=6) public BigDecimal quantity;
 @Column(nullable=false) public LocalDateTime countedAt;
 @Column(length=2000) public String comment;
 @Column(columnDefinition="text") public String evidence;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
