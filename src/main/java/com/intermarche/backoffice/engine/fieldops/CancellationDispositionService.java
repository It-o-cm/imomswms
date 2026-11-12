package com.intermarche.backoffice.engine.fieldops;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.wms.handling.*;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.operator.*;
import com.intermarche.backoffice.engine.executing.*;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.util.*;

/** Cancellation creates explicit compensating work; it never pretends that a tote has been emptied. */
@ApplicationScoped @Transactional
public class CancellationDispositionService {
 @Inject com.intermarche.backoffice.engine.stock.PickedStockCoordinator stockTrace;
 @Inject com.intermarche.backoffice.engine.commerce.PickupContract arrivals;
 @Inject OperatorExecutionService operator;@Inject FieldOperationsService field;@Inject StockCommands commandReceipts;@Inject TemporalStockService stock;@Inject BufferOccupancyService buffers;
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="CustomerCancellationInput") public static class Cancel {public Long storeId;public List<Long> lineIds;public String requestKey,reason,policy="RETURN";}
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="CancellationDispositionInput") public static class Complete {public Long storeId,destinationId,destinationToteId,targetItemId,bufferId,cabinetId;public Integer cellRow,cellColumn;public String requestKey,expectedRevision,action,destinationToteCode;}
 private String nextCode(){long value=((Number)Order.getEntityManager().createNativeQuery("select next value for cancellation_order_codes_SEQ").getSingleResult()).longValue();String suffix=Long.toString(value,36).toUpperCase(java.util.Locale.ROOT);if(suffix.length()>6)throw new IllegalStateException("Compteur des ordres de traitement épuisé");return DateTimeProvider.now().format(java.time.format.DateTimeFormatter.ofPattern("ddMM"))+"-RET-"+"0".repeat(6-suffix.length())+suffix;}
 private static String revision(CancellationDisposition task){return CommandCanonical.hash("["+task.id+","+task.version+",\""+task.state+"\","+task.quantity+","+task.source.version+","+task.item.version+"]");}
 private Map<String,Object> view(CancellationDisposition t){var v=new LinkedHashMap<String,Object>();v.put("id",t.id);v.put("orderId",id(t.handlingOrder));v.put("originOrderId",id(t.handlingOrder.origin));v.put("productId",id(t.item.product));v.put("productName",t.item.product.name);v.put("ean",t.item.product.ean);v.put("zone",t.item.product.temperatureZone.name());v.put("quantity",t.quantity);v.put("unit",com.intermarche.backoffice.engine.catalog.ProductInformationService.unit(t.item.product));v.put("sourceToteId",id(t.source));v.put("sourceToteCode",t.source.tote.code);v.put("sourceAddress",t.source.buffer==null?"Bac en cours de manutention":t.source.buffer.building.buildingCode+" → "+t.source.buffer.locationDescription+(t.source.cabinet==null?"":" → Armoire "+t.source.cabinet.code+" → Cellule "+t.source.cellRow+"/"+t.source.cellColumn));v.put("state",t.state);v.put("policy",t.policy);v.put("instruction",t.instruction);v.put("revision",revision(t));v.put("originalLocationId",id(t.item.sourceLocation==null?t.item.lotLine.location:t.item.sourceLocation));v.put("originalLocationCode",(t.item.sourceLocation==null?t.item.lotLine.location:t.item.sourceLocation)==null?null:(t.item.sourceLocation==null?t.item.lotLine.location:t.item.sourceLocation).locationCode);v.put("destinationId",id(t.destination));v.put("destinationToteId",id(t.destinationTote));if("DONE".equals(t.state)){v.put("treatment",t.destination!=null?"RETURN":t.targetItemId!=null?"REALLOCATE":"ISOLATE");v.put("destinationAddress",t.destination!=null?t.destination.locationCode:t.destinationTote!=null?t.destinationTote.tote.code:null);}return v;}
 public Object lookup(Long storeId,String code){Store store=field.store(storeId,false);field.requireManager();if(code==null||code.isBlank()||code.length()>50)throw new BadRequestException("Référence de commande requise");Order customer=Order.find("store=?1 and orderId=?2",store,code).firstResult();if(customer==null)throw new NotFoundException("Commande commerciale introuvable");var lines=LotLine.<LotLine>list("select distinct line from Lot lot join lot.lines line join fetch line.product where lot.store=?1 and lot.fulfillment.order=?2",store,customer);return Map.of("id",customer.id,"code",customer.orderId,"status",customer.status,"lines",lines.stream().map(l->Map.of("id",l.id,"name",l.product.name,"ean",l.product.ean,"quantity",l.quantity)).toList());}
 public Object read(Long storeId,Long orderId){Store store=field.store(storeId,false);var order=field.order(store,orderId);if(!(org.hibernate.Hibernate.unproxy(order) instanceof CancellationDispositionOrder))throw new NotFoundException();return CancellationDisposition.<CancellationDisposition>list("handlingOrder=?1 order by id",order).stream().map(this::view).toList();}
 public Object choices(Long storeId,Long taskId){Store store=field.store(storeId,false);CancellationDisposition task=CancellationDisposition.find("id=?1 and handlingOrder.store=?2",taskId,store).firstResult();if(task==null)throw new NotFoundException();
  var free=Tote.<Tote>find("select t from Tote t where t.store=?1 and not exists (select l.id from LoadedTote l where l.tote=t and l.status=?2) order by t.code",store,LoadedTote.Status.IN_USE).range(0,99).list().stream().filter(t->t.isUsageAllowed(task.item.product.temperatureZone)).map(t->Map.of("code",t.code,"type",t.toteType,"volume",t.volume)).toList();
  var targets=new ArrayList<Map<String,Object>>();
  var rows=PickedItem.getEntityManager().createQuery("select i.id,t.id,e.handlingOrder.code,e.employee.firstName,e.employee.lastName,t.tote.code from PickingExecution e join e.itemsToPick i join e.loadedTotes t where e.handlingOrder.store=:store and e.status=:executionStatus and i.product=:product and i.status=:pending and i.quantity=:qty and t.status=:toteStatus and t.storageType=:zone and not exists (select c.id from CancelledMissionLine c where c.line=i.lotLine) and (:manager=true or e.employee.badgeId=:actor) order by i.id,t.id",Object[].class).setParameter("store",store).setParameter("executionStatus",com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.IN_PROGRESS).setParameter("product",task.item.product).setParameter("pending",PickedItem.PickStatus.PENDING).setParameter("qty",task.quantity).setParameter("toteStatus",LoadedTote.Status.IN_USE).setParameter("zone",task.item.product.temperatureZone).setParameter("manager",field.manager()).setParameter("actor",field.actor()).setMaxResults(100).getResultList();
  for(var row:rows)targets.add(Map.of("itemId",row[0],"toteId",row[1],"label",row[2]+" · "+row[3]+" "+row[4]+" · bac "+row[5]));
  return Map.of("freeSupports",free,"targets",targets,"buffers",buffers.occupancy(store));
 }
 /**
  * Cancels all or part of a customer order once, idempotently per request key, and records the receipt.
  *
  * @param customerId the cancelled customer order
  * @param c the cancellation request
  * @return the cancelled lines, the compensating handling orders and their tasks
  */
 @SuppressWarnings("unchecked") public Object cancel(Long customerId,Cancel c){
  if(c==null)throw new BadRequestException();FieldOperationsService.key(c.requestKey);FieldOperationsService.text(c.reason,500);if(!Set.of("RETURN","REALLOCATE","ISOLATE").contains(c.policy==null?"":c.policy))throw new BadRequestException("Politique : RETURN, REALLOCATE ou ISOLATE");Store store=field.store(c.storeId,true);field.requireManager();Order customer=Order.find("id=?1 and store=?2",customerId,store).firstResult();if(customer==null)throw new NotFoundException();String hash=field.hash(Arrays.asList(customerId,c));CustomerCancellation previous=CustomerCancellation.find("store=?1 and requestKey=?2",store,c.requestKey).firstResult();if(previous!=null){if(!hash.equals(previous.fingerprint))throw new OperatorFailure(409,"IDEMPOTENCY_KEY_REUSED","Cette référence désigne une autre annulation");return field.decode(previous.result,Map.class);}
  var lines=LotLine.<LotLine>list("select distinct line from Lot lot join lot.lines line where lot.store=?1 and lot.fulfillment.order=?2",store,customer);
  if(c.lineIds!=null&&!c.lineIds.isEmpty()){var requested=new HashSet<>(c.lineIds);if(!lines.stream().map(l->l.id).collect(java.util.stream.Collectors.toSet()).containsAll(requested))throw new BadRequestException("Ligne étrangère à cette commande");lines=lines.stream().filter(l->requested.contains(l.id)).toList();}
  if(lines.isEmpty()&&c.lineIds!=null&&!c.lineIds.isEmpty())throw new BadRequestException("Aucune ligne logistique à annuler");
  var result=register(store,customer,lines,c.lineIds==null||c.lineIds.isEmpty(),c.reason,c.policy,field.actor());
  for(var created:(List<Map<String,Object>>)result.get("handlingOrders")){CancellationDispositionOrder order=CancellationDispositionOrder.findById(created.get("id"));var audience=new TreeSet<String>();audience.add(field.actor());if(order.assignee!=null)audience.add(order.assignee.badgeId);field.notice(store,"cancellation:"+order.id,"Produits à traiter · "+order.code,"Commande "+customer.orderId+" annulée : consultez les instructions pour les produits déjà en bac.",order.id,audience);}
  var receipt=new CustomerCancellation();receipt.store=store;receipt.customerOrderId=customerId;receipt.requestKey=c.requestKey;receipt.fingerprint=hash;receipt.actor=field.actor();receipt.result=field.encode(result);receipt.persist();return result;
 }
 /**
  * Cancels the given logistics lines of a customer order and creates the compensating work for goods already in totes.
  * Pending picks are cancelled; each picked quantity gets a disposition task, grouped by origin mission and building,
  * with a stock hold kept until the goods are returned, isolated or reallocated. Notifying the people concerned is left
  * to the caller, which knows who is acting.
  *
  * @param store the store of the customer order
  * @param customer the customer order
  * @param lines the logistics lines to cancel
  * @param whole whether the whole customer order is cancelled
  * @param reason the cancellation reason
  * @param policy the requested treatment: RETURN, REALLOCATE or ISOLATE
  * @param actor the badge of the person recorded as cancelling
  * @return the cancelled lines, the compensating handling orders and their tasks
  */
 public Map<String,Object> register(Store store,Order customer,List<LotLine> lines,boolean whole,String reason,String policy,String actor){
  var ids=lines.stream().map(l->l.id).toList();var items=ids.isEmpty()?List.<PickedItem>of():PickedItem.<PickedItem>list("lotLine.id in ?1 and status<>?2 order by id",ids,PickedItem.PickStatus.CANCELED);
  if(items.stream().anyMatch(i->com.intermarche.backoffice.domain.shipping.ShipmentLine.count("pickedItem",i)>0))throw new BadRequestException("Produits déjà expédiés : utilisez le parcours de retour");
  var linked=new LinkedHashMap<String,CancellationDispositionOrder>();var tasks=new ArrayList<CancellationDisposition>();
  for(var line:lines)if(CancelledMissionLine.count("line",line)==0){var cancelled=new CancelledMissionLine();cancelled.store=store;cancelled.line=line;cancelled.actor=actor;cancelled.reason=reason;cancelled.persist();}
  for(var item:items){if(item.status==PickedItem.PickStatus.PENDING){item.status=PickedItem.PickStatus.CANCELED;continue;}if(item.quantity.signum()<=0||CancellationDisposition.count("item",item)>0)continue;
   if(item.loadedTote==null||item.loadedTote.status!=LoadedTote.Status.IN_USE)throw new BadRequestException("Produit prélevé sans bac actif : corrigez son emplacement avant l’annulation");
   var custodians=new ArrayList<com.intermarche.backoffice.domain.handling.HandlingExecution>();
   custodians.addAll(com.intermarche.backoffice.client.PickingExecution.<com.intermarche.backoffice.client.PickingExecution>list("select e from PickingExecution e join e.loadedTotes t where t=?1 and e.handlingOrder.store=?2 and e.status<>?3",item.loadedTote,store,com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.CANCELLED));
   custodians.addAll(com.intermarche.backoffice.domain.executing.GroupingExecution.<com.intermarche.backoffice.domain.executing.GroupingExecution>list("select e from GroupingExecution e join e.loadedTotes t where t=?1 and e.handlingOrder.store=?2 and e.status<>?3",item.loadedTote,store,com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.CANCELLED));
   var custodian=custodians.stream().max(Comparator.comparing(e->e.id)).orElseThrow(()->new BadRequestException("Responsable du bac source introuvable. Faites contrôler son affectation."));
   BaseHandlingOrder origin=custodian.handlingOrder;
   Building physicalBuilding=item.loadedTote.buffer==null?com.intermarche.backoffice.engine.storeops.GlobalPlanningService.endBuilding(origin):item.loadedTote.buffer.building;
   if(physicalBuilding==null){BaseLocation source=item.sourceLocation==null?item.lotLine.location:item.sourceLocation;if(source!=null){var found=BaseLocation.findBuildingsByLocations(List.of(source));if(found.size()==1)physicalBuilding=found.getFirst();}}
   String group=id(origin)+":"+id(physicalBuilding);var order=linked.get(group);if(order==null){order=new CancellationDispositionOrder();order.store=store;order.origin=origin;order.customerOrder=customer;order.assignee=custodian.employee;order.building=physicalBuilding;order.code=nextCode();order.requestedStart=DateTimeProvider.now();order.requestedEnd=order.requestedStart.plusMinutes(30);order.status=order.assignee==null?BaseHandlingOrder.HandlingOrderStatus.OPEN:BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;order.persistAndFlush();linked.put(group,order);}
   var task=new CancellationDisposition();task.handlingOrder=order;task.item=item;task.source=item.loadedTote;task.quantity=item.quantity;task.policy=policy;task.instruction=switch(policy){case "ISOLATE"->"Isoler ces produits dans un bac distinct et confirmer son dépôt.";case "REALLOCATE"->"Réaffecter à un besoin compatible, ou retourner au rayon si aucun besoin n’est validé.";default->"Rapporter ces produits à leur emplacement d’origine et confirmer le retour.";};
   Building originBuilding=order.building;BaseLocation location=item.sourceLocation==null?item.lotLine.location:item.sourceLocation;if(location!=null){var bs=BaseLocation.findBuildingsByLocations(List.of(location));if(bs.size()==1)originBuilding=bs.getFirst();}if(originBuilding==null)throw new BadRequestException("Bâtiment source requis pour suivre les produits annulés");
   task.persistAndFlush();var account=stock.account(store.id,id(item.product));task.hold=stock.reserve(account.id,"cancellation:"+task.id,id(originBuilding),task.quantity,DateTimeProvider.now(),false);tasks.add(task);
  }
  if(whole){customer.status="CANCELLED";arrivals.orderCancelled(customer,actor);}
  field.flush();
  var result=new LinkedHashMap<String,Object>();result.put("customerOrderId",customer.id);result.put("cancelledLines",ids);result.put("handlingOrders",linked.values().stream().map(o->Map.of("id",o.id,"code",o.code)).toList());result.put("tasks",tasks.stream().map(this::view).toList());
  return result;
 }
 public Object complete(Long taskId,Complete c){
  if(c==null)throw new BadRequestException();FieldOperationsService.key(c.requestKey);Store store=field.store(c.storeId,true);CancellationDisposition task=CancellationDisposition.find("id=?1 and handlingOrder.store=?2",taskId,store).firstResult();if(task==null)throw new NotFoundException();var order=task.handlingOrder;
  if(!field.manager()&&(order.assignee==null||!order.assignee.badgeId.equals(field.actor())))throw new ForbiddenException("Traitement affecté à un autre préparateur");String hash=field.hash(c);if(task.confirmationKey!=null){if(!task.confirmationKey.equals(c.requestKey)||!task.fingerprint.equals(hash))throw new OperatorFailure(409,"DISPOSITION_DONE","Cette action est déjà confirmée");return field.decode(task.result,Map.class);}MissionOperationalState.requireActive(order.id);if(!Objects.equals(c.expectedRevision,revision(task)))throw new OperatorFailure(409,"STALE_DISPOSITION","Les produits ont changé. Actualisez.");
  if(task.item.status!=PickedItem.PickStatus.PICKED||!id(task.item.loadedTote).equals(id(task.source))||task.item.quantity.compareTo(task.quantity)!=0)throw new OperatorFailure(409,"DISPOSITION_CONTENT_CHANGED","Le contenu source a changé. Faites contrôler le bac.");
  if("RETURN".equals(c.action)){
   BaseLocation original=task.item.sourceLocation==null?task.item.lotLine.location:task.item.sourceLocation;BaseLocation destination=BaseLocation.findById(c.destinationId);if(destination==null||original==null||!id(destination).equals(id(original)))throw new BadRequestException("Confirmez l’emplacement d’origine indiqué");task.destination=destination;task.source.items.remove(task.item);task.item.loadedTote=null;task.item.status=PickedItem.PickStatus.CANCELED;stock.cancelCommitment(id(task.hold));
  }else if("ISOLATE".equals(c.action)){
   Tote physical=Tote.findByCode(c.destinationToteCode);if(physical==null||!id(physical.store).equals(store.id))throw new BadRequestException("Support de destination du magasin requis");
   LoadedTote destination=LoadedTote.useAnEmptyTote(physical,task.item.product.temperatureZone);destination.storageType=task.item.product.temperatureZone;destination.persistAndFlush();
   DispositionExecution execution=DispositionExecution.find("handlingOrder=?1 and status=?2",order,com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.IN_PROGRESS).firstResult();if(execution==null){execution=new DispositionExecution();execution.handlingOrder=order;execution.employee=order.assignee;if(execution.employee==null)throw new BadRequestException("Affectez l’ordre de traitement avant l’isolement");execution.persist();}execution.loadedTotes.add(destination);
if(destination==null||!id(destination.tote.store).equals(store.id)||destination.status!=LoadedTote.Status.IN_USE||id(destination).equals(id(task.source))||destination.items.stream().anyMatch(i->i.quantity.signum()>0))throw new BadRequestException("Choisissez un bac distinct, actif et vide du magasin");if(destination.storageType!=task.item.product.temperatureZone||!destination.tote.isUsageAllowed(task.item.product.temperatureZone))throw new BadRequestException("Conditionnement incompatible");
   buffers.deposit(destination,c.bufferId,c.cabinetId,c.cellRow,c.cellColumn,store);task.source.items.remove(task.item);destination.addItem(task.item);task.destinationTote=destination;task.item.status=PickedItem.PickStatus.PICKED;
   // Hold remains active while goods are isolated; no available stock is manufactured.
  }else if("REALLOCATE".equals(c.action)){
   PickedItem target=PickedItem.findById(c.targetItemId);if(target==null||target.status!=PickedItem.PickStatus.PENDING||!id(target.product).equals(id(task.item.product))||target.quantity.compareTo(task.quantity)!=0||CancelledMissionLine.count("line",target.lotLine)>0)throw new BadRequestException("Besoin en attente du même produit et de la même quantité requis");
   var executions=com.intermarche.backoffice.client.PickingExecution.<com.intermarche.backoffice.client.PickingExecution>list("select e from PickingExecution e join e.itemsToPick i where i=?1 and e.handlingOrder.store=?2 and e.status=?3",target,store,com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.IN_PROGRESS);if(executions.size()!=1)throw new BadRequestException("Exécution de destination active requise");var execution=executions.getFirst();if(!field.manager()&&!execution.employee.badgeId.equals(field.actor()))throw new ForbiddenException("La destination appartient à un autre préparateur");LoadedTote destination=execution.loadedTotes.stream().filter(t->Objects.equals(t.id,c.destinationToteId)).findFirst().orElseThrow(()->new BadRequestException("Bac de la mission de destination requis"));if(destination.storageType!=target.product.temperatureZone||!destination.tote.isUsageAllowed(target.product.temperatureZone))throw new BadRequestException("Conditionnement incompatible");
   task.source.items.remove(task.item);task.item.loadedTote=null;task.item.status=PickedItem.PickStatus.CANCELED;destination.addItem(target);target.status=PickedItem.PickStatus.PICKED;target.sourceLocation=task.item.sourceLocation;task.targetItemId=target.id;task.destinationTote=destination;stock.cancelCommitment(id(task.hold));operator.validateExecutionCapacity(execution);
  }else throw new BadRequestException("Action : RETURN, REALLOCATE ou ISOLATE");
  var traceCommand=new OperatorCommandInput();traceCommand.action="RETURN".equals(c.action)?"CANCEL_RETURN":c.action;traceCommand.itemId=task.item.id;var traceLines=new java.util.HashSet<Long>();traceLines.add(id(task.item.lotLine));if(task.targetItemId!=null){PickedItem target=PickedItem.findById(task.targetItemId);if(target!=null)traceLines.add(id(target.lotLine));}stockTrace.syncLines(store,traceLines,traceCommand,"disposition:"+task.id+":"+c.requestKey);
  task.state="DONE";task.confirmationKey=c.requestKey;task.fingerprint=hash;field.flush();var result=view(task);task.result=field.encode(result);if(CancellationDisposition.count("handlingOrder=?1 and state='PENDING'",order)==0)order.status=BaseHandlingOrder.HandlingOrderStatus.DONE;return commandReceipts.record(store,c.requestKey,hash,"COMPLETE_DISPOSITION",result,List.of(com.intermarche.backoffice.engine.operator.OperationQuantities.amount("CANCELLATION_"+c.action,task.item.product,id(task.item.lotLine),task.quantity)));
 }
 public static void requireUncancelled(PickedItem item){if(item!=null&&CancelledMissionLine.count("line",item.lotLine)>0)throw new IllegalArgumentException("Ligne annulée : suivez l’ordre de traitement des produits déjà prélevés");}
}
