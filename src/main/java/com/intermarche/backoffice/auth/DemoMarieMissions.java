package com.intermarche.backoffice.auth;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.wms.handling.*;
import com.intermarche.backoffice.domain.inventory.InventoryTarget;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.shipping.*;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.*;
import org.hibernate.Hibernate;

/** One initial mission per operational type, never resetting later operator decisions. */
@ApplicationScoped public class DemoMarieMissions {
 @Inject DemoActivityInitializer activity;@Inject DemoOperationalScenarios scenarios;@Inject TemporalStockService stocks;
 static final List<BaseHandlingOrder.HandlingOrderStatus> ACTIVE=List.of(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED,BaseHandlingOrder.HandlingOrderStatus.PROCESSING);
 static String key(BaseHandlingOrder order){var o=Hibernate.unproxy(order,BaseHandlingOrder.class);return o instanceof ReplenishmentOrder r?"SUPPLY:"+r.operation:o.getClass().getSimpleName();}
 @Transactional public void seed(Store store){
  if(!"LYO-042".equals(store.code)||BaseHandlingOrder.count("store=?1 and code='MAR-TEST-PACK'",store)>0)return;
  Employee marie=Employee.findByBadgeId("100235");StoreBuilding shop=StoreBuilding.find("buildingCode","LYO-042-SHOP").firstResult();WarehouseBuilding warehouse=WarehouseBuilding.find("buildingCode","LYO-042-WH").firstResult();Customer customer=Customer.findAll().firstResult();if(marie==null||shop==null||warehouse==null||customer==null)return;
  var now=com.intermarche.backoffice.domain.util.DateTimeProvider.now();var shelf=scenarios.shelf(store,shop);var buffer=scenarios.buffer(store,shop);
  var retained=new LinkedHashMap<String,BaseHandlingOrder>();
  for(var o:BaseHandlingOrder.<BaseHandlingOrder>list("store=?1 and assignee=?2 and status in ?3 order by status,id",store,marie,ACTIVE)){
   String k=key(o);var previous=retained.putIfAbsent(k,o);if(previous!=null){if(o.status==BaseHandlingOrder.HandlingOrderStatus.PROCESSING)throw new IllegalStateException("Deux missions déjà en cours du même type : arbitrage requis");o.assignee=null;}
  }
  // Keep ongoing work, and add fresh, independent customer lots for the missing preparation types.
  if(!retained.containsKey("ProductCollectingOrder")){var c=created(store,shop,customer,shelf,"PRO",now);var o=new ProductCollectingOrder();o.building=shop;o.addLot(c.lot());o.eans.add(shelf.product.ean);init(o,store,marie,"MAR-TEST-PRO",now);retained.put(key(o),o);}
  // Her product collection, new or kept, carries a line instruction, so the mission instruction and its acknowledgement show.
  if(Hibernate.unproxy(retained.get("ProductCollectingOrder"),BaseHandlingOrder.class) instanceof PreparingOrder collect&&com.intermarche.backoffice.domain.fieldops.LineDirective.count("handlingOrder",collect)==0)
   for(var lot:collect.lots)for(var line:lot.lines)lineInstruction(store,collect,line.id,"Prendre les dates les plus longues, au fond du rayon.",1);
  if(!retained.containsKey("ZoneCollectingOrder")){var c=created(store,shop,customer,shelf,"ZONE",now);var o=new ZoneCollectingOrder();o.building=shop;o.area=shelf.area;o.aisle=shelf.aisle;o.addLot(c.lot());init(o,store,marie,"MAR-TEST-ZONE",now);retained.put(key(o),o);}
  if(!retained.containsKey("GroupingOrder")){var c=created(store,shop,customer,shelf,"GROUP",now);var o=new GroupingOrder();o.customer=customer;o.buffer=buffer;o.lots.add(c.lot());init(o,store,marie,"MAR-TEST-GROUP",now);retained.put(key(o),o);}
  if(!retained.containsKey("InventoryOrder")){var o=new InventoryOrder();o.building=shop;o.scopeKind="BAY";o.area=shelf.area;o.aisle=shelf.aisle;o.bay=shelf.unit;o.scopeLabel="Baie "+shelf.unit;o.referenceAt=now;o.blind=false;init(o,store,marie,"MAR-TEST-INV",now);var t=new InventoryTarget();t.handlingOrder=o;t.location=shelf;t.product=shelf.product;t.address=shelf.locationCode;t.persist();retained.put(key(o),o);}
  // Reuse the existing cancellation decision, whose picked goods already exist and remain traceable.
  if(!retained.containsKey("CancellationDispositionOrder")){var o=CancellationDispositionOrder.<CancellationDispositionOrder>find("store=?1 and status in ?2 order by id",store,ACTIVE).firstResult();if(o!=null){o.assignee=marie;retained.put(key(o),o);}}
  for(var operation:List.of(ReplenishmentOrder.Operation.TRUCK_UNLOADING,ReplenishmentOrder.Operation.SHELF_REPLENISHMENT,ReplenishmentOrder.Operation.STOCK_TRANSFER,ReplenishmentOrder.Operation.STOCK_PROTECTION))if(!retained.containsKey("SUPPLY:"+operation)){
   ReplenishmentOrder o=operation==ReplenishmentOrder.Operation.STOCK_PROTECTION?new StockProtectionOrder():new ReplenishmentOrder();o.operation=operation;o.sourceBuilding=operation==ReplenishmentOrder.Operation.TRUCK_UNLOADING?null:operation==ReplenishmentOrder.Operation.STOCK_PROTECTION?shop:warehouse;o.destinationBuilding=operation==ReplenishmentOrder.Operation.TRUCK_UNLOADING?warehouse:shop;
   var reserve=warehouse.locations.stream().map(l->Hibernate.unproxy(l,ShelfLocation.class)).filter(l->l.product!=null&&l.product.id.equals(shelf.product.id)).findFirst().orElseThrow();ShelfLocation destination=operation==ReplenishmentOrder.Operation.TRUCK_UNLOADING?reserve:shelf;
   if(o instanceof StockProtectionOrder secure){secure.protectionBuffer=protectionZone(shop);secure.protectionHorizonMinutes=240;secure.protectionRuleKey="MAR-TEST";var pending=new PickingToteLocation();pending.product=shelf.product;pending.area="SEC";pending.aisle="MAR";pending.unit="1";pending.elevation="1";pending.position="1";pending.locationCode="SEC-MAR-TEST";pending.persist();shop.locations.add(pending);destination=pending;}
   if(operation==ReplenishmentOrder.Operation.TRUCK_UNLOADING)o.truckReference="CAMION-MAR-TEST";
   init(o,store,marie,"MAR-T-"+operation.name().substring(0,Math.min(9,operation.name().length())),now);var account=stocks.account(store.id,shelf.product.id);o.supplies.add(stocks.planSupply(account.id,o.code,operation==ReplenishmentOrder.Operation.TRUCK_UNLOADING?null:operation==ReplenishmentOrder.Operation.STOCK_PROTECTION?shelf.id:reserve.id,destination.id,BigDecimal.ONE,o.requestedEnd));retained.put(key(o),o);
   // The protection must answer a real shortage: a customer commitment takes every unit still free within its horizon.
   if(o instanceof StockProtectionOrder){var product=org.hibernate.Hibernate.unproxy(shelf.product,com.intermarche.backoffice.domain.order.Product.class);if(product.referenceVolume==null)product.referenceVolume=new java.math.BigDecimal("1.3");var free=stocks.availability(account.id,shop.id,now).periods.stream().map(p->p.balance).max(java.math.BigDecimal::compareTo).orElse(java.math.BigDecimal.ZERO);if(free.signum()>0)stocks.reserve(account.id,"DEMO-MAR-PROTECTION",shop.id,free,now.plusMinutes(30),false);}
  }
  // A loading of her own completes the types: the totes of a picked customer order wait in the buffer for their handover.
  if(LogisticsDeparture.count("store=?1 and owner=?2 and state='LOADING'",store,marie)==0){
   var p=scenarios.prepared(store,shop,customer,marie,"MAR-MDP","MAR-CMD-DEP",1,true,buffer,now);
   var departure=new LogisticsDeparture();departure.store=store;departure.order=p.customer().order();departure.owner=marie;departure.code="MAR-TEST-DEP";departure.destinationCode="DEST-MAR-TEST";departure.mode="CUSTOMER";
   departure.recipient=(java.util.Objects.toString(customer.firstName,"")+" "+java.util.Objects.toString(customer.lastName,"")).trim();departure.persist();
   for(var tote:p.totes()){var unit=new DepartureUnit();unit.departure=departure;unit.loadedTote=tote;unit.originalBuffer=tote.buffer;unit.persist();}
  }
  // Marker written last: replay cannot add or reassign missions after the operator starts testing.
  if(!retained.containsKey("PackingOrder")){var c=created(store,shop,customer,shelf,"PACK",now);var o=new PackingOrder();o.building=shop;o.customer=customer;o.deadLine=now.plusDays(1);o.addLot(c.lot());init(o,store,marie,"MAR-TEST-PACK",now);
   for(var line:c.lot().lines)lineInstruction(store,o,line.id,"Emballer les produits fragiles à part, dans du papier.",0);
   // Team instructions of every kind: one to acknowledge, one urgent tied to a mission, one for information.
   var team=List.of("100235","100234");
   notice(store,"DEMO-CONSIGNE-FROID","Responsable magasin","Chaîne du froid","Les produits frais et surgelés sont prélevés en dernier et déposés dès la fin de la préparation. Aucun bac frais ne reste plus de 20 minutes hors froid.","NORMAL",true,null,team);
   notice(store,"DEMO-CONSIGNE-COLIS","Responsable magasin","Colis "+o.code+" prioritaire","Le client passe à 11 h : préparez et fermez les colis de "+o.code+" avant toute autre mission.","URGENT",false,o.id,team);
   notice(store,"DEMO-CONSIGNE-QUAI","Responsable magasin","Quai Drive en travaux","Le quai Drive côté parking est fermé cet après-midi : déposez les bacs au quai de réception camion.","NORMAL",false,null,team);
  }else{throw new IllegalStateException("Le scénario initial attend une mission de packing à créer");}
  BaseHandlingOrder.getEntityManager().flush();
 }
 /**
  * Gives a mission line an instruction from the manager, as the line directive screen would.
  *
  * @param store the store
  * @param order the mission
  * @param lineId the lot line
  * @param text the instruction
  * @param priority the line priority
  */
 private void lineInstruction(Store store,BaseHandlingOrder order,Long lineId,String text,int priority){
  var d=new com.intermarche.backoffice.domain.fieldops.LineDirective();d.store=store;d.handlingOrder=order;d.lineKind="LOT_LINE";d.lineId=lineId;d.instruction=text;d.instructionVersion=1;d.priority=priority;d.persist();
 }
 /**
  * Publishes a team instruction to the given badges, as the manager's notice would, on the store's notice stream.
  *
  * @param store the store
  * @param key the idempotency key of the notice
  * @param author who gives the instruction
  * @param label the title
  * @param message the text
  * @param urgency NORMAL or URGENT
  * @param mandatory whether each recipient must acknowledge it
  * @param orderId the related mission, or null
  * @param audience the badges notified
  */
 private void notice(Store store,String key,String author,String label,String message,String urgency,boolean mandatory,Long orderId,List<String> audience){
  if(com.intermarche.backoffice.domain.operator.OperatorNotice.count("store=?1 and eventKey=?2",store,key)>0)return;
  var stream=com.intermarche.backoffice.domain.operator.OperatorStream.<com.intermarche.backoffice.domain.operator.OperatorStream>find("store",store).firstResult();
  if(stream==null){stream=new com.intermarche.backoffice.domain.operator.OperatorStream();stream.store=store;stream.persist();}
  var n=new com.intermarche.backoffice.domain.operator.OperatorNotice();n.store=store;n.cursor=++stream.cursor;n.eventKey=key;n.fingerprint=key;n.author=author;n.category="INSTRUCTION";n.label=label;n.message=message;n.urgency=urgency;n.mandatory=mandatory;n.orderId=orderId;n.persist();
  for(String badge:audience){var r=new com.intermarche.backoffice.domain.operator.OperatorNoticeRecipient();r.notice=n;r.principal=badge;r.persist();}
 }
 /**
  * Gives Marie's protection mission a zone of its own in the shop, which the other demo scenarios never fill, so that
  * its imposed deposit can always be performed.
  *
  * @param shop the store building
  * @return the protection zone, created on first use
  */
 private Buffer protectionZone(StoreBuilding shop){
  Buffer zone=Buffer.find("bufferCode","LYO-042-SECU").firstResult();
  if(zone!=null)return zone;
  zone=new Buffer();zone.building=shop;zone.bufferCode="LYO-042-SECU";zone.purpose=Buffer.BufferPurpose.PROCESSING;zone.locationDescription="Mise en sécurité";zone.active=true;
  zone.ambientCapacity=12;zone.chilledCapacity=6;zone.frozenCapacity=6;zone.maxWeightKg=500.0;zone.persist();
  return zone;
 }
 private DemoActivityInitializer.Created created(Store s,StoreBuilding b,Customer c,ShelfLocation l,String kind,LocalDateTime now){return activity.order(s,b,c,"MAR-CMD-"+kind,now.plusDays(1),Map.of(l,1),true);}
 private void init(BaseHandlingOrder o,Store s,Employee e,String code,LocalDateTime now){o.store=s;o.assignee=e;o.code=code;o.status=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;o.requestedStart=now;o.requestedEnd=now.plusHours(8);o.persist();}
}
