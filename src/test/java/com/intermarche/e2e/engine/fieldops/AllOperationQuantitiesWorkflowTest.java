package com.intermarche.e2e.engine.fieldops;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.*;import java.math.BigDecimal;
import static io.restassured.RestAssured.given;import static org.junit.jupiter.api.Assertions.*;
@QuarkusTest @jakarta.enterprise.inject.Typed(AllOperationQuantitiesWorkflowTest.class)
class AllOperationQuantitiesWorkflowTest extends DepartureWorkflowTest {
 Map<String,Object> bilan(){return given().auth().oauth2(f.picker()).get("/handling-orders/operator/consultation/summary?storeId="+f.store()).then().statusCode(200).extract().jsonPath().getMap("$");}
 Map<String,Object> metric(Map<String,Object> summary,String action,String metric){return ((List<Map<String,Object>>)summary.get("operations")).stream().filter(r->action.equals(r.get("action"))&&metric.equals(r.get("metric"))).findFirst().orElseThrow();}
 void decimal(Object actual,String expected){assertEquals(0,new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));}
 @Test void preparationLoadingReversalAndShipmentAreSeparatedAndReplayDoesNotInflateWork(){
  var parcel=closedParcel(2);long buffer=buffer();var d=load(draft(List.of(Map.of("parcelId",parcel.get("id")))),0);
  var unit=((List<Map<String,Object>>)d.get("units")).getFirst();var unload=inputDeparture(d);unload.put("unitId",unit.get("id"));unload.put("scannedUnit",unit.get("code"));unload.put("bufferId",buffer);
  d=departure(f.picker(),"/unload",unload).then().statusCode(200).extract().jsonPath().getMap("$");departure(f.picker(),"/unload",unload).then().statusCode(200);
  var summary=bilan();assertEquals("ALL_CONFIRMED_OPERATIONS",summary.get("basis"));decimal(metric(summary,"CLOSE_PARCEL","PACKED").get("quantity"),"2");decimal(metric(summary,"LOAD_DEPARTURE","LOADED").get("quantity"),"2");decimal(metric(summary,"UNLOAD_DEPARTURE","LOADED").get("quantity"),"-2");
  d=load(d,0);var hand=inputDeparture(d);hand.put("recipient","Chauffeur test");hand.put("unitIds",List.of(unit.get("id")));departure(f.picker(),"/handover",hand).then().statusCode(200);departure(f.picker(),"/handover",hand).then().statusCode(200);
  summary=bilan();decimal(metric(summary,"LOAD_DEPARTURE","LOADED").get("quantity"),"4");var shipment=((List<Map<String,Object>>)summary.get("physicalQuantities")).stream().filter(r->"SHIPMENT".equals(r.get("operation"))).findFirst().orElseThrow();decimal(shipment.get("outgoing"),"2");decimal(shipment.get("net"),"-2");
  var picks=((List<Map<String,Object>>)summary.get("physicalQuantities")).stream().filter(r->"PICKING_TRACE".equals(r.get("operation"))).findFirst().orElseThrow();decimal(picks.get("handled"),"2");decimal(picks.get("incoming"),"2");decimal(picks.get("outgoing"),"2");decimal(picks.get("net"),"0");assertEquals(false,summary.get("totalsAreAdditiveAcrossOperations"));
  given().auth().oauth2(f.picker()).get("/handling-orders/operator/consultation/summary?storeId="+f.other()).then().statusCode(403);
 }
 @Test void movingAndReturningWholeItemsUseTheirActualQuantityRatherThanOptionalInputQuantity(){
  line();var stock=stock();pick(stock);attach(f.second());var move=command("MOVE_ITEM");move.putAll(Map.of("itemId",f.item(),"toteCode",f.tote(),"targetToteCode",f.second()));command(move).then().statusCode(200);command(move).then().statusCode(200);
  decimal(metric(bilan(),"MOVE_ITEM","TRANSFERRED").get("quantity"),"2");var back=command("RETURN_ITEM");back.putAll(Map.of("itemId",f.item(),"toteCode",f.second()));command(back).then().statusCode(200);decimal(metric(bilan(),"RETURN_ITEM","RETURNED_TO_SHELF").get("quantity"),"2");
 }
 @Test void recordedUnitsRemainFrozenIfTheCatalogueChangesLater(){
  line();var physical=stock();pick(physical);
  io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(()->{var p=com.intermarche.backoffice.domain.order.Product.<com.intermarche.backoffice.domain.order.Product>findById(f.product());p.productType=com.intermarche.backoffice.domain.order.ProductType.WEIGHT;});
  var summary=bilan();assertEquals("PCS",metric(summary,"PICK","PICKED").get("unit"));assertTrue(((List<Map<String,Object>>)summary.get("physicalQuantities")).stream().allMatch(v->"PCS".equals(v.get("unit"))));
 }

}
