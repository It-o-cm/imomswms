package com.intermarche.e2e.engine.fieldops;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.store.Tote;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@QuarkusTest @jakarta.enterprise.inject.Typed(SupportBatchWorkflowTest.class)
class SupportBatchWorkflowTest extends FieldOperationsTest {
 @Test void previewCreatesNothingAndBatchIsAtomicAndReplaySafe(){
  long type=call(f.manager(),"PUT","/support-types",Map.of("storeId",f.store(),"code","BATCH_CART","label","Chariot série","kind","CART","allowedZones","AMBIENT","maxVolumeLiters",240)).then().statusCode(200).extract().jsonPath().getLong("id");
  var body=new LinkedHashMap<String,Object>(Map.of("storeId",f.store(),"typeId",type,"pattern","SER-{001}","start",7,"count",3,"rows",2,"columns",3,"requestKey",key()));
  var preview=call(f.manager(),"POST","/supports/batch/preview",body).then().statusCode(200).extract().jsonPath();assertEquals(List.of("SER-007","SER-008","SER-009"),preview.getList("codes"));
  QuarkusTransaction.requiringNew().run(()->assertEquals(0,PhysicalSupport.count("store.id",f.store())));
  call(f.picker(),"POST","/supports/batch",body).then().statusCode(403);
  call(f.manager(),"POST","/supports/batch",body).then().statusCode(200);call(f.manager(),"POST","/supports/batch",body).then().statusCode(200);
  QuarkusTransaction.requiringNew().run(()->{assertEquals(3,PhysicalSupport.count("store.id",f.store()));assertEquals(18,CartCompartment.count("cart.store.id",f.store()));});
  body.put("start",8);body.put("requestKey",key());call(f.manager(),"POST","/supports/batch/preview",body).then().statusCode(200).body("canCreate",org.hamcrest.Matchers.is(false));call(f.manager(),"POST","/supports/batch",body).then().statusCode(409);
  QuarkusTransaction.requiringNew().run(()->assertNull(Tote.findByCode("SER-010")));
  body.put("pattern","INVALID");call(f.manager(),"POST","/supports/batch/preview",body).then().statusCode(400);
 }
}
