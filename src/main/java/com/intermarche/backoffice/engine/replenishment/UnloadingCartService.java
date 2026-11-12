package com.intermarche.backoffice.engine.replenishment;

import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.stock.ExpectedSupply;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder;
import com.intermarche.backoffice.engine.executing.PickingPlanService.RouteStepDTO;
import com.intermarche.backoffice.engine.executing.StorePickingRoutePlanner;
import com.intermarche.backoffice.engine.fieldops.SupportLoadEstimate;
import com.intermarche.backoffice.engine.stock.StockTraceLedger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.hibernate.Hibernate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;
import java.time.LocalDateTime;

/**
 * Organises a truck delivery into carts: proposes how to fill the free compatible carts within their capacity,
 * records what the picker actually loaded, and follows the put-away of each cart along its own route.
 * Stock is still received line by line by the existing receipt rules; carts only group and order the work.
 */
@ApplicationScoped @Transactional
public class UnloadingCartService {
 @Inject StorePickingRoutePlanner planner;

 /** Picker confirmation of the carts actually loaded. */
 public static class Confirmation {public Long storeId;public String requestKey;public List<CartInput> loads;}
 /** One loaded cart and its contents. */
 public static class CartInput {public Long cartId;public List<LineInput> lines;}
 /** One delivery line quantity placed on a cart. */
 public static class LineInput {public Long supplyId;public BigDecimal quantity;}

 /** A cart candidate with its capacity; capacities may be unknown for weight. */
 record Cart(PhysicalSupport support,Set<String> zones,BigDecimal liters,BigDecimal kg,int compartments){}
 /** A quantity of a delivery line to place, with its estimated volume and weight per unit of quantity. */
 record Need(ExpectedSupply supply,Product product,String zone,BigDecimal quantity,BigDecimal litersPerUnit,BigDecimal kgPerUnit){
  /**
   * Tells whether this need can be split into smaller quantities across carts.
   *
   * @return true for products counted in whole units
   */
  boolean divisible(){return product.productType==ProductType.UNIT;}
 }
 /** A cart being filled by the proposal. */
 static final class Fill {
  final Cart cart;final String zone;final Map<ExpectedSupply,BigDecimal> lines=new LinkedHashMap<>();BigDecimal liters=BigDecimal.ZERO,kg=BigDecimal.ZERO;boolean complete=true,overloaded=false;
  /**
   * Opens an empty fill of the given cart for one usage zone.
   *
   * @param cart the cart
   * @param zone the usage zone
   */
  Fill(Cart cart,String zone){this.cart=cart;this.zone=zone;}
 }

 /**
  * Returns the cart work of a delivery: a fresh proposal for what remains to be loaded, and the carts already loaded.
  *
  * @param order the truck unloading order
  * @return the proposal, the loaded carts and the counts of free carts per usage zone
  */
 public Map<String,Object> board(ReplenishmentOrder order){
  requireTruck(order);Store store=store(order);
  var result=new LinkedHashMap<String,Object>();
  result.put("proposal",propose(order,store));
  result.put("loads",UnloadingCartLoad.<UnloadingCartLoad>list("handlingOrder=?1 order by id",order).stream().map(this::view).toList());
  return result;
 }

 /**
  * Proposes how to fill the free compatible carts with what remains to be loaded from the truck.
  * Each cart carries a single usage zone; known volumes and weights stay within its capacity; a product counted in
  * units may be split across carts. Unknown dimensions are reported as such, never counted as zero capacity.
  *
  * @param order the truck unloading order
  * @param store the store of the order
  * @return the proposed carts with their route summary, and what could not be placed
  */
 Map<String,Object> propose(ReplenishmentOrder order,Store store){
  Map<String,List<Need>> byZone=new TreeMap<>();
  for(Need need:needs(order))byZone.computeIfAbsent(need.zone(),z->new ArrayList<>()).add(need);
  List<Cart> free=freeCarts(store);Set<Long> used=new HashSet<>();
  var loads=new ArrayList<Map<String,Object>>();var unassigned=new ArrayList<Map<String,Object>>();
  for(var entry:byZone.entrySet()){
   String zone=entry.getKey();
   List<Need> needs=new ArrayList<>(entry.getValue());
   needs.sort(Comparator.comparing((Need n)->n.litersPerUnit()==null?BigDecimal.ZERO:n.litersPerUnit().multiply(n.quantity())).reversed().thenComparing(n->n.supply().id));
   List<Cart> candidates=free.stream().filter(c->c.zones().contains(zone)&&!used.contains(c.support().id)).toList();
   var fills=new ArrayList<Fill>();int next=0;
   for(Need need:needs){
    BigDecimal left=need.quantity();
    for(Fill fill:fills){if(left.signum()<=0)break;left=left.subtract(place(fill,need,left,false));}
    while(left.signum()>0&&next<candidates.size()){Fill fill=new Fill(candidates.get(next++),zone);used.add(fill.cart.support().id);fills.add(fill);left=left.subtract(place(fill,need,left,true));}
    if(left.signum()>0)unassigned.add(needView(need,left,"Plus de chariot "+zoneLabel(zone).toLowerCase(Locale.ROOT)+" libre : à répartir sur un autre support."));
   }
   for(Fill fill:fills)loads.add(fillView(order,fill));
  }
  var result=new LinkedHashMap<String,Object>();result.put("loads",loads);result.put("unassigned",unassigned);
  result.put("freeCarts",free.stream().map(UnloadingCartService::cartView).toList());
  return result;
 }

 /**
  * Places as much of a need as fits in a cart and returns the quantity placed. An empty cart always takes at least
  * one unit (or the whole quantity of a product that cannot be split), marked as overloaded when it exceeds the
  * estimated capacity: the picker sees the real load and decides.
  *
  * @param fill the cart being filled
  * @param need the delivery line to place
  * @param left the quantity of that line still to place
  * @param empty whether the cart has just been opened for this need
  * @return the quantity placed in this cart
  */
 BigDecimal place(Fill fill,Need need,BigDecimal left,boolean empty){
  BigDecimal fit=left;
  BigDecimal roomLiters=fill.cart.liters().subtract(fill.liters),roomKg=fill.cart.kg()==null?null:fill.cart.kg().subtract(fill.kg);
  if(need.litersPerUnit()!=null&&need.litersPerUnit().signum()>0)fit=fit.min(units(roomLiters,need.litersPerUnit(),need.divisible()));
  if(roomKg!=null&&need.kgPerUnit()!=null&&need.kgPerUnit().signum()>0)fit=fit.min(units(roomKg,need.kgPerUnit(),need.divisible()));
  if(!need.divisible()&&fit.compareTo(left)<0)fit=BigDecimal.ZERO;
  if(fit.signum()<=0){if(!empty)return BigDecimal.ZERO;fit=need.divisible()?BigDecimal.ONE.min(left):left;fill.overloaded=true;}
  fill.lines.merge(need.supply(),fit,BigDecimal::add);
  if(need.litersPerUnit()==null)fill.complete=false;else fill.liters=fill.liters.add(need.litersPerUnit().multiply(fit));
  if(need.kgPerUnit()==null){if(fill.cart.kg()!=null)fill.complete=false;}else fill.kg=fill.kg.add(need.kgPerUnit().multiply(fit));
  return fit;
 }

 /**
  * Returns how many units of a size fit in a room: whole units for divisible products, the exact quotient otherwise.
  *
  * @param room the room left, possibly negative
  * @param perUnit the size of one unit of quantity
  * @param whole whether only whole units count
  * @return the quantity that fits, never negative
  */
 static BigDecimal units(BigDecimal room,BigDecimal perUnit,boolean whole){
  if(room.signum()<=0)return BigDecimal.ZERO;
  return whole?room.divide(perUnit,0,RoundingMode.FLOOR):room.divide(perUnit,6,RoundingMode.FLOOR);
 }

 /**
  * Lists what remains to be loaded on carts: expected quantity, minus what is already received, minus what already
  * sits on a loaded cart.
  *
  * @param order the truck unloading order
  * @return the remaining needs, one per delivery line
  */
 List<Need> needs(ReplenishmentOrder order){
  Map<Long,BigDecimal> committed=committed(order);
  var result=new ArrayList<Need>();
  for(ExpectedSupply supply:order.supplies){
   if(supply.status!=ExpectedSupply.SupplyStatus.EXPECTED)continue;
   BigDecimal left=supply.quantity.subtract(supply.received).subtract(committed.getOrDefault(supply.id,BigDecimal.ZERO));
   if(left.signum()<=0)continue;
   Product product=Hibernate.unproxy(supply.account.product,Product.class);
   var one=SupportLoadEstimate.estimate(List.of(new SupportLoadEstimate.Item(product.productType.name(),BigDecimal.ONE,product.referenceWeight,product.referenceVolume)));
   result.add(new Need(supply,product,product.temperatureZone.name(),left,one.volumeComplete()?one.liters():null,one.weightComplete()?one.kg():null));
  }
  return result;
 }

 /**
  * Sums, per delivery line, the quantities still sitting on loaded carts of this order.
  *
  * @param order the truck unloading order
  * @return the quantity committed to carts, per supply identifier
  */
 Map<Long,BigDecimal> committed(ReplenishmentOrder order){
  var result=new HashMap<Long,BigDecimal>();
  for(UnloadingCartLine line:UnloadingCartLine.<UnloadingCartLine>list("load.handlingOrder=?1 and load.state=?2",order,UnloadingCartLoad.CONFIRMED))result.merge(line.supply.id,line.remaining(),BigDecimal::add);
  return result;
 }

 /**
  * Lists the carts of the store that can take a delivery now: available, not loaded with another delivery and not
  * holding totes of a preparation. Larger carts come first so that fewer trips are needed.
  *
  * @param store the store
  * @return the free carts with their usage zones and capacities
  */
 List<Cart> freeCarts(Store store){
  var result=new ArrayList<Cart>();
  for(PhysicalSupport s:PhysicalSupport.<PhysicalSupport>list("select s from PhysicalSupport s join fetch s.type t join fetch s.tote where s.store=?1 and t.kind=?2 and s.state='AVAILABLE' order by s.code",store,SupportType.Kind.CART)){
   if(busy(s))continue;
   result.add(cart(s));
  }
  result.sort(Comparator.comparing((Cart c)->c.liters()).reversed().thenComparing(c->c.support().code));
  return result;
 }

 /**
  * Tells whether a cart is already in use: loaded with a delivery, or holding totes or a mission of a preparation.
  *
  * @param cart the cart
  * @return true when the cart cannot take a delivery
  */
 boolean busy(PhysicalSupport cart){
  return UnloadingCartLoad.count("cart=?1 and state=?2",cart,UnloadingCartLoad.CONFIRMED)>0||CartCompartment.count("cart=?1 and (occupant is not null or handlingOrder is not null)",cart)>0;
 }

 /**
  * Describes a cart with its usage zones and capacities. The volume capacity of the type prevails over the volume of
  * the backing support; a missing weight capacity means no weight limit is known.
  *
  * @param support the cart
  * @return the cart description
  */
 Cart cart(PhysicalSupport support){
  SupportType type=Hibernate.unproxy(support.type,SupportType.class);
  BigDecimal liters=type.maxVolumeLiters!=null?type.maxVolumeLiters:Hibernate.unproxy(support.tote,Tote.class).volume;
  return new Cart(support,new LinkedHashSet<>(Arrays.asList(type.allowedZones.split(","))),liters,type.maxWeightKg,(int)CartCompartment.count("cart",support));
 }

 /**
  * Records the carts the picker actually loaded. Temperature compatibility, availability and quantities are checked;
  * an estimated capacity overrun is only reported, since the picker sees the real load. Replaying the same request
  * returns the same result.
  *
  * @param order the truck unloading order
  * @param c the confirmation
  * @param actor the badge of the picker
  * @return the cart work after confirmation, with the capacity warnings of the confirmed carts
  */
 public Map<String,Object> confirm(ReplenishmentOrder order,Confirmation c,String actor){
  requireTruck(order);Store store=store(order);
  if(c==null||c.requestKey==null||!c.requestKey.matches("[a-zA-Z0-9-]{8,64}"))throw new IllegalArgumentException("Référence de confirmation requise");
  if(c.loads==null||c.loads.isEmpty())throw new IllegalArgumentException("Indiquez au moins un chariot chargé");
  String fingerprint=StockTraceLedger.key("UNLOADING_CARTS",order.id,c.loads.stream().map(l->Arrays.asList(l.cartId,l.lines==null?List.of():l.lines.stream().map(x->Arrays.asList(x.supplyId,x.quantity==null?null:x.quantity.stripTrailingZeros().toPlainString())).toList())).toList());
  var previous=UnloadingCartLoad.<UnloadingCartLoad>list("store=?1 and requestKey=?2",store,c.requestKey);
  if(!previous.isEmpty()){if(!previous.getFirst().fingerprint.equals(fingerprint)||!previous.getFirst().handlingOrder.id.equals(order.id))throw new IllegalStateException("Cette référence désigne un autre chargement");return withWarnings(board(order),previous);}
  Map<Long,BigDecimal> available=new HashMap<>();
  for(Need need:needs(order))available.put(need.supply().id,need.quantity());
  Set<Long> carts=new HashSet<>();var created=new ArrayList<UnloadingCartLoad>();LocalDateTime now=DateTimeProvider.now();
  for(CartInput input:c.loads){
   if(input==null||input.cartId==null||!carts.add(input.cartId))throw new IllegalArgumentException("Chaque chariot ne peut être confirmé qu’une fois");
   PhysicalSupport support=PhysicalSupport.find("id=?1 and store=?2",input.cartId,store).firstResult();
   if(support==null)throw new IllegalArgumentException("Chariot inconnu dans ce magasin");
   SupportType type=Hibernate.unproxy(support.type,SupportType.class);
   if(type.kind!=SupportType.Kind.CART)throw new IllegalArgumentException(support.code+" n’est pas un chariot");
   if(!"AVAILABLE".equals(support.state)||busy(support))throw new IllegalArgumentException("Le chariot "+support.code+" est déjà utilisé");
   if(input.lines==null||input.lines.isEmpty())throw new IllegalArgumentException("Le chariot "+support.code+" est vide");
   Set<String> zones=new HashSet<>(Arrays.asList(type.allowedZones.split(",")));
   var load=new UnloadingCartLoad();load.store=store;load.handlingOrder=order;load.cart=support;load.requestKey=c.requestKey;load.fingerprint=fingerprint;load.confirmedBy=actor;load.confirmedAt=now;
   Set<Long> supplies=new HashSet<>();
   for(LineInput line:input.lines){
    if(line==null||line.supplyId==null||!supplies.add(line.supplyId))throw new IllegalArgumentException("Une ligne de livraison apparaît deux fois sur "+support.code);
    ExpectedSupply supply=order.supplies.stream().filter(s->s.id.equals(line.supplyId)).findFirst().orElseThrow(()->new IllegalArgumentException("Ligne hors de cette livraison"));
    Product product=Hibernate.unproxy(supply.account.product,Product.class);
    if(line.quantity==null||line.quantity.signum()<=0||line.quantity.scale()>6)throw new IllegalArgumentException(product.name+" : quantité chargée invalide");
    if(product.productType==ProductType.UNIT&&line.quantity.stripTrailingZeros().scale()>0)throw new IllegalArgumentException(product.name+" : quantité entière requise");
    String zone=product.temperatureZone.name();
    if(!zones.contains(zone))throw new IllegalArgumentException(product.name+" ("+zoneLabel(zone).toLowerCase(Locale.ROOT)+") ne peut pas aller sur le chariot "+support.code);
    if(load.zone==null)load.zone=zone;else if(!load.zone.equals(zone))throw new IllegalArgumentException("Le chariot "+support.code+" mélange "+zoneLabel(load.zone).toLowerCase(Locale.ROOT)+" et "+zoneLabel(zone).toLowerCase(Locale.ROOT)+" : un usage par chariot");
    BigDecimal left=available.getOrDefault(supply.id,BigDecimal.ZERO);
    if(line.quantity.compareTo(left)>0)throw new IllegalArgumentException(product.name+" : "+left.stripTrailingZeros().toPlainString()+" restent à charger, "+line.quantity.stripTrailingZeros().toPlainString()+" indiqués");
    available.put(supply.id,left.subtract(line.quantity));
    var cartLine=new UnloadingCartLine();cartLine.load=load;cartLine.supply=supply;cartLine.quantity=line.quantity;load.lines.add(cartLine);
   }
   created.add(load);
  }
  for(var load:created)load.persist();
  UnloadingCartLoad.getEntityManager().flush();
  return withWarnings(board(order),created);
 }

 /**
  * Adds to a board the capacity warnings of the given carts.
  *
  * @param board the cart work
  * @param loads the carts just confirmed
  * @return the board with a warnings list
  */
 Map<String,Object> withWarnings(Map<String,Object> board,List<UnloadingCartLoad> loads){
  var warnings=new ArrayList<String>();
  for(var load:loads){var v=view(load);if(Boolean.TRUE.equals(v.get("overloaded")))warnings.add("Le chariot "+load.cart.code+" dépasse sa capacité estimée : vérifiez sa stabilité avant de rouler.");else if(!Boolean.TRUE.equals(v.get("estimateComplete")))warnings.add("Dimensions incomplètes sur "+load.cart.code+" : contrôle visuel du chargement nécessaire.");}
  var result=new LinkedHashMap<>(board);result.put("warnings",warnings);return result;
 }

 /**
  * Releases a loaded cart whose goods were put away another way or unloaded: what is still on it becomes available
  * for another cart, and the cart becomes free. Releasing an already closed cart changes nothing.
  *
  * @param order the truck unloading order
  * @param loadId the loaded cart
  * @return the cart work after release
  */
 public Map<String,Object> release(ReplenishmentOrder order,Long loadId){
  requireTruck(order);
  UnloadingCartLoad load=UnloadingCartLoad.find("id=?1 and handlingOrder=?2",loadId,order).firstResult();
  if(load==null)throw new IllegalArgumentException("Chariot hors de cette livraison");
  if(UnloadingCartLoad.CONFIRMED.equals(load.state)){load.state=UnloadingCartLoad.RELEASED;load.closedAt=DateTimeProvider.now();}
  return board(order);
 }

 /**
  * Returns the quantities still on a loaded cart, per delivery line, to restrict its put-away route.
  *
  * @param order the truck unloading order
  * @param loadId the loaded cart
  * @return the remaining quantity per supply identifier
  */
 public Map<Long,BigDecimal> routeLimits(ReplenishmentOrder order,Long loadId){
  UnloadingCartLoad load=UnloadingCartLoad.find("id=?1 and handlingOrder=?2",loadId,order).firstResult();
  if(load==null)throw new IllegalArgumentException("Chariot hors de cette livraison");
  if(!UnloadingCartLoad.CONFIRMED.equals(load.state))throw new IllegalArgumentException("Ce chariot est déjà vidé");
  var result=new LinkedHashMap<Long,BigDecimal>();for(var line:load.lines)if(line.remaining().signum()>0)result.put(line.supply.id,line.remaining());return result;
 }

 /**
  * Returns the code of a loaded cart, for labels.
  *
  * @param order the truck unloading order
  * @param loadId the loaded cart
  * @return the cart code
  */
 public String cartCode(ReplenishmentOrder order,Long loadId){
  UnloadingCartLoad load=UnloadingCartLoad.find("id=?1 and handlingOrder=?2",loadId,order).firstResult();
  if(load==null)throw new IllegalArgumentException("Chariot hors de cette livraison");
  return load.cart.code;
 }

 /**
  * Checks, before a receipt, that a put-away from a cart stays within what is on it.
  *
  * @param order the truck unloading order
  * @param cartLineId the line of the cart
  * @param supply the delivery line received
  * @param quantity the quantity put away
  * @return the cart line
  */
 public UnloadingCartLine requirePutAway(ReplenishmentOrder order,Long cartLineId,ExpectedSupply supply,BigDecimal quantity){
  UnloadingCartLine line=UnloadingCartLine.find("id=?1 and load.handlingOrder=?2",cartLineId,order).firstResult();
  if(line==null||!line.supply.id.equals(supply.id))throw new IllegalArgumentException("Ligne de chariot hors de cette livraison");
  if(!UnloadingCartLoad.CONFIRMED.equals(line.load.state))throw new IllegalArgumentException("Le chariot "+line.load.cart.code+" est déjà vidé");
  if(quantity.compareTo(line.remaining())>0)throw new IllegalArgumentException("Il reste "+line.remaining().stripTrailingZeros().toPlainString()+" sur le chariot "+line.load.cart.code);
  return line;
 }

 /**
  * Records a put-away from a cart once the receipt is confirmed, and frees the cart when it is empty.
  *
  * @param line the cart line
  * @param quantity the quantity put away
  */
 public void recordPutAway(UnloadingCartLine line,BigDecimal quantity){
  line.putAway=line.putAway.add(quantity);
  UnloadingCartLoad load=line.load;
  if(load.lines.stream().allMatch(l->l.remaining().signum()==0)){load.state=UnloadingCartLoad.DONE;load.closedAt=DateTimeProvider.now();}
 }

 /**
  * Checks that the order is an unloading from an external truck and is still open.
  *
  * @param order the order
  */
 void requireTruck(ReplenishmentOrder order){
  if(order.operation!=ReplenishmentOrder.Operation.TRUCK_UNLOADING||order.sourceBuilding!=null)throw new IllegalArgumentException("Les chariots de déchargement concernent une livraison camion");
  if(Set.of(BaseHandlingOrder.HandlingOrderStatus.CANCELLED,BaseHandlingOrder.HandlingOrderStatus.FAILED).contains(order.status))throw new IllegalArgumentException("Cette livraison est fermée");
 }

 /**
  * Returns the store of an order, unproxied.
  *
  * @param order the order
  * @return its store
  */
 static Store store(ReplenishmentOrder order){return Hibernate.unproxy(order.store,Store.class);}

 /**
  * Returns the French label of a usage zone.
  *
  * @param zone the zone name
  * @return its label
  */
 static String zoneLabel(String zone){return switch(zone){case "CHILLED"->"Frais";case "FROZEN"->"Surgelé";case "HOUSEHOLD"->"Entretien";default->"Ambiant";};}

 /**
  * Describes a proposed cart: its contents, fill rates and route summary.
  *
  * @param order the truck unloading order
  * @param fill the proposed fill
  * @return the proposed cart view
  */
 Map<String,Object> fillView(ReplenishmentOrder order,Fill fill){
  var v=new LinkedHashMap<String,Object>();v.put("cart",cartView(fill.cart));v.put("zone",fill.zone);
  v.put("lines",fill.lines.entrySet().stream().map(e->lineView(e.getKey(),e.getValue(),null,null)).toList());
  fillRates(v,fill.cart,fill.liters,fill.kg,fill.complete,fill.overloaded);
  v.put("route",route(order,fill.lines));
  return v;
 }

 /**
  * Describes a loaded cart with its progress and route summary.
  *
  * @param load the loaded cart
  * @return the loaded cart view
  */
 Map<String,Object> view(UnloadingCartLoad load){
  Cart cart=cart(load.cart);BigDecimal liters=BigDecimal.ZERO,kg=BigDecimal.ZERO;boolean complete=true;
  var v=new LinkedHashMap<String,Object>();v.put("id",load.id);v.put("state",load.state);v.put("cart",cartView(cart));v.put("zone",load.zone);v.put("confirmedBy",load.confirmedBy);v.put("confirmedAt",load.confirmedAt);v.put("closedAt",load.closedAt);
  var lines=new ArrayList<Map<String,Object>>();var remaining=new LinkedHashMap<ExpectedSupply,BigDecimal>();
  for(var line:load.lines){
   Product product=Hibernate.unproxy(line.supply.account.product,Product.class);
   var one=SupportLoadEstimate.estimate(List.of(new SupportLoadEstimate.Item(product.productType.name(),line.quantity,product.referenceWeight,product.referenceVolume)));
   if(one.volumeComplete())liters=liters.add(one.liters());else complete=false;
   if(one.weightComplete())kg=kg.add(one.kg());else if(cart.kg()!=null)complete=false;
   lines.add(lineView(line.supply,line.quantity,line.putAway,line.id));
   if(line.remaining().signum()>0)remaining.put(line.supply,line.remaining());
  }
  v.put("lines",lines);
  fillRates(v,cart,liters,kg,complete,liters.compareTo(cart.liters())>0||cart.kg()!=null&&kg.compareTo(cart.kg())>0);
  v.put("route",UnloadingCartLoad.CONFIRMED.equals(load.state)?route(load.handlingOrder,remaining):null);
  return v;
 }

 /**
  * Adds volume and weight fill rates to a cart view.
  *
  * @param v the view
  * @param cart the cart
  * @param liters the estimated volume loaded
  * @param kg the estimated weight loaded
  * @param complete whether every dimension was known
  * @param overloaded whether a known estimate exceeds the capacity
  */
 static void fillRates(Map<String,Object> v,Cart cart,BigDecimal liters,BigDecimal kg,boolean complete,boolean overloaded){
  v.put("liters",liters.setScale(1,RoundingMode.HALF_UP));v.put("kg",kg.setScale(1,RoundingMode.HALF_UP));
  v.put("volumePercent",known(liters,complete)?percent(liters,cart.liters()):null);v.put("weightPercent",cart.kg()==null||!known(kg,complete)?null:percent(kg,cart.kg()));
  v.put("estimateComplete",complete);v.put("overloaded",overloaded);
 }

 /**
  * Tells whether an estimate carries information: an incomplete estimate that found nothing would display a
  * misleading empty cart.
  *
  * @param value the estimated load
  * @param complete whether every dimension was known
  * @return false when the estimate is empty because no dimension was known
  */
 static boolean known(BigDecimal value,boolean complete){
  return complete||value.signum()!=0;
 }

 /**
  * Returns a fill rate as a whole percentage.
  *
  * @param value the load
  * @param capacity the capacity
  * @return the rounded percentage, or null when the capacity is unknown
  */
 static Integer percent(BigDecimal value,BigDecimal capacity){
  if(capacity==null||capacity.signum()<=0)return null;
  return value.multiply(BigDecimal.valueOf(100)).divide(capacity,0,RoundingMode.HALF_UP).intValue();
 }

 /**
  * Describes a cart for display.
  *
  * @param cart the cart
  * @return its identifier, code, type, usage zones, capacities and number of compartments
  */
 static Map<String,Object> cartView(Cart cart){
  var v=new LinkedHashMap<String,Object>();v.put("id",cart.support().id);v.put("code",cart.support().code);v.put("label",Hibernate.unproxy(cart.support().type,SupportType.class).label);
  v.put("zones",cart.zones());v.put("capacityLiters",cart.liters());v.put("capacityKg",cart.kg());v.put("compartments",cart.compartments());return v;
 }

 /**
  * Describes a delivery line placed on a cart.
  *
  * @param supply the delivery line
  * @param quantity the quantity on the cart
  * @param putAway the quantity already put away, or null for a proposal
  * @param lineId the cart line identifier, or null for a proposal
  * @return the line view
  */
 static Map<String,Object> lineView(ExpectedSupply supply,BigDecimal quantity,BigDecimal putAway,Long lineId){
  Product product=Hibernate.unproxy(supply.account.product,Product.class);BaseLocation destination=Hibernate.unproxy(supply.destination,BaseLocation.class);
  var v=new LinkedHashMap<String,Object>();v.put("id",lineId);v.put("supplyId",supply.id);v.put("productId",product.id);v.put("name",product.name);v.put("ean",product.ean);v.put("productType",product.productType.name());
  v.put("unit",product.productType==ProductType.WEIGHT?"kg":product.productType==ProductType.VOLUME?"L":product.unitName==null?"pcs":product.unitName);
  v.put("quantity",quantity);if(putAway!=null)v.put("putAway",putAway);v.put("destination",destination==null?null:destination.locationCode);return v;
 }

 /**
  * Describes a need that no cart could take.
  *
  * @param need the delivery line
  * @param left the quantity left
  * @param reason why it is left
  * @return the unassigned line view
  */
 static Map<String,Object> needView(Need need,BigDecimal left,String reason){
  var v=lineView(need.supply(),left,null,null);v.put("zone",need.zone());v.put("reason",reason);return v;
 }

 /**
  * Plans the put-away route of a cart over the destinations of its lines, with the store route planner.
  *
  * @param order the truck unloading order
  * @param lines the quantity on the cart per delivery line
  * @return the number of stops, the estimated distance and the ordered addresses, or the reason no route exists
  */
 Map<String,Object> route(ReplenishmentOrder order,Map<ExpectedSupply,BigDecimal> lines){
  var v=new LinkedHashMap<String,Object>();
  Building building=order.destinationBuilding==null?null:Hibernate.unproxy(order.destinationBuilding,Building.class);
  var steps=steps(order,lines,s->true);
  if(building==null||steps.isEmpty()){v.put("stops",0);v.put("distanceMeters",0);v.put("addresses",List.of());return v;}
  var result=planner.plan(building,steps,null);
  if(result==null){v.put("stops",steps.size());v.put("distanceMeters",null);v.put("addresses",steps.stream().map(s->s.aisle+" / "+s.unit).toList());v.put("reason","Plan du bâtiment à définir pour optimiser le parcours");return v;}
  var addresses=new ArrayList<String>();for(var group:result.route())for(var step:group.steps)addresses.add(step.productName+" → "+step.aisle+" / "+step.unit);
  v.put("stops",steps.size());v.put("distanceMeters",result.geometry().distanceMeters==null?0:(int)Math.round(result.geometry().distanceMeters));v.put("addresses",addresses);return v;
 }

 /**
  * Builds the route steps of a set of delivery lines towards their destination shelves.
  *
  * @param order the truck unloading order
  * @param lines the quantity per delivery line
  * @param keep a filter on the lines
  * @return the route steps
  */
 static List<RouteStepDTO> steps(ReplenishmentOrder order,Map<ExpectedSupply,BigDecimal> lines,Function<ExpectedSupply,Boolean> keep){
  var steps=new ArrayList<RouteStepDTO>();
  for(var e:lines.entrySet()){
   ExpectedSupply supply=e.getKey();if(!keep.apply(supply))continue;
   BaseLocation location=Hibernate.unproxy(supply.destination,BaseLocation.class);if(!(location instanceof ShelfLocation shelf))continue;
   Product product=Hibernate.unproxy(supply.account.product,Product.class);
   var step=new RouteStepDTO();step.pickedItemId=supply.id;step.sourceOrderId=order.id;step.sourceOrderCode=order.code;step.productId=product.id;step.productName=product.name;step.quantity=e.getValue();step.productType=product.productType.name();step.temperatureZone=product.temperatureZone;step.aisle=shelf.aisle;step.unit=shelf.unit;step.area=shelf.area;step.position=shelf.position;
   if(shelf instanceof BufferToteStockLocation bufferPlace){Buffer buffer=Hibernate.unproxy(bufferPlace.buffer,Buffer.class);step.unit=buffer.bufferCode;step.area="BUFFER";step.aisle="BUFFER";step.planNodeCode=buffer.planNodeCode;}
   steps.add(step);
  }
  return steps;
 }
}
