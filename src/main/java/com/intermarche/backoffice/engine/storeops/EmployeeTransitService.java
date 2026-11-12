package com.intermarche.backoffice.engine.storeops;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.workforce.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import jakarta.enterprise.context.ApplicationScoped;
import org.hibernate.Hibernate;
import java.time.*;
import java.util.*;
/** Planning may describe transits; operator work never requires a declared employee position. */
@ApplicationScoped
public class EmployeeTransitService {
 @jakarta.inject.Inject jakarta.persistence.EntityManager em;
 private Long id(Building b){return b==null?null:Hibernate.unproxy(b,Building.class).id;}
 public EmployeePosition position(Employee e){return EmployeePosition.find("employee",e).firstResult();}
 private PlanningEntry presence(Employee e){var now=DateTimeProvider.now();return PlanningEntry.find("employee=?1 and kind='SHIFT' and cancelled=false and building is not null and startsAt<=?2 and endsAt>?2 order by startsAt desc",e,now).firstResult();}
 public Map<String,Object> view(Employee e){var p=position(e);var shift=p==null?presence(e):null;var b=p==null?(shift==null?null:shift.building):p.building;var v=new LinkedHashMap<String,Object>();v.put("buildingId",id(b));v.put("buildingCode",b==null?null:b.buildingCode);v.put("availableAt",p==null?(shift==null?null:shift.startsAt):p.availableAt);v.put("inTransit",p!=null&&p.availableAt.isAfter(DateTimeProvider.now()));return v;}
 public Map<String,Object> move(Employee e,Building destination){
  var now=DateTimeProvider.now();var p=position(e);if(p!=null && p.availableAt.isAfter(now)){if(Objects.equals(id(p.building),id(destination)))return view(e);throw new IllegalArgumentException("Un transit est déjà en cours jusqu’à "+p.availableAt);}
  var shift=p==null?presence(e):null;Building origin=p==null?(shift==null?null:shift.building):p.building;
  int minutes=0;if(origin!=null&&!Objects.equals(id(origin),id(destination))){var t=BuildingTransit.<BuildingTransit>find("store=?1 and origin=?2 and destination=?3",e.store,origin,destination).firstResult();if(t==null)throw new IllegalArgumentException("Configurez d’abord le transit "+origin.buildingCode+" → "+destination.buildingCode);minutes=t.minutes;}
  if(p==null){p=new EmployeePosition();p.employee=e;}p.origin=origin;p.building=destination;p.availableAt=now.plusMinutes(minutes);p.persistAndFlush();return view(e);
 }
 public void require(Employee e,BaseHandlingOrder order,Building target){
  if(e==null || e.store==null || !Hibernate.unproxy(e.store,Store.class).id.equals(Hibernate.unproxy(order.store,Store.class).id))throw new IllegalArgumentException("Employé hors du magasin");
  em.lock(Hibernate.unproxy(order.store,Store.class),jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
  // Existing installations activate physical transit control when their first directed route is configured.
  if(BuildingTransit.count("store",order.store)==0)return;
  if(target==null)throw new IllegalArgumentException("Bâtiment de l’opération non renseigné");
  if(order.assignee!=null&&!Hibernate.unproxy(order.assignee,Employee.class).id.equals(e.id))throw new IllegalArgumentException("Cette opération est affectée à "+order.assignee.getFullName()+". Corrigez l’affectation avant de démarrer.");
  // A destination is work guidance, not a requirement to locate or track the operator.
 }
}
