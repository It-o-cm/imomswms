package com.intermarche.backoffice.engine.fieldops;

import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.handling.*;
import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.shipping.*;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.*;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;

/** Rechecked in the same transaction as completion; an open incident is a status, not a shortage. */
@ApplicationScoped @Transactional
public class MissionClosureService {
 @Inject FieldOperationsService field;
 @Inject ParcelService parcels;
 public Map<String,Object> read(Long storeId,Long orderId){var store=field.store(storeId,false);var order=field.order(store,orderId);
  if(!field.manager()&&(order.assignee==null||!Objects.equals(org.hibernate.Hibernate.unproxy(order.assignee,com.intermarche.backoffice.domain.store.Employee.class).badgeId,field.actor())))throw new jakarta.ws.rs.ForbiddenException();
  return review(order);
 }
 private List<PickedItem> items(BaseHandlingOrder order){var out=new LinkedHashMap<Long,PickedItem>();
  for(var raw:HandlingExecution.<HandlingExecution>list("handlingOrder",order)){
   var e=org.hibernate.Hibernate.unproxy(raw,HandlingExecution.class);
   if(e instanceof PickingExecution p){for(var item:p.itemsToPick)if(item.status==PickedItem.PickStatus.PICKED&&item.quantity.signum()>0)out.put(item.id,item);for(var tote:p.loadedTotes)for(var item:tote.items)if(item.status==PickedItem.PickStatus.PICKED&&item.quantity.signum()>0)out.put(item.id,item);}
  }return new ArrayList<>(out.values());
 }
 public Map<String,Object> review(BaseHandlingOrder order){var items=items(order);var out=new LinkedHashMap<String,Object>();
  out.put("orderId",order.id);out.put("requireScan",required(order));
  out.put("anomalies",FieldIncident.<FieldIncident>find("handlingOrder=?1 order by id",order).list().stream().map(i->{var v=new LinkedHashMap<String,Object>();v.put("id",i.id);v.put("title",i.title);v.put("state",i.state);v.put("outcome",i.outcome);v.put("nextAction",i.nextAction);return v;}).toList());
  out.put("shortages",PickingShortage.<PickingShortage>list("handlingOrder",order).stream().map(s->Map.of("id",s.id,"state","DECLARED")).toList());
  var codes=items.stream().filter(i->i.loadedTote!=null).map(i->i.loadedTote.tote.code).distinct().sorted().toList();out.put("supports",codes);
  try{out.put("items",parcels.finalTrace(items));out.put("traceError",null);}catch(OperatorFailure e){out.put("items",List.of());Object payload=e.getResponse().getEntity();out.put("traceError",payload instanceof Map<?,?> detail?detail.getOrDefault("message",null):"Vérifiez les lots et séries avant de terminer.");}
  out.put("traceErrors",out.get("traceError")==null?List.of():traceFailures(items));
  var linked=items.isEmpty()?List.<ParcelContent>of():ParcelContent.<ParcelContent>list("pickedItem in ?1",items);
  out.put("parcels",linked.stream().map(c->c.parcel).distinct().map(p->parcels.summary(p)).toList());return out;
 }
 List<Map<String,Object>> traceFailures(Collection<PickedItem> items){
  var failures=new LinkedHashMap<Long,Map<String,Object>>();
   var serialOwners=new HashMap<Long,PickedItem>();
   for(var item:items){
    try{var checked=parcels.finalTrace(List.of(item));for(var view:checked){
     @SuppressWarnings("unchecked") var trace=(List<Map<String,Object>>)view.get("trace");
     for(var t:trace)if(t.get("serialId") instanceof Number serial){var previous=serialOwners.putIfAbsent(serial.longValue(),item);if(previous!=null){failures.put(previous.id,traceFailure(previous));failures.put(item.id,traceFailure(item));}}
    }}catch(OperatorFailure ex){failures.put(item.id,traceFailure(item));}
   }
  return new ArrayList<>(failures.values());
 }
 private Map<String,Object> traceFailure(PickedItem item){var v=new LinkedHashMap<String,Object>();v.put("itemId",item.id);v.put("productId",id(item.product));v.put("lotLineId",id(item.lotLine));v.put("name",item.product.name);return v;}
 private boolean required(BaseHandlingOrder order){MissionPreparationContext c=MissionPreparationContext.find("handlingOrder",order).firstResult();return c!=null&&c.requireFinalScan;}
 public void requireFinal(BaseHandlingOrder order,List<String> scanned){for(var incident:FieldIncident.<FieldIncident>list("handlingOrder",order))if(incident.state==null||!Set.of("OPEN","OWNED","WAITING","RESOLVED","REJECTED").contains(incident.state))throw new OperatorFailure(409,"INCIDENT_STATUS_REQUIRED","Attribuez un état à chaque anomalie avant de terminer");var items=items(order);parcels.finalTrace(items);
  if(required(order)){var codes=items.stream().filter(i->i.loadedTote!=null).map(i->i.loadedTote.tote.code).collect(java.util.stream.Collectors.toSet());if(scanned==null||!new HashSet<>(scanned).containsAll(codes))throw new OperatorFailure(400,"FINAL_SCAN_REQUIRED","Scannez chaque support avant de terminer");}
  if(!items.isEmpty())for(var c:ParcelContent.<ParcelContent>list("pickedItem in ?1",items))if(!Set.of("CLOSED","CANCELLED").contains(c.parcel.state))throw new OperatorFailure(409,"PARCEL_NOT_CLOSED","Contrôlez et fermez le colis "+c.parcel.code+" avant de terminer");
 }
}
