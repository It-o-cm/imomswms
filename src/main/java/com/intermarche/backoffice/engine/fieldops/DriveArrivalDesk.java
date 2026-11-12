package com.intermarche.backoffice.engine.fieldops;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;

import com.intermarche.backoffice.domain.commerce.*;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.commerce.PickupContract;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.time.Duration;
import java.util.*;
import org.hibernate.Hibernate;

/**
 * The store side of Drive arrivals: the queue of customers announced by commerce, taken in charge then handed over
 * by an operator, the arrival window of the store and the commerce identities allowed on it.
 */
@ApplicationScoped @Transactional
public class DriveArrivalDesk {
 @Inject FieldOperationsService field;@Inject StockCommands receipts;@Inject PickupContract contract;

 /** Command body of the desk endpoints. */
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="DriveArrivalInput") public static class Input {public Long storeId,arrivalId,expectedRevision;public String requestKey,reason,principal;public Integer minutesBefore,minutesAfter;public Boolean active;}

 /**
  * Lists the arrivals of the store: those still to serve, oldest first, or the closed ones, latest first.
  *
  * @param storeId the store
  * @param closed true for the closed arrivals
  * @return the arrivals and the window of the store
  */
 public Object queue(Long storeId,boolean closed){Store s=field.store(storeId,false);
  var rows=closed?DriveArrival.<DriveArrival>find("select a from DriveArrival a join fetch a.order where a.store=?1 and a.state in ?2 order by a.receivedAt desc",s,Set.of(DriveArrival.COMPLETED,DriveArrival.CANCELLED)).range(0,99).list()
   :DriveArrival.<DriveArrival>find("select a from DriveArrival a join fetch a.order where a.store=?1 and a.state in ?2 order by a.receivedAt",s,Set.of(DriveArrival.RECEIVED,DriveArrival.ACKNOWLEDGED)).list();
  var v=new LinkedHashMap<String,Object>();v.put("items",rows.stream().map(this::view).toList());v.put("policy",policyView(s));v.put("canManage",field.manager());return v;}

 /**
  * Takes an arrival in charge: the customer is told the store is coming.
  *
  * @param i the arrival and its revision
  * @return the arrival
  */
 public Object acknowledge(Input i){return act(i,"ACKNOWLEDGE_DRIVE_ARRIVAL",a->contract.acknowledge(a,field.actor()));}
 /**
  * Closes an arrival once its order is handed over to the customer.
  *
  * @param i the arrival and its revision
  * @return the arrival
  */
 public Object complete(Input i){return act(i,"COMPLETE_DRIVE_ARRIVAL",a->contract.complete(a,field.actor()));}
 /**
  * Cancels an arrival, with its reason.
  *
  * @param i the arrival, its revision and the reason
  * @return the arrival
  */
 public Object cancel(Input i){if(i!=null)FieldOperationsService.text(i.reason,255);return act(i,"CANCEL_DRIVE_ARRIVAL",a->contract.cancel(a,field.actor(),i.reason.trim()));}

 /**
  * Runs an idempotent, revision-checked action on an arrival.
  *
  * @param i the command
  * @param operation the receipt name
  * @param change the change
  * @return the arrival
  */
 Object act(Input i,String operation,java.util.function.Consumer<DriveArrival> change){if(i==null)throw new BadRequestException();FieldOperationsService.key(i.requestKey);Store s=field.store(i.storeId,true);String fp=field.hash(i);var previous=receipts.previous(s,i.requestKey,fp);if(previous!=null)return receipts.value(previous);
  DriveArrival a=DriveArrival.find("id=?1 and store=?2",i.arrivalId,s).firstResult();if(a==null)throw new NotFoundException();
  if(i.expectedRevision==null||a.revisionNumber!=i.expectedRevision)throw new OperatorFailure(409,"STALE_ARRIVAL","Cette arrivée a changé : actualisez avant de confirmer.");
  try{change.accept(a);}catch(IllegalStateException e){throw new OperatorFailure(409,"ARRIVAL_PHASE_CHANGED",e.getMessage());}
  return receipts.record(s,i.requestKey,fp,operation,view(a));}

 /**
  * Sets the arrival window of the store around the pickup slots.
  *
  * @param i minutes accepted before and after the slot
  * @return the queue
  */
 public Object policy(Input i){if(i==null)throw new BadRequestException();FieldOperationsService.key(i.requestKey);Store s=field.store(i.storeId,true);field.requireManager();String fp=field.hash(i);var previous=receipts.previous(s,i.requestKey,fp);if(previous!=null)return receipts.value(previous);
  if(i.minutesBefore==null||i.minutesAfter==null||i.minutesBefore<0||i.minutesBefore>720||i.minutesAfter<0||i.minutesAfter>720)throw new BadRequestException("Fenêtre en minutes, de 0 à 720 avant et après le créneau");
  DriveArrivalPolicy p=DriveArrivalPolicy.find("store",s).firstResult();
  if(p==null){p=new DriveArrivalPolicy();p.store=s;}else{if(i.expectedRevision==null||p.revisionNumber!=i.expectedRevision)throw new OperatorFailure(409,"STALE_POLICY","La fenêtre a changé : actualisez avant de confirmer.");p.revisionNumber++;}
  p.minutesBefore=i.minutesBefore;p.minutesAfter=i.minutesAfter;p.updatedBy=field.actor();p.persist();
  return receipts.record(s,i.requestKey,fp,"SET_DRIVE_ARRIVAL_POLICY",policyView(s));}

 /**
  * Lists the commerce identities allowed on the store.
  *
  * @param storeId the store
  * @return the grants
  */
 public Object grants(Long storeId){Store s=field.store(storeId,false);field.requireManager();return CommerceStoreGrant.<CommerceStoreGrant>list("store=?1 order by principal",s).stream().map(g->Map.of("id",g.id,"principal",g.principal,"sourceSystem",g.sourceSystem,"active",g.active,"grantedBy",g.grantedBy)).toList();}

 /**
  * Opens or closes the store to a commerce identity; reserved to administrators.
  *
  * @param i the technical identity and whether it is allowed
  * @param admin whether the caller is an administrator
  * @return the grants
  */
 public Object grant(Input i,boolean admin){if(i==null)throw new BadRequestException();FieldOperationsService.key(i.requestKey);Store s=field.store(i.storeId,true);if(!admin)throw new ForbiddenException();String fp=field.hash(i);var previous=receipts.previous(s,i.requestKey,fp);if(previous!=null)return receipts.value(previous);
  FieldOperationsService.text(i.principal,100);String principal=i.principal.trim();
  CommerceStoreGrant g=CommerceStoreGrant.find("store=?1 and principal=?2 and sourceSystem=?3",s,principal,PickupContract.SOURCE).firstResult();
  if(g==null){g=new CommerceStoreGrant();g.store=s;g.principal=principal;g.sourceSystem=PickupContract.SOURCE;}
  g.active=i.active==null||i.active;g.grantedBy=field.actor();g.persist();field.flush();
  return receipts.record(s,i.requestKey,fp,"GRANT_COMMERCE_STORE",grants(s.id));}

 /**
  * Describes the window of a store.
  *
  * @param s the store
  * @return minutes before and after the slot, and whether they are the defaults
  */
 static Map<String,Object> policyView(Store s){DriveArrivalPolicy p=DriveArrivalPolicy.find("store",s).firstResult();var v=new LinkedHashMap<String,Object>();
  v.put("minutesBefore",p==null?DriveArrivalPolicy.DEFAULT_BEFORE:p.minutesBefore);v.put("minutesAfter",p==null?DriveArrivalPolicy.DEFAULT_AFTER:p.minutesAfter);v.put("revision",p==null?null:p.revisionNumber);v.put("defaults",p==null);return v;}

 /**
  * Describes an arrival for the store team.
  *
  * @param a the arrival
  * @return the view
  */
 Map<String,Object> view(DriveArrival a){var v=new LinkedHashMap<String,Object>();var o=a.order;Customer c=o.customer==null?null:Hibernate.unproxy(o.customer,Customer.class);
  v.put("id",a.id);v.put("arrivalId",a.arrivalId);v.put("state",a.state);v.put("revision",a.revisionNumber);v.put("orderId",id(o));v.put("orderReference",o.orderId);
  v.put("customer",c==null?null:(Objects.toString(c.firstName,"")+" "+Objects.toString(c.lastName,"")).trim());
  v.put("parkingKind",a.parkingKind);v.put("parkingLabel",a.parkingLabel);v.put("receivedAt",a.receivedAt);
  v.put("waitingMinutes",a.active()?Duration.between(a.receivedAt,DateTimeProvider.now()).toMinutes():null);
  v.put("acknowledgedAt",a.acknowledgedAt);v.put("acknowledgedBy",a.acknowledgedBy);v.put("completedAt",a.completedAt);v.put("cancelledAt",a.cancelledAt);v.put("cancelReason",a.cancelReason);
  v.put("orderHandedOver","SHIPPED".equals(o.status));return v;}
}
