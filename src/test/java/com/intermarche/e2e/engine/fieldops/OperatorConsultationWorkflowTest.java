package com.intermarche.e2e.engine.fieldops;
import io.quarkus.test.junit.QuarkusTest;import io.quarkus.narayana.jta.QuarkusTransaction;import org.junit.jupiter.api.Test;import java.util.*;import java.math.BigDecimal;import static org.junit.jupiter.api.Assertions.*;import static io.restassured.RestAssured.given;
@QuarkusTest @jakarta.enterprise.inject.Typed(OperatorConsultationWorkflowTest.class)
class OperatorConsultationWorkflowTest extends ParcelWorkflowTest {

 Map<String,Object> current(String kind){return given().auth().oauth2(f.manager()).get("/operational-policies/current?storeId="+f.store()+"&type="+kind).then().statusCode(200).extract().jsonPath().getMap("$");}
 Map<String,Object> sample(String kind){var defs=given().auth().oauth2(f.manager()).get("/operational-policies/catalog?storeId="+f.store()).then().statusCode(200).extract().jsonPath().<Map<String,Object>>getList("definitions");return new LinkedHashMap<>((Map<String,Object>)defs.stream().filter(d->kind.equals(d.get("type"))).findFirst().orElseThrow().get("testCase"));}
 Map<String,Object> post(String path,Map<String,Object> body,int status){return given().auth().oauth2(f.manager()).contentType("application/json").body(body).post("/operational-policies/"+path).then().statusCode(status).extract().jsonPath().getMap("$");}
 Map<String,Object> request(Map<String,Object> current,Map<String,Object> spec,Map<String,Object> test){return new LinkedHashMap<>(Map.of("storeId",f.store(),"specification",spec,"testCase",test,"expectedRevision",current.get("revision"),"requestKey",key(),"reason","Politique vérifiée dans un scénario de test"));}

 Map<String,Object> consult(String actor,String path){return given().auth().oauth2(actor).get("/handling-orders/operator/consultation/"+path+"?storeId="+f.store()).then().statusCode(200).extract().jsonPath().getMap("$");}
 @Test void readOnlyProductProjectionDistinguishesUnknownFromKnownZeroAndDoesNotCreateAccounts(){
  var unknown=consult(f.picker(),"products/"+f.product());assertNull(unknown.get("physical"));assertEquals(false,unknown.get("quantityKnown"));assertEquals(true,unknown.get("incomplete"));assertEquals(1,((List<?>)unknown.get("addresses")).size());
  QuarkusTransaction.requiringNew().run(()->assertEquals(0,com.intermarche.backoffice.domain.stock.StockAccount.count("store.id",f.store())));
  var stock=stock();var known=consult(f.picker(),"products/"+f.product());assertEquals(0,new BigDecimal(known.get("physical").toString()).compareTo(BigDecimal.valueOf(20)));assertNull(known.get("weightKg"));
  QuarkusTransaction.requiringNew().run(()->{stocks.adjust(stock.account(),f.location(),"zero",BigDecimal.valueOf(-20),com.intermarche.backoffice.domain.util.DateTimeProvider.now());});
  var zero=consult(f.picker(),"products/"+f.product());assertEquals(true,zero.get("quantityKnown"));assertEquals(0,new BigDecimal(zero.get("physical").toString()).signum());
  given().auth().oauth2(f.picker()).get("/handling-orders/operator/consultation/products/"+f.product()+"?storeId="+f.other()).then().statusCode(403);
  given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("storeId",f.store(),"text","interdit")).put("/product-information/"+f.product()+"/instructions").then().statusCode(403);
 }
 @Test void ownActivityUsesActualConfirmedCommandsWithStablePagingAndNoOtherActor(){
  line();stock();attach(f.tote());attach(f.second());
  var first=given().auth().oauth2(f.picker()).get("/handling-orders/operator/consultation/activity?storeId="+f.store()+"&limit=1").then().statusCode(200).extract().jsonPath().<String,Object>getMap("$");
  assertEquals("CONFIRMED_COMMANDS_ONLY",first.get("basis"));assertEquals(true,first.get("hasMore"));var rows=(List<Map<String,Object>>)first.get("items");assertEquals(1,rows.size());
  var next=given().auth().oauth2(f.picker()).queryParam("storeId",f.store()).queryParam("limit",1).queryParam("cursor",first.get("nextCursor")).get("/handling-orders/operator/consultation/activity").then().statusCode(200).extract().jsonPath().<Map<String,Object>>getList("items");assertFalse(next.isEmpty());assertNotEquals(rows.getFirst().get("id"),next.getFirst().get("id"));
  var manager=consult(f.manager(),"activity");assertTrue(((List<?>)manager.get("items")).isEmpty());
  given().auth().oauth2(f.picker()).get("/handling-orders/operator/consultation/activity?storeId="+f.store()+"&cursor=garbage").then().statusCode(400);
 }
 @Test void summaryCountsOnlyAcknowledgedPicksOnceAndLabelsLegacyUnknowns(){
  line();stock();attach(f.tote());var input=command("PICK");input.putAll(Map.of("itemId",f.item(),"quantity",1,"toteCode",f.tote()));command(input).then().statusCode(200);command(input).then().statusCode(200);
  var summary=consult(f.picker(),"summary");var quantities=(List<Map<String,Object>>)summary.get("quantities");assertEquals(1,quantities.size());assertEquals(0,BigDecimal.ONE.compareTo(new BigDecimal(quantities.getFirst().get("quantity").toString())));assertEquals(1,((Number)quantities.getFirst().get("confirmedCommands")).intValue());assertEquals(false,summary.get("durationInferred"));
  QuarkusTransaction.requiringNew().run(()->{var receipt=new com.intermarche.backoffice.domain.executing.OperatorCommandReceipt();receipt.store=com.intermarche.backoffice.domain.store.Store.findById(f.store());receipt.performedBy=com.intermarche.backoffice.domain.executing.OperatorCommandReceipt.<com.intermarche.backoffice.domain.executing.OperatorCommandReceipt>find("store.id=?1 and action=?2",f.store(),"PICK").firstResult().performedBy;receipt.requestKey=key();receipt.fingerprint=key();receipt.action="PICK";receipt.result="{}";receipt.persist();});
  assertEquals(1,((Number)consult(f.picker(),"summary").get("unquantifiedPickCommands")).intValue());assertTrue(((List<?>)consult(f.manager(),"summary").get("quantities")).isEmpty());
 }
 @Test void historyMaskIsEnforcedServerSideWithoutHidingCurrentInstructions(){
  QuarkusTransaction.requiringNew().run(()->{for(int n=1;n<=2;n++){var i=new com.intermarche.backoffice.domain.catalog.ProductInstruction();i.store=com.intermarche.backoffice.domain.store.Store.findById(f.store());i.product=com.intermarche.backoffice.domain.order.Product.findById(f.product());i.revision=n;i.text="Consigne "+n;i.author="responsable";i.persist();}});
  var current=current("ConsultationConstraints");var spec=new LinkedHashMap<>((Map<String,Object>)current.get("values"));spec.put("showOwnActivity",false);spec.put("showCommandHistory",false);spec.put("showPreviousProductInstructions",false);var input=request(current,spec,sample("ConsultationConstraints"));input.put("simulationToken",post("simulate",input,200).get("simulationToken"));post("activate",input,200);
  assertEquals(true,consult(f.picker(),"activity").get("historyMasked"));assertEquals(true,consult(f.picker(),"summary").get("historyMasked"));
  given().auth().oauth2(f.picker()).get("/product-information/"+f.product()+"?storeId="+f.store()).then().statusCode(200).body("instructionHistoryVisible",org.hamcrest.Matchers.is(false)).body("instructions.size()",org.hamcrest.Matchers.is(1)).body("instructions[0].text",org.hamcrest.Matchers.is("Consigne 2"));
  given().auth().oauth2(f.manager()).get("/product-information/"+f.product()+"?storeId="+f.store()).then().statusCode(200).body("instructions.size()",org.hamcrest.Matchers.is(2));
  given().auth().oauth2(f.picker()).get("/handling-orders/operator/support/events?storeId="+f.store()+"&category=COMMAND_CONFIRMED").then().statusCode(200).body("historyMasked",org.hamcrest.Matchers.is(true));
 }
}
