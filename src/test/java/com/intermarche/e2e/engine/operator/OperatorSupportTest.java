package com.intermarche.e2e.engine.operator;

import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.wms.handling.*;
import com.intermarche.backoffice.domain.executing.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;
import org.mindrot.jbcrypt.BCrypt;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, JWT authentication and independent committed transactions (including concurrent requests). */
@QuarkusTest
class OperatorSupportTest {
 record Fixture(Long store,Long other,Long employee,String badge,String token,String manager,Long order,Long execution,Long item,Long buffer,Long cabinet,String tote,String secondTote){}
 Fixture f;
 @BeforeEach void fixture(){
  String key=UUID.randomUUID().toString().substring(0,8);
  f=QuarkusTransaction.requiringNew().call(()->{
   Store store=new Store();store.code="OP-"+key;store.name="Test operator";store.persist();Store other=new Store();other.code="OTHER-"+key;other.name="Voisin";other.persist();
   StoreBuilding building=new StoreBuilding();building.buildingCode="B-"+key;building.persist();store.buildings.add(building);
   Employee picker=employee(store,key+"P",Employee.EmployeeRole.PICKER);employee(store,key+"M",Employee.EmployeeRole.MANAGER);
   Product product=new Product();product.ean="OP"+key;product.name="Produit témoin";product.productType=ProductType.UNIT;product.temperatureZone=TemperatureZone.AMBIENT;product.referenceVolume=BigDecimal.ONE;product.persist();
   StoreShelfLocation location=new StoreShelfLocation();location.product=product;location.area="ZONE";location.aisle="A";location.unit="1";location.elevation="1";location.position="1";location.persist();building.locations.add(location);
   FulfillmentLine fl=new FulfillmentLine();fl.product=product;fl.requestedQuantity=new BigDecimal("2");fl.location=location;fl.persist();
   LotLine line=new LotLine();line.fulfillmentLine=fl;line.product=product;line.location=location;line.quantity=new BigDecimal("2");line.persist();
   ProductCollectingOrder order=new ProductCollectingOrder();order.code="PRO-"+key;order.store=store;order.assignee=picker;order.status=BaseHandlingOrder.HandlingOrderStatus.PROCESSING;order.persist();
   PickingExecution execution=new PickingExecution();execution.handlingOrder=order;execution.employee=picker;execution.persist();
   PickedItem item=new PickedItem();item.product=product;item.lotLine=line;item.quantity=new BigDecimal("2");item.status=PickedItem.PickStatus.PENDING;item.persist();execution.itemsToPick.add(item);
   Buffer buffer=new Buffer();buffer.bufferCode="BUF-"+key;buffer.building=building;buffer.active=true;buffer.ambientCapacity=9;buffer.persist();
   BufferCabinet cabinet=new BufferCabinet();cabinet.buffer=buffer;cabinet.code="A";cabinet.columns=3;cabinet.rows=3;cabinet.zone=TemperatureZone.AMBIENT;cabinet.persist();
   for(String suffix:List.of("A","B")){Tote tote=new Tote();tote.code=key+suffix;tote.store=store;tote.toteType=Tote.ToteType.STANDARD;tote.volume=new BigDecimal("60");tote.persist();}
   return new Fixture(store.id,other.id,picker.id,picker.badgeId,null,key+"M",order.id,execution.id,item.id,buffer.id,cabinet.id,key+"A",key+"B");
  });
  f=new Fixture(f.store,f.other,f.employee,f.badge,login(f.badge),login(f.manager),f.order,f.execution,f.item,f.buffer,f.cabinet,f.tote,f.secondTote);
 }
 Employee employee(Store store,String badge,Employee.EmployeeRole role){Employee e=new Employee();e.badgeId=badge;e.firstName="Test";e.lastName=role.name();e.email=badge+"@example.test";e.password=BCrypt.hashpw("test-pin",BCrypt.gensalt(4));e.role=role;e.store=store;e.active=true;e.persist();return e;}
 String login(String badge){return given().contentType("application/json").body(Map.of("username",badge,"password","test-pin")).post("/auth/login").then().statusCode(200).extract().path("token");}
 Response get(String path){return given().auth().oauth2(f.token).get("/handling-orders/operator"+path);}
 Response command(Map<String,Object> body){return given().auth().oauth2(f.token).contentType("application/json").body(body).post("/handling-orders/operator/commands");}
 Map<String,Object> action(String action){var c=new LinkedHashMap<String,Object>();c.put("storeId",f.store);c.put("executionId",f.execution);c.put("requestKey",UUID.randomUUID().toString());c.put("action",action);return c;}
 String revision(){return get("/orders/"+f.order+"?storeId="+f.store).then().statusCode(200).extract().path("revision");}
 Map<String,Object> v2(String action){var c=action(action);c.put("contractVersion",2);c.put("expectedRevision",revision());return c;}
 void attach(String code){var c=action("ADD_TOTE");c.put("toteCode",code);c.put("usage","AMBIENT");command(c).then().statusCode(200);}
 Map<String,Object> pick(){var c=action("PICK");c.put("itemId",f.item);c.put("quantity",2);c.put("toteCode",f.tote);return c;}
 List<Response> concurrent(Map<String,Object> a,Map<String,Object> b) throws Exception {
  var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try {var x=pool.submit(()->{gate.await();return command(a);});var y=pool.submit(()->{gate.await();return command(b);});gate.countDown();return List.of(x.get(30,TimeUnit.SECONDS),y.get(30,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
 }

 @Test void preferencesAndPauseNeverChangeStockOrCloseMission(){
  var before=QuarkusTransaction.requiringNew().call(()->com.intermarche.backoffice.domain.stock.StockMovement.count());
  support("PUT","/session",Map.of("storeId",f.store,"state","PAUSED","textPercent",130,"sound",false)).then().statusCode(200);
  var session=support("GET","/session?storeId="+f.store,null).then().statusCode(200).extract();assertEquals("PAUSED",session.path("state"));assertEquals(130,session.<Integer>path("textPercent"));assertEquals(false,session.path("policy.localPersistence"));
  QuarkusTransaction.requiringNew().run(()->{assertEquals(before,com.intermarche.backoffice.domain.stock.StockMovement.count());assertEquals(BaseHandlingOrder.HandlingOrderStatus.PROCESSING,BaseHandlingOrder.<BaseHandlingOrder>findById(f.order).status);});
  support("PUT","/session",Map.of("storeId",f.store,"state","INVALID")).then().statusCode(400);
 }
 Response support(String method,String path,Object body){var req=given().auth().oauth2(f.token);if(body!=null)req.contentType("application/json").body(body);return req.request(method,"/handling-orders/operator/support"+path);}
 Response manager(String method,String path,Object body){var req=given().auth().oauth2(f.manager);if(body!=null)req.contentType("application/json").body(body);return req.request(method,"/handling-orders/operator/support"+path);}
 Map<String,Object> instruction(){return new LinkedHashMap<>(Map.of("storeId",f.store,"employeeId",f.employee,"orderId",f.order,"requestKey",UUID.randomUUID().toString(),"label","Urgente","message","Vérifier la chaîne du froid","urgent",true,"mandatory",true));}
 @Test void commandEventAndReceiptAreAtomicAndReplaysDoNotPublishAgain(){var c=action("ADD_TOTE");c.putAll(Map.of("toteCode",f.tote,"usage","AMBIENT"));command(c).then().statusCode(200);command(c).then().statusCode(200);var page=support("GET","/events?storeId="+f.store,null).then().statusCode(200).extract();assertEquals(1,page.jsonPath().getList("items").size());assertEquals("COMMAND_CONFIRMED",page.path("items[0].category"));assertEquals(1,manager("GET","/events?storeId="+f.store,null).then().statusCode(200).extract().jsonPath().getList("items").size());}
 @Test void failedBusinessOperationDoesNotPublishEvent(){attach(f.tote);QuarkusTransaction.requiringNew().run(()->{Tote tote=Tote.findByCode(f.tote);tote.volume=BigDecimal.ONE;});command(pick()).then().statusCode(400);assertEquals(1,support("GET","/events?storeId="+f.store,null).then().statusCode(200).extract().jsonPath().getList("items").size());}
 @Test void reconnectPagesReplayInCursorOrderWithoutExecutingAnything(){for(int i=0;i<3;i++)manager("POST","/instructions",instruction()).then().statusCode(200);long cursor=0;var ids=new HashSet<Object>();for(int i=0;i<3;i++){var p=support("GET","/events?storeId="+f.store+"&limit=1&after="+cursor,null).then().statusCode(200).extract();long next=p.jsonPath().getLong("nextCursor");assertTrue(next>cursor);cursor=next;assertTrue(ids.add(p.path("items[0].id")));}var replay=support("GET","/events?storeId="+f.store+"&after=0&category=INSTRUCTION",null).then().statusCode(200).extract();assertEquals(3,replay.jsonPath().getList("items").size());QuarkusTransaction.requiringNew().run(()->assertEquals(0,OperatorCommandReceipt.count("store.id",f.store)));}
 @Test void mandatoryInstructionHasIdempotentAckVisibleToManager(){var request=instruction();var sent=manager("POST","/instructions",request).then().statusCode(200).extract();long id=sent.jsonPath().getLong("id");assertEquals(id,manager("POST","/instructions",request).then().statusCode(200).extract().jsonPath().getLong("id"));var read=support("POST","/events/"+id+"/received?storeId="+f.store,null).then().statusCode(200).extract();assertNotNull(read.path("receivedAt"));String ack=support("POST","/events/"+id+"/ack?storeId="+f.store,null).then().statusCode(200).extract().asString();assertEquals(ack,support("POST","/events/"+id+"/ack?storeId="+f.store,null).then().statusCode(200).extract().asString());var board=manager("GET","/instructions?storeId="+f.store,null).then().statusCode(200).extract();assertNotNull(board.path("items[0].recipients[0].acknowledgedAt"));assertEquals(f.order,board.jsonPath().getLong("items[0].orderId"));}
 @Test void instructionAndFeedAreStrictlyScoped(){support("POST","/instructions",instruction()).then().statusCode(403);var foreign=instruction();foreign.put("storeId",f.other);manager("POST","/instructions",foreign).then().statusCode(403);support("GET","/events?storeId="+f.other,null).then().statusCode(403);long id=manager("POST","/instructions",instruction()).then().statusCode(200).extract().jsonPath().getLong("id");support("POST","/events/"+id+"/ack?storeId="+f.other,null).then().statusCode(403);}
 @Test void diagnosticsDiscardSecretsAndCanBeConsultedByManager(){var packet=new LinkedHashMap<String,Object>(Map.of("storeId",f.store,"requestKey",UUID.randomUUID().toString(),"appVersion","0.2.0","osVersion","14","sdk",34,"screen","TERMINAL","errorCode","NETWORK_UNCERTAIN","httpStatus",0));packet.put("token","SECRET_BEARER");packet.put("password","SECRET_PASSWORD");packet.put("scan","FULL_RAW_SCAN");var response=support("POST","/diagnostics",packet).then().statusCode(200).extract();assertNotNull(response.path("trackingId"));assertEquals(response.<String>path("trackingId"),support("POST","/diagnostics",packet).then().statusCode(200).extract().path("trackingId"));String stored=manager("GET","/diagnostics?storeId="+f.store,null).then().statusCode(200).extract().asString();assertFalse(stored.contains("SECRET_"));assertFalse(stored.contains("RAW_SCAN"));assertTrue(stored.contains("NETWORK_UNCERTAIN"));support("GET","/diagnostics?storeId="+f.store,null).then().statusCode(403);}
 @Test void diagnosticValidationAndBodyLimitAndHealthAreReal(){support("POST","/diagnostics",Map.of("storeId",f.store,"requestKey",UUID.randomUUID().toString(),"osVersion","Bearer secret")).then().statusCode(400);support("POST","/diagnostics",Map.of("storeId",f.store,"requestKey",UUID.randomUUID().toString(),"extra","x".repeat(17000))).then().statusCode(413);assertEquals("UP",support("GET","/health",null).then().statusCode(200).extract().path("dependencies.database"));}
 @Test void targetedInstructionAndPreferencesArePrivateWithinSameStore(){String badge="P"+UUID.randomUUID().toString().substring(0,8);QuarkusTransaction.requiringNew().run(()->employee(Store.findById(f.store),badge,Employee.EmployeeRole.PICKER));String otherToken=login(badge);long id=manager("POST","/instructions",instruction()).then().statusCode(200).extract().jsonPath().getLong("id");given().auth().oauth2(otherToken).get("/handling-orders/operator/support/events?storeId="+f.store).then().statusCode(200).body("items.size()",org.hamcrest.Matchers.is(0));given().auth().oauth2(otherToken).post("/handling-orders/operator/support/events/"+id+"/ack?storeId="+f.store).then().statusCode(404);support("PUT","/session",Map.of("storeId",f.store,"textPercent",130)).then().statusCode(200);assertEquals(100,given().auth().oauth2(otherToken).get("/handling-orders/operator/support/session?storeId="+f.store).then().statusCode(200).extract().jsonPath().getInt("textPercent"));}
 @Test void concurrentInstructionCommitsHaveDistinctMonotoneCursors()throws Exception{var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);try{var a=pool.submit(()->{gate.await();return manager("POST","/instructions",instruction());});var b=pool.submit(()->{gate.await();return manager("POST","/instructions",instruction());});gate.countDown();a.get(30,java.util.concurrent.TimeUnit.SECONDS).then().statusCode(200);b.get(30,java.util.concurrent.TimeUnit.SECONDS).then().statusCode(200);var feed=support("GET","/events?storeId="+f.store,null).then().statusCode(200).extract().jsonPath();assertEquals(2,feed.getList("items").size());assertTrue(feed.getLong("items[1].cursor")>feed.getLong("items[0].cursor"));}finally{pool.shutdownNow();}}
 @Test void changingAnInstructionUnderTheSameKeyIsRefused(){var input=instruction();manager("POST","/instructions",input).then().statusCode(200);input.put("message","Autre intention");manager("POST","/instructions",input).then().statusCode(409);assertEquals(1,support("GET","/events?storeId="+f.store,null).then().statusCode(200).extract().jsonPath().getList("items").size());}

 @Test void measuredNetUsesSameBusinessPickingAndReplayDoesNotConsumeAgain(){
  attach(f.tote);
  QuarkusTransaction.requiringNew().run(()->{PickedItem item=PickedItem.findById(f.item);item.product.productType=ProductType.WEIGHT;item.product.referenceWeight=BigDecimal.ONE;});
  Long product=QuarkusTransaction.requiringNew().call(()->PickedItem.<PickedItem>findById(f.item).product.id);
  var measurement=Map.of("gross","400","tare","25","unit","G","origin","MANUAL");
  var preview=given().auth().oauth2(f.token).contentType("application/json").body(Map.of("storeId",f.store,"measurement",measurement)).post("/product-information/"+product+"/measure").then().statusCode(200).extract();
  assertEquals("0.375",preview.path("quantity"));
  var c=pick();c.put("quantity","0.375");c.put("measurement",measurement);c.put("scanId",preview.path("scanId"));
  String confirmed=command(c).then().statusCode(200).extract().asString();assertEquals(confirmed,command(c).then().statusCode(200).extract().asString());
  c.put("requestKey",UUID.randomUUID().toString());command(c).then().statusCode(409);
  QuarkusTransaction.requiringNew().run(()->{var receipt=com.intermarche.backoffice.domain.catalog.ProductScanReceipt.<com.intermarche.backoffice.domain.catalog.ProductScanReceipt>find("scanId",preview.<String>path("scanId")).firstResult();assertEquals(0,new BigDecimal("0.375").compareTo(receipt.consumedQuantity));});
 }
 @Test void failedTareDoesNotCreateAMeasurementReceiptOrMoveAnItem(){
  Long product=QuarkusTransaction.requiringNew().call(()->PickedItem.<PickedItem>findById(f.item).product.id);
  given().auth().oauth2(f.token).contentType("application/json").body(Map.of("storeId",f.store,"measurement",Map.of("gross","1","tare","2","unit","PCS","origin","MANUAL"))).post("/product-information/"+product+"/measure").then().statusCode(400);
  QuarkusTransaction.requiringNew().run(()->{assertEquals(0,com.intermarche.backoffice.domain.catalog.ProductScanReceipt.count("store.id",f.store));assertEquals(PickedItem.PickStatus.PENDING,PickedItem.<PickedItem>findById(f.item).status);});
 }

 @Test void manualAndGrossTareReceiptsCreateIdenticalStockLedgerQuantities(){
  var ids=QuarkusTransaction.requiringNew().call(()->{
   PickedItem item=PickedItem.findById(f.item);item.product.productType=ProductType.WEIGHT;
   var account=new com.intermarche.backoffice.domain.stock.StockAccount();account.store=Store.findById(f.store);account.product=item.product;account.persist();var rows=new ArrayList<Long>();
   for(int n=0;n<2;n++){
    var supply=new com.intermarche.backoffice.domain.stock.ExpectedSupply();supply.account=account;supply.reference=UUID.randomUUID().toString();supply.destination=item.lotLine.location;supply.quantity=new BigDecimal("2");supply.availableAt=com.intermarche.backoffice.domain.util.DateTimeProvider.now();supply.persist();
    var order=new ReplenishmentOrder();order.code="MSR-"+UUID.randomUUID().toString().substring(0,8);order.store=account.store;order.assignee=Employee.findById(f.employee);order.operation=ReplenishmentOrder.Operation.TRUCK_UNLOADING;order.status=BaseHandlingOrder.HandlingOrderStatus.PROCESSING;order.destinationBuilding=account.store.buildings.iterator().next();order.supplies.add(supply);order.persist();rows.add(order.id);rows.add(supply.id);
   }return rows;
  });
  for(int n=0;n<2;n++){
   var c=action("RECEIVE_SUPPLIES");c.remove("executionId");c.put("orderId",ids.get(n*2));c.put("receipt",Map.of("storeId",f.store,"eventKey",UUID.randomUUID().toString(),"lines",List.of(Map.of("supplyId",ids.get(n*2+1),"quantity","0.375"))));
   if(n==1)c.put("measurement",Map.of("gross","400","tare","25","unit","G","origin","SCALE","deviceId","BAL-TEST"));
   command(c).then().statusCode(200);command(c).then().statusCode(200);
  }
  QuarkusTransaction.requiringNew().run(()->{var movements=com.intermarche.backoffice.domain.stock.StockMovement.<com.intermarche.backoffice.domain.stock.StockMovement>list("account.store.id",f.store);assertEquals(2,movements.size());for(var movement:movements)assertEquals(0,new BigDecimal("0.375").compareTo(movement.quantity));});
 }

}
