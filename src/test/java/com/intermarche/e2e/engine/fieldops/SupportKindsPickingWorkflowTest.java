package com.intermarche.e2e.engine.fieldops;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.executing.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@QuarkusTest @jakarta.enterprise.inject.Typed(SupportKindsPickingWorkflowTest.class)
class SupportKindsPickingWorkflowTest extends SupportTraceabilityTest {
 @ParameterizedTest @ValueSource(strings={"CARTON","PALLET"})
 void physicalSupportsCanBePickedAssignedToCartDepositedAndUnassigned(String kind){
  line();String code=kind+"-"+f.store();long child=support(kind,code);long cart=support("CART","CART-"+f.store());
  attach(code);var pick=command("PICK");pick.putAll(Map.of("itemId",f.item(),"quantity",2,"toteCode",code));command(pick).then().statusCode(200);
  var initial=call(f.manager(),"GET","/supports/"+cart+"/layout?storeId="+f.store(),null).then().statusCode(200).extract();
  var layout=call(f.manager(),"PUT","/supports/layout",Map.of("storeId",f.store(),"cartId",cart,"rows",1,"columns",1,"expectedRevision",initial.<String>path("revision"))).then().statusCode(200).extract();
  var packet=Map.of("storeId",f.store(),"compartmentId",layout.jsonPath().getLong("compartments[0].id"),"supportId",child,"orderId",f.order(),"requestKey",key(),"expectedRevision",layout.<String>path("compartments[0].revision"));
  call(f.picker(),"POST","/supports/compartments",packet).then().statusCode(200);call(f.picker(),"POST","/supports/compartments",packet).then().statusCode(200);
  var mission=call(f.picker(),"GET","/orders/"+f.order()+"/supports?storeId="+f.store(),null).then().statusCode(200).extract();
  assertEquals(code,mission.path("compartments[0].supportCode"));assertEquals("CART-"+f.store(),mission.path("compartments[0].cartCode"));
  long buffer=QuarkusTransaction.requiringNew().call(()->{var b=new Buffer();b.building=Building.findById(f.building());b.bufferCode="SUP-"+f.store();b.ambientCapacity=20;b.active=true;b.persistAndFlush();return b.id;});
  var deposit=command("DEPOSIT_TOTE");deposit.putAll(Map.of("toteCode",code,"bufferId",buffer));command(deposit).then().statusCode(200);
  QuarkusTransaction.requiringNew().run(()->{var load=LoadedTote.<LoadedTote>find("tote.code=?1 and status=?2",code,LoadedTote.Status.IN_USE).firstResult();assertEquals(buffer,load.buffer.id.longValue());assertFalse(load.items.isEmpty());});
  var current=call(f.picker(),"GET","/supports/"+cart+"/layout?storeId="+f.store(),null).then().statusCode(200).extract();
  var clear=new LinkedHashMap<String,Object>();clear.put("storeId",f.store());clear.put("compartmentId",current.jsonPath().getLong("compartments[0].id"));clear.put("supportId",null);clear.put("requestKey",key());clear.put("expectedRevision",current.path("compartments[0].revision"));
  call(f.picker(),"POST","/supports/compartments",clear).then().statusCode(200);
 }
}
