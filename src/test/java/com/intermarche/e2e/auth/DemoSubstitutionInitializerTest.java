package com.intermarche.e2e.auth;
import com.intermarche.backoffice.auth.DemoDataInitializer;
import com.intermarche.backoffice.domain.executing.ProductSubstitution;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.stock.StockMovement;
import com.intermarche.backoffice.engine.executing.PickingResolutionService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
@io.quarkus.test.junit.TestProfile(DemoSubstitutionInitializerTest.Isolated.class)
@QuarkusTest @TestTransaction
class DemoSubstitutionInitializerTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:isolated_DemoSubstitutionInitializerTest;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}}

 @Inject DemoDataInitializer seed;
 @Inject jakarta.persistence.EntityManager em;
 @Inject PickingResolutionService resolutions;
 @Test void everyDemoProductHasAPickableCompatibleReplacementAndReseedingIsIncremental(){
  seed.seedDemoData();Store store=Store.findByCode("LYO-042");
  var products=Product.<Product>list("active=true");assertTrue(products.size()>=116);
  for(Product product:products){assertNotNull(product.characteristics,product.name);assertTrue(product.characteristics.length()>255,product.name);var options=ProductSubstitution.<ProductSubstitution>list("store=?1 and requested=?2 and active=true",store,product);assertFalse(options.isEmpty(),product.name);for(var option:options){assertDoesNotThrow(()->resolutions.replacement(store,product,option.replacement.id));assertEquals(product.unitName,option.replacement.unitName);assertTrue(store.buildings.stream().anyMatch(b->b.locations.stream().anyMatch(l->l.product!=null && l.product.id.equals(option.replacement.id))),option.replacement.name);}}
  long rules=ProductSubstitution.count(),count=Product.count(),movements=StockMovement.count();
  var edited=ProductSubstitution.<ProductSubstitution>find("store",store).firstResult();edited.active=false;
  Product milk=Product.findByEan("3274080005003");String customized="Caractéristiques saisies par le magasin. "+"Texte libre riche sans liste de champs imposés. ".repeat(20);milk.characteristics=customized;em.flush();em.clear();
  seed.seedDemoData();assertEquals(customized,Product.findByEan("3274080005003").characteristics);assertFalse(edited.active);assertEquals(rules,ProductSubstitution.count());assertEquals(count,Product.count());assertEquals(movements,StockMovement.count());
 }

 @Test void productsWithoutCharacteristicsHaveAnExplicitSemanticStatus(){
  seed.seedDemoData();Product milk=Product.findByEan("3274080005003");milk.characteristics=null;
  var result=resolutions.similar(milk,"",0);
  assertEquals("MISSING_CHARACTERISTICS",result.get("state"));assertTrue(((java.util.List<?>)result.get("items")).isEmpty());
 }
}
