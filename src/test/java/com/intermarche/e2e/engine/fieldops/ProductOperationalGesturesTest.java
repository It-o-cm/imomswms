package com.intermarche.e2e.engine.fieldops;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.executing.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
@QuarkusTest @jakarta.enterprise.inject.Typed(ProductOperationalGesturesTest.class)
class ProductOperationalGesturesTest extends SupportTraceabilityTest {
 Map<String,Object> edit(Map<String,Object> values){var result=new LinkedHashMap<>(values);result.put("storeId",f.store());result.put("expectedRevision",given().auth().oauth2(f.manager()).get("/product-information/"+f.product()+"?storeId="+f.store()).then().statusCode(200).extract().path("revision"));return result;}
 void configure(String path,Map<String,Object> values){given().auth().oauth2(f.manager()).contentType("application/json").body(edit(values)).put("/product-information/"+f.product()+"/"+path).then().statusCode(200);}
 @Test void selectedWarehouseLocationExposesItsRealAccessAndEquipment(){
  line();long address=QuarkusTransaction.requiringNew().call(()->{var store=Store.<Store>findById(f.store());var building=new WarehouseBuilding();building.buildingCode="WH-"+f.store();building.persist();store.buildings.add(building);var loc=new WarehouseShelfLocation();loc.product=Product.findById(f.product());loc.locationCode="HIGH-"+f.store();loc.area="AMBIENT";loc.aisle="A1";loc.unit="B2";loc.elevation="4";loc.position="1";loc.maxWeightKg=1250.5;loc.maxPallets=2;loc.persistAndFlush();building.locations.add(loc);return loc.id;});
  var rows=given().auth().oauth2(f.picker()).get("/handling-orders/operator/orders/"+f.order()+"/product-locations?storeId="+f.store()+"&requestedProductId="+f.product()+"&productId="+f.product()).then().statusCode(200).extract().jsonPath().<Map<String,Object>>getList("$");
  var row=rows.stream().filter(r->((Number)r.get("id")).longValue()==address).findFirst().orElseThrow();
  var access=(Map<?,?>)row.get("access");assertEquals(List.of("FORKLIFT"),access.get("equipment"));assertEquals(List.of("ELEVATED_ACCESS"),access.get("constraints"));assertEquals("4",access.get("level"));assertEquals(2,access.get("maxPallets"));
  var normal=rows.stream().filter(r->((Number)r.get("id")).longValue()!=address).findFirst().orElseThrow();assertTrue(((List<?>)((Map<?,?>)normal.get("access")).get("equipment")).isEmpty());
 }
 @ParameterizedTest @ValueSource(strings={"CASE","PALLET"})
 void packageScanPicksItsBaseQuantityOnce(String level){
  line();QuarkusTransaction.requiringNew().run(()->{var item=PickedItem.<PickedItem>findById(f.item());item.quantity=new BigDecimal("6");item.lotLine.quantity=new BigDecimal("6");});attach(f.tote());
  configure("packagings",Map.of("code","CASE2","label","Carton de deux","level","CASE","factor","2","unit","PCS"));
  configure("packagings",Map.of("code","PALLET6","label","Palette de trois cartons","level","PALLET","parentCode","CASE2","factor","3","unit","PCS"));
  String packaging=level.equals("CASE")?"CASE2":"PALLET6";int quantity=level.equals("CASE")?2:6;
  configure("identifiers",Map.of("kind","INTERNAL","value","PACK-"+f.store(),"packaging",packaging));
  String scan=key();var resolved=given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("storeId",f.store(),"code","PACK-"+f.store(),"scanId",scan,"productId",f.product())).post("/product-information/resolve").then().statusCode(200).extract();
  assertEquals(Integer.toString(quantity),resolved.path("quantity"));assertEquals(level,resolved.path("packagingLevel"));
  var pick=command("PICK");pick.putAll(Map.of("itemId",f.item(),"quantity",quantity,"toteCode",f.tote(),"scanId",scan));command(pick).then().statusCode(200);command(pick).then().statusCode(200);
  QuarkusTransaction.requiringNew().run(()->{var load=LoadedTote.<LoadedTote>find("tote.code",f.tote()).firstResult();assertEquals(0,load.items.stream().map(i->i.quantity).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(BigDecimal.valueOf(quantity)));});
  var duplicate=command("PICK");duplicate.putAll(Map.of("itemId",f.item(),"quantity",quantity,"toteCode",f.tote(),"scanId",scan));command(duplicate).then().statusCode(level.equals("PALLET")?400:409);
  QuarkusTransaction.requiringNew().run(()->{var load=LoadedTote.<LoadedTote>find("tote.code",f.tote()).firstResult();assertEquals(0,load.items.stream().map(i->i.quantity).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(BigDecimal.valueOf(quantity)));});
 }
}
