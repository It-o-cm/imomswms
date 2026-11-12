package com.intermarche.backoffice.domain.commerce;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

/** Last pickup view published to commerce for an order: its revision only grows, also across restarts. */
@Entity @Table(name="commerce_pickup_states")
public class CommercePickupState extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @OneToOne(optional=false,fetch=FetchType.LAZY) public Order order;
 @Column(nullable=false) public long revision=1;
 /** Contract order state last observed. */
 @Column(nullable=false,length=20) public String orderState;
 @Column(nullable=false) public LocalDateTime changedAt;
 /**
  * Returns the checksum of the identity of this state.
  *
  * @return the checksum
  */
 @Override public int getChecksum(){return Objects.hash(id);}
}
