package com.intermarche.backoffice.engine.semantic;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@QuarkusTest @TestProfile(LocalEmbeddingProfile.class)
@EnabledIfSystemProperty(named="imomswms.embeddings.integration",matches="true")
class ProductSemanticIndexTest {
 @Inject ProductSemanticIndex index;
 private final List<Long> ids=new ArrayList<>();
 private Product create(String name,String text,TemperatureZone zone){return QuarkusTransaction.requiringNew().call(()->{var p=new Product();p.name=name;p.ean="989"+String.format("%010d",ids.size());p.characteristics=text;p.productType=ProductType.UNIT;p.temperatureZone=zone;p.persistAndFlush();ids.add(p.id);return p;});}
 @AfterEach void clean(){QuarkusTransaction.requiringNew().run(()->{for(Long id:ids){ProductEmbedding.delete("product.id",id);Product.deleteById(id);}});index.refreshNow();}
 @Test void realVectorsArePersistedThresholdedPagedAndUpdatedWithoutMixingTemperatures(){
  Product source=create("Lait source","Lait de vache demi-écrémé, bouteille de 1 litre.",TemperatureZone.CHILLED);
  Product milk=create("Autre marque de lait","Lait de vache demi-écrémé, bouteille de 1 litre.",TemperatureZone.CHILLED);
  Product juice=create("Jus d’orange","Jus d'orange pressée à boire au petit déjeuner, bouteille de 1 litre.",TemperatureZone.CHILLED);
  Product wrongZone=create("Lait ambiant",source.characteristics,TemperatureZone.AMBIENT);
  Product missing=create("Sans description",null,TemperatureZone.CHILLED);
  for(int i=0;i<42;i++)create("Lait "+i,source.characteristics,TemperatureZone.CHILLED);
  index.refreshNow();assertEquals("READY",index.status().get("state"),index.status().toString());
  var first=index.similar(source,"",2,0);var second=index.similar(source,"",2,1);
  assertEquals(40,first.hits().size());assertEquals(44,first.total());assertEquals(4,second.hits().size());assertTrue(first.hits().stream().noneMatch(a->second.hits().stream().anyMatch(b->a.id().equals(b.id()))));
  var exact=index.similar(source,"",0.0001,0);assertEquals(43,exact.total());assertTrue(exact.hits().stream().noneMatch(h->h.id().equals(juice.id)||h.id().equals(wrongZone.id)||h.id().equals(missing.id)));
  String before=QuarkusTransaction.requiringNew().call(()->ProductEmbedding.<ProductEmbedding>find("product.id",milk.id).firstResult().textHash);
  index.refreshNow();assertEquals(before,QuarkusTransaction.requiringNew().call(()->ProductEmbedding.<ProductEmbedding>find("product.id",milk.id).firstResult().textHash));
  QuarkusTransaction.requiringNew().run(()->Product.<Product>findById(milk.id).characteristics="Saumon fumé en tranches pour les entrées et les tartines.");index.refreshNow();
  assertNotEquals(before,QuarkusTransaction.requiringNew().call(()->ProductEmbedding.<ProductEmbedding>find("product.id",milk.id).firstResult().textHash));assertEquals(42,index.similar(source,"",0.0001,0).total());
  assertThrows(IllegalArgumentException.class,()->index.similar(source,"",Double.NaN,0));
 }
}
