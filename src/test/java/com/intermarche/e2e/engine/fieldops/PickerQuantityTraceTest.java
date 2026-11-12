package com.intermarche.e2e.engine.fieldops;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.stock.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
@QuarkusTest @jakarta.enterprise.inject.Typed(PickerQuantityTraceTest.class)
class PickerQuantityTraceTest extends SupportTraceabilityTest {
 long lot(String code){return call(f.picker(),"POST","/manufacturer-lots",Map.of("storeId",f.store(),"productId",f.product(),"code",code)).then().statusCode(200).extract().jsonPath().getLong("id");}
 long serial(long lot,String code){return call(f.picker(),"POST","/product-serials",Map.of("storeId",f.store(),"productId",f.product(),"lotId",lot,"code",code,"claimKey",key())).then().statusCode(200).extract().jsonPath().getLong("id");}
 long pending(){return given().auth().oauth2(f.picker()).get("/handling-orders/operator/executions/"+f.execution()+"?storeId="+f.store()).then().statusCode(200).extract().jsonPath().<Map<String,Object>>getList("items").stream().filter(i->i.get("status").equals("PENDING")).map(i->((Number)i.get("id")).longValue()).findFirst().orElseThrow();}
 long buffer(){return QuarkusTransaction.requiringNew().call(()->{var b=new Buffer();b.building=Building.findById(f.building());b.bufferCode="TRACE-"+f.store();b.ambientCapacity=20;b.active=true;b.persistAndFlush();return b.id;});}
 void weighProduct(String q){QuarkusTransaction.requiringNew().run(()->{var p=Product.<Product>findById(f.product());p.productType=ProductType.WEIGHT;var item=PickedItem.<PickedItem>findById(f.item());item.quantity=new BigDecimal(q);item.lotLine.quantity=new BigDecimal(q);});}
 @Test void manuallyCapturedSerialsAreStoredIndividuallyAndCannotBePickedTwiceEvenWithUnknownStock(){
  line();attach(f.tote());long lot=lot("MANUAL");long one=serial(lot,"SER-1"),two=serial(lot,"SER-2");
  var first=command("PICK");first.putAll(Map.of("itemId",pending(),"quantity",1,"toteCode",f.tote(),"manufacturerLotId",lot,"serialId",one));command(first).then().statusCode(200);command(first).then().statusCode(200);
  var duplicate=command("PICK");duplicate.putAll(Map.of("itemId",pending(),"quantity",1,"toteCode",f.tote(),"manufacturerLotId",lot,"serialId",one));command(duplicate).then().statusCode(409);
  var next=command("PICK");next.putAll(Map.of("itemId",pending(),"quantity",1,"toteCode",f.tote(),"manufacturerLotId",lot,"serialId",two));command(next).then().statusCode(200);
  QuarkusTransaction.requiringNew().run(()->{var allocations=PickedStockAllocation.<PickedStockAllocation>list("state.store.id=?1 and quantity>0",f.store());assertEquals(2,allocations.size());assertEquals(Set.of(one,two),allocations.stream().map(a->a.holding.serial.id).collect(java.util.stream.Collectors.toSet()));assertTrue(allocations.stream().allMatch(a->a.holding.lot.id.equals(lot)&&a.quantity.compareTo(BigDecimal.ONE)==0));});
 }
 @Test void gs1EncodedWeightIsTheActualPickedQuantityAndCarriesItsLot(){
  line();weighProduct("1");attach(f.tote());
  String revision=given().auth().oauth2(f.manager()).get("/product-information/"+f.product()+"?storeId="+f.store()).then().statusCode(200).extract().path("revision");
  given().auth().oauth2(f.manager()).contentType("application/json").body(Map.of("storeId",f.store(),"expectedRevision",revision,"kind","GTIN","value","4006381333931")).put("/product-information/"+f.product()+"/identifiers").then().statusCode(200);
  var scan=given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("storeId",f.store(),"scanId",key(),"code","(01)04006381333931(3103)000375(10)WEIGHT-LOT")).post("/product-information/resolve").then().statusCode(200).extract();assertEquals("0.375",scan.path("quantity"));
  var pick=command("PICK");pick.putAll(Map.of("itemId",pending(),"quantity","0.375","toteCode",f.tote(),"scanId",scan.<String>path("scanId")));command(pick).then().statusCode(200);
  QuarkusTransaction.requiringNew().run(()->{var a=PickedStockAllocation.<PickedStockAllocation>find("state.store.id=?1 and quantity>0",f.store()).firstResult();assertEquals(0,new BigDecimal("0.375").compareTo(a.quantity));assertEquals("WEIGHT-LOT",a.holding.lot.code);});
 }
 @Test void scaleProofAndQuantitySurviveThePreparationReport(){
  long line=line();weighProduct("0.375");var release=command("RELEASE");release.put("orderId",f.order());command(release).then().statusCode(200);
  var measurement=Map.of("net","375","unit","G","origin","SCALE","deviceId","TCP:balance-1");
  var weight=given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("storeId",f.store(),"measurement",measurement)).post("/product-information/"+f.product()+"/measure").then().statusCode(200).extract();
  var item=Map.of("pickedItemId",line,"qty","0.375","measurement",measurement,"scanId",weight.<String>path("scanId"));
  var report=Map.of("orderIds",List.of(f.order()),"reports",List.of(Map.of("orderId",f.order(),"totes",List.of(Map.of("code",f.tote(),"zone","AMBIENT","bufferId",buffer(),"items",List.of(item))),"missing",List.of())));
  var input=new LinkedHashMap<String,Object>();input.put("storeId",f.store());input.put("requestKey",key());input.put("action","VALIDATE_REPORT");input.put("report",report);
  command(input).then().statusCode(200);command(input).then().statusCode(200);
  QuarkusTransaction.requiringNew().run(()->{var loads=LoadedTote.<LoadedTote>list("tote.code",f.tote());assertEquals(1,loads.size());assertEquals(0,new BigDecimal("0.375").compareTo(loads.getFirst().items.stream().map(i->i.quantity).reduce(BigDecimal.ZERO,BigDecimal::add)));});
 }
 @Test void preparationReportKeepsBothSerialsInsteadOfRepeatingItsFirstTraceRow(){
  long line=line();var release=command("RELEASE");release.put("orderId",f.order());command(release).then().statusCode(200);long lot=lot("REPORT"),one=serial(lot,"REPORT-1"),two=serial(lot,"REPORT-2");
  var items=List.of(Map.of("pickedItemId",line,"qty",1,"manufacturerLotId",lot,"serialId",one),Map.of("pickedItemId",line,"qty",1,"manufacturerLotId",lot,"serialId",two));
  var report=Map.of("orderIds",List.of(f.order()),"reports",List.of(Map.of("orderId",f.order(),"totes",List.of(Map.of("code",f.tote(),"zone","AMBIENT","bufferId",buffer(),"items",items)),"missing",List.of())));
  var input=Map.of("storeId",f.store(),"requestKey",key(),"action","VALIDATE_REPORT","report",report);command(input).then().statusCode(200);command(input).then().statusCode(200);
  QuarkusTransaction.requiringNew().run(()->{var rows=PickedStockAllocation.<PickedStockAllocation>list("state.store.id=?1 and quantity>0",f.store());assertEquals(2,rows.size());assertEquals(Set.of(one,two),rows.stream().map(a->a.holding.serial.id).collect(java.util.stream.Collectors.toSet()));});
 }
 @Test void aSerialInTransitCannotBePickedOrReceivedAgain(){
  line();long lot=lot("TRANSIT"),serial=serial(lot,"TRANSIT-1");
  long supply=QuarkusTransaction.requiringNew().call(()->{var account=stock.account(f.store(),f.product());var destination=new StoreShelfLocation();destination.product=Product.findById(f.product());destination.locationCode="DEST-"+f.store();destination.area="A";destination.aisle="1";destination.unit="2";destination.elevation="1";destination.position="1";destination.persist();Building.<Building>findById(f.building()).locations.add(destination);return stock.planSupply(account.id,key(),f.location(),destination.id,new BigDecimal("2"),com.intermarche.backoffice.domain.util.DateTimeProvider.now().plusHours(1)).id;});
  var hint=new com.intermarche.backoffice.engine.executing.OperatorCommandInput();hint.manufacturerLotId=lot;hint.serialId=serial;
  QuarkusTransaction.requiringNew().run(()->stock.pick(supply,"first-serial",BigDecimal.ONE,com.intermarche.backoffice.domain.util.DateTimeProvider.now(),f.location(),hint));
  assertThrows(com.intermarche.backoffice.engine.operator.OperatorFailure.class,()->QuarkusTransaction.requiringNew().run(()->stock.pick(supply,"duplicate-serial",BigDecimal.ONE,com.intermarche.backoffice.domain.util.DateTimeProvider.now(),f.location(),hint)));
  QuarkusTransaction.requiringNew().run(()->assertTrue(com.intermarche.backoffice.engine.stock.StockTraceLedger.serialOccupied(ProductSerial.findById(serial),null)));
 }

}
