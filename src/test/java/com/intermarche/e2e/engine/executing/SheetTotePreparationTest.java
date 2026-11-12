package com.intermarche.e2e.engine.executing;
import com.intermarche.backoffice.auth.DemoDataInitializer;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.engine.executing.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
@io.quarkus.test.junit.TestProfile(SheetTotePreparationTest.Isolated.class)
@QuarkusTest @TestTransaction
class SheetTotePreparationTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:isolated_SheetTotePreparationTest;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}}

 @Inject DemoDataInitializer seed;
 @Inject SheetTotePreparation service;
 @Test void estimatesSeparateZonesAndSkipsAlreadyLoadedSources(){
  seed.seedDemoData();Store store=Store.find("code","LYO-042").firstResult();
  Product p=Product.find("ean","3274080005003").firstResult();
  p.productType=ProductType.UNIT;p.referenceVolume=BigDecimal.ONE;
  var step=new PickingPlanService.RouteStepDTO();step.productId=p.id;step.temperatureZone=TemperatureZone.CHILLED;step.quantity=new BigDecimal("61");
  var loaded=new PickingPlanService.RouteStepDTO();loaded.productId=p.id;loaded.temperatureZone=TemperatureZone.CHILLED;loaded.quantity=new BigDecimal("600");loaded.sourceToteCode="AFF-03";
  var result=service.estimate(store,List.of(step,loaded));assertEquals(1,result.size());assertEquals(2,result.get(0).count());assertEquals("INSULATED",result.get(0).type());assertTrue(result.get(0).complete());
  p.referenceVolume=null;assertNull(service.estimate(store,List.of(step)).get(0).count());
 }
 @Test void variableWeightUsesReferenceWeightAndVolume(){
  seed.seedDemoData();Store store=Store.find("code","LYO-042").firstResult();
  Product p=Product.find("ean","3274080005003").firstResult();p.productType=ProductType.WEIGHT;p.referenceWeight=new BigDecimal("0.5");p.referenceVolume=BigDecimal.ONE;
  var step=new PickingPlanService.RouteStepDTO();step.productId=p.id;step.temperatureZone=TemperatureZone.CHILLED;step.quantity=new BigDecimal("31");
  assertEquals(2,service.estimate(store,List.of(step)).get(0).count());
 }
}
