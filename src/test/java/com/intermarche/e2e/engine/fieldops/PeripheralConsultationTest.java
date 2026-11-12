package com.intermarche.e2e.engine.fieldops;
import com.intermarche.backoffice.domain.printing.PrintJob;
import com.intermarche.backoffice.domain.stock.StockSegment;
import com.intermarche.backoffice.domain.store.BaseLocation;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
@QuarkusTest @jakarta.enterprise.inject.Typed(PeripheralConsultationTest.class)
class PeripheralConsultationTest extends PrintingWorkflowTest {
 @Test void printerReconnectProbeNeverCreatesOrResendsAPrintJob()throws Exception{
  var simulation=printer("SUCCESS");given().auth().oauth2(f.picker()).get("/printing/printers/"+simulation.get("id")+"/probe?storeId="+f.store()).then().statusCode(200).body("state",org.hamcrest.Matchers.is("SIMULATED"));
  try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var executor=Executors.newSingleThreadExecutor()){
   var payload=executor.submit(()->{try(var socket=server.accept()){return socket.getInputStream().readAllBytes();}});
   var printer=post(f.manager(),"/printers",Map.of("storeId",f.store(),"requestKey",key(),"code","TCP-TEST","label","Imprimante réseau","transport","TCP_ZPL","host","127.0.0.1","port",server.getLocalPort()));
   given().auth().oauth2(f.picker()).get("/printing/printers/"+printer.get("id")+"/probe?storeId="+f.store()).then().statusCode(200).body("state",org.hamcrest.Matchers.is("REACHABLE"));assertEquals(0,payload.get(4,TimeUnit.SECONDS).length);
   given().auth().oauth2(f.picker()).get("/printing/printers/"+printer.get("id")+"/probe?storeId="+f.other()).then().statusCode(403);
  }
  QuarkusTransaction.requiringNew().run(()->assertEquals(0,PrintJob.count("store.id",f.store())));
 }
 @Test void stockConsultationDoesNotSilentlyHideSegmentsAfterFiveHundred(){
  QuarkusTransaction.requiringNew().run(()->{var account=stocks.account(f.store(),f.product());for(int n=0;n<501;n++){var segment=new StockSegment();segment.account=account;segment.location=BaseLocation.findById(f.location());segment.quality="AVAILABLE";segment.segmentKey="consultation-"+n;segment.persist();}});
  var read=call(f.picker(),"GET","/products/"+f.product()+"/stock-segments?storeId="+f.store(),null).then().statusCode(200).extract();assertEquals(501,read.jsonPath().getList("$").size());call(f.picker(),"GET","/products/"+f.product()+"/stock-segments?storeId="+f.other(),null).then().statusCode(403);
  var projection=given().auth().oauth2(f.picker()).get("/handling-orders/operator/consultation/products/"+f.product()+"?storeId="+f.store()).then().statusCode(200).extract();assertEquals("StoreBuilding",projection.path("addresses[0].buildingType"));assertEquals(false,projection.path("quantityKnown"));
 }
}
