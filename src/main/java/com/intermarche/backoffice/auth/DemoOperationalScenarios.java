package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.inventory.*;
import com.intermarche.backoffice.domain.shipping.*;
import com.intermarche.backoffice.domain.printing.*;
import com.intermarche.backoffice.domain.workforce.*;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.wms.handling.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.stock.*;
import com.intermarche.backoffice.engine.executing.OperatorCommandInput;
import com.intermarche.backoffice.engine.printing.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;import jakarta.inject.Inject;import jakarta.persistence.EntityManager;import jakarta.transaction.Transactional;
import org.mindrot.jbcrypt.BCrypt;
import java.math.BigDecimal;import java.time.*;import java.util.*;

/** Additive, explicit demonstration fixtures. Existing scenario roots are never reset, even after user decisions. */
@ApplicationScoped public class DemoOperationalScenarios {
 @Inject DemoActivityInitializer activity;@Inject EntityManager em;@Inject TemporalStockService stocks;@Inject StockTraceLedger ledger;@Inject PickedStockCoordinator custody;
 @Inject com.intermarche.backoffice.engine.executing.OperatorExecutionService operator;
 @Inject com.intermarche.backoffice.engine.fieldops.InventoryCountingService counts;
 @Inject com.intermarche.backoffice.engine.fieldops.CancellationDispositionService cancellations;
 @Inject ObjectMapper json;
 static final String PREFIX="DEMO-V14-";
 @Transactional public void seed(Store store){
  if(!"LYO-042".equals(store.code))return;
  StoreBuilding shop=store.buildings.stream().map(b->org.hibernate.Hibernate.unproxy(b,Building.class)).filter(b->b instanceof StoreBuilding).map(b->(StoreBuilding)b).findFirst().orElse(null);
  Customer customer=Customer.findAll().firstResult();if(shop==null||customer==null)return;
  var now=DateTimeProvider.now();
  Employee picker=employee(store,shop,"DEMO-OPS-P","Camille","Démo prélèvement",now),target=employee(store,shop,"DEMO-OPS-R","Alex","Démo relais",now),counter=employee(store,shop,"DEMO-OPS-C","Sam","Démo comptage",now);
  incident(store,picker,now);
  if(BaseHandlingOrder.count("store=?1 and code in ?2",store,List.of("DEM-REL-01","DEM-INV-01","DEM-COL-01","DEM-DEP-01"))<4){try{shelf(store,shop);}catch(IllegalStateException unavailable){org.jboss.logging.Logger.getLogger(getClass()).warn("Scénarios démo non ajoutés : aucun produit ambiant éligible ; les blocages qualité existants sont conservés.");return;}}
  // A root guard makes a replay independent of later quality blocks or modifications to its original stock.
  if(BaseHandlingOrder.count("store=?1 and code='DEM-REL-01'",store)==0)preparation(store,shop,customer,picker,target,now,true);
  if(BaseHandlingOrder.count("store=?1 and code='DEM-INV-01'",store)==0)inventory(store,shop,counter,now);
  if(ProductReturn.count("store=?1 and reference=?2",store,PREFIX+"RETURN")==0)damagedReturn(store,shop,now);
  if(Parcel.count("store=?1 and code='COL-DEMO-V14'",store)==0)parcelAndPrinter(store,shop,customer,picker,now);
  if(LogisticsDeparture.count("store=?1 and code='DEP-DEMO-V14'",store)==0)partialDeparture(store,shop,customer,picker,now);
  if(BaseHandlingOrder.count("store=?1 and code='DEM-ANN-01'",store)==0)cancelledAfterPicking(store,shop,customer,picker,now);
  carts(store);
  pallets(store);
  consumables(store);
  filledSupports(store,shop,customer,picker,now);
  demoCustomers();
  if(DepartureTour.count("code","TRN-DEMO-V14")==0)tours(store,shop,customer,picker,now);
  positions();
  if(LogisticsDeparture.count("store=?1 and code='DEP-DEMO-PT1'",store)==0)tourCandidates(store,shop,picker,now);
  if(com.intermarche.backoffice.domain.commerce.DriveArrival.count("arrivalId","arr-demo-v14")==0)driveArrival(store,shop,customer,picker,now);
 }
 String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
 Employee employee(Store store,Building building,String badge,String first,String last,LocalDateTime now){
  Employee existing=Employee.findByBadgeId(badge);if(existing!=null)return existing;
  var e=new Employee();e.store=store;e.badgeId=badge;e.firstName=first;e.lastName=last;e.email=badge.toLowerCase(Locale.ROOT)+"@demo.invalid";e.password=BCrypt.hashpw("1234",BCrypt.gensalt(4));e.role=Employee.EmployeeRole.PICKER;e.active=true;e.persistAndFlush();
  var shift=new PlanningEntry();shift.employee=e;shift.kind="SHIFT";shift.title="Démo opérations · présence";shift.startsAt=now.minusHours(2);shift.endsAt=now.plusHours(10);shift.building=building;shift.persist();
  var position=new EmployeePosition();position.employee=e;position.building=building;position.availableAt=now.minusHours(1);position.persist();return e;
 }
 void incident(Store store,Employee picker,LocalDateTime now){
  if(FieldIncident.count("store=?1 and requestKey=?2",store,PREFIX+"ARBITRATED")>0)return;
  var i=new FieldIncident();i.store=store;i.reporter=picker.badgeId;i.requestKey=PREFIX+"ARBITRATED";i.fingerprint="DEMO";i.kind="OTHER";i.state="RESOLVED";i.title="Démo · étiquette vérifiée";i.comment="Scénario de démonstration : doute sur une étiquette, contrôle du responsable puis résolution sans mouvement de stock.";i.owner="100234";i.resolvedAt=now;i.persist();
  for(String action:List.of("REPORTED","OWNED","RESOLVED")){var h=new IncidentHistory();h.incident=i;h.eventKey=PREFIX+"ARBITRATED-"+action;h.fingerprint="DEMO";h.actor=action.equals("REPORTED")?picker.badgeId:"100234";h.action=action;h.detail=action.equals("RESOLVED")?"Démo : référence vérifiée sur l’étiquette, aucun écart de stock constaté.":i.comment;h.persist();}
 }
 /**
  * Finds the first ambient unit shelf whose product is free of quality blocks and serial traceability.
  *
  * @param store the demo store
  * @param shop its store building
  * @return the shelf
  */
 ShelfLocation shelf(Store store,StoreBuilding shop){return shelf(store,shop,false);}
 /**
  * Finds an ambient unit shelf whose product is free of quality blocks and serial traceability, Nutella and Penne excepted.
  *
  * @param store the demo store
  * @param shop its store building
  * @param last true for the last eligible shelf, false for the first one, in the order of the product EANs (the
  *     identifiers of the shelves vary from one seeding to the next), so that a scenario leaves the stock of the other
  *     untouched; the shelves of Nutella, Penne and of the substitution alternatives are never chosen, their stocks
  *     carry their own scenarios
  * @return the shelf
  */
 ShelfLocation shelf(Store store,StoreBuilding shop,boolean last){
  return em.createQuery("select l from Building b join b.locations l where b=:building and type(l)=StoreShelfLocation and l.locationCode is not null and l.area is not null and l.aisle is not null and l.unit is not null and l.product.active=true and l.product.productType='UNIT' and l.product.temperatureZone='AMBIENT' and not exists(select q.id from QualityCase q where q.store=:store and q.product=l.product and q.state='ACTIVE') and not exists(select p.id from TraceabilityPolicy p where p.store=:store and p.product=l.product and p.serialRequired=true) and l.product.ean not in ('3017620422003','3560070057003') and (l.product.description is null or l.product.description not like 'DEMO-ALTERNATIVE-%')"+(last?" order by l.product.ean desc, l.locationCode desc":" order by l.product.ean, l.locationCode"),ShelfLocation.class).setParameter("building",shop).setParameter("store",store).setMaxResults(1).getResultStream().findFirst().orElseThrow(()->new IllegalStateException("Aucun emplacement éligible pour les scénarios de démonstration"));
 }
 StockSegment initial(Store store,ShelfLocation location,String code,BigDecimal quantity,LocalDateTime at){
  var account=stocks.account(store.id,location.product.id);var lot=new ManufacturerLot();lot.store=store;lot.product=location.product;lot.code=code;lot.manufacturedOn=at.toLocalDate().minusDays(1);
  int minimum=TraceabilityPolicy.<TraceabilityPolicy>list("store=?1 and product=?2",store,location.product).stream().mapToInt(p->p.minimumRemainingDays).max().orElse(0);lot.useBy=at.toLocalDate().plusDays(Math.max(60,minimum+30));lot.persist();
  var segment=ledger.segment(account,location,null,lot,null,"AVAILABLE");ledger.entry(segment,quantity,code,"initial","DEMO_RECEIPT",at);return segment;
 }
 record Preparation(DemoActivityInitializer.Created customer,ProductCollectingOrder order,PickingExecution execution,List<LoadedTote> totes,StockSegment source){}
 /**
  * Prepares a demo customer order due two hours from now, picked from the first eligible shelf.
  *
  * @param store the demo store
  * @param shop its store building
  * @param customer the customer of the order
  * @param picker the picker
  * @param code the mission code, whose characters 5 to 7 name the totes
  * @param customerCode the order reference
  * @param quantity the units ordered, one tote each
  * @param complete whether every unit is picked
  * @param buffer the buffer holding the totes, or null
  * @param now the seeding time
  * @return the preparation
  */
 Preparation prepared(Store store,StoreBuilding shop,Customer customer,Employee picker,String code,String customerCode,int quantity,boolean complete,Buffer buffer,LocalDateTime now){
  return prepared(store,shop,customer,picker,code,customerCode,quantity,complete,buffer,now,now.plusHours(2),shelf(store,shop));
 }
 /**
  * Prepares a demo customer order: stock on a shelf, the order with its delivery deadline, a picking mission and the
  * totes holding what was picked.
  *
  * @param store the demo store
  * @param shop its store building
  * @param customer the customer of the order
  * @param picker the picker
  * @param code the mission code, whose characters 5 to 7 name the totes
  * @param customerCode the order reference
  * @param quantity the units ordered, one tote each
  * @param complete whether every unit is picked
  * @param buffer the buffer holding the totes, or null
  * @param now the seeding time
  * @param deadline the delivery deadline of the order
  * @param shelf the shelf the units are picked from
  * @return the preparation
  */
 Preparation prepared(Store store,StoreBuilding shop,Customer customer,Employee picker,String code,String customerCode,int quantity,boolean complete,Buffer buffer,LocalDateTime now,LocalDateTime deadline,ShelfLocation shelf){
  var source=initial(store,shelf,PREFIX+code,BigDecimal.TEN,now.minusMinutes(30));
  var c=activity.order(store,shop,customer,customerCode,deadline,new LinkedHashMap<>(Map.of(shelf,quantity)),true);
  var order=new ProductCollectingOrder();order.store=store;order.building=shop;order.code=code;order.assignee=picker;order.status=BaseHandlingOrder.HandlingOrderStatus.PROCESSING;order.requestedStart=now.minusMinutes(20);order.requestedEnd=now.plusHours(2);order.lots.add(c.lot());order.eans.add(shelf.product.ean);order.persistAndFlush();
  var execution=new PickingExecution(order,picker);execution.persistAndFlush();var totes=new ArrayList<LoadedTote>();int picked=complete?quantity:1;
  for(int n=1;n<=picked;n++){var t=new Tote();t.store=store;t.code="AFF-"+code.substring(4,7)+"-"+n;t.volume=new BigDecimal("40");t.toteType=Tote.ToteType.STANDARD;t.persistAndFlush();var load=execution.addTote(t,TemperatureZone.AMBIENT);load.buffer=buffer;totes.add(load);var pending=execution.itemsToPick.stream().filter(i->i.status==PickedItem.PickStatus.PENDING).findFirst().orElseThrow();execution.addItemToTote(load,pending,BigDecimal.ONE,null,shelf);}
  em.flush();var hint=new OperatorCommandInput();hint.action="PICK";hint.stockSegmentId=source.id;custody.syncLines(store,c.lines().stream().map(l->l.id).toList(),hint,PREFIX+code+"-PICK");
  if(complete){execution.status=com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.DONE;order.status=BaseHandlingOrder.HandlingOrderStatus.DONE;c.lot().status=Lot.LotStatus.COLLECTED;}
  em.flush();return new Preparation(c,order,execution,totes,source);
 }
 void preparation(Store store,StoreBuilding shop,Customer customer,Employee picker,Employee target,LocalDateTime now,boolean unused){
  var p=prepared(store,shop,customer,picker,"DEM-REL-01","DEMO-V14-RELAIS",2,false,null,now);
  var h=new MissionHandover();h.store=store;h.handlingOrder=p.order;h.source=picker;h.target=target;h.requester=picker.badgeId;h.requestKey=PREFIX+"HANDOVER";h.fingerprint="DEMO";h.reason="Démo : une unité déjà prélevée ; transmettre le bac et le reliquat au collègue.";h.readyAt=now;h.expiresAt=now.plusDays(7);h.orderRevision=operator.currentRevisionForHandover(p.order);h.persist();
 }
 void inventory(Store store,StoreBuilding shop,Employee counter,LocalDateTime now){
  ShelfLocation shelf=shelf(store,shop);var segment=initial(store,shelf,PREFIX+"INVENTORY",BigDecimal.TEN,now.minusMinutes(20));
  var o=new InventoryOrder();o.store=store;o.building=shop;o.code="DEM-INV-01";o.assignee=counter;o.status=BaseHandlingOrder.HandlingOrderStatus.PROCESSING;o.requestedStart=now.minusMinutes(10);o.requestedEnd=now.plusHours(2);o.referenceAt=now.minusMinutes(10);o.scopeKind="BAY";o.scopeLabel="Démo · comptage avec mouvement";o.area=shelf.area;o.aisle=shelf.aisle;o.bay=shelf.unit;o.blind=false;o.persist();
  var target=new InventoryTarget();target.handlingOrder=o;target.location=shelf;target.segment=segment;target.product=shelf.product;target.expectedQuantity=BigDecimal.TEN;target.address=shelf.locationCode;target.persist();
  var e=new InventoryExecution();e.handlingOrder=o;e.employee=counter;e.countStarted=true;e.persist();
  var session=new InventoryCountSession();session.handlingOrder=o;session.employee=counter;session.referenceAt=o.referenceAt;session.movementMode="RECONCILE_MOVEMENTS";session.policySnapshot=encode(counts.control(store));session.persistAndFlush();
  ledger.entry(segment,BigDecimal.valueOf(2),PREFIX+"INVENTORY-MOVEMENT","in","DEMO_RECEIPT",now.minusMinutes(5));
  var line=new InventoryCountLine();line.session=session;line.product=shelf.product;line.location=shelf;line.lot=segment.lot;line.lineKey=StockTraceLedger.key(PREFIX,"INVENTORY-LINE");line.address=shelf.locationCode;line.sourceLocationIds=encode(List.of(shelf.id));line.referenceQuantity=BigDecimal.TEN;line.observedQuantity=BigDecimal.valueOf(12);line.state="COUNTED";line.countedAt=now.minusMinutes(1);line.comment="Démo : 10 au démarrage, entrée de 2 avant le comptage ; 12 observés.";line.persist();var observation=new InventoryCountObservation();observation.line=line;observation.actor=counter.badgeId;observation.requestKey=PREFIX+"COUNT";observation.state="COUNTED";observation.quantity=line.observedQuantity;observation.countedAt=line.countedAt;observation.comment=line.comment;observation.evidence=encode(Map.of("origin","DEMO"));observation.persist();
 }
 void damagedReturn(Store store,StoreBuilding shop,LocalDateTime now){
  ShelfLocation shelf=shelf(store,shop);var account=stocks.account(store.id,shelf.product.id);
  var location=new ReturnTransitLocation();location.product=shelf.product;location.area="RETOURS";location.aisle="RET";location.unit="DEMO";location.elevation="1";location.position="1";location.locationCode="DEMO-V14-RETOUR";location.persist();shop.locations.add(location);
  var segment=ledger.segment(account,location,null,null,null,"RETURN_PENDING");ledger.entry(segment,BigDecimal.valueOf(2),PREFIX+"RETURN","received","PRODUCT_RETURN",now.minusMinutes(20));
  var r=new ProductReturn();r.store=store;r.code="RET-DEMO-V14";r.reference=PREFIX+"RETURN";r.reportedBy="DEMO";r.reason="Démo : emballages écrasés au retour, à orienter vers la quarantaine ou le rebut.";r.state="INSPECTED";r.receivedAt=now.minusMinutes(20);r.persist();var line=new ProductReturnLine();line.productReturn=r;line.product=shelf.product;line.segment=segment;line.quantity=BigDecimal.valueOf(2);line.inspection="DAMAGED";line.inspectedBy="100234";line.inspectedAt=now.minusMinutes(10);line.inspectionReason="Démo : deux emballages abîmés, remise en vente interdite.";line.persist();
 }
 /**
  * Prepares a customer order whose two units are already in totes, then cancels it through the real cancellation rules,
  * so that the post-cancellation treatment screen has two products waiting to be returned, isolated or reallocated.
  *
  * @param store the demo store
  * @param shop the store building where the goods are picked
  * @param customer the demo customer
  * @param picker the picker who keeps the totes
  * @param now the demo reference time
  */
 void cancelledAfterPicking(Store store,StoreBuilding shop,Customer customer,Employee picker,LocalDateTime now){
  var p=prepared(store,shop,customer,picker,"DEM-ANN-01","DEMO-V14-ANNULATION",2,true,buffer(store,shop),now);
  cancellations.register(store,p.customer().order(),p.customer().lines(),true,"Démo : le client annule après le prélèvement ; deux unités sont déjà en bacs.","RETURN","DEMO");
 }
 /**
  * Declares the demo carts with their compartments: two six-tote ambient carts, a four-tote insulated cart and an
  * eight-tote ambient cart. Each cart is added once; carts already present, even modified, are kept as they are.
  *
  * @param store the demo store
  */
 void carts(Store store){
  record Spec(String code,String type,int rows,int columns){}
  for(var spec:List.of(new Spec("CH-01","CART_AMBIENT_6",2,3),new Spec("CH-02","CART_AMBIENT_6",2,3),new Spec("CH-03","CART_COLD_4",2,2),new Spec("CH-04","CART_AMBIENT_8",2,4))){
   if(Tote.findByCode(spec.code())!=null||PhysicalSupport.count("store=?1 and code=?2",store,spec.code())>0)continue;
   SupportType type=cartType(store,spec.type());
   var tote=new Tote();tote.store=store;tote.code=spec.code();tote.volume=type.maxVolumeLiters;tote.toteType=type.allowedZones.contains("CHILLED")?Tote.ToteType.INSULATED:Tote.ToteType.STANDARD;tote.persist();
   var cart=new PhysicalSupport();cart.store=store;cart.type=type;cart.tote=tote;cart.code=spec.code();cart.persist();
   for(int row=1;row<=spec.rows();row++)for(int column=1;column<=spec.columns();column++){var compartment=new CartCompartment();compartment.cart=cart;compartment.rowNumber=row;compartment.columnNumber=column;compartment.label=row+" / "+column;compartment.persist();}
  }
 }
 /**
  * Returns the store's demo cart type with the given code, creating it on first use.
  *
  * @param store the demo store
  * @param code CART_AMBIENT_6, CART_COLD_4 or CART_AMBIENT_8
  * @return the cart type
  */
 SupportType cartType(Store store,String code){
  SupportType existing=SupportType.find("store=?1 and code=?2",store,code).firstResult();if(existing!=null)return existing;
  var type=new SupportType();type.store=store;type.code=code;type.kind=SupportType.Kind.CART;
  switch(code){case "CART_COLD_4"->{type.label="Chariot isotherme 4 bacs";type.allowedZones="CHILLED,FROZEN";type.maxVolumeLiters=new BigDecimal("160");type.maxWeightKg=new BigDecimal("80");}case "CART_AMBIENT_8"->{type.label="Grand chariot 8 bacs";type.allowedZones="AMBIENT,HOUSEHOLD";type.maxVolumeLiters=new BigDecimal("320");type.maxWeightKg=new BigDecimal("160");}default->{type.label="Chariot 6 bacs";type.allowedZones="AMBIENT,HOUSEHOLD";type.maxVolumeLiters=new BigDecimal("240");type.maxWeightKg=new BigDecimal("120");}}
  type.persist();return type;
 }
 /** Add dedicated display scenarios once; never refill supports that an operator later moves or empties. */
 void filledSupports(Store store,StoreBuilding shop,Customer customer,Employee picker,LocalDateTime now){
  for(int n=1;n<=3;n++){
   String code="DEM-VS"+n+"-01";if(BaseHandlingOrder.count("store=?1 and code=?2",store,code)>0)continue;
   String cartCode=n<=2?"CH-01":"CH-02";var slot=CartCompartment.<CartCompartment>find("cart.store=?1 and cart.code=?2 and occupant is null and handlingOrder is null order by rowNumber,columnNumber",store,cartCode).firstResult();if(slot==null)continue;
   Preparation p;try{p=prepared(store,shop,customer,picker,code,PREFIX+"VISUEL-BAC-"+n,2,false,null,now);}catch(IllegalStateException unavailable){return;}
   var t=p.totes.getFirst().tote;var type=SupportType.<SupportType>find("store=?1 and code='DEMO_DISPLAY_TOTE'",store).firstResult();if(type==null){type=new SupportType();type.store=store;type.code="DEMO_DISPLAY_TOTE";type.label="Bac de démonstration · chariot";type.kind=SupportType.Kind.TOTE;type.allowedZones="AMBIENT";type.maxVolumeLiters=new BigDecimal("40");type.persist();}
   var support=PhysicalSupport.<PhysicalSupport>find("tote",t).firstResult();if(support==null){support=new PhysicalSupport();support.store=store;support.tote=t;}support.type=type;support.code=t.code;support.persist();slot.occupant=support;slot.handlingOrder=p.order;slot.customerOrder=p.customer.order();slot.destination="Préparation magasin";
  }
  for(String kind:List.of("CARTON","PALLET")){
   String mission=kind.equals("CARTON")?"DEM-BOX-01":"DEM-PAL-01",typeCode=kind.equals("CARTON")?"DEMO_CARTON_S":"DEMO_PALLET_EURO";
   if(BaseHandlingOrder.count("store=?1 and code=?2",store,mission)>0)continue;
   var type=SupportType.<SupportType>find("store=?1 and code=?2",store,typeCode).firstResult();if(type==null||type.stockQuantity<1)continue;
   Preparation p;try{p=prepared(store,shop,customer,picker,mission,PREFIX+"VISUEL-"+kind,kind.equals("CARTON")?2:3,true,null,now);}catch(IllegalStateException unavailable){return;}
   var target=p.totes.getFirst();for(var from:p.totes.subList(1,p.totes.size())){for(var item:new ArrayList<>(from.items))p.execution.moveItemToTote(from,target,item);from.status=LoadedTote.Status.DELIVERED;}
   target.buffer=buffer(store,shop);target.tote.code=kind.equals("CARTON")?"CARTON-DEMO-PLEIN":"PAL-DEMO-PLEINE";target.tote.volume=type.maxVolumeLiters;
   em.flush();custody.syncLines(store,p.customer.lines().stream().map(l->l.id).toList(),null,PREFIX+mission+"-MERGE");
   var support=PhysicalSupport.<PhysicalSupport>find("tote",target.tote).firstResult();if(support==null){support=new PhysicalSupport();support.store=store;support.tote=target.tote;}support.type=type;support.code=target.tote.code;support.persist();em.flush();com.intermarche.backoffice.engine.fieldops.ConsumableService.consume(support,"DEMO");
  }
  em.flush();
 }
 void consumables(Store store){
  String[][] samples={{"DEMO_CARTON_S","Carton petit · 15 L","CARTON","15","10","120","30","200"},{"DEMO_CARTON_L","Carton grand · 50 L","CARTON","50","25","12","20","100"},{"DEMO_PALLET_LOST","Palette à usage unique","PALLET","1200","800","0","5","25"}};
  for(var v:samples){if(SupportType.count("store=?1 and code=?2",store,v[0])>0)continue;var t=new SupportType();t.store=store;t.code=v[0];t.label=v[1];t.kind=SupportType.Kind.valueOf(v[2]);t.allowedZones="AMBIENT";t.maxVolumeLiters=new BigDecimal(v[3]);t.maxWeightKg=new BigDecimal(v[4]);t.stockQuantity=Long.parseLong(v[5]);t.reorderThreshold=Long.parseLong(v[6]);t.reorderTarget=Long.parseLong(v[7]);t.persist();var m=new com.intermarche.backoffice.domain.fieldops.PackagingMovement();m.type=t;m.quantity=t.stockQuantity;m.balance=t.stockQuantity;m.actor="DEMO";m.reason="Stock initial de démonstration";m.persist();}
  var pallet=SupportType.<SupportType>find("store=?1 and code='DEMO_PALLET_EURO'",store).firstResult();if(pallet!=null&&com.intermarche.backoffice.domain.fieldops.PackagingMovement.count("type",pallet)==0){pallet.stockQuantity=18;pallet.reorderThreshold=5;pallet.reorderTarget=30;var m=new com.intermarche.backoffice.domain.fieldops.PackagingMovement();m.type=pallet;m.quantity=18;m.balance=18;m.actor="DEMO";m.reason="Stock initial de démonstration";m.persist();}
 }
 /** Adds three identified demonstration pallets without changing existing supports. */
 void pallets(Store store){
  SupportType type=SupportType.find("store=?1 and code='DEMO_PALLET_EURO'",store).firstResult();
  if(type==null){type=new SupportType();type.store=store;type.code="DEMO_PALLET_EURO";type.label="Palette Europe";type.kind=SupportType.Kind.PALLET;type.allowedZones="AMBIENT";type.maxVolumeLiters=new BigDecimal("1200");type.maxWeightKg=new BigDecimal("800");type.persist();}
  if(type.kind!=SupportType.Kind.PALLET)return;
  for(String code:List.of("PAL-01","PAL-02","PAL-03")){
   if(Tote.findByCode(code)!=null||PhysicalSupport.count("store=?1 and code=?2",store,code)>0)continue;
   var tote=new Tote();tote.store=store;tote.code=code;tote.volume=type.maxVolumeLiters==null?new BigDecimal("1200"):type.maxVolumeLiters;tote.toteType=Tote.ToteType.STANDARD;tote.persist();
   var support=new PhysicalSupport();support.store=store;support.type=type;support.tote=tote;support.code=code;support.persist();
  }
 }

 Buffer buffer(Store store,StoreBuilding shop){return Buffer.find("building=?1 and active=true",shop).firstResult();}
 void parcelAndPrinter(Store store,StoreBuilding shop,Customer customer,Employee picker,LocalDateTime now){
  var p=prepared(store,shop,customer,picker,"DEM-COL-01","DEMO-V14-COLIS",1,true,buffer(store,shop),now);
  var parcel=new Parcel();parcel.store=store;parcel.order=p.customer.order();parcel.code="COL-DEMO-V14";parcel.createdBy="DEMO";parcel.currentAddress="À fermer · bac "+p.totes.getFirst().tote.code;parcel.persistAndFlush();
  var picked=p.totes.getFirst().items.iterator().next();var content=new ParcelContent();content.parcel=parcel;content.pickedItem=picked;content.quantity=BigDecimal.ONE;content.sourceToteCode=p.totes.getFirst().tote.code;content.sourceToteId=p.totes.getFirst().id;content.sourceProductId=picked.product.id;content.sourceQuantity=picked.quantity;content.sourceAddress=p.totes.getFirst().buffer==null?"Démo · en préparation":p.totes.getFirst().buffer.bufferCode;content.traceSnapshot=encode(PickedStockAllocation.<PickedStockAllocation>list("state.itemId=?1 and quantity>0 and shipped=false",picked.id).stream().map(a->{var v=new LinkedHashMap<String,Object>();v.put("allocationId",a.id);v.put("quantity",a.quantity);v.put("lotId",a.holding.lot==null?null:a.holding.lot.id);v.put("lot",a.holding.lot==null?null:a.holding.lot.code);v.put("serialId",a.holding.serial==null?null:a.holding.serial.id);v.put("serial",a.holding.serial==null?null:a.holding.serial.code);v.put("quality",a.holding.quality);return v;}).toList());content.persist();
  StorePrinter printer=printer(store,"DEMO-V14-PANNE","Démo · résultat d’impression incertain","UNKNOWN_WRITE");printer(store,"DEMO-V14-OK","Démo · impression réussie","SUCCESS");
  var template=new LabelTemplate();template.store=store;template.code="DEMO-V14-COLIS";template.title="Colis de démonstration";template.createdBy="DEMO";template.persist();
  var label=LabelRenderer.render(parcel.code,template.title,List.of(p.customer.order().orderId,"Démo : vérifier avant réimpression"),812,600);
  var job=new PrintJob();job.store=store;job.printer=printer;job.template=template;job.subjectType="PARCEL";job.subjectId=parcel.id;job.logisticsCode=parcel.code;job.state="UNKNOWN";job.requestedBy="DEMO";job.reason="Démo : panne après transmission, impression physique inconnue";job.attemptCount=1;job.lastError="SIMULATED_UNCERTAIN_WRITE";job.frozenDestination=encode(new PrinterTransport.Destination("SIMULATOR",null,9100,"UNKNOWN_WRITE"));job.frozenData=encode(Map.of("code",parcel.code,"lines",List.of(p.customer.order().orderId)));job.frozenPayload=label.payload();job.frozenPreview=label.preview();try{job.payloadSha256=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(label.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}job.persist();var attempt=new PrintAttempt();attempt.job=job;attempt.attemptNumber=1;attempt.state="UNKNOWN";attempt.errorCode=job.lastError;attempt.startedAt=now.minusMinutes(5);attempt.completedAt=now.minusMinutes(4);attempt.persist();
 }
 StorePrinter printer(Store store,String code,String label,String simulation){var old=StorePrinter.<StorePrinter>find("store=?1 and code=?2",store,code).firstResult();if(old!=null)return old;var p=new StorePrinter();p.store=store;p.code=code;p.label=label;p.simulation=simulation;p.persist();return p;}
 void partialDeparture(Store store,StoreBuilding shop,Customer customer,Employee picker,LocalDateTime now){
  var p=prepared(store,shop,customer,picker,"DEM-DEP-01","DEMO-V14-DEPART",2,true,buffer(store,shop),now);
  var departure=new LogisticsDeparture();departure.store=store;departure.order=p.customer.order();departure.owner=picker;departure.code="DEP-DEMO-V14";departure.destinationCode="DEST-DEMO-V14";departure.mode="CUSTOMER";departure.recipient="Client de démonstration";departure.transportReference="DEMO-V14-REMISE";departure.state="PARTIALLY_HANDED_OVER";departure.lastHandoverAt=now.minusMinutes(5);departure.persist();
  var sent=p.totes.getFirst();var picked=sent.items.iterator().next();custody.ship(store,picked,PREFIX+"SHIPMENT");
  var shipment=new Shipment();shipment.store=store;shipment.order=p.customer.order();shipment.requestKey=store.id+":"+PREFIX+"SHIPMENT";shipment.requestHash="DEMO";shipment.code="EXP-DEMO-V14";shipment.mode="CUSTOMER";shipment.recipient=departure.recipient;shipment.transportReference=departure.transportReference;shipment.performedBy="DEMO";shipment.dispatchedAt=now.minusMinutes(5);shipment.persist();
  var line=new ShipmentLine();line.pickedItem=picked;line.loadedTote=sent;line.toteCode=sent.tote.code;line.productName=picked.product.name;line.quantity=BigDecimal.ONE;line.unit="pcs";line.departureBuffer=sent.buffer==null?"Démo · remise":sent.buffer.bufferCode;shipment.lines.add(line);
  var handover=new DepartureHandover();handover.departure=departure;handover.shipment=shipment;handover.performedBy="DEMO";handover.recipient=departure.recipient;handover.reference=departure.transportReference;handover.handedOverAt=shipment.dispatchedAt;handover.persist();
  for(int n=0;n<2;n++){var unit=new DepartureUnit();unit.departure=departure;unit.loadedTote=p.totes.get(n);unit.originalBuffer=unit.loadedTote.buffer;unit.state=n==0?"HANDED_OVER":"SELECTED";if(n==0){unit.handover=handover;unit.loadedBy=picker.badgeId;unit.loadedAt=now.minusMinutes(10);unit.loadCycle=1;}unit.persist();}
  sent.clearCell();sent.buffer=null;p.customer.order().status="PARTIALLY_SHIPPED";
  var history=new LogisticsHistory();history.store=store;history.subjectType="DEPARTURE";history.subjectId=departure.id;history.action="HANDOVER";history.actor="DEMO";history.reason="Démo : un bac remis sur deux. Le second attend encore son chargement et sa remise.";history.snapshot=encode(Map.of("state",departure.state,"selected",2,"handedOver",1));history.persist();
 }

 /**
  * Seeds carrier tours: two carriers with their services, two docks, and an open tour at the first dock holding four
  * departures for four customers spread over Lyon, whose delivery order is left to set; the first one holds a
  * carrier departure whose two totes are still to load, so that loading, sealing, documents, departure and empty
  * tote returns can be played end to end.
  *
  * @param store the demo store
  * @param shop its store building
  * @param customer a demo customer
  * @param picker the demo picker in charge of the departure
  * @param now the seeding time
  */
 void tours(Store store,StoreBuilding shop,Customer customer,Employee picker,LocalDateTime now){
  var rhone=carrier(store,"DEMO-RHONE","Rhône Fret (démo)");var alpes=carrier(store,"DEMO-ALPES","Alpes Express (démo)");
  var nextDay=service(rhone,"J1","Messagerie J+1","17:30",24,new BigDecimal("800"));service(rhone,"FRAIS","Frais 24 h","14:00",12,new BigDecimal("400"));service(alpes,"EXPRESS","Express matin","11:00",8,null);
  var dry=dock(store,"QUAI-1","Quai expédition sec");dock(store,"QUAI-2","Quai frais");
  var p=prepared(store,shop,customer,picker,"DEM-TRN-01","DEMO-V14-TOURNEE",2,true,buffer(store,shop),now);
  var tour=new DepartureTour();tour.store=store;tour.serviceLevel=nextDay;tour.dock=dry;tour.code="TRN-DEMO-V14";tour.vehicleReference="DM-142-TR";tour.createdBy=picker.badgeId;
  tour.plannedDeparture=now.toLocalTime().isBefore(LocalTime.of(17,0))?now.toLocalDate().atTime(17,0):now.toLocalDate().plusDays(1).atTime(17,0);tour.persist();
  var departure=new LogisticsDeparture();departure.store=store;departure.order=p.customer.order();departure.owner=picker;departure.tour=tour;departure.code="DEP-DEMO-TRN";departure.destinationCode="DEST-DEMO-TRN";departure.mode="CARRIER";departure.recipient=rhone.name;departure.transportReference=tour.code;departure.persist();
  for(var tote:p.totes){var unit=new DepartureUnit();unit.departure=departure;unit.loadedTote=tote;unit.originalBuffer=tote.buffer;unit.persist();}
  var history=new LogisticsHistory();history.store=store;history.subjectType="DEPARTURE";history.subjectId=departure.id;history.action="CREATED";history.actor="DEMO";history.reason="Démo : départ transporteur rattaché à la tournée TRN-DEMO-V14, deux bacs à charger.";history.snapshot=encode(Map.of("state",departure.state,"tour",tour.code));history.persistAndFlush();
  ShelfLocation shelf=shelf(store,shop,true);String[] others={"CLI-452302","CLI-452303","CLI-452304"};int[] units={1,2,1};
  for(int n=0;n<others.length;n++){Customer other=Customer.find("customerId",others[n]).firstResult();if(other==null)continue;String code="DEM-TR"+(n+2);
   var q=prepared(store,shop,other,picker,code,PREFIX+"TOURNEE-"+(n+2),units[n],true,null,now,now.plusHours(3+n),shelf);
   var d=new LogisticsDeparture();d.store=store;d.order=q.customer().order();d.owner=picker;d.tour=tour;d.code="DEP-DEMO-TR"+(n+2);d.destinationCode="DEST-DEMO-TR"+(n+2);d.mode="CARRIER";d.recipient=other.firstName+" "+other.lastName;d.transportReference=tour.code;d.persist();
   for(var tote:q.totes()){var unit=new DepartureUnit();unit.departure=d;unit.loadedTote=tote;unit.originalBuffer=tote.buffer;unit.persist();}
   var h=new LogisticsHistory();h.store=store;h.subjectType="DEPARTURE";h.subjectId=d.id;h.action="CREATED";h.actor="DEMO";h.reason="Démo : départ transporteur rattaché à la tournée TRN-DEMO-V14, ordre des arrêts à fixer.";h.snapshot=encode(Map.of("state",d.state,"tour",tour.code));h.persistAndFlush();}
 }
 /**
  * A demo customer added for carrier tours.
  *
  * @param id the customer id
  * @param first the first name
  * @param last the last name
  * @param street the street line
  * @param postalCode the postal code
  * @param city the city
  * @param lat the latitude of the address
  * @param lon the longitude of the address
  */
 record TourCustomer(String id,String first,String last,String street,String postalCode,String city,double lat,double lon){}
 /** Customers spread over Lyon and its suburbs, so that demo tours have several stops in several directions. */
 static final List<TourCustomer> TOUR_CUSTOMERS=List.of(
  new TourCustomer("CLI-452306","Léa","Bernard","12 Rue Francis de Pressensé","69100","Villeurbanne",45.7672,4.8798),
  new TourCustomer("CLI-452307","Nathan","Garnier","5 Grande Rue de Saint-Clair","69300","Caluire-et-Cuire",45.7928,4.8531),
  new TourCustomer("CLI-452308","Manon","Faure","20 Avenue Jean Jaurès","69007","Lyon",45.7441,4.8412),
  new TourCustomer("CLI-452309","Louis","Girard","30 Avenue Franklin Roosevelt","69500","Bron",45.7383,4.9128),
  new TourCustomer("CLI-452310","Camille","Lambert","3 Avenue Guy de Collongue","69130","Écully",45.7791,4.7768),
  new TourCustomer("CLI-452311","Jules","Fontaine","18 Grande Rue","69600","Oullins",45.7142,4.8079));
 /**
  * Adds the demo customers of carrier tours that do not exist yet, with the position of their address as the commerce
  * sends it; existing customers are kept as they are.
  */
 void demoCustomers(){
  for(var spec:TOUR_CUSTOMERS){if(Customer.findByCustomerId(spec.id())!=null)continue;
   var c=new Customer();c.customerId=spec.id();c.firstName=spec.first();c.lastName=spec.last();c.email=spec.first().toLowerCase(Locale.ROOT).replace("é","e")+"."+spec.last().toLowerCase(Locale.ROOT)+"@demo.invalid";
   c.address=new com.intermarche.backoffice.domain.Adresse();c.address.streetLine1=spec.street();c.address.postalCode=spec.postalCode();c.address.city=spec.city();c.address.country="France";
   c.addressPosition=new com.intermarche.backoffice.domain.GPSPosition(spec.lat(),spec.lon());c.addressPositionSource=Customer.POSITION_PROVIDED;c.persist();}
 }
 /**
  * Gives the demo customers the position of their address, as the commerce sends it with an order, when they have
  * none yet; positions already known are kept.
  */
 void positions(){
  for(Customer c:Customer.<Customer>listAll()){if(c.addressPosition!=null&&c.addressPosition.latitude!=null)continue;var p=DemoDataInitializer.demoPosition(c.customerId);if(p==null)continue;c.addressPosition=p;c.addressPositionSource=Customer.POSITION_PROVIDED;}
 }
 /**
  * Seeds seven prepared carrier departures waiting outside any tour, for seven customers spread over Lyon and its
  * suburbs and with staggered deadlines, so that tours can be proposed: one vehicle of the next-day service takes them
  * all, the eight-unit express service needs two. Their totes wait at the dock, outside the buffers, and their units come from
  * the last eligible shelf so that the stock of the other scenarios is untouched.
  *
  * @param store the demo store
  * @param shop its store building
  * @param picker the demo picker in charge of the departures
  * @param now the seeding time
  */
 void tourCandidates(Store store,StoreBuilding shop,Employee picker,LocalDateTime now){
  var customers=Customer.<Customer>list("customerId in ?1 order by customerId",List.of("CLI-452305","CLI-452306","CLI-452307","CLI-452308","CLI-452309","CLI-452310","CLI-452311"));if(customers.isEmpty())return;
  LocalDateTime tomorrow=now.toLocalDate().plusDays(1).atStartOfDay();
  List<LocalDateTime> deadlines=List.of(now.plusHours(3),now.plusHours(4),now.plusHours(5),tomorrow.plusHours(10),tomorrow.plusHours(11),tomorrow.plusHours(12),tomorrow.plusHours(16));
  int[] units={2,1,2,1,2,1,2};ShelfLocation shelf=shelf(store,shop,true);
  for(int n=0;n<units.length;n++){var customer=customers.get(n%customers.size());
   var p=prepared(store,shop,customer,picker,"DEM-PT"+(n+1),PREFIX+"PROPOSITION-"+(n+1),units[n],true,null,now,deadlines.get(n),shelf);
   var departure=new LogisticsDeparture();departure.store=store;departure.order=p.customer().order();departure.owner=picker;departure.code="DEP-DEMO-PT"+(n+1);departure.destinationCode="DEST-DEMO-PT"+(n+1);departure.mode="CARRIER";departure.recipient=customer.firstName+" "+customer.lastName;departure.transportReference="DEMO-PROPOSITION";departure.persist();
   for(var tote:p.totes()){var unit=new DepartureUnit();unit.departure=departure;unit.loadedTote=tote;unit.originalBuffer=tote.buffer;unit.persist();}
   var history=new LogisticsHistory();history.store=store;history.subjectType="DEPARTURE";history.subjectId=departure.id;history.action="CREATED";history.actor="DEMO";history.reason="Démo : départ transporteur en attente de tournée, à regrouper par « Proposer des tournées ».";history.snapshot=encode(Map.of("state",departure.state));history.persistAndFlush();}
 }
 /**
  * Creates a demo carrier.
  *
  * @param store the store
  * @param code its code
  * @param name its name
  * @return the carrier
  */
 Carrier carrier(Store store,String code,String name){var c=new Carrier();c.store=store;c.code=code;c.name=name;c.persist();return c;}
 /**
  * Creates a demo carrier service.
  *
  * @param carrier the carrier
  * @param code its code
  * @param label its label
  * @param cutoff its latest collection time
  * @param maxUnits its capacity in units
  * @param maxWeightKg its weight capacity, or null
  * @return the service
  */
 CarrierServiceLevel service(Carrier carrier,String code,String label,String cutoff,int maxUnits,BigDecimal maxWeightKg){var l=new CarrierServiceLevel();l.carrier=carrier;l.code=code;l.label=label;l.cutoff=cutoff;l.maxUnits=maxUnits;l.maxWeightKg=maxWeightKg;l.persist();return l;}
 /**
  * Creates a demo dock.
  *
  * @param store the store
  * @param code the code posted on the dock
  * @param label its label
  * @return the dock
  */
 ShippingDock dock(Store store,String code,String label){var d=new ShippingDock();d.store=store;d.code=code;d.label=label;d.persist();return d;}

 /**
  * Seeds a Drive arrival: the demo commerce identity is granted the store, and a prepared Drive order whose slot has
  * started already has its customer waiting at space B12, so that the store team can take it in charge, hand the
  * order over and see the arrival close.
  *
  * @param store the demo store
  * @param shop its store building
  * @param customer a demo customer
  * @param picker the demo picker who prepared the order
  * @param now the seeding time
  */
 void driveArrival(Store store,StoreBuilding shop,Customer customer,Employee picker,LocalDateTime now){
  if(com.intermarche.backoffice.domain.commerce.CommerceStoreGrant.count("store=?1 and principal='service'",store)==0){var grant=new com.intermarche.backoffice.domain.commerce.CommerceStoreGrant();grant.store=store;grant.principal="service";grant.sourceSystem="IMMCOMM";grant.grantedBy="DEMO";grant.persist();}
  var p=prepared(store,shop,customer,picker,"DEM-DRV-01","DEMO-V14-DRIVE",1,true,buffer(store,shop),now);var order=p.customer.order();
  var slot=now.minusMinutes(10).atZone(ZoneId.systemDefault()).toOffsetDateTime();
  try{var spec=order.specification==null?json.createObjectNode():(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(order.specification);var fulfilment=spec.putObject("fulfillment");fulfilment.put("type","DRIVE");var window=fulfilment.putObject("slot");window.put("start",slot.toString());window.put("end",slot.plusHours(1).toString());order.specification=json.writeValueAsString(spec);}catch(Exception e){throw new IllegalStateException(e);}
  var arrival=new com.intermarche.backoffice.domain.commerce.DriveArrival();arrival.store=store;arrival.order=order;arrival.sourceSystem="IMMCOMM";arrival.principal="service";arrival.requestId="5d1f0c2e-7a4b-4c3d-9e8f-1a2b3c4d5e6f";arrival.arrivalId="arr-demo-v14";arrival.fingerprint="DEMO";arrival.expectedRevision=1;arrival.parkingKind="SPACE";arrival.parkingLabel="B12";arrival.receivedAt=now.minusMinutes(4);arrival.persist();
  var state=new com.intermarche.backoffice.domain.commerce.CommercePickupState();state.store=store;state.order=order;state.orderState="READY";state.revision=2;state.changedAt=arrival.receivedAt;state.persistAndFlush();
 }
}
