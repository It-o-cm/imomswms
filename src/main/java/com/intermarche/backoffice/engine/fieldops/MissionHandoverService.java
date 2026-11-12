package com.intermarche.backoffice.engine.fieldops;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.operator.OperatorProfile;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.executing.OperatorExecutionService;
import com.intermarche.backoffice.engine.operator.*;
import com.intermarche.backoffice.engine.storeops.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.util.*;

/** Offers do not free the source operator. Only recipient acceptance transfers custody. */
@ApplicationScoped @Transactional
public class MissionHandoverService {
 @Inject SupportLayoutService layouts;@Inject FieldOperationsService field;@Inject StockCommands commandReceipts;@Inject OperatorExecutionService operator;@Inject PlanningAssignmentService planning;
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="MissionHandoverOffer") public static class Offer {public Long storeId,targetId,cartId;public String requestKey,expectedRevision,reason;}
 private void eligible(Store store,BaseHandlingOrder order,Employee target){
  if(target==null||!target.active||target.role!=Employee.EmployeeRole.PICKER||!id(target.store).equals(store.id))throw new BadRequestException("Destinataire actif et habilité du magasin requis");
  OperatorProfile profile=OperatorProfile.find("store=?1 and principal=?2",store,target.badgeId).firstResult();if(profile!=null&&!"AVAILABLE".equals(profile.state))throw new OperatorFailure(409,"RECIPIENT_UNAVAILABLE","Le destinataire est en pause ou a terminé son poste");
  var now=DateTimeProvider.now();var until=order.requestedEnd.isAfter(now)?order.requestedEnd:now.plusMinutes(15);
  var context=planning.context(store,now.toLocalDate().atStartOfDay(),until.toLocalDate().plusDays(1).atStartOfDay());
  if(!planning.reasons(context,target,now,until,null,order.id,"HANDLING","PICKER").isEmpty())throw new OperatorFailure(409,"RECIPIENT_PLANNING_CONFLICT","Le destinataire ne couvre pas le créneau restant sans conflit");
 }
 public Object candidates(Long storeId,Long orderId){Store store=field.store(storeId,false);BaseHandlingOrder order=field.order(store,orderId);if(!field.manager()&&(order.assignee==null||!order.assignee.badgeId.equals(field.actor())))throw new ForbiddenException();var now=DateTimeProvider.now();var until=order.requestedEnd.isAfter(now)?order.requestedEnd:now.plusMinutes(15);var paused=OperatorProfile.<OperatorProfile>list("store=?1 and state<>'AVAILABLE'",store).stream().map(p->p.principal).collect(java.util.stream.Collectors.toSet());var employees=Employee.<Employee>list("store=?1 and active=true and role=?2",store,Employee.EmployeeRole.PICKER).stream().collect(java.util.stream.Collectors.toMap(e->e.id,e->e));return planning.candidates(store,now,until,null,orderId,"HANDLING","PICKER").stream().filter(c->!Objects.equals(c.employeeId(),id(order.assignee))&&!paused.contains(employees.get(c.employeeId()).badgeId)).toList();}
 private int transitMinutes(Store store,BaseHandlingOrder order,Employee target){
  var concrete=org.hibernate.Hibernate.unproxy(order,BaseHandlingOrder.class);var required=concrete instanceof com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder r&&r.supplies.stream().allMatch(s->s.pickedQuantity().compareTo(s.quantity)>=0)?r.destinationBuilding:GlobalPlanningService.startBuilding(order);if(required==null&&concrete instanceof com.intermarche.backoffice.domain.wms.handling.PreparingOrder preparing){var buildings=preparing.lots.stream().filter(l->l.chunk!=null&&l.chunk.building!=null).map(l->l.chunk.building).collect(java.util.stream.Collectors.toMap(b->id(b),b->b,(a,b)->a));if(buildings.size()==1)required=buildings.values().iterator().next();}
  if(required==null)throw new BadRequestException("Bâtiment de la mission requis");
  var position=com.intermarche.backoffice.domain.workforce.EmployeePosition.<com.intermarche.backoffice.domain.workforce.EmployeePosition>find("employee",target).firstResult();var now=DateTimeProvider.now();
  var shift=com.intermarche.backoffice.domain.workforce.PlanningEntry.<com.intermarche.backoffice.domain.workforce.PlanningEntry>find("employee=?1 and kind='SHIFT' and cancelled=false and startsAt<=?2 and endsAt>?2 and building is not null order by startsAt desc",target,now).firstResult();
  Building origin=position==null?(shift==null?null:shift.building):position.building;
  if(position!=null&&position.availableAt.isAfter(now))throw new OperatorFailure(409,"RECIPIENT_IN_TRANSIT","Le destinataire a déjà un transit en cours");
  if(origin==null||id(origin).equals(id(required)))return 0;
  var transit=com.intermarche.backoffice.domain.workforce.BuildingTransit.<com.intermarche.backoffice.domain.workforce.BuildingTransit>find("store=?1 and origin=?2 and destination=?3",store,origin,required).firstResult();
  if(transit==null)throw new OperatorFailure(409,"RECIPIENT_TRANSIT_UNDEFINED","Configurez le transit entre le bâtiment de départ et celui de la mission");return transit.minutes;
 }
 private Map<String,Object> view(MissionHandover h){var v=new LinkedHashMap<String,Object>();v.put("id",h.id);v.put("orderId",id(h.handlingOrder));v.put("orderCode",h.handlingOrder.code);v.put("state",h.state);v.put("sourceMine",h.source.badgeId.equals(field.actor()));v.put("targetMine",h.target.badgeId.equals(field.actor()));v.put("canCancel","OFFERED".equals(h.state)&&(field.manager()||h.source.badgeId.equals(field.actor())));v.put("sourceId",id(h.source));v.put("sourceName",h.source.getFullName());v.put("targetId",id(h.target));v.put("targetName",h.target.getFullName());v.put("reason",h.reason);v.put("expired",!h.expiresAt.isAfter(DateTimeProvider.now()));v.put("expiresAt",h.expiresAt);v.put("readyAt",h.readyAt);v.put("cartId",h.cartId);if(h.orderSnapshots!=null)v.put("scope",field.decode(h.orderSnapshots,Map.class));v.put("canAccept","OFFERED".equals(h.state)&&h.target.badgeId.equals(field.actor())&&!h.readyAt.isAfter(DateTimeProvider.now())&&h.expiresAt.isAfter(DateTimeProvider.now()));return v;}
 public Object list(Long storeId,Long orderId){Store store=field.store(storeId,false);var params=new HashMap<String,Object>();params.put("store",store);String query="store=:store";if(orderId!=null){field.order(store,orderId);query+=" and handlingOrder.id=:order";params.put("order",orderId);}if(!field.manager()){query+=" and (source.badgeId=:actor or target.badgeId=:actor)";params.put("actor",field.actor());}return MissionHandover.<MissionHandover>find(query+" order by id desc",params).range(0,99).list().stream().map(this::view).toList();}
 @SuppressWarnings("unchecked") public Object offer(Long orderId,Offer c){
  if(c==null)throw new BadRequestException();FieldOperationsService.key(c.requestKey);FieldOperationsService.text(c.reason,500);Store store=field.store(c.storeId,true);BaseHandlingOrder order=field.order(store,orderId);
  String hash=field.hash(Arrays.asList(orderId,c));MissionHandover old=MissionHandover.find("store=?1 and requester=?2 and requestKey=?3",store,field.actor(),c.requestKey).firstResult();if(old!=null){if(!old.fingerprint.equals(hash))throw new OperatorFailure(409,"IDEMPOTENCY_KEY_REUSED","Cette référence désigne une autre transmission");return view(old);}
  if(order.assignee==null)throw new BadRequestException("Affectez la mission avant de proposer sa transmission");if(!field.manager()&&!order.assignee.badgeId.equals(field.actor()))throw new ForbiddenException();
  if(Set.of(BaseHandlingOrder.HandlingOrderStatus.CANCELLED,BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.FAILED).contains(order.status))throw new BadRequestException("Mission fermée");
  var snapshot=(Map<String,Object>)operator.order(store.id,orderId);if(!Objects.equals(c.expectedRevision,snapshot.get("revision")))throw new OperatorFailure(409,"STALE_REVISION","La mission a changé. Actualisez avant de transmettre.");
  Employee target=Employee.findById(c.targetId);eligible(store,order,target);if(id(target).equals(id(order.assignee)))throw new BadRequestException("Choisissez un autre préparateur");
  var h=new MissionHandover();h.store=store;h.handlingOrder=order;h.source=order.assignee;h.target=target;h.requester=field.actor();h.requestKey=c.requestKey;h.fingerprint=hash;h.orderRevision=c.expectedRevision;h.reason=c.reason.trim();int transit=transitMinutes(store,order,target);if(c.cartId!=null){var bundle=cartBundle(store,c.cartId);@SuppressWarnings("unchecked") var members=(List<Map<String,Object>>)bundle.get("orders");if(members.stream().noneMatch(v->((Number)v.get("id")).longValue()==orderId))throw new BadRequestException("Cette mission ne fait pas partie du chariot");for(var member:members){BaseHandlingOrder linked=field.order(store,((Number)member.get("id")).longValue());if(linked.assignee==null||!id(linked.assignee).equals(id(order.assignee)))throw new OperatorFailure(409,"CART_SHARED_CUSTODY","Le chariot doit dépendre d’un seul préparateur");eligible(store,linked,target);transit=Math.max(transit,transitMinutes(store,linked,target));}h.cartId=c.cartId;h.orderSnapshots=field.encode(bundle);}h.readyAt=DateTimeProvider.now().plusMinutes(transit);h.expiresAt=h.readyAt.plusMinutes(30);h.persistAndFlush();field.notice(store,"handover:"+h.id,"Transmission proposée · "+order.code,h.source.getFullName()+" propose de vous transmettre la mission : "+h.reason,orderId,List.of(target.badgeId,h.source.badgeId));return commandReceipts.record(store,c.requestKey,hash,"OFFER_HANDOVER",view(h));
 }
 private Map<String,Object> cartBundle(Store store,Long cartId){
  var cart=PhysicalSupport.<PhysicalSupport>find("id=?1 and store=?2",cartId,store).firstResult();if(cart==null||cart.type.kind!=SupportType.Kind.CART)throw new BadRequestException("Chariot du magasin requis");
  var first=CartCompartment.<CartCompartment>list("cart=?1 and handlingOrder is not null order by id",cart);var ids=new TreeSet<Long>();first.forEach(c->ids.add(id(c.handlingOrder)));if(ids.isEmpty())throw new BadRequestException("Ce chariot ne porte aucune mission");
  var affected=CartCompartment.<CartCompartment>list("handlingOrder.id in ?1 order by id",ids).stream().map(c->id(c.cart)).distinct().toList();
  var slots=CartCompartment.<CartCompartment>list("cart.id in ?1 order by id",affected);for(var slot:slots)if(slot.handlingOrder!=null&&!ids.contains(id(slot.handlingOrder)))throw new OperatorFailure(409,"CART_SHARED_CUSTODY","Une mission partage un autre chariot avec un collègue : séparez les supports avant transmission");
  var orders=new ArrayList<Map<String,Object>>();for(Long id:ids){BaseHandlingOrder order=field.order(store,id);if(Set.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED,BaseHandlingOrder.HandlingOrderStatus.FAILED).contains(order.status))throw new BadRequestException("Une mission du chariot est fermée");orders.add(Map.of("id",id,"code",order.code,"revision",operator.currentRevisionForHandover(order)));}
  var result=new LinkedHashMap<String,Object>();result.put("cartId",cart.id);result.put("cartCode",cart.code);result.put("orders",orders);result.put("layout",layouts.read(store.id,cartId));result.put("directives",LineDirective.<LineDirective>list("handlingOrder.id in ?1 order by id",ids).stream().map(d->Arrays.asList(d.id,d.version,d.state,d.priority,d.instructionVersion,d.deferReason)).toList());result.put("slots",slots.stream().map(c->Arrays.asList(c.id,c.version,id(c.cart),c.handlingOrder==null?null:id(c.handlingOrder),c.occupant==null?null:id(c.occupant),c.customerOrder==null?null:id(c.customerOrder))).toList());return result;
 }
 @SuppressWarnings("unchecked") private Object acceptCart(Store store,MissionHandover h){
  var bundle=cartBundle(store,h.cartId);if(!CommandCanonical.hash(field.encode(bundle)).equals(CommandCanonical.hash(h.orderSnapshots)))throw new OperatorFailure(409,"STALE_HANDOVER","Le chariot ou une mission a changé : renouvelez la proposition");
  var members=(List<Map<String,Object>>)bundle.get("orders");var orders=new ArrayList<BaseHandlingOrder>();var children=new ArrayList<BaseHandlingOrder>();
  for(var row:members){BaseHandlingOrder order=field.order(store,((Number)row.get("id")).longValue());if(order.assignee==null||!id(order.assignee).equals(id(h.source)))throw new OperatorFailure(409,"STALE_HANDOVER","Une mission n’appartient plus au préparateur initial");eligible(store,order,h.target);orders.add(order);
   for(var child:com.intermarche.backoffice.domain.wms.handling.CancellationDispositionOrder.<com.intermarche.backoffice.domain.wms.handling.CancellationDispositionOrder>list("origin=?1 and status not in ?2",order,List.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED))){if(child.assignee!=null&&!id(child.assignee).equals(id(h.source)))throw new OperatorFailure(409,"SHARED_CUSTODY_CONFLICT","Un traitement lié dépend d’un autre opérateur");children.add(child);}
  }
  var all=new ArrayList<>(orders);all.addAll(children);for(var order:all){order.assignee=h.target;for(var execution:HandlingExecution.<HandlingExecution>list("handlingOrder=?1 and status in ?2",order,List.of(HandlingExecution.ExecutionStatus.PENDING,HandlingExecution.ExecutionStatus.IN_PROGRESS)))execution.employee=h.target;for(var sibling:MissionHandover.<MissionHandover>list("handlingOrder=?1 and state='OFFERED' and id<>?2",order,h.id))sibling.state="CANCELLED";}
  h.state="ACCEPTED";field.flush();var result=view(h);result.put("transferred",true);h.result=field.encode(result);for(var order:orders)field.notice(store,"cart-handover:"+h.id+":"+order.id,"Chariot transmis · "+bundle.get("cartCode"),"Missions, compartiments, bacs et quantités conservés. Nouveau préparateur : "+h.target.getFullName(),id(order),List.of(h.source.badgeId,h.target.badgeId));return result;
 }
 @SuppressWarnings("unchecked") public Object decide(Long storeId,Long handoverId,String action){
  Store store=field.store(storeId,true);MissionHandover h=MissionHandover.find("id=?1 and store=?2",handoverId,store).firstResult();if(h==null)throw new NotFoundException();
  if("CANCEL".equals(action)){if(!field.manager()&&!h.source.badgeId.equals(field.actor()))throw new ForbiddenException();if(!"OFFERED".equals(h.state))throw new OperatorFailure(409,"HANDOVER_CLOSED","Cette proposition est déjà traitée");h.state="CANCELLED";return view(h);}
  if(!h.target.badgeId.equals(field.actor()))throw new ForbiddenException("Seul le destinataire peut accepter ou refuser");
  if("ACCEPTED".equals(h.state)&&"ACCEPT".equals(action))return field.decode(h.result,Map.class);
  if(!"OFFERED".equals(h.state))throw new OperatorFailure(409,"HANDOVER_CLOSED","Cette proposition est déjà traitée");
  if("DECLINE".equals(action)){h.state="DECLINED";return view(h);}if(!"ACCEPT".equals(action))throw new BadRequestException("Décision inconnue");
  if(!h.expiresAt.isAfter(DateTimeProvider.now()))throw new OperatorFailure(409,"HANDOVER_EXPIRED","La proposition a expiré");
  if(h.readyAt.isAfter(DateTimeProvider.now()))throw new OperatorFailure(409,"HANDOVER_TRANSIT_PENDING","Temps de transit prévu jusqu’à "+h.readyAt+". La mission reste au préparateur actuel jusque-là.");
  BaseHandlingOrder order=h.handlingOrder;
  // Revision lookup must be authorized for the current custodian. No permission bypass of operator reads.
  String revision=operator.currentRevisionForHandover(order);
  if(!Objects.equals(id(order.assignee),id(h.source))||!h.orderRevision.equals(revision))throw new OperatorFailure(409,"STALE_HANDOVER","La mission a évolué. Le préparateur doit renouveler la proposition.");
  if(h.cartId!=null)return acceptCart(store,h);
  eligible(store,order,h.target);
  for(var child:com.intermarche.backoffice.domain.wms.handling.CancellationDispositionOrder.<com.intermarche.backoffice.domain.wms.handling.CancellationDispositionOrder>list("origin=?1 and status not in ?2",order,List.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED))){
   if(child.assignee!=null&&!Objects.equals(id(child.assignee),id(h.source)))throw new OperatorFailure(409,"SHARED_CUSTODY_CONFLICT","Un traitement lié dépend d’un autre préparateur. Faites arbitrer la responsabilité du bac avant de transmettre.");
   child.assignee=h.target;for(var execution:HandlingExecution.<HandlingExecution>list("handlingOrder=?1 and status in ?2",child,List.of(HandlingExecution.ExecutionStatus.PENDING,HandlingExecution.ExecutionStatus.IN_PROGRESS)))execution.employee=h.target;
  }
  SupportLayoutService.requireHandoverTogether(order);
  order.assignee=h.target;
  for(var execution:HandlingExecution.<HandlingExecution>list("handlingOrder=?1 and status in ?2",order,List.of(HandlingExecution.ExecutionStatus.PENDING,HandlingExecution.ExecutionStatus.IN_PROGRESS)))execution.employee=h.target;
  for(var sibling:MissionHandover.<MissionHandover>list("handlingOrder=?1 and state='OFFERED' and id<>?2",order,h.id))sibling.state="CANCELLED";
  h.state="ACCEPTED";field.flush();var result=view(h);result.put("transferred",true);h.result=field.encode(result);field.notice(store,"handover-accepted:"+h.id,"Mission transmise · "+order.code,"Bacs et quantités conservés. Nouveau préparateur : "+h.target.getFullName(),id(order),List.of(h.source.badgeId,h.target.badgeId));return result;
 }
}
