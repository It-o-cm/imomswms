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
class OperatorContractTest {
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
 @Test void contextHasCapabilitiesVersionsAndNoPrivateFields(){var picker=get("/context").then().statusCode(200).extract().jsonPath();assertEquals(f.badge,picker.getString("identity.username"));assertTrue(picker.getList("capabilities").contains("MISSIONS_EXECUTE"));assertFalse(picker.getList("capabilities").contains("PLANNING_MANAGE"));assertNotNull(picker.getString("serverTime"));assertEquals(List.of(1,2),picker.getList("contractVersions"));String json=get("/context").asString();assertFalse(json.contains("password"));assertFalse(json.contains("test-pin"));assertFalse(json.contains("@example.test"));var manager=given().auth().oauth2(f.manager).get("/handling-orders/operator/context").then().statusCode(200).extract().jsonPath();assertTrue(manager.getList("capabilities").contains("PLANNING_MANAGE"));}
 @Test void foreignStoreAndGeneralManagerMutationsAreForbidden(){get("/context?storeId="+f.other).then().statusCode(403);get("/orders?storeId="+f.other).then().statusCode(403);var c=action("ADD_TOTE");c.put("storeId",f.other);command(c).then().statusCode(403);given().auth().oauth2(f.token).contentType("application/json").body(Map.of("storeId",f.store,"buildingCode","HACK")).post("/store-operations/buildings").then().statusCode(403);}
 /** Checks that the token of an account disabled after login is refused at once (session ended). */
 @Test void inactiveAccountCannotUseExistingJwt(){get("/context").then().statusCode(200);QuarkusTransaction.requiringNew().run(()->{Employee e=Employee.findById(f.employee);e.active=false;});get("/context").then().statusCode(401);command(action("ADD_TOTE")).then().statusCode(401);}
 @Test void v2ReturnsFactsAndRevisionAndLegacyStillWorks(){var c=v2("ADD_TOTE");c.put("toteCode",f.tote);c.put("usage","AMBIENT");var r=command(c).then().statusCode(200).extract().jsonPath();assertEquals("CONFIRMED",r.getString("outcome"));assertEquals("ADD_TOTE",r.getString("facts.confirmedAction"));assertEquals(0,r.getList("warnings").size());assertNotEquals(c.get("expectedRevision"),r.getString("orders[0].revision"));attach(f.secondTote);}
 @Test void staleRevisionHasStableConflictAndTracking(){var stale=v2("ADD_TOTE");stale.put("toteCode",f.secondTote);stale.put("usage","AMBIENT");attach(f.tote);var r=command(stale).then().statusCode(409).extract();assertEquals("STALE_REVISION",r.path("code"));assertNotNull(r.path("trackingId"));assertNotNull(r.header("X-Tracking-Id"));}
 @Test void malformedAndUnauthenticatedRequestsHaveSafeErrors(){var r=given().auth().oauth2(f.token).contentType("application/json").body("{bad").post("/handling-orders/operator/commands").then().statusCode(400).extract();assertEquals("INVALID_COMMAND",r.path("code"));assertFalse(r.asString().contains("JsonParseException"));given().get("/handling-orders/operator/context").then().statusCode(401);var missing=v2("ADD_TOTE");missing.remove("expectedRevision");assertEquals("REVISION_REQUIRED",command(missing).then().statusCode(400).extract().path("code"));}
 @Test void lostResponseReplaysExactlyOneToteClaim(){var c=v2("ADD_TOTE");c.put("toteCode",f.tote);c.put("usage","AMBIENT");String first=command(c).then().statusCode(200).extract().asString();assertEquals(first,command(c).then().statusCode(200).extract().asString());var receipt=get("/commands/"+c.get("requestKey")+"?storeId="+f.store).then().statusCode(200).extract();assertEquals(true,receipt.path("found"));assertEquals(c.get("requestKey"),receipt.path("result.requestKey"));long count=QuarkusTransaction.requiringNew().call(()->OperatorCommandReceipt.count("requestKey",c.get("requestKey")));assertEquals(1,count);}
 @Test void sameKeyDifferentContentIsRefused(){var c=action("ADD_TOTE");c.put("toteCode",f.tote);c.put("usage","AMBIENT");command(c).then().statusCode(200);c.put("toteCode",f.secondTote);assertEquals("IDEMPOTENCY_KEY_REUSED",command(c).then().statusCode(409).extract().path("code"));}
 @Test void concurrentIdenticalPickHasOneEffectAndOneReceipt() throws Exception {attach(f.tote);var c=pick();var results=concurrent(c,c);results.forEach(r->assertEquals(200,r.statusCode(),r.asString()));assertEquals(results.get(0).asString(),results.get(1).asString());assertPickedTwo();}
 @Test void concurrentDifferentKeysCannotDoublePick() throws Exception {attach(f.tote);var results=concurrent(pick(),pick());assertEquals(1,results.stream().filter(r->r.statusCode()==200).count());assertPickedTwo();}
 void assertPickedTwo(){BigDecimal quantity=QuarkusTransaction.requiringNew().call(()->PickedItem.<PickedItem>list("lotLine.id=(select i.lotLine.id from PickedItem i where i.id=?1) and status=?2",f.item,PickedItem.PickStatus.PICKED).stream().map(i->i.quantity).reduce(BigDecimal.ZERO,BigDecimal::add));assertEquals(0,new BigDecimal("2").compareTo(quantity));}
 @Test void concurrentDepositsCannotOccupySameCell() throws Exception {attach(f.tote);attach(f.secondTote);var a=action("DEPOSIT_TOTE");a.putAll(Map.of("toteCode",f.tote,"bufferId",f.buffer,"cabinetId",f.cabinet,"cellRow",1,"cellColumn",1));var b=new LinkedHashMap<>(a);b.put("requestKey",UUID.randomUUID().toString());b.put("toteCode",f.secondTote);var results=concurrent(a,b);assertEquals(1,results.stream().filter(r->r.statusCode()==200).count(),results.toString());long occupants=QuarkusTransaction.requiringNew().call(()->LoadedTote.count("cabinet.id=?1 and cellRow=1 and cellColumn=1",f.cabinet));assertEquals(1,occupants);}
 @Test void canonicalPayloadIgnoresMapOrderAndDecimalScale(){String a="{\"quantity\":1.0,\"item\":2}";String b="{\"item\":2,\"quantity\":1}";assertEquals(com.intermarche.backoffice.engine.operator.CommandCanonical.hash(a),com.intermarche.backoffice.engine.operator.CommandCanonical.hash(b));}
 @Test void foreignOrderIsNotReadableEvenWithOwnStoreParameter(){Long foreign=QuarkusTransaction.requiringNew().call(()->{ProductCollectingOrder o=new ProductCollectingOrder();o.code="FOR-"+UUID.randomUUID().toString().substring(0,8);o.store=Store.findById(f.other);o.status=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;o.persist();return o.id;});get("/orders/"+foreign+"?storeId="+f.store).then().statusCode(404);var c=action("RESERVE");c.put("orderId",foreign);command(c).then().statusCode(404);}
 @Test void competingClaimsOfOnePhysicalToteHaveOneWinner() throws Exception {
  Long second=QuarkusTransaction.requiringNew().call(()->{ProductCollectingOrder o=new ProductCollectingOrder();o.code="SEC-"+UUID.randomUUID().toString().substring(0,8);o.store=Store.findById(f.store);o.assignee=Employee.findById(f.employee);o.status=BaseHandlingOrder.HandlingOrderStatus.PROCESSING;o.persist();PickingExecution e=new PickingExecution();e.handlingOrder=o;e.employee=o.assignee;e.persist();return e.id;});
  var a=action("ADD_TOTE");a.put("toteCode",f.tote);a.put("usage","AMBIENT");var b=new LinkedHashMap<>(a);b.put("requestKey",UUID.randomUUID().toString());b.put("executionId",second);
  var results=concurrent(a,b);assertEquals(1,results.stream().filter(r->r.statusCode()==200).count());assertEquals(1,results.stream().filter(r->r.statusCode()==409).count());
 }
 @Test void failedPostMutationCapacityCheckRollsBackAndSameKeyCanRetry(){attach(f.tote);QuarkusTransaction.requiringNew().run(()->{Tote t=Tote.findByCode(f.tote);t.volume=BigDecimal.ONE;});var c=pick();command(c).then().statusCode(400);assertEquals(false,get("/commands/"+c.get("requestKey")+"?storeId="+f.store).then().statusCode(200).extract().path("found"));QuarkusTransaction.requiringNew().run(()->{PickedItem item=PickedItem.findById(f.item);assertEquals(PickedItem.PickStatus.PENDING,item.status);assertNull(item.loadedTote);Tote t=Tote.findByCode(f.tote);t.volume=new BigDecimal("60");});command(c).then().statusCode(200);assertPickedTwo();}
 @Test void successfulConfirmationCanCarryWarningWithoutBecomingFailure(){attach(f.tote);QuarkusTransaction.requiringNew().run(()->{PickedItem item=PickedItem.findById(f.item);item.product.referenceVolume=null;});var c=v2("PICK");c.putAll(Map.of("itemId",f.item,"quantity",2,"toteCode",f.tote));var r=command(c).then().statusCode(200).extract();assertEquals("CONFIRMED",r.path("outcome"));assertEquals("VOLUME_UNKNOWN",r.path("warnings[0].code"));assertEquals(2,r.<Integer>path("facts.quantity"));}

 @Test void receiptsWrittenBeforeNewOptionalFieldsRemainReplayable(){var c=action("ADD_TOTE");c.put("toteCode",f.tote);c.put("usage","AMBIENT");String first=command(c).then().statusCode(200).extract().asString();QuarkusTransaction.requiringNew().run(()->{OperatorCommandReceipt receipt=OperatorCommandReceipt.find("requestKey",c.get("requestKey")).firstResult();try{var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var old=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(receipt.payload);old.remove(List.of("contractVersion","expectedRevision","expectedRevisions"));receipt.payload=old.toString();receipt.fingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(receipt.payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new RuntimeException(e);}});assertEquals(first,command(c).then().statusCode(200).extract().asString());}

 @Test void depositedToteCanBeReplayedWithItsOriginalRevision(){attach(f.tote);var c=v2("DEPOSIT_TOTE");c.putAll(Map.of("toteCode",f.tote,"bufferId",f.buffer,"cabinetId",f.cabinet,"cellRow",1,"cellColumn",1));var first=command(c).then().statusCode(200).extract();assertEquals(first.path("trackingId"),first.header("X-Tracking-Id"));assertEquals(first.asString(),command(c).then().statusCode(200).extract().asString());}
 @Test void orderProjectionOffersContextualActions(){var order=get("/orders/"+f.order+"?storeId="+f.store).then().statusCode(200).extract();assertTrue(order.jsonPath().getList("allowedActions").contains("PICK"));assertFalse(order.jsonPath().getList("allowedActions").contains("PLANNING_MANAGE"));}

}
