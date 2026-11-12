package com.intermarche.e2e.engine.fieldops;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.shipping.*;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.engine.fieldops.MissionClosureService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.junit.jupiter.api.Test;
import jakarta.inject.Inject;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@QuarkusTest @jakarta.enterprise.inject.Typed(MissionClosureTest.class)
class MissionClosureTest extends FieldOperationsTest {
 @Inject MissionClosureService closure;
 @Test void closureListsExplicitAnomalyStatusesAndKeepsQuantities(){incident();picked();var read=call(f.picker(),"GET","/orders/"+f.order()+"/closure?storeId="+f.store(),null).then().statusCode(200).extract();assertEquals("OPEN",read.path("anomalies[0].state"));assertEquals(2.0,read.jsonPath().getDouble("items[0].quantity"));assertNull(read.path("traceError"));assertEquals(List.of(f.tote()),read.jsonPath().getList("supports"));}
 @Test void requiredFinalScanChecksEverySupportAndDoesNotMoveAnything(){picked();QuarkusTransaction.requiringNew().run(()->{var o=BaseHandlingOrder.<BaseHandlingOrder>findById(f.order());var p=new MissionPreparationContext();p.store=o.store;p.handlingOrder=o;p.mode="PRODUCT";p.requireFinalScan=true;p.persist();});
  assertThrows(RuntimeException.class,()->QuarkusTransaction.requiringNew().run(()->closure.requireFinal(BaseHandlingOrder.findById(f.order()),List.of("OTHER"))));QuarkusTransaction.requiringNew().run(()->{var o=BaseHandlingOrder.<BaseHandlingOrder>findById(f.order());closure.requireFinal(o,List.of(f.tote()));assertEquals(BaseHandlingOrder.HandlingOrderStatus.PROCESSING,o.status);});
  call(f.picker(),"GET","/orders/"+f.order()+"/closure?storeId="+f.store(),null).then().statusCode(200).body("requireScan",org.hamcrest.Matchers.is(true));
 }
 @Test void finalControlDetectsNewTraceRequirements(){picked();QuarkusTransaction.requiringNew().run(()->{var p=new TraceabilityPolicy();p.store=Store.findById(f.store());p.product=Product.findById(f.product());p.context="PICKING";p.lotRequired=true;p.persist();});
  call(f.picker(),"GET","/orders/"+f.order()+"/closure?storeId="+f.store(),null).then().statusCode(200).body("traceError",org.hamcrest.Matchers.notNullValue());
  assertThrows(RuntimeException.class,()->QuarkusTransaction.requiringNew().run(()->closure.requireFinal(BaseHandlingOrder.findById(f.order()),List.of())));
 }
 @Test void anOpenCartonOrPalletMustBeClosedBeforeTheMission(){picked();QuarkusTransaction.requiringNew().run(()->{var p=new Parcel();p.store=Store.findById(f.store());var order=new Order();order.store=p.store;order.orderId="FINAL-"+f.store();order.status="PENDING";order.channel="DRIVE";order.orderDate=java.time.LocalDateTime.now();order.persist();p.order=order;p.code="PALLET-"+f.store();p.packagingType="PALLET";p.createdBy="test";p.persist();var c=new ParcelContent();c.parcel=p;c.pickedItem=PickedItem.find("loadedTote.tote.code",f.tote()).firstResult();c.quantity=c.pickedItem.quantity;c.sourceQuantity=c.quantity;c.traceSnapshot="[]";c.persist();});
  assertThrows(RuntimeException.class,()->QuarkusTransaction.requiringNew().run(()->closure.requireFinal(BaseHandlingOrder.findById(f.order()),List.of())));QuarkusTransaction.requiringNew().run(()->{var o=BaseHandlingOrder.<BaseHandlingOrder>findById(f.order());Parcel.<Parcel>find("code","PALLET-"+f.store()).firstResult().state="CLOSED";closure.requireFinal(o,List.of());});
 }
 @Test void concludeCannotBypassTheRequiredScanAndRetriesAreIdempotent(){picked();long buffer=QuarkusTransaction.requiringNew().call(()->{var o=BaseHandlingOrder.<BaseHandlingOrder>findById(f.order());var p=new MissionPreparationContext();p.store=o.store;p.handlingOrder=o;p.mode="PRODUCT";p.requireFinalScan=true;p.persist();var b=new Buffer();b.bufferCode="FINAL-"+f.store();b.building=Building.findById(f.building());b.active=true;b.ambientCapacity=10;b.persist();return b.id;});
  var deposit=command("DEPOSIT_TOTE");deposit.putAll(Map.of("toteCode",f.tote(),"bufferId",buffer));command(deposit).then().statusCode(200);
  var finish=command("CONCLUDE");command(finish).then().statusCode(400);QuarkusTransaction.requiringNew().run(()->assertEquals(BaseHandlingOrder.HandlingOrderStatus.PROCESSING,BaseHandlingOrder.<BaseHandlingOrder>findById(f.order()).status));
  finish.put("requestKey",key());finish.put("finalScans",List.of(f.tote()));command(finish).then().statusCode(200);command(finish).then().statusCode(200);
  QuarkusTransaction.requiringNew().run(()->assertEquals(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.<BaseHandlingOrder>findById(f.order()).status));
 }

}
