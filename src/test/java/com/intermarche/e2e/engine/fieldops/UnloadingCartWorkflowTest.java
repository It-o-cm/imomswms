package com.intermarche.e2e.engine.fieldops;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.stock.ExpectedSupply;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/** Truck unloading by carts: proposal within capacity and usage, picker confirmation, put-away per cart and release. */
@QuarkusTest @jakarta.enterprise.inject.Typed(UnloadingCartWorkflowTest.class)
class UnloadingCartWorkflowTest extends MissionCoordinationTest {
 /**
  * Truck delivery prepared for a test.
  *
  * @param order the replenishment order
  * @param food the supply of the ambient food product
  * @param household the supply of the household product
  * @param chilled the supply of the chilled product
  * @param carts the cart ids by test label
  */
 record Delivery(long order,long food,long household,long chilled,Map<String,Long> carts){}

 /**
  * Sends an API request as the given actor.
  *
  * @param actor the bearer token
  * @param method the HTTP method
  * @param path the path
  * @param body the JSON body, or null
  * @return the response
  */
 Response api(String actor,String method,String path,Object body){var r=given().auth().oauth2(actor).contentType("application/json");if(body!=null)r.body(body);return r.request(method,path);}

 /**
  * Creates three products (ambient food 1 L, household 2 L, chilled of unknown size), four carts (two ambient carts
  * of 10 L, a small ambient cart of 4 L, an insulated cart of 10 L) and a truck delivering 15, 3 and 2 of them.
  *
  * @return the delivery and the identifiers of its lines and carts
  */
 Delivery delivery(){
  String code=key().substring(0,6);
  long[] ids=QuarkusTransaction.requiringNew().call(()->{
   Store store=Store.findById(f.store());Building building=Building.findById(f.building());
   Product food=Product.findById(f.product());food.referenceVolume=BigDecimal.ONE;food.referenceWeight=null;
   Product household=product("H"+code,"Lessive test",TemperatureZone.HOUSEHOLD,new BigDecimal("2"));
   Product chilled=product("C"+code,"Yaourt test",TemperatureZone.CHILLED,null);
   long householdPlace=place(building,household,"2"),chilledPlace=place(building,chilled,"3");
   SupportType ambient=type(store,"AMB-"+code,"AMBIENT,HOUSEHOLD",new BigDecimal("10")),small=type(store,"SML-"+code,"AMBIENT,HOUSEHOLD",new BigDecimal("4")),cold=type(store,"CLD-"+code,"CHILLED,FROZEN",new BigDecimal("10"));
   long c1=cart(store,ambient,"C1"+code),c2=cart(store,ambient,"C2"+code),c4=cart(store,small,"C4"+code),c3=cart(store,cold,"C3"+code);
   return new long[]{household.id,chilled.id,householdPlace,chilledPlace,c1,c2,c3,c4};
  });
  long order=api(f.manager(),"POST","/replenishments",Map.of("storeId",f.store(),"requestKey",key(),"operation","TRUCK_UNLOADING","truckReference","CAM-CHARIOTS","start",DateTimeProvider.now().toString(),"end",DateTimeProvider.now().plusHours(1).toString(),"lines",List.of(Map.of("productId",f.product(),"quantity",15,"destinationLocationId",f.location()),Map.of("productId",ids[0],"quantity",3,"destinationLocationId",ids[2]),Map.of("productId",ids[1],"quantity",2,"destinationLocationId",ids[3])))).then().statusCode(200).extract().jsonPath().getLong("id");
  Map<Long,Long> supplies=QuarkusTransaction.requiringNew().call(()->{var result=new HashMap<Long,Long>();for(ExpectedSupply s:com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder.<com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder>findById(order).supplies)result.put(s.account.product.id,s.id);return result;});
  return new Delivery(order,supplies.get(f.product()),supplies.get(ids[0]),supplies.get(ids[1]),Map.of("C1",ids[4],"C2",ids[5],"C3",ids[6],"C4",ids[7]));
 }
 /**
  * Creates a product.
  *
  * @param ean the EAN
  * @param name the name
  * @param zone the temperature zone
  * @param liters the reference volume of one unit, or null when unknown
  * @return the product
  */
 static Product product(String ean,String name,TemperatureZone zone,BigDecimal liters){var p=new Product();p.ean=ean;p.name=name;p.productType=ProductType.UNIT;p.temperatureZone=zone;p.referenceVolume=liters;p.persist();return p;}
 /**
  * Creates a shelf address for a product in a building.
  *
  * @param building the building
  * @param product the product
  * @param unit the bay
  * @return the address identifier
  */
 static long place(Building building,Product product,String unit){var l=new StoreShelfLocation();l.product=product;l.area="Z";l.aisle="A";l.unit=unit;l.elevation="1";l.position="1";l.persist();building.locations.add(l);return l.id;}
 /**
  * Creates a cart type.
  *
  * @param store the store
  * @param code the type code
  * @param zones the usage zones
  * @param liters the volume capacity
  * @return the type
  */
 static SupportType type(Store store,String code,String zones,BigDecimal liters){var t=new SupportType();t.store=store;t.code=code;t.label="Chariot "+code;t.kind=SupportType.Kind.CART;t.allowedZones=zones;t.maxVolumeLiters=liters;t.persist();return t;}
 /**
  * Creates a cart of a type with two compartments.
  *
  * @param store the store
  * @param type the cart type
  * @param code the cart code
  * @return the cart identifier
  */
 static long cart(Store store,SupportType type,String code){var tote=new Tote();tote.store=store;tote.code=code;tote.volume=type.maxVolumeLiters;tote.toteType=type.allowedZones.contains("CHILLED")?Tote.ToteType.INSULATED:Tote.ToteType.STANDARD;tote.persist();var s=new PhysicalSupport();s.store=store;s.type=type;s.tote=tote;s.code=code;s.persist();for(int c=1;c<=2;c++){var compartment=new CartCompartment();compartment.cart=s;compartment.rowNumber=1;compartment.columnNumber=c;compartment.label="1 / "+c;compartment.persist();}return s.id;}
 /**
  * Reads the cart work of a delivery.
  *
  * @param d the delivery
  * @return the JSON body
  */
 JsonPath board(Delivery d){return api(f.manager(),"GET","/replenishments/"+d.order()+"/carts?storeId="+f.store(),null).then().statusCode(200).extract().jsonPath();}
 /**
  * Builds a confirmation of loaded carts.
  *
  * @param key the request key
  * @param loads cart identifier followed by supply and quantity pairs, one array per cart
  * @return the confirmation body
  */
 Map<String,Object> confirmation(String key,Object[]... loads){var list=new ArrayList<Map<String,Object>>();for(Object[] l:loads){var lines=new ArrayList<Map<String,Object>>();for(int i=1;i<l.length;i+=2)lines.add(Map.of("supplyId",l[i],"quantity",l[i+1]));list.add(Map.of("cartId",l[0],"lines",lines));}return Map.of("storeId",f.store(),"requestKey",key,"loads",list);}

 /**
  * The proposal fills carts compatible with each zone within their capacity and explains what stays unplaced.
  */
 @Test void proposalFillsCompatibleCartsWithinCapacityAndReportsWhatCannotBePlaced(){
  Delivery d=delivery();JsonPath b=board(d);
  assertEquals(4,b.getList("proposal.freeCarts").size());
  List<Map<String,Object>> loads=b.getList("proposal.loads");
  var ambient=loads.stream().filter(l->"AMBIENT".equals(l.get("zone"))).toList();
  assertEquals(2,ambient.size());
  assertEquals(List.of(10,5),ambient.stream().map(l->((Number)((List<Map<String,Object>>)l.get("lines")).getFirst().get("quantity")).intValue()).toList());
  assertEquals(100,((Number)ambient.getFirst().get("volumePercent")).intValue());
  var chilled=loads.stream().filter(l->"CHILLED".equals(l.get("zone"))).findFirst().orElseThrow();
  assertEquals(false,chilled.get("estimateComplete"));
  assertNull(chilled.get("volumePercent"));
  assertEquals(d.carts().get("C3").intValue(),((Number)((Map<String,Object>)chilled.get("cart")).get("id")).intValue());
  var householdCart=loads.stream().filter(l->"HOUSEHOLD".equals(l.get("zone"))).findFirst().orElseThrow();
  assertEquals(d.carts().get("C4").intValue(),((Number)((Map<String,Object>)householdCart.get("cart")).get("id")).intValue());
  assertEquals(2,((Number)((List<Map<String,Object>>)householdCart.get("lines")).getFirst().get("quantity")).intValue());
  List<Map<String,Object>> unassigned=b.getList("proposal.unassigned");
  var household=unassigned.stream().filter(u->"HOUSEHOLD".equals(u.get("zone"))).findFirst().orElseThrow();
  assertEquals(1,((Number)household.get("quantity")).intValue());
  assertTrue(String.valueOf(household.get("reason")).contains("entretien"));
  assertTrue(((Map<String,Object>)ambient.getFirst().get("route")).containsKey("reason"));
 }

 /**
  * A confirmation refuses incompatible, mixed, excessive or reused carts and replays identically.
  */
 @Test void confirmationRejectsWrongTemperatureMixedUsageExcessAndReusedCartsAndIsIdempotent(){
  Delivery d=delivery();String path="/replenishments/"+d.order()+"/carts";
  api(f.manager(),"POST",path,confirmation(key(),new Object[]{d.carts().get("C3"),d.household(),1})).then().statusCode(400);
  api(f.manager(),"POST",path,confirmation(key(),new Object[]{d.carts().get("C2"),d.food(),1,d.household(),1})).then().statusCode(400);
  api(f.manager(),"POST",path,confirmation(key(),new Object[]{d.carts().get("C2"),d.food(),16})).then().statusCode(400);
  api(f.manager(),"POST",path,confirmation(key(),new Object[]{d.carts().get("C2"),d.food(),1},new Object[]{d.carts().get("C2"),d.food(),1})).then().statusCode(400);
  api(f.manager(),"POST",path,confirmation(key(),new Object[]{999999L,d.food(),1})).then().statusCode(400);
  api(f.manager(),"POST",path,Map.of("storeId",f.store(),"requestKey","court","loads",List.of())).then().statusCode(400);
  api(f.manager(),"POST",path,Map.of("storeId",f.store(),"requestKey",key(),"loads",List.of())).then().statusCode(400);
  api(f.manager(),"POST",path,Map.of("storeId",f.store(),"requestKey",key(),"loads",List.of(Map.of("cartId",d.carts().get("C2"),"lines",List.of())))).then().statusCode(400);
  api(f.manager(),"POST",path,confirmation(key(),new Object[]{d.carts().get("C2"),d.food(),0})).then().statusCode(400);
  api(f.manager(),"POST",path,confirmation(key(),new Object[]{d.carts().get("C2"),d.food(),new BigDecimal("1.5")})).then().statusCode(400);
  api(f.manager(),"POST",path,confirmation(key(),new Object[]{d.carts().get("C2"),d.food(),1,d.food(),1})).then().statusCode(400);
  api(f.manager(),"POST",path,confirmation(key(),new Object[]{d.carts().get("C2"),999999L,1})).then().statusCode(400);
  Long tote=QuarkusTransaction.requiringNew().call(()->{supportCatalog.registerAll(Store.findById(f.store()));return PhysicalSupport.<PhysicalSupport>find("code",f.tote()).firstResult().id;});
  api(f.manager(),"POST",path,confirmation(key(),new Object[]{tote,d.food(),1})).then().statusCode(400);
  QuarkusTransaction.requiringNew().run(()->PhysicalSupport.<PhysicalSupport>findById(d.carts().get("C2")).state="BLOCKED");
  api(f.manager(),"POST",path,confirmation(key(),new Object[]{d.carts().get("C2"),d.food(),1})).then().statusCode(400);
  QuarkusTransaction.requiringNew().run(()->PhysicalSupport.<PhysicalSupport>findById(d.carts().get("C2")).state="AVAILABLE");
  String first=key();var body=confirmation(first,new Object[]{d.carts().get("C1"),d.food(),10},new Object[]{d.carts().get("C3"),d.chilled(),2});
  var confirmed=api(f.manager(),"POST",path,body).then().statusCode(200).extract().jsonPath();
  assertEquals(2,confirmed.getList("loads").size());
  assertTrue(confirmed.getList("warnings",String.class).stream().anyMatch(w->w.contains("Dimensions incomplètes")));
  api(f.manager(),"POST",path,body).then().statusCode(200);
  api(f.manager(),"POST",path,confirmation(first,new Object[]{d.carts().get("C2"),d.food(),1})).then().statusCode(409);
  api(f.manager(),"POST",path,confirmation(key(),new Object[]{d.carts().get("C1"),d.food(),1})).then().statusCode(400);
  api(f.manager(),"POST",path,confirmation(key(),new Object[]{d.carts().get("C2"),d.food(),6})).then().statusCode(400);
  QuarkusTransaction.requiringNew().run(()->assertEquals(2,UnloadingCartLoad.count("handlingOrder.id",d.order())));
  JsonPath after=board(d);
  List<Map<String,Object>> proposal=after.getList("proposal.loads");
  assertEquals(1,proposal.stream().filter(l->"AMBIENT".equals(l.get("zone"))).count());
  assertTrue(proposal.stream().noneMatch(l->"CHILLED".equals(l.get("zone"))));
 }

 /**
  * An estimated overload is reported as a warning: the picker sees the real load.
  */
 @Test void anOverloadIsOnlyAWarningSinceThePickerSeesTheRealLoad(){
  Delivery d=delivery();
  var confirmed=api(f.manager(),"POST","/replenishments/"+d.order()+"/carts",confirmation(key(),new Object[]{d.carts().get("C4"),d.food(),5})).then().statusCode(200).extract().jsonPath();
  assertTrue(confirmed.getList("warnings",String.class).stream().anyMatch(w->w.contains("dépasse sa capacité estimée")));
  assertEquals(true,confirmed.getBoolean("loads[0].overloaded"));
 }

 /**
  * Putting away from a cart is limited to its content and closes the load once empty.
  */
 @Test void putAwayFromACartStaysWithinItsContentAndFreesTheCartWhenEmpty(){
  Delivery d=delivery();
  var confirmed=api(f.manager(),"POST","/replenishments/"+d.order()+"/carts",confirmation(key(),new Object[]{d.carts().get("C1"),d.food(),10})).then().statusCode(200).extract().jsonPath();
  long load=confirmed.getLong("loads[0].id"),line=confirmed.getLong("loads[0].lines[0].id");
  var receipt=Map.of("storeId",f.store(),"eventKey",key(),"lines",List.of(Map.of("supplyId",d.food(),"quantity",4,"cartLineId",line)));
  api(f.manager(),"POST","/replenishments/"+d.order()+"/receipts",receipt).then().statusCode(200);
  api(f.manager(),"POST","/replenishments/"+d.order()+"/receipts",receipt).then().statusCode(200);
  QuarkusTransaction.requiringNew().run(()->{var l=UnloadingCartLine.<UnloadingCartLine>findById(line);assertEquals(0,new BigDecimal("4").compareTo(l.putAway));assertEquals(UnloadingCartLoad.CONFIRMED,l.load.state);});
  api(f.manager(),"POST","/replenishments/"+d.order()+"/receipts",Map.of("storeId",f.store(),"eventKey",key(),"lines",List.of(Map.of("supplyId",d.food(),"quantity",7,"cartLineId",line)))).then().statusCode(400);
  api(f.manager(),"POST","/replenishments/"+d.order()+"/receipts",Map.of("storeId",f.store(),"eventKey",key(),"lines",List.of(Map.of("supplyId",d.household(),"quantity",1,"cartLineId",line)))).then().statusCode(400);
  api(f.manager(),"POST","/replenishments/"+d.order()+"/receipts",Map.of("storeId",f.store(),"eventKey",key(),"lines",List.of(Map.of("supplyId",d.food(),"quantity",6,"cartLineId",line)))).then().statusCode(200);
  QuarkusTransaction.requiringNew().run(()->{var l=UnloadingCartLoad.<UnloadingCartLoad>findById(load);assertEquals(UnloadingCartLoad.DONE,l.state);assertNotNull(l.closedAt);});
  api(f.manager(),"POST","/replenishments/"+d.order()+"/receipts",Map.of("storeId",f.store(),"eventKey",key(),"lines",List.of(Map.of("supplyId",d.food(),"quantity",1,"cartLineId",line)))).then().statusCode(400);
  api(f.manager(),"GET","/replenishments/"+d.order()+"/route?storeId="+f.store()+"&cartLoadId="+load,null).then().statusCode(400);
  List<Map<String,Object>> proposal=board(d).getList("proposal.loads");
  assertTrue(proposal.stream().filter(p->"AMBIENT".equals(p.get("zone"))).anyMatch(p->((Number)((Map<String,Object>)p.get("cart")).get("id")).longValue()==d.carts().get("C1")));
 }

 /**
  * Releasing a cart gives what remained on it back to the proposal.
  */
 @Test void releasingACartReturnsItsRemainingContentToTheProposal(){
  Delivery d=delivery();String path="/replenishments/"+d.order()+"/carts";
  long load=api(f.manager(),"POST",path,confirmation(key(),new Object[]{d.carts().get("C3"),d.chilled(),2})).then().statusCode(200).extract().jsonPath().getLong("loads[0].id");
  assertTrue(board(d).<Map<String,Object>>getList("proposal.loads").stream().noneMatch(l->"CHILLED".equals(l.get("zone"))));
  api(f.manager(),"POST",path+"/"+load+"/release?storeId="+f.store(),null).then().statusCode(200);
  api(f.manager(),"POST",path+"/"+load+"/release?storeId="+f.store(),null).then().statusCode(200);
  QuarkusTransaction.requiringNew().run(()->assertEquals(UnloadingCartLoad.RELEASED,UnloadingCartLoad.<UnloadingCartLoad>findById(load).state));
  assertTrue(board(d).<Map<String,Object>>getList("proposal.loads").stream().anyMatch(l->"CHILLED".equals(l.get("zone"))));
  api(f.manager(),"POST",path+"/999999/release?storeId="+f.store(),null).then().statusCode(400);
  api(f.manager(),"GET","/replenishments/"+d.order()+"/route?storeId="+f.store()+"&cartLoadId="+load,null).then().statusCode(400);
 }

 /**
  * Carts only apply to open truck deliveries.
  */
 @Test void cartsConcernOnlyOpenTruckDeliveries(){
  Delivery d=delivery();
  QuarkusTransaction.requiringNew().run(()->BaseHandlingOrder.<BaseHandlingOrder>findById(d.order()).status=BaseHandlingOrder.HandlingOrderStatus.CANCELLED);
  api(f.manager(),"GET","/replenishments/"+d.order()+"/carts?storeId="+f.store(),null).then().statusCode(400);
  QuarkusTransaction.requiringNew().run(()->{var o=com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder.<com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder>findById(d.order());o.status=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;o.operation=com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder.Operation.SHELF_REPLENISHMENT;});
  api(f.manager(),"GET","/replenishments/"+d.order()+"/carts?storeId="+f.store(),null).then().statusCode(400);
  api(f.manager(),"POST","/replenishments/"+d.order()+"/carts",Map.of("storeId",f.store())).then().statusCode(400);
  QuarkusTransaction.requiringNew().run(()->{var o=com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder.<com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder>findById(d.order());o.operation=com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder.Operation.TRUCK_UNLOADING;o.sourceBuilding=Building.findById(f.building());});
  api(f.manager(),"GET","/replenishments/"+d.order()+"/carts?storeId="+f.store(),null).then().statusCode(400);
  QuarkusTransaction.requiringNew().run(()->{var o=com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder.<com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder>findById(d.order());o.sourceBuilding=null;o.status=BaseHandlingOrder.HandlingOrderStatus.FAILED;});
  api(f.manager(),"GET","/replenishments/"+d.order()+"/carts?storeId="+f.store(),null).then().statusCode(400);
 }

 /**
  * A cart already holding totes of a preparation is not proposed.
  */
 @Test void aCartHoldingTotesOfAPreparationIsNotProposed(){
  Delivery d=delivery();
  QuarkusTransaction.requiringNew().run(()->{var c=CartCompartment.<CartCompartment>find("cart.id",d.carts().get("C1")).firstResult();c.handlingOrder=BaseHandlingOrder.findById(f.order());});
  List<Map<String,Object>> loads=board(d).getList("proposal.loads");
  assertTrue(loads.stream().noneMatch(l->((Number)((Map<String,Object>)l.get("cart")).get("id")).longValue()==d.carts().get("C1")));
  api(f.manager(),"POST","/replenishments/"+d.order()+"/carts",confirmation(key(),new Object[]{d.carts().get("C1"),d.food(),1})).then().statusCode(400);
 }
}
