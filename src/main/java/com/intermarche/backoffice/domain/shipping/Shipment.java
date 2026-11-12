package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;
@Entity @Table(name="shipments")
public class Shipment extends BaseEntity {
    @ManyToOne(optional=false) public Store store;
    @ManyToOne(optional=false) public Order order;
    @Column(unique=true) public String requestKey;
    public String requestHash,code,mode,recipient,transportReference,performedBy;
    public LocalDateTime dispatchedAt;
    @OneToMany(cascade=CascadeType.ALL) @JoinColumn(name="shipment_id") public List<ShipmentLine> lines=new ArrayList<>();
    @Override public int getChecksum(){return Objects.hash(requestKey,order,code,dispatchedAt);}
}
