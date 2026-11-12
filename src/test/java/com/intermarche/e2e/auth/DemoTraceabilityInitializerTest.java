package com.intermarche.e2e.auth;
import com.intermarche.backoffice.auth.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.inventory.*;
import com.intermarche.backoffice.domain.wms.handling.InventoryOrder;
import com.intermarche.backoffice.domain.store.*;
import io.quarkus.test.TestTransaction;import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
@io.quarkus.test.junit.TestProfile(DemoTraceabilityInitializerTest.Isolated.class)
@QuarkusTest @TestTransaction
class DemoTraceabilityInitializerTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:isolated_DemoTraceabilityInitializerTest;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}}

 @Inject DemoDataInitializer base;@Inject DemoTraceabilityInitializer demo;
 Object totals(){return StockMovement.getEntityManager().createQuery("select m.account.id,m.location.id,sum(m.quantity) from StockMovement m group by m.account.id,m.location.id order by m.account.id,m.location.id",Object[].class).getResultList().stream().map(Arrays::asList).toList();}
 @Test void demoAddsTraceCasesAndABlindInventoryWithoutAddingStockOrRepeatingThem(){base.seedDemoData();Object before=totals();demo.seed();assertEquals(before,totals());assertEquals(3,ManufacturerLot.count("code like 'DEMO-TRACE-%'"));assertEquals(1,QualityCase.count("createdBy","DEMO"));var order=InventoryOrder.<InventoryOrder>find("code","INV-DEMO-01").firstResult();assertNotNull(order);assertTrue(order.blind);assertTrue(InventoryTarget.count("handlingOrder",order)>0);long targets=InventoryTarget.count();long movements=StockMovement.count();demo.seed();assertEquals(movements,StockMovement.count());assertEquals(targets,InventoryTarget.count());assertEquals(before,totals());}
}
