package com.intermarche.e2e.engine.fieldops;

import com.intermarche.backoffice.domain.wms.handling.PackingOrder;
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@jakarta.enterprise.inject.Typed(MixedOrderDirectivesTest.class)
@TestProfile(MixedOrderDirectivesTest.Isolated.class)
class MixedOrderDirectivesTest extends MissionCoordinationTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {
  public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:mixed_order_directives;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}
 }
 @Test void mixedOrderDetailMapsItsPreparationSourcesToDirectiveLines(){
  Long line=line();
  Long order=QuarkusTransaction.requiringNew().call(()->{
   var original=ProductCollectingOrder.<ProductCollectingOrder>findById(f.order());
   var mixed=new PackingOrder();mixed.store=original.store;mixed.assignee=original.assignee;
   mixed.code="MIX-DIRECTIVE";mixed.status=original.status;mixed.lots.addAll(original.lots);mixed.persist();return mixed.id;
  });
  var result=given().auth().oauth2(f.picker()).get("/handling-orders/operator/orders/"+order+"?storeId="+f.store()).then().statusCode(200).extract();
  Map<String,Object> aliases=result.jsonPath().getMap("directiveSourceLines");
  assertEquals(line,((Number)aliases.get(line.toString())).longValue());
 }
}
