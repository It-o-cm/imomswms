package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.*;
@Entity @Table(name="mission_operational_states",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","handlingOrder_id"}))
public class MissionOperationalState extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public BaseHandlingOrder handlingOrder;
 @Column(length=20,nullable=false) public String state="ACTIVE";
 @Column(length=300,nullable=false) public String reason="";
 @Column(length=100,nullable=false) public String actor;
 public static void requireActive(Long orderId){
  if(orderId==null)return;BaseHandlingOrder order=BaseHandlingOrder.findById(orderId);if(order==null)return;
  io.quarkus.hibernate.orm.panache.Panache.getEntityManager().createNativeQuery("select id from stores where id=?1 for update").setParameter(1,FieldEntityIds.id(order.store)).getSingleResult();
  MissionOperationalState state=MissionOperationalState.find("handlingOrder.id=?1 and state<>'ACTIVE'",orderId).firstResult();if(state!=null)throw new IllegalArgumentException("Mission suspendue : "+state.reason+". Reprenez-la avant une nouvelle opération.");
 }
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
