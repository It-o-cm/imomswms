package com.intermarche.e2e.engine.catalog;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.stock.StockMovement;
import com.intermarche.backoffice.domain.catalog.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;
import org.mindrot.jbcrypt.BCrypt;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
@QuarkusTest
class ProductInformationTest {
 record Fixture(Long store,Long other,Long product,Long second,String manager,String picker){}
 Fixture f;
 @BeforeEach void fixture(){String code=UUID.randomUUID().toString().substring(0,10);f=QuarkusTransaction.requiringNew().call(()->{
  Store s=new Store();s.code="CAT-"+code;s.name="Catalogue";s.persist();Store other=new Store();other.code="OTHER-"+code;other.name="Autre magasin";other.persist();
  for(var role:List.of(Employee.EmployeeRole.MANAGER,Employee.EmployeeRole.PICKER)){var e=new Employee();e.badgeId=role.name()+code;e.firstName="Test";e.lastName=role.name();e.email=e.badgeId+"@example.test";e.password=BCrypt.hashpw("test",BCrypt.gensalt(4));e.role=role;e.store=s;e.active=true;e.persist();}
  var p=new Product();p.ean="C"+code.substring(0,10);p.name="Produit "+code;p.productType=ProductType.UNIT;p.temperatureZone=TemperatureZone.AMBIENT;p.characteristics="Description libre";p.persist();var second=new Product();second.ean="D"+code.substring(0,10);second.name="Autre "+code;second.productType=ProductType.WEIGHT;second.temperatureZone=TemperatureZone.AMBIENT;second.persist();return new Fixture(s.id,other.id,p.id,second.id,"MANAGER"+code,"PICKER"+code);
 });f=new Fixture(f.store,f.other,f.product,f.second,login(f.manager),login(f.picker));}
 String login(String user){return given().contentType("application/json").body(Map.of("username",user,"password","test")).post("/auth/login").then().statusCode(200).extract().path("token");}
 Response call(String token,String method,String path,Object body){var r=given().auth().oauth2(token).contentType("application/json");if(body!=null)r.body(body);return r.request(method,"/product-information"+path);}
 String revision(Long product){return call(f.manager,"GET","/"+product+"?storeId="+f.store,null).then().statusCode(200).extract().path("revision");}
 Map<String,Object> edit(Long product,Map<String,?> fields){var e=new HashMap<String,Object>(fields);e.put("storeId",f.store);e.put("expectedRevision",revision(product));return e;}
 void pack(String code,String parent,String factor){call(f.manager,"PUT","/"+f.product+"/packagings",edit(f.product,Map.of("code",code,"label",code,"level","CASE","parentCode",parent,"factor",factor,"unit","PCS"))).then().statusCode(200);}
 Map<String,Object> scan(String code,String id){return Map.of("storeId",f.store,"code",code,"scanId",id);}
 @Test void sameFileScopedInstructionsAndStaleRevision(){String old=revision(f.product);call(f.manager,"PUT","/"+f.product+"/instructions",edit(f.product,Map.of("text","Ne pas empiler"))).then().statusCode(200);var file=call(f.picker,"GET","/"+f.product+"?storeId="+f.store,null).then().statusCode(200).extract();assertEquals("Description libre",file.path("product.characteristics"));assertEquals("Ne pas empiler",file.path("instructions[0].text"));call(f.manager,"PUT","/"+f.product+"/instructions",Map.of("storeId",f.store,"expectedRevision",old,"text","stale")).then().statusCode(409);call(f.picker,"PUT","/"+f.product+"/instructions",edit(f.product,Map.of("text","Forbidden"))).then().statusCode(403);call(f.picker,"GET","/"+f.product+"?storeId="+f.other,null).then().statusCode(403);}
 @Test void identifiersResolveSameProductAndSupplierAmbiguityRequiresChoice(){
  for(var alias:List.of(Map.of("kind","INTERNAL","value","INT-1"),Map.of("kind","GTIN","value","4006381333931")))call(f.manager,"PUT","/"+f.product+"/identifiers",edit(f.product,alias)).then().statusCode(200);
  for(String code:List.of("INT-1","4006381333931")){var r=call(f.picker,"POST","/resolve",scan(code,UUID.randomUUID().toString())).then().statusCode(200).extract();assertEquals("RESOLVED",r.path("state"));assertEquals(f.product.longValue(),r.jsonPath().getLong("product.id"));}
  call(f.manager,"PUT","/"+f.product+"/identifiers",edit(f.product,Map.of("kind","SUPPLIER","value","123","context","A"))).then().statusCode(200);
  call(f.manager,"PUT","/"+f.second+"/identifiers",edit(f.second,Map.of("kind","SUPPLIER","value","123","context","B"))).then().statusCode(200);
  String id=UUID.randomUUID().toString();var ambiguous=call(f.picker,"POST","/resolve",scan("123",id)).then().statusCode(200).extract();assertEquals("AMBIGUOUS",ambiguous.path("state"));assertEquals(2,ambiguous.jsonPath().getList("choices").size());var choice=new HashMap<>(scan("123",id));choice.put("identifierId",ambiguous.jsonPath().getLong("choices[0].identifierId"));call(f.picker,"POST","/resolve",choice).then().statusCode(200);choice.put("code","different");call(f.picker,"POST","/resolve",choice).then().statusCode(409);
 }
 @Test void collisionAndInvalidGtinAreNotSilentlyAccepted(){call(f.manager,"PUT","/"+f.product+"/identifiers",edit(f.product,Map.of("kind","INTERNAL","value","SAME"))).then().statusCode(200);call(f.manager,"PUT","/"+f.second+"/identifiers",edit(f.second,Map.of("kind","INTERNAL","value","SAME"))).then().statusCode(409);call(f.manager,"PUT","/"+f.product+"/identifiers",edit(f.product,Map.of("kind","GTIN","value","4006381333932"))).then().statusCode(400);}
 @Test void exactNestedConversionsCycleAndFractions(){pack("CASE12","BASE","12");pack("PALLET","CASE12","40");var c=call(f.picker,"POST","/"+f.product+"/convert",Map.of("storeId",f.store,"packaging","PALLET","quantity","1")).then().statusCode(200).extract();assertEquals("480",c.path("quantity"));assertEquals("PCS",c.path("unit"));call(f.manager,"PUT","/"+f.product+"/packagings",edit(f.product,Map.of("code","CASE12","label","Cycle","level","CASE","parentCode","PALLET","factor","12","unit","PCS"))).then().statusCode(400);call(f.manager,"PUT","/"+f.product+"/packagings",edit(f.product,Map.of("code","HALF","label","Fraction","level","PACK","factor","0.5","unit","PCS"))).then().statusCode(400);call(f.manager,"PUT","/"+f.second+"/packagings",edit(f.second,Map.of("code","WRONG","label","Dimension","level","CASE","factor","12","unit","L"))).then().statusCode(400);}
 @Test void scanReplayPhysicalIdsAndInvalidCodesNeverMoveStock(){long before=QuarkusTransaction.requiringNew().call(()->StockMovement.count());String code=QuarkusTransaction.requiringNew().call(()->Product.<Product>findById(f.product).ean),id=UUID.randomUUID().toString();String one=call(f.picker,"POST","/resolve",scan(code,id)).then().statusCode(200).extract().asString();assertEquals(one,call(f.picker,"POST","/resolve",scan(code,id)).then().statusCode(200).extract().asString());call(f.picker,"POST","/resolve",scan(code,UUID.randomUUID().toString())).then().statusCode(200);call(f.picker,"POST","/resolve",scan("(01)INVALID",UUID.randomUUID().toString())).then().statusCode(400);assertEquals(2L,QuarkusTransaction.requiringNew().call(()->ProductScanReceipt.count("store.id",f.store)).longValue());assertEquals(before,QuarkusTransaction.requiringNew().call(()->StockMovement.count()).longValue());}
 @Test void measurementPreviewIsExactAndRejectsWrongDimension(){var response=call(f.picker,"POST","/"+f.second+"/measure",Map.of("storeId",f.store,"measurement",Map.of("gross","400","tare","25","unit","G","origin","MANUAL"))).then().statusCode(200).extract();assertEquals("0.375",response.path("quantity"));assertNotNull(response.path("scanId"));call(f.picker,"POST","/"+f.second+"/measure",Map.of("storeId",f.store,"measurement",Map.of("net","1","unit","L","origin","MANUAL"))).then().statusCode(400);}

 @Test void revisionReturnedByANewInstructionCanBeUsedImmediatelyForTheNextEdit(){String revision=call(f.manager,"PUT","/"+f.product+"/instructions",edit(f.product,Map.of("text","Consigne immédiatement suivie d’un code"))).then().statusCode(200).extract().path("revision");call(f.manager,"PUT","/"+f.product+"/identifiers",Map.of("storeId",f.store,"expectedRevision",revision,"kind","INTERNAL","value","AFTER-NOTE")).then().statusCode(200);}

 @Test void gs1CaseAndContentCountAreConvertedOnlyOnce(){
  pack("CASE12","BASE","12");pack("PALLET480","CASE12","40");
  call(f.manager,"PUT","/"+f.product+"/identifiers",edit(f.product,Map.of("kind","GTIN","value","4006381333931","packaging","CASE12"))).then().statusCode(200);
  var carton=call(f.picker,"POST","/resolve",scan("(01)04006381333931",UUID.randomUUID().toString())).then().statusCode(200).extract();assertEquals("12",carton.path("quantity"));assertEquals("CASE12",carton.path("packaging"));
  var counted=call(f.picker,"POST","/resolve",scan("(02)04006381333931(37)24",UUID.randomUUID().toString())).then().statusCode(200).extract();assertEquals("24",counted.path("quantity"));
  var palette=call(f.picker,"POST","/"+f.product+"/convert",Map.of("storeId",f.store,"packaging","PALLET480","quantity","2")).then().statusCode(200).extract();assertEquals("960",palette.path("quantity"));
 }

 @Test void upcAndSupplierPackagingAmbiguityRemainStoreScopedAndExplicit(){
  pack("CASE12","BASE","12");
  call(f.manager,"PUT","/"+f.product+"/identifiers",edit(f.product,Map.of("kind","GTIN","value","036000291452"))).then().statusCode(200);
  var upc=call(f.picker,"POST","/resolve",scan("]E0036000291452",UUID.randomUUID().toString())).then().statusCode(200).extract();assertEquals(f.product.longValue(),upc.jsonPath().getLong("product.id"));
  for(String context:List.of("UNIT-SUPPLIER","CASE-SUPPLIER"))call(f.manager,"PUT","/"+f.product+"/identifiers",edit(f.product,Map.of("kind","SUPPLIER","value","SAME-SKU","context",context,"packaging",context.startsWith("UNIT")?"BASE":"CASE12"))).then().statusCode(200);
  var request=new HashMap<String,Object>(scan("SAME-SKU",UUID.randomUUID().toString()));request.put("productId",f.product);
  var response=call(f.picker,"POST","/resolve",request).then().statusCode(200).extract();assertEquals("AMBIGUOUS",response.path("state"));
  var choices=response.jsonPath().<Map<String,Object>>getList("choices");var carton=choices.stream().filter(x->x.get("packaging").equals("CASE12")).findFirst().orElseThrow();request.put("identifierId",carton.get("identifierId"));
  var selected=call(f.picker,"POST","/resolve",request).then().statusCode(200).extract();assertEquals("12",selected.path("quantity"));assertEquals("CASE12",selected.path("packaging"));
  request.put("storeId",f.other);call(f.picker,"POST","/resolve",request).then().statusCode(403);
 }

 @Test void nameLookupReturnsRealVariantFactsWithoutMovingStockAndRespectsStoreScope(){
  QuarkusTransaction.requiringNew().run(()->{var p=Product.<Product>findById(f.product);p.brand="Marque test";p.characteristics="Taille XL · bleu · version 2026 · qualité coton";p.description="Description réelle";});
  long stockBefore=QuarkusTransaction.requiringNew().call(()->StockMovement.count());
  var result=given().auth().oauth2(f.picker).queryParam("storeId",f.store).queryParam("ids",f.product).get("/replenishments/catalog/products").then().statusCode(200).extract();
  assertEquals("Taille XL · bleu · version 2026 · qualité coton",result.path("items[0].characteristics"));assertEquals("Marque test",result.path("items[0].brand"));assertTrue(result.jsonPath().getBoolean("items[0].active"));
  var search=given().auth().oauth2(f.picker).queryParam("storeId",f.store).queryParam("q","Produit").get("/replenishments/catalog/products").then().statusCode(200).extract();assertFalse(search.jsonPath().getList("items").isEmpty());
  call(f.picker,"GET","/"+f.product+"?storeId="+f.store,null).then().statusCode(200);
  given().auth().oauth2(f.picker).queryParam("storeId",f.other).queryParam("q","Produit").get("/replenishments/catalog/products").then().statusCode(403);
  String alias="UNREADABLE-"+f.product;
  call(f.manager,"PUT","/"+f.product+"/identifiers",edit(f.product,Map.of("kind","INTERNAL","value",alias))).then().statusCode(200);
  var byReference=given().auth().oauth2(f.picker).queryParam("storeId",f.store).queryParam("q",alias).get("/replenishments/catalog/products").then().statusCode(200).extract();assertEquals(f.product.longValue(),byReference.jsonPath().getLong("items[0].id"));
  assertEquals(stockBefore,QuarkusTransaction.requiringNew().call(()->StockMovement.count()).longValue());
 }

}
