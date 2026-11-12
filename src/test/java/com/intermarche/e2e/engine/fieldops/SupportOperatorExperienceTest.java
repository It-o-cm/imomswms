package com.intermarche.e2e.engine.fieldops;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.printing.PrintJob;
import com.intermarche.backoffice.domain.store.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
@QuarkusTest @jakarta.enterprise.inject.Typed(SupportOperatorExperienceTest.class)
class SupportOperatorExperienceTest extends SupportTraceabilityTest {
 Map<String,Object> currentLoad(){return call(f.picker(),"GET","/orders/"+f.order()+"/supports?storeId="+f.store(),null).then().statusCode(200).extract().jsonPath().getMap("loads[0]");}
 @Test void cartShowsOnlyActuallyCollectedContentsInTheRightCell(){
  picked();QuarkusTransaction.requiringNew().run(()->supportCatalog.registerAll(Store.findById(f.store())));
  long leaf=QuarkusTransaction.requiringNew().call(()->PhysicalSupport.<PhysicalSupport>find("tote.code",f.tote()).firstResult().id);
  long cart=support("CART","VIEW-"+f.store());var initial=call(f.manager(),"GET","/supports/"+cart+"/layout?storeId="+f.store(),null).then().statusCode(200).extract();
  var layout=call(f.manager(),"PUT","/supports/layout",Map.of("storeId",f.store(),"cartId",cart,"rows",2,"columns",2,"expectedRevision",initial.<String>path("revision"))).then().statusCode(200).extract();
  call(f.picker(),"POST","/supports/compartments",Map.of("storeId",f.store(),"orderId",f.order(),"supportId",leaf,"compartmentId",layout.jsonPath().getLong("compartments[1].id"),"expectedRevision",layout.path("compartments[1].revision"),"requestKey",key(),"destination","Drive")).then().statusCode(200);
  var view=call(f.picker(),"GET","/supports/"+cart+"/layout?storeId="+f.store(),null).then().statusCode(200).extract();
  assertEquals(4,view.jsonPath().getList("compartments").size());assertTrue(view.jsonPath().getList("compartments[0].items").isEmpty());
  assertEquals(f.tote(),view.path("compartments[1].supportCode"));assertEquals("Drive",view.path("compartments[1].destination"));
  assertEquals(f.product().longValue(),view.jsonPath().getLong("compartments[1].items[0].productId"));assertEquals(2,view.jsonPath().getFloat("compartments[1].items[0].quantity"));
  assertEquals(1,view.jsonPath().getList("compartments[1].items").size());
  given().auth().oauth2(f.picker()).get("/field-operations/supports/"+cart+"/layout?storeId="+f.other()).then().statusCode(403);
 }
 @ParameterizedTest @ValueSource(strings={"FULL","DAMAGED","UNSUITABLE"})
 void replacementPreservesContentsAndItsReason(String cause){
  picked();QuarkusTransaction.requiringNew().run(()->supportCatalog.registerAll(Store.findById(f.store())));var load=currentLoad();
  var body=new LinkedHashMap<String,Object>(Map.of("storeId",f.store(),"loadedToteId",load.get("id"),"expectedRevision",load.get("revision"),"expectedOrderRevision",orderRevision(),"cause",cause,"reason","Constat terrain","replacementCode",f.second(),"requestKey",key()));
  call(f.picker(),"POST","/supports/full",body).then().statusCode(200);call(f.picker(),"POST","/supports/full",body).then().statusCode(200);
  var read=call(f.picker(),"GET","/orders/"+f.order()+"/supports?storeId="+f.store(),null).then().statusCode(200).extract();
  var source=read.jsonPath().<Map<String,Object>>getList("loads").stream().filter(x->x.get("code").equals(f.tote())).findFirst().orElseThrow();
  assertEquals(cause,source.get("withdrawalCause"));assertEquals(f.second(),source.get("replacementCode"));
  QuarkusTransaction.requiringNew().run(()->{var original=LoadedTote.<LoadedTote>findById(((Number)load.get("id")).longValue());assertEquals(1,original.items.size());assertEquals(new BigDecimal("2.000"),original.items.iterator().next().quantity);assertEquals(cause.equals("DAMAGED")?"BLOCKED":"AVAILABLE",PhysicalSupport.<PhysicalSupport>find("tote",original.tote).firstResult().state);});
  var again=command("PICK");again.putAll(Map.of("itemId",f.item(),"quantity",1,"toteCode",f.tote()));command(again).then().statusCode(409);
  long buffer=QuarkusTransaction.requiringNew().call(()->{var b=new Buffer();b.building=Building.findById(f.building());b.bufferCode="WITHDRAW-"+f.store();b.ambientCapacity=20;b.active=true;b.persistAndFlush();return b.id;});
  var deposit=command("DEPOSIT_TOTE");deposit.putAll(Map.of("toteCode",f.tote(),"bufferId",buffer));command(deposit).then().statusCode(200);

 }
 @ParameterizedTest @ValueSource(strings={"DAMAGED","UNSUITABLE"})
 void emptyFaultySupportCanAlsoBeReplaced(String cause){
  line();attach(f.tote());QuarkusTransaction.requiringNew().run(()->supportCatalog.registerAll(Store.findById(f.store())));var load=currentLoad();
  call(f.picker(),"POST","/supports/full",Map.of("storeId",f.store(),"loadedToteId",load.get("id"),"expectedRevision",load.get("revision"),"expectedOrderRevision",orderRevision(),"cause",cause,"reason","Défaut avant utilisation","replacementCode",f.second(),"requestKey",key())).then().statusCode(200);
 }
 @Test void printingLoadedSupportIsIdempotentAndReprintingUsesTheSameIdentity(){
  picked();var load=currentLoad();
  var printer=given().auth().oauth2(f.manager()).contentType("application/json").body(Map.of("storeId",f.store(),"requestKey",key(),"code","PRINT"+f.store(),"label","Test","transport","SIMULATOR","simulation","SUCCESS")).post("/printing/printers").then().statusCode(200).extract().jsonPath().getMap("$");
  var request=Map.of("storeId",f.store(),"requestKey",key(),"printerId",printer.get("id"),"subjectType","TOTE","subjectId",load.get("id"));
  var job=given().auth().oauth2(f.picker()).contentType("application/json").body(request).post("/printing/jobs").then().statusCode(200).extract();
  long jobId=job.jsonPath().getLong("id");
  assertEquals(f.tote(),job.path("code"));assertEquals("QUEUED",job.path("state"));
  assertEquals(jobId,given().auth().oauth2(f.picker()).contentType("application/json").body(request).post("/printing/jobs").then().statusCode(200).extract().jsonPath().getLong("id"));
  given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("storeId",f.store(),"requestKey",key(),"printerId",printer.get("id"),"jobId",jobId,"reason","Étiquette abîmée")).post("/printing/jobs").then().statusCode(409);
  given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("storeId",f.store(),"requestKey",key(),"jobId",jobId,"expectedRevision",job.jsonPath().getLong("revision"),"reason","Mauvaise imprimante")).post("/printing/jobs/cancel").then().statusCode(200);
  var copy=given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("storeId",f.store(),"requestKey",key(),"printerId",printer.get("id"),"jobId",jobId,"reason","Étiquette abîmée")).post("/printing/jobs").then().statusCode(200).extract();
  assertEquals(jobId,copy.jsonPath().getLong("reprintOf"));assertEquals(job.<String>path("payloadSha256"),copy.path("payloadSha256"));assertEquals(f.tote(),copy.path("code"));
  given().auth().oauth2(f.picker()).get("/printing/subjects/TOTE/"+load.get("id")+"/last-job?storeId="+f.store()).then().statusCode(200);
  QuarkusTransaction.requiringNew().run(()->assertEquals(new BigDecimal("2.000"),LoadedTote.<LoadedTote>findById(((Number)load.get("id")).longValue()).items.iterator().next().quantity));
  given().auth().oauth2(f.picker()).get("/printing/subjects/TOTE/"+load.get("id")+"/last-job?storeId="+f.other()).then().statusCode(403);
 }
}
