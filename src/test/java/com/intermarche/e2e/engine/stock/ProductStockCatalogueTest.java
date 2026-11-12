package com.intermarche.e2e.engine.stock;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.stock.StockAccount;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.graphql.stock.TemporalStockResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@io.quarkus.test.junit.TestProfile(ProductStockCatalogueTest.Isolated.class)
@QuarkusTest @TestTransaction @TestSecurity(user="catalogue-test",roles="SERVICE_CALLER")
class ProductStockCatalogueTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:unknown_catalogue_40k;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false","quarkus.hibernate-orm.statistics","true");}}

 @Inject TemporalStockResource api;
 @Inject EntityManager em;
 TemporalStockResource.StockPage page(String store,String search,String state,Long after,int limit){return api.stockPage(store,null,search,state,null,null,null,null,null,null,null,null,false,24,after,limit);}
 @Test void fortyThousandProductsPageAndOpenWithoutCreatingAccounts() throws Exception {
  Store store=new Store();store.code="CAT40K";store.name="Catalogue 40k";store.persistAndFlush();
  com.intermarche.e2e.domain.util.DomainUtils.grantStore(store,"catalogue-test");
  long before=StockAccount.count();
  em.createNativeQuery("insert into products(id,version,created_at,updated_at,ean,name,productType,temperature_zone,is_active,characteristics) select next value for products_SEQ,0,current_timestamp,current_timestamp,'T10'||lpad(cast(x as varchar),8,'0'),'CAT40K'||lpad(cast(x as varchar),5,'0'),'UNIT','AMBIENT',true,'Produit sans relevé de stock' from system_range(1,40000)").executeUpdate();
  em.clear();Long cursor=null;int found=0;java.util.Set<Long> seen=new java.util.HashSet<>();
  do {var p=page("CAT40K","CAT40K","ALL",cursor,50);assertTrue(p.items.size()<=50);assertTrue(p.scanned<=200);for(var row:p.items){assertTrue(seen.add(row.productId));assertFalse(row.stockKnown);assertNull(row.accountId);assertNull(row.physical);assertEquals("UNKNOWN",row.availabilityStatus);assertNotNull(row.product.characteristics);}found+=p.items.size();cursor=p.hasMore?p.nextCursor:null;em.clear();}while(cursor!=null);
  assertEquals(40000,found);assertEquals(before,StockAccount.count());
  var one=page("CAT40K","CAT40K39999","ALL",null,30);assertEquals(1,one.items.size());assertEquals("CAT40K39999",one.items.getFirst().product.name);
  Product product=Product.find("name","CAT40K00001").firstResult();StockAccount known=new StockAccount();known.store=Store.findByCode("CAT40K");known.product=product;known.persistAndFlush();
  var zero=page("CAT40K","CAT40K00001","ALL",null,30).items.getFirst();assertTrue(zero.stockKnown);assertEquals(known.id,zero.accountId);assertEquals("UNAVAILABLE",zero.availabilityStatus);assertEquals(0,zero.physical.signum());
  assertTrue(page("CAT40K","CAT40K00001","UNKNOWN",null,30).items.isEmpty());
  var stats=em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
  var queries=java.util.Arrays.stream(stats.getQueries()).filter(q->q.stripLeading().toLowerCase().startsWith("select")).map(q->java.util.Map.of("query",q,"maxMs",stats.getQueryStatistics(q).getExecutionMaxTime(),"executions",stats.getQueryStatistics(q).getExecutionCount())).toList();
  java.nio.file.Files.writeString(java.nio.file.Path.of("target/t59-unknown-catalogue-performance.json"),new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(java.util.Map.of("products",40000,"continuousPages",800,"persistenceContext","Cleared between pages, matching independent HTTP requests; fixture transaction rolls back at test end","queries",queries)));

 }
}
