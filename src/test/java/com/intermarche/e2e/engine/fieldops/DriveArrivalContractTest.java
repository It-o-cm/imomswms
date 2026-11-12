package com.intermarche.e2e.engine.fieldops;
import com.intermarche.backoffice.domain.auth.Administrative;
import com.intermarche.backoffice.domain.commerce.CommerceStoreGrant;
import com.intermarche.backoffice.domain.commerce.DriveArrival;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.operator.OperatorNotice;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Drive arrival contract v1 (scenarios D01-D11, D13): commerce reads and announces, the store team serves. */
@QuarkusTest @jakarta.enterprise.inject.Typed(DriveArrivalContractTest.class)
class DriveArrivalContractTest extends DepartureWorkflowTest {
 /**
  * The order under test, as commerce designates it.
  *
  * @param store the store code
  * @param reference the order reference
  * @param service a granted commerce token
  * @param stranger a commerce token without grant
  */
 record Drive(String store,String reference,String service,String stranger){}
 /**
  * Creates a technical commerce account and logs it in.
  *
  * @param grant whether it is granted the store under test
  * @return its token
  */
 String service(boolean grant){String name="svc-"+key().substring(0,8);QuarkusTransaction.requiringNew().run(()->{var a=new Administrative();a.username=name;a.password=BCrypt.hashpw("test",BCrypt.gensalt(4));a.fullName="Commerce "+name;a.email=name+"@example.test";a.roles=new HashSet<>(Set.of("SERVICE_CALLER"));a.persist();
  if(grant){var g=new CommerceStoreGrant();g.store=Store.findById(f.store());g.principal=name;g.sourceSystem="IMMCOMM";g.grantedBy="test";g.persist();}});return login(name);}
 /**
  * Gives the order a pickup fulfilment and a slot around now.
  *
  * @param type the fulfilment type
  * @param startMinutes start of the slot from now
  * @param endMinutes end of the slot from now
  */
 void fulfilment(String type,int startMinutes,int endMinutes){QuarkusTransaction.requiringNew().run(()->{Order o=Order.findById(customer);var now=DateTimeProvider.now().atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC);
  o.specification="{\"fulfillment\":{\"type\":\""+type+"\",\"slot\":{\"start\":\""+now.plusMinutes(startMinutes).toOffsetDateTime()+"\",\"end\":\""+now.plusMinutes(endMinutes).toOffsetDateTime()+"\"}}}";});}
 /**
  * Marks the fulfilment of the order as routed, as the order flow does once its chunks are planned.
  */
 void routed(){QuarkusTransaction.requiringNew().run(()->em.createQuery("select f from Fulfillment f where f.order.id=?1",com.intermarche.backoffice.domain.oms.Fulfillment.class).setParameter(1,customer).getResultList().forEach(x->x.chunks.forEach(c->c.type=com.intermarche.backoffice.domain.oms.FulfillmentChunk.READY)));}
 /**
  * Closes the picking mission of the order, as the picker does once every product is in its tote.
  */
 void missionDone(){QuarkusTransaction.requiringNew().run(()->com.intermarche.backoffice.domain.wms.BaseHandlingOrder.<com.intermarche.backoffice.domain.wms.BaseHandlingOrder>findById(f.order()).status=com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus.DONE);}
 /**
  * Prepares an order ready for pickup: routed, picked into a tote deposited in a buffer.
  *
  * @return the order as commerce designates it
  */
 Drive ready(){prepared();routed();missionDone();buffer();fulfilment("DRIVE",-15,45);String store=QuarkusTransaction.requiringNew().call(()->Store.<Store>findById(f.store()).code);String reference=QuarkusTransaction.requiringNew().call(()->Order.<Order>findById(customer).orderId);return new Drive(store,reference,service(true),service(false));}
 /**
  * Reads the pickup state.
  *
  * @param d the order
  * @param token the caller
  * @return the response
  */
 Response pickup(Drive d,String token){return given().auth().oauth2(token).get("/commerce/v1/stores/"+d.store()+"/orders/"+d.reference()+"/pickup");}
 /**
  * Announces an arrival.
  *
  * @param d the order
  * @param token the caller
  * @param body the announcement
  * @return the response
  */
 Response arrive(Drive d,String token,Map<String,Object> body){return given().auth().oauth2(token).contentType("application/json").body(body).post("/commerce/v1/stores/"+d.store()+"/orders/"+d.reference()+"/arrivals");}
 /**
  * Builds an announcement.
  *
  * @param requestId the request id
  * @param revision the revision last read
  * @param kind the parking kind
  * @param label the parking label, or null
  * @return the body
  */
 static Map<String,Object> body(String requestId,long revision,String kind,String label){var p=new HashMap<String,Object>();p.put("kind",kind);if(label!=null)p.put("label",label);return new HashMap<>(Map.of("contractVersion","1","sourceSystem","IMMCOMM","requestId",requestId,"expectedRevision",revision,"parking",p));}
 /**
  * Counts the operator notices of an arrival.
  *
  * @param arrivalId the arrival id
  * @return the number of notices
  */
 long notices(String arrivalId){return QuarkusTransaction.requiringNew().call(()->OperatorNotice.count("eventKey",(Object)("drive-arrival:"+arrivalId)));}
 /**
  * Reads the desk queue.
  *
  * @return the queue
  */
 JsonPath desk(){return given().auth().oauth2(f.picker()).get("/drive-arrivals?storeId="+f.store()).then().statusCode(200).extract().jsonPath();}
 /**
  * Posts a desk command.
  *
  * @param token the caller
  * @param path the action
  * @param body the command
  * @return the response
  */
 Response act(String token,String path,Map<String,Object> body){var b=new HashMap<>(body);b.put("storeId",f.store());b.put("requestKey",key());return given().auth().oauth2(token).contentType("application/json").body(b).post("/drive-arrivals/"+path);}

 /**
  * D01-D07, D10: one durable arrival per ready order, identical replays, conflicts and refusals without side effects.
  */
 @Test void readyOrderReceivesOneDurableArrivalReplaysAndConflictsWithoutSideEffects(){var d=ready();
  var first=pickup(d,d.service()).then().statusCode(200).header("Cache-Control","no-store").body("orderState",is("READY")).body("pickupState",is("NONE")).body("arrival",nullValue()).body("contractVersion",is("1")).extract().jsonPath();long revision=first.getLong("revision");
  assertEquals(revision,pickup(d,d.service()).then().extract().jsonPath().getLong("revision"));
  String request=UUID.randomUUID().toString();
  for(String bad:new String[]{"","B12!","ABCDEFGHIJKLM"})arrive(d,d.service(),body(request,revision,"SPACE",bad)).then().statusCode(400).body("reason",is("INVALID_PARKING")).body("correlationId",notNullValue());
  arrive(d,d.service(),body(request,revision,"SPACE",null)).then().statusCode(400).body("reason",is("INVALID_PARKING"));
  arrive(d,d.service(),body(request,revision,"PEDESTRIAN","B1")).then().statusCode(400).body("reason",is("INVALID_PARKING"));
  arrive(d,d.service(),body(request,revision,"CAR","B1")).then().statusCode(400).body("reason",is("INVALID_PARKING"));
  arrive(d,d.service(),body("not-a-uuid",revision,"SPACE","B12")).then().statusCode(400).body("reason",is("INVALID_REQUEST"));
  var wrongSource=body(request,revision,"SPACE","B12");wrongSource.put("sourceSystem","OTHER");arrive(d,d.service(),wrongSource).then().statusCode(400).body("reason",is("INVALID_REQUEST"));
  var wrongVersion=body(request,revision,"SPACE","B12");wrongVersion.put("contractVersion","2");arrive(d,d.service(),wrongVersion).then().statusCode(400);
  var noRevision=body(request,revision,"SPACE","B12");noRevision.remove("expectedRevision");arrive(d,d.service(),noRevision).then().statusCode(400);
  arrive(d,d.service(),body(request,revision+5,"SPACE","B12")).then().statusCode(409).body("reason",is("REVISION_CONFLICT"));
  var created=arrive(d,d.service(),body(request,revision,"SPACE"," b12 ")).then().statusCode(201).header("Location",containsString("/commerce/v1/stores/"+d.store()+"/orders/"+d.reference()+"/pickup"))
   .body("pickupState",is("RECEIVED")).body("arrival.parking.label",is("B12")).body("arrival.requestId",is(request)).body("arrival.acknowledgedAt",nullValue()).body("arrival.receivedAt",endsWith("Z")).body("revision",greaterThan((int)revision)).extract().jsonPath();
  String arrivalId=created.getString("arrival.arrivalId");String receivedAt=created.getString("arrival.receivedAt");assertEquals(1,notices(arrivalId));
  arrive(d,d.service(),body(request,revision,"SPACE","b12")).then().statusCode(200).body("arrival.arrivalId",is(arrivalId)).body("arrival.receivedAt",is(receivedAt));
  given().auth().oauth2(d.service()).get("/commerce/v1/arrival-requests/"+request).then().statusCode(200).body("arrival.arrivalId",is(arrivalId)).body("orderReference",is(d.reference()));
  assertEquals(1,notices(arrivalId));assertEquals(1L,QuarkusTransaction.requiringNew().call(()->DriveArrival.count("order.id",customer)));
  arrive(d,d.service(),body(request,revision,"SPACE","C7")).then().statusCode(409).body("reason",is("IDEMPOTENCY_CONFLICT"));
  arrive(d,d.service(),body(request,revision,"PEDESTRIAN",null)).then().statusCode(409).body("reason",is("IDEMPOTENCY_CONFLICT"));
  given().auth().oauth2(d.service()).contentType("application/json").body(body(request,revision,"SPACE","B12")).post("/commerce/v1/stores/"+d.store()+"/orders/INCONNUE/arrivals").then().statusCode(409).body("reason",is("IDEMPOTENCY_CONFLICT"));
  long current=pickup(d,d.service()).then().extract().jsonPath().getLong("revision");
  arrive(d,d.service(),body(UUID.randomUUID().toString(),current,"SPACE","B12")).then().statusCode(409).body("reason",is("ARRIVAL_ALREADY_EXISTS"));
  assertEquals(1L,QuarkusTransaction.requiringNew().call(()->DriveArrival.count("order.id",customer)));
  pickup(d,d.stranger()).then().statusCode(403).body("reason",is("STORE_FORBIDDEN"));
  arrive(d,d.stranger(),body(UUID.randomUUID().toString(),current,"SPACE","B12")).then().statusCode(403).body("reason",is("STORE_FORBIDDEN"));
  given().auth().oauth2(d.stranger()).get("/commerce/v1/arrival-requests/"+request).then().statusCode(404).body("reason",is("REQUEST_NOT_FOUND"));
  given().auth().oauth2(d.service()).get("/commerce/v1/arrival-requests/"+UUID.randomUUID()).then().statusCode(404).body("reason",is("REQUEST_NOT_FOUND"));
  given().auth().oauth2(d.service()).get("/commerce/v1/stores/INCONNU/orders/"+d.reference()+"/pickup").then().statusCode(403).body("reason",is("STORE_FORBIDDEN"));
  given().auth().oauth2(d.service()).get("/commerce/v1/stores/"+d.store()+"/orders/INCONNUE/pickup").then().statusCode(404).body("reason",is("ORDER_NOT_FOUND"));
  pickup(d,f.picker()).then().statusCode(403);given().get("/commerce/v1/stores/"+d.store()+"/orders/"+d.reference()+"/pickup").then().statusCode(401);}

 /**
  * D09, D11: the team takes the arrival in charge, the handover closes it, and no new arrival is accepted.
  */
 @Test void storeTeamTakesChargeThenTheHandoverClosesTheArrivalAndForbidsAnyNewOne(){var d=ready();long revision=pickup(d,d.service()).then().extract().jsonPath().getLong("revision");String request=UUID.randomUUID().toString();
  arrive(d,d.service(),body(request,revision,"PEDESTRIAN",null)).then().statusCode(201).body("arrival.parking.label",nullValue());
  var queued=desk();var item=queued.getMap("items.find{it.orderId=="+customer+"}");assertEquals("RECEIVED",item.get("state"));assertEquals(false,item.get("orderHandedOver"));
  act(f.picker(),"acknowledge",Map.of("arrivalId",item.get("id"),"expectedRevision",0)).then().statusCode(409);
  act(f.picker(),"complete",Map.of("arrivalId",item.get("id"),"expectedRevision",item.get("revision"))).then().statusCode(409).body("message",containsString("remise"));
  var acked=act(f.picker(),"acknowledge",Map.of("arrivalId",item.get("id"),"expectedRevision",item.get("revision"))).then().statusCode(200).body("state",is("ACKNOWLEDGED")).extract().jsonPath();
  act(f.picker(),"acknowledge",Map.of("arrivalId",item.get("id"),"expectedRevision",acked.getLong("revision"))).then().statusCode(409);
  long before=revision;var seen=pickup(d,d.service()).then().statusCode(200).body("pickupState",is("ACKNOWLEDGED")).body("arrival.acknowledgedAt",notNullValue()).extract().jsonPath();assertTrue(seen.getLong("revision")>before+1);
  long tote=QuarkusTransaction.requiringNew().call(()->PickedItem.<PickedItem>findById(f.item()).loadedTote.id);
  var dep=departure(f.picker(),"",Map.of("storeId",f.store(),"orderId",customer,"mode","CUSTOMER","recipient","Client Drive","transportReference","DRIVE","units",List.of(Map.of("toteId",tote)),"requestKey",key())).then().statusCode(200).extract().jsonPath().<String,Object>getMap("$");
  dep=load(dep,0);var hand=inputDeparture(dep);hand.put("recipient","Client Drive");hand.put("unitIds",List.of(((List<Map<String,Object>>)dep.get("units")).getFirst().get("id")));departure(f.picker(),"/handover",hand).then().statusCode(200);
  var done=pickup(d,d.service()).then().statusCode(200).body("orderState",is("COMPLETED")).body("pickupState",is("COMPLETED")).body("arrival.completedAt",notNullValue()).extract().jsonPath();assertTrue(done.getLong("revision")>seen.getLong("revision"));
  arrive(d,d.service(),body(request,revision,"PEDESTRIAN",null)).then().statusCode(200).body("pickupState",is("COMPLETED")).body("orderState",is("COMPLETED"));
  arrive(d,d.service(),body(UUID.randomUUID().toString(),done.getLong("revision"),"SPACE","A1")).then().statusCode(409).body("reason",is("PICKUP_COMPLETED"));
  assertEquals(0,desk().getList("items.findAll{it.orderId=="+customer+"}").size());
  given().auth().oauth2(f.picker()).get("/drive-arrivals?storeId="+f.store()+"&closed=true").then().statusCode(200).body("items.find{it.orderId=="+customer+"}.state",is("COMPLETED"));}

 /**
  * D08: a cancelled arrival is never reopened by a replay, and a cancelled order refuses arrivals.
  */
 @Test void cancelledArrivalStaysCancelledAndACancelledOrderRefusesArrivals(){var d=ready();long revision=pickup(d,d.service()).then().extract().jsonPath().getLong("revision");String request=UUID.randomUUID().toString();
  arrive(d,d.service(),body(request,revision,"SPACE","B12")).then().statusCode(201);
  var item=desk().getMap("items.find{it.orderId=="+customer+"}");
  act(f.picker(),"cancel",Map.of("arrivalId",item.get("id"),"expectedRevision",item.get("revision"))).then().statusCode(400);
  act(f.picker(),"cancel",Map.of("arrivalId",item.get("id"),"expectedRevision",item.get("revision"),"reason","Client reparti")).then().statusCode(200).body("state",is("CANCELLED"));
  arrive(d,d.service(),body(request,revision,"SPACE","B12")).then().statusCode(200).body("pickupState",is("CANCELLED"));
  long current=pickup(d,d.service()).then().body("pickupState",is("CANCELLED")).extract().jsonPath().getLong("revision");
  arrive(d,d.service(),body(UUID.randomUUID().toString(),current,"SPACE","B14")).then().statusCode(201).body("pickupState",is("RECEIVED"));
  long customerId=customer;given().auth().oauth2(f.manager()).contentType("application/json").body(Map.of("storeId",f.store(),"requestKey",key(),"reason","Commande annulée par le client","policy","RETURN")).post("/field-operations/customer-orders/"+customerId+"/cancel").then().statusCode(200);
  var after=pickup(d,d.service()).then().statusCode(200).body("orderState",is("CANCELLED")).body("pickupState",is("CANCELLED")).extract().jsonPath();
  arrive(d,d.service(),body(UUID.randomUUID().toString(),after.getLong("revision"),"SPACE","B15")).then().statusCode(409).body("reason",is("ORDER_CANCELLED"));}

 /**
  * D13: an unsupported fulfilment or a closed window is refused with its reason; the store sets its window.
  */
 @Test void unsupportedFulfilmentClosedWindowAndUnpreparedOrderAreExplained(){var d=ready();long revision=pickup(d,d.service()).then().extract().jsonPath().getLong("revision");
  fulfilment("HOME_DELIVERY",-15,45);arrive(d,d.service(),body(UUID.randomUUID().toString(),revision,"SPACE","B12")).then().statusCode(409).body("reason",is("UNSUPPORTED_FULFILLMENT"));
  fulfilment("CLICK_AND_COLLECT",180,240);arrive(d,d.service(),body(UUID.randomUUID().toString(),revision,"SPACE","B12")).then().statusCode(409).body("reason",is("ARRIVAL_WINDOW_CLOSED"));
  fulfilment("DRIVE",-240,-120);arrive(d,d.service(),body(UUID.randomUUID().toString(),revision,"SPACE","B12")).then().statusCode(409).body("reason",is("ARRIVAL_WINDOW_CLOSED"));
  act(f.picker(),"policy",Map.of("minutesBefore",30,"minutesAfter",180)).then().statusCode(403);
  act(f.manager(),"policy",Map.of("minutesBefore",30,"minutesAfter",721)).then().statusCode(400);
  act(f.manager(),"policy",Map.of("minutesBefore",30,"minutesAfter",180)).then().statusCode(200).body("minutesAfter",is(180));
  arrive(d,d.service(),body(UUID.randomUUID().toString(),revision,"SPACE","B12")).then().statusCode(201).body("slot.start",endsWith("Z"));
  act(f.manager(),"policy",Map.of("minutesBefore",30,"minutesAfter",60)).then().statusCode(409);
  act(f.manager(),"policy",Map.of("minutesBefore",30,"minutesAfter",60,"expectedRevision",1)).then().statusCode(200);
  act(f.manager(),"grants",Map.of("principal","svc-x")).then().statusCode(403);
  given().auth().oauth2(f.manager()).get("/drive-arrivals/grants?storeId="+f.store()).then().statusCode(200).body("principal",hasItem(startsWith("svc-")));
  }

 /**
  * D13: an order still in preparation is not ready, and becomes ready once its tote is deposited.
  */
 @Test void orderStillInPreparationIsNotReady(){prepared();routed();missionDone();fulfilment("DRIVE",-15,45);String store=QuarkusTransaction.requiringNew().call(()->Store.<Store>findById(f.store()).code);String reference=QuarkusTransaction.requiringNew().call(()->Order.<Order>findById(customer).orderId);var d=new Drive(store,reference,service(true),service(false));
  long revision=pickup(d,d.service()).then().statusCode(200).body("orderState",anyOf(is("PREPARING"),is("RECEIVED"))).body("pickupState",is("NONE")).extract().jsonPath().getLong("revision");
  arrive(d,d.service(),body(UUID.randomUUID().toString(),revision,"SPACE","B12")).then().statusCode(409).body("reason",is("ORDER_NOT_READY"));
  buffer();long ready=pickup(d,d.service()).then().statusCode(200).body("orderState",is("READY")).extract().jsonPath().getLong("revision");assertEquals(revision+1,ready);}
}
