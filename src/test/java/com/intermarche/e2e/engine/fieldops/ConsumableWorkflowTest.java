package com.intermarche.e2e.engine.fieldops;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.store.Tote;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@QuarkusTest @jakarta.enterprise.inject.Typed(ConsumableWorkflowTest.class)
class ConsumableWorkflowTest extends SupportTraceabilityTest {
 long type(String kind){return call(f.manager(),"PUT","/support-types",Map.of("storeId",f.store(),"code","PACK-"+f.store(),"label","Emballage","kind",kind,"allowedZones","AMBIENT","maxVolumeLiters",60)).then().statusCode(200).extract().jsonPath().getLong("id");}
 Map<String,Object> receipt(long type,int q){return Map.of("storeId",f.store(),"typeId",type,"quantity",q,"reason","Livraison fournisseur","requestKey",key());}
 @Test void receivesOnceAppliesThresholdsAndConsumesOnceAtUse(){
  long type=type("CARTON");var delivery=receipt(type,3);
  call(f.manager(),"POST","/consumables/receive",delivery).then().statusCode(200);call(f.manager(),"POST","/consumables/receive",delivery).then().statusCode(200);
  call(f.manager(),"POST","/consumables/settings",Map.of("storeId",f.store(),"typeId",type,"threshold",2,"target",10,"requestKey",key())).then().statusCode(200);
  var use=Map.of("storeId",f.store(),"typeId",type,"quantity",2,"reason","Consommation hors colis","requestKey",key());
  call(f.picker(),"POST","/consumables/loss",use).then().statusCode(200);call(f.picker(),"POST","/consumables/loss",use).then().statusCode(200);

  QuarkusTransaction.requiringNew().run(()->{var t=SupportType.<SupportType>findById(type);assertEquals(1,t.stockQuantity);assertEquals(2,PackagingMovement.count("type",t));assertEquals(0,PhysicalSupport.count("type",t));});
  var list=call(f.manager(),"GET","/consumables?storeId="+f.store(),null).then().statusCode(200).extract().jsonPath();assertEquals(true,list.getBoolean("[0].low"));assertEquals(9,list.getInt("[0].suggestedQuantity"));
  assertEquals(2,call(f.manager(),"GET","/consumables/"+type+"/movements?storeId="+f.store(),null).then().statusCode(200).extract().jsonPath().getList("$").size());
 }
 @Test void legacyPalletConsumesAtFirstLoadAndCannotBeReused(){
  line();String code="PAL-"+f.store();support("PALLET",code);attach(code);
  QuarkusTransaction.requiringNew().run(()->{var s=PhysicalSupport.<PhysicalSupport>find("code",code).firstResult();assertEquals(9,s.type.stockQuantity);var load=LoadedTote.<LoadedTote>find("tote",s.tote).firstResult();load.status=LoadedTote.Status.DELIVERED;});
  QuarkusTransaction.requiringNew().run(()->assertThrows(jakarta.ws.rs.BadRequestException.class,()->LoadedTote.useAnEmptyTote(Tote.findByCode(code))));
 }
 @Test void blocksNegativeStockAndRestrictsManagement(){
  long type=type("PALLET");call(f.picker(),"POST","/consumables/receive",receipt(type,2)).then().statusCode(403);
  call(f.picker(),"POST","/consumables/loss",Map.of("storeId",f.store(),"typeId",type,"quantity",1,"reason","Utilisation","requestKey",key())).then().statusCode(400);
  call(f.manager(),"POST","/consumables/receive",receipt(type,2)).then().statusCode(200);
  call(f.manager(),"POST","/consumables/receive",Map.of("storeId",f.store(),"typeId",type,"quantity",1.5,"reason","Pas de fraction de carton","requestKey",key())).then().statusCode(400);
  call(f.manager(),"POST","/consumables/adjust",receipt(type,-3)).then().statusCode(400);
  call(f.manager(),"POST","/consumables/adjust",receipt(type,-1)).then().statusCode(200);
  QuarkusTransaction.requiringNew().run(()->assertEquals(1,SupportType.<SupportType>findById(type).stockQuantity));
  call(f.manager(),"POST","/consumables/settings",Map.of("storeId",f.store(),"typeId",type,"threshold",10,"target",5,"requestKey",key())).then().statusCode(400);
 }
 @Test void concurrentClaimsCannotSpendTheLastUnitTwice()throws Exception{
  long type=type("CARTON");call(f.manager(),"POST","/consumables/receive",receipt(type,1)).then().statusCode(200);
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);
  try{var one=pool.submit(()->{gate.await();return call(f.picker(),"POST","/consumables/loss",Map.of("storeId",f.store(),"typeId",type,"quantity",1,"reason","Utilisation A","requestKey",key())).statusCode();});var two=pool.submit(()->{gate.await();return call(f.picker(),"POST","/consumables/loss",Map.of("storeId",f.store(),"typeId",type,"quantity",1,"reason","Utilisation B","requestKey",key())).statusCode();});gate.countDown();assertEquals(List.of(200,400),java.util.stream.Stream.of(one.get(),two.get()).sorted().toList());}finally{pool.shutdownNow();}
  QuarkusTransaction.requiringNew().run(()->{assertEquals(0,SupportType.<SupportType>findById(type).stockQuantity);assertEquals(0,PhysicalSupport.count("type.id",type));});
 }
 @Test void isolatesStoresAndSeparatesReusableSupports(){
  support("CARTON","BOX-"+f.store());support("CART","CART-"+f.store());
  var list=call(f.manager(),"GET","/supports?storeId="+f.store()+"&kind=REUSABLE",null).then().statusCode(200).extract().jsonPath().getList("items.type.kind",String.class);assertFalse(list.contains("CARTON"));assertTrue(list.contains("CART"));
  call(f.picker(),"GET","/consumables?storeId="+f.other(),null).then().statusCode(403);
 }
}
