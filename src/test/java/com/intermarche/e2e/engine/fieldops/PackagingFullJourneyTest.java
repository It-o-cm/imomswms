package com.intermarche.e2e.engine.fieldops;

import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.printing.*;
import com.intermarche.backoffice.engine.printing.PrinterTransport;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;

/** Real HTTP workflow, real print queue and simulated printer; never touches a physical printer. */
@QuarkusTest @jakarta.enterprise.inject.Typed(PackagingFullJourneyTest.class)
class PackagingFullJourneyTest extends PrintingWorkflowTest {
 @Inject PrinterTransport transport;
 long stockOf(long type){return QuarkusTransaction.requiringNew().call(()->SupportType.<SupportType>findById(type).stockQuantity);}
 Map<String,Object> closeParcel(Map<String,Object> p,long type){var body=input(p);body.putAll(Map.of("consumableTypeId",type,"contentsConfirmed",true));var result=parcel(f.picker(),"/close",body).then().statusCode(200).extract().jsonPath().<String,Object>getMap("$");parcel(f.picker(),"/close",body).then().statusCode(200);return result;}
 /**
  * Claims the given print job from the queue and checks that the simulated printer receives its exact payload.
  *
  * @param job the job returned by the API
  * @throws Exception if the transmission fails
  */
 void transmit(Map<String,Object> job)throws Exception{
  // The print queue is shared by the whole application: jobs left queued by earlier tests (a reprint never sent) are claimed first and skipped here.
  long expected=((Number)job.get("id")).longValue();var work=queue.claim();for(int skipped=0;work!=null&&work.id()!=expected&&skipped<50;skipped++)work=queue.claim();assertNotNull(work);assertEquals(expected,work.id());assertEquals("SIMULATOR",work.destination().transport());String encoded="_"+java.util.HexFormat.ofDelimiter("_").withUpperCase().formatHex(job.get("code").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));assertTrue(work.payload().contains(encoded));assertTrue(work.payload().contains("^GFA"));assertTrue(work.payload().startsWith("^XA"));assertTrue(work.payload().endsWith("^XZ"));transport.send(work.destination(),work.payload());queue.complete(work,null,false);given().auth().oauth2(f.picker()).get("/printing/jobs/"+work.id()+"?storeId="+f.store()).then().statusCode(200).body("state",org.hamcrest.Matchers.is("SENT")).body("simulated",org.hamcrest.Matchers.is(true)).body("attemptCount",org.hamcrest.Matchers.is(1));}
 @ParameterizedTest @ValueSource(strings={"CARTON","PALLET"})
 void completesCreationContentConsumptionPrintingAndReopening(String kind)throws Exception{
  var initial=prepared();parcel(f.picker(),"/cancel",input(initial)).then().statusCode(200);
  long type=call(f.manager(),"PUT","/support-types",Map.of("storeId",f.store(),"code","JOURNEY_"+kind,"label",kind+" test complet","kind",kind,"allowedZones","AMBIENT","maxVolumeLiters",kind.equals("PALLET")?1200:60)).then().statusCode(200).extract().jsonPath().getLong("id");
  call(f.manager(),"POST","/consumables/receive",Map.of("storeId",f.store(),"typeId",type,"quantity",3,"reason","Stock du parcours complet","requestKey",key())).then().statusCode(200);
  var create=Map.<String,Object>of("storeId",f.store(),"orderId",customer,"consumableTypeId",type,"requestKey",key());
  var p=parcel(f.picker(),"",create).then().statusCode(200).body("packagingType",org.hamcrest.Matchers.is(kind)).extract().jsonPath().<String,Object>getMap("$");
  parcel(f.picker(),"",create).then().statusCode(200).body("id",org.hamcrest.Matchers.is(((Number)p.get("id")).intValue()));
  String code=(String)p.get("code");assertNotNull(code);assertFalse(code.isBlank());assertEquals(3,stockOf(type));
  var content=input(p);content.putAll(Map.of("packagingType",kind,"weightKg",1.25,"contents",List.of(Map.of("itemId",f.item(),"quantity",2))));
  p=parcel(f.picker(),"/content",content).then().statusCode(200).body("contents.size()",org.hamcrest.Matchers.is(1)).extract().jsonPath().getMap("$");
  assertEquals(3,stockOf(type));assertEquals(0,new BigDecimal("2").compareTo(new BigDecimal(((List<Map<String,Object>>)p.get("contents")).getFirst().get("quantity").toString())));
  p=closeParcel(p,type);assertEquals("CLOSED",p.get("state"));assertEquals(2,stockOf(type));String support=(String)p.get("packagingSupportCode");assertNotNull(support);
  var printer=printer("SUCCESS");var printBody=Map.<String,Object>of("storeId",f.store(),"printerId",printer.get("id"),"subjectType","PARCEL","subjectId",p.get("id"),"requestKey",key());
  var job=post(f.picker(),"/jobs",printBody);assertEquals(code,job.get("code"));assertTrue(job.get("preview").toString().contains("<svg"));assertTrue(job.get("preview").toString().contains(code));transmit(job);
  assertEquals(job.get("id"),post(f.picker(),"/jobs",printBody).get("id"));assertEquals(2,stockOf(type));
  var reopen=input(p);reopen.put("reason","Contrôle du contenu après fermeture");p=parcel(f.manager(),"/reopen",reopen).then().statusCode(200).body("state",org.hamcrest.Matchers.is("OPEN")).extract().jsonPath().getMap("$");assertEquals(2,stockOf(type));
  p=closeParcel(p,type);assertEquals(code,p.get("code"));assertEquals(support,p.get("packagingSupportCode"));assertEquals(2,stockOf(type));
  var reprint=post(f.picker(),"/jobs",Map.of("storeId",f.store(),"printerId",printer.get("id"),"jobId",job.get("id"),"reason","Étiquette abîmée après vérification","requestKey",key()));assertEquals(code,reprint.get("code"));assertEquals(job.get("payloadSha256"),reprint.get("payloadSha256"));transmit(reprint);
  assertEquals(2,stockOf(type));QuarkusTransaction.requiringNew().run(()->{assertEquals(1,PhysicalSupport.count("type.id",type));var movements=PackagingMovement.<PackagingMovement>list("type.id=?1 and quantity<0",type);assertEquals(1,movements.size());assertEquals(-1,movements.getFirst().quantity);eq(20,stocks.availability(physical.account(),null,com.intermarche.backoffice.domain.util.DateTimeProvider.now()).physical);});
 }
}
