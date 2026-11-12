package com.intermarche.e2e.engine.fieldops;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.order.Order;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
@QuarkusTest @jakarta.enterprise.inject.Typed(SupportFollowupWorkflowTest.class)
class SupportFollowupWorkflowTest extends ParcelWorkflowTest {
 @Test void detachesToABufferAtomicallyAndFiltersTheActualLocations(){
  prepared();long type=call(f.manager(),"PUT","/support-types",Map.of("storeId",f.store(),"code","FOLLOW_CART","label","Chariot test","kind","CART","allowedZones","AMBIENT","maxVolumeLiters",240)).then().statusCode(200).extract().jsonPath().getLong("id");
  long cart=call(f.manager(),"PUT","/supports",Map.of("storeId",f.store(),"typeId",type,"code","FOLLOW-CART")).then().statusCode(200).extract().jsonPath().getLong("id");
  var read=call(f.manager(),"GET","/supports/"+cart+"/layout?storeId="+f.store(),null).then().statusCode(200).extract().jsonPath();var layout=call(f.manager(),"PUT","/supports/layout",Map.of("storeId",f.store(),"cartId",cart,"rows",1,"columns",2,"expectedRevision",read.getString("revision"))).then().statusCode(200).extract().jsonPath();long slot=layout.getLong("compartments[0].id");
  long child=QuarkusTransaction.requiringNew().call(()->{supportCatalog.registerAll(Store.findById(f.store()));return PhysicalSupport.<PhysicalSupport>find("tote.code",f.tote()).firstResult().id;});
  var assignment=call(f.manager(),"POST","/supports/compartments",Map.of("storeId",f.store(),"compartmentId",slot,"supportId",child,"orderId",f.order(),"expectedRevision",layout.getString("compartments[0].revision"),"requestKey",key())).then().statusCode(200).extract().jsonPath();
  String base="/supports?storeId="+f.store()+"&kind=REUSABLE&limit=100";
  assertTrue(call(f.manager(),"GET",base+"&availability=BUSY",null).then().statusCode(200).extract().jsonPath().getList("items.id",Long.class).contains(child));
  assertFalse(call(f.manager(),"GET",base+"&availability=FREE",null).then().statusCode(200).extract().jsonPath().getList("items.id",Long.class).contains(child));
  assertEquals(List.of(f.tote()),call(f.manager(),"GET",base+"&locationKind=CART",null).then().statusCode(200).extract().jsonPath().getList("items.code",String.class));
  assertEquals(List.of("FOLLOW-CART"),call(f.manager(),"GET",base+"&typeId="+type,null).then().statusCode(200).extract().jsonPath().getList("items.code",String.class));
  long buffer=QuarkusTransaction.requiringNew().call(()->{var b=new Buffer();b.building=Building.findById(f.building());b.bufferCode="FOLLOW-BUF";b.active=true;b.ambientCapacity=10;b.persistAndFlush();return b.id;});
  var removal=new LinkedHashMap<String,Object>();removal.put("storeId",f.store());removal.put("compartmentId",slot);removal.put("supportId",null);removal.put("expectedRevision",assignment.getString("revision"));removal.put("requestKey",key());removal.put("bufferId",-1L);call(f.manager(),"POST","/supports/compartments",removal).then().statusCode(400);
  QuarkusTransaction.requiringNew().run(()->assertNotNull(CartCompartment.<CartCompartment>findById(slot).occupant));
  removal.put("bufferId",buffer);removal.put("requestKey",key());call(f.manager(),"POST","/supports/compartments",removal).then().statusCode(200);call(f.manager(),"POST","/supports/compartments",removal).then().statusCode(200);
  QuarkusTransaction.requiringNew().run(()->{assertNull(CartCompartment.<CartCompartment>findById(slot).occupant);var load=LoadedTote.<LoadedTote>find("tote.code=?1 and status=?2",f.tote(),LoadedTote.Status.IN_USE).firstResult();assertEquals(buffer,load.buffer.id);assertFalse(load.items.isEmpty());});
  assertEquals(List.of(f.tote()),call(f.manager(),"GET",base+"&locationKind=BUFFER&locationId="+buffer,null).then().statusCode(200).extract().jsonPath().getList("items.code",String.class));
  assertEquals(List.of(f.tote()),call(f.manager(),"GET",base+"&locationKind=BUILDING&locationId="+f.building(),null).then().statusCode(200).extract().jsonPath().getList("items.code",String.class));
  assertTrue(call(f.manager(),"GET",base+"&availability=FREE&typeId="+type,null).then().statusCode(200).extract().jsonPath().getList("items.code",String.class).contains("FOLLOW-CART"));
 }
 @Test void placingAToteAlsoAttachesItToTheCollectionOnce(){
  long type=call(f.manager(),"PUT","/support-types",Map.of("storeId",f.store(),"code","AUTO_CART","label","Chariot test","kind","CART","allowedZones","AMBIENT","maxVolumeLiters",240)).then().statusCode(200).extract().jsonPath().getLong("id");
  long cart=call(f.manager(),"PUT","/supports",Map.of("storeId",f.store(),"typeId",type,"code","AUTO-CART")).then().statusCode(200).extract().jsonPath().getLong("id");
  var read=call(f.manager(),"GET","/supports/"+cart+"/layout?storeId="+f.store(),null).then().statusCode(200).extract().jsonPath();
  var layout=call(f.manager(),"PUT","/supports/layout",Map.of("storeId",f.store(),"cartId",cart,"rows",1,"columns",2,"expectedRevision",read.getString("revision"))).then().statusCode(200).extract().jsonPath();
  long child=QuarkusTransaction.requiringNew().call(()->{supportCatalog.registerAll(Store.findById(f.store()));return PhysicalSupport.<PhysicalSupport>find("tote.code",f.tote()).firstResult().id;});
  String revision=given().auth().oauth2(f.picker()).get("/handling-orders/operator/orders/"+f.order()+"?storeId="+f.store()).then().statusCode(200).extract().jsonPath().getString("revision");
  var request=new LinkedHashMap<String,Object>();request.put("storeId",f.store());request.put("compartmentId",layout.getLong("compartments[0].id"));request.put("supportId",child);request.put("orderId",f.order());request.put("expectedRevision",layout.getString("compartments[0].revision"));request.put("requestKey",key());request.put("executionId",f.execution());request.put("usage","AMBIENT");request.put("expectedOrderRevision",revision);
  var invalid=new LinkedHashMap<>(request);invalid.remove("usage");invalid.put("requestKey",key());call(f.picker(),"POST","/supports/compartments",invalid).then().statusCode(400);
  QuarkusTransaction.requiringNew().run(()->{assertNull(CartCompartment.<CartCompartment>findById(layout.getLong("compartments[0].id")).occupant);assertEquals(0,LoadedTote.count("tote.code",f.tote()));});
  call(f.picker(),"POST","/supports/compartments",request).then().statusCode(200);
  call(f.picker(),"POST","/supports/compartments",request).then().statusCode(200);
  QuarkusTransaction.requiringNew().run(()->{var execution=com.intermarche.backoffice.client.PickingExecution.<com.intermarche.backoffice.client.PickingExecution>findById(f.execution());assertEquals(1,execution.loadedTotes.stream().filter(t->t.tote.code.equals(f.tote())).count());assertEquals(child,CartCompartment.<CartCompartment>findById(layout.getLong("compartments[0].id")).occupant.id);});
 }
 @Test void candidatesSearchClientsAndExcludeFullyAllocatedGoods(){
  var p=prepared();var customerName=QuarkusTransaction.requiringNew().call(()->{var o=Order.<Order>findById(customer);return o.customer.firstName;});
  var data=given().auth().oauth2(f.picker()).queryParam("storeId",f.store()).queryParam("q",customerName).get("/parcels/candidates").then().statusCode(200).extract().jsonPath();assertTrue(data.getList("items.id",Long.class).contains(customer));
  save(p,2);var after=given().auth().oauth2(f.picker()).queryParam("storeId",f.store()).get("/parcels/candidates").then().statusCode(200).extract().jsonPath();assertFalse(after.getList("items.id",Long.class).contains(customer));
 }
 @Test void qualityAllAcceptsAnOmittedOrBlankState(){for(String suffix:List.of("","&state="))given().auth().oauth2(f.manager()).get("/quality/cases?storeId="+f.store()+suffix).then().statusCode(200);}
}
