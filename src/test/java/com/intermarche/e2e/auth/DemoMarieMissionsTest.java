package com.intermarche.e2e.auth;
import com.intermarche.backoffice.auth.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.wms.handling.*;
import io.quarkus.test.junit.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@QuarkusTest @TestProfile(DemoOperationalScenariosTest.Isolated.class)
class DemoMarieMissionsTest {
 @Inject DemoDataInitializer initializer;
 @Inject com.intermarche.backoffice.engine.stock.StockProtectionService protection;
 @Inject com.intermarche.backoffice.engine.stock.TemporalStockService stocks;
 @Test void exactlyOneActiveMissionPerTypeAndReplayPreservesThem(){
  QuarkusTransaction.requiringNew().run(()->initializer.seedDemoData());
  var first=QuarkusTransaction.requiringNew().call(()->{
   var marie=Employee.findByBadgeId("100235");var orders=BaseHandlingOrder.<BaseHandlingOrder>list("assignee=?1 and status in ?2",marie,List.of(BaseHandlingOrder.HandlingOrderStatus.PROCESSING,BaseHandlingOrder.HandlingOrderStatus.SCHEDULED));var counts=new TreeMap<String,Integer>();for(var raw:orders){var o=org.hibernate.Hibernate.unproxy(raw,BaseHandlingOrder.class);String key=o instanceof ReplenishmentOrder r?"SUPPLY:"+r.operation:o.getClass().getSimpleName();counts.merge(key,1,Integer::sum);if(o instanceof PreparingOrder p)assertFalse(p.lots.isEmpty());if(o instanceof ReplenishmentOrder r)assertFalse(r.supplies.isEmpty());}assertEquals(10,counts.size());assertTrue(counts.values().stream().allMatch(n->n==1),counts.toString());return orders.stream().map(o->o.id).sorted().toList();});
  QuarkusTransaction.requiringNew().run(()->initializer.seedDemoData());
  var second=QuarkusTransaction.requiringNew().call(()->BaseHandlingOrder.<BaseHandlingOrder>list("assignee.badgeId=?1 and status in ?2","100235",List.of(BaseHandlingOrder.HandlingOrderStatus.PROCESSING,BaseHandlingOrder.HandlingOrderStatus.SCHEDULED)).stream().map(o->o.id).sorted().toList());assertEquals(first,second);
 }
 /**
  * Marie also owns one loading, whose picked totes wait for their handover, and a replay does not add another.
  */
 @Test void marieOwnsOneLoadingWithItsTotes(){
  QuarkusTransaction.requiringNew().run(()->initializer.seedDemoData());
  QuarkusTransaction.requiringNew().run(()->initializer.seedDemoData());
  QuarkusTransaction.requiringNew().run(()->{
   var marie=Employee.findByBadgeId("100235");
   var loadings=com.intermarche.backoffice.domain.shipping.LogisticsDeparture.<com.intermarche.backoffice.domain.shipping.LogisticsDeparture>list("owner=?1 and state='LOADING'",marie);
   assertEquals(1,loadings.size());assertEquals("MAR-TEST-DEP",loadings.get(0).code);
   assertTrue(com.intermarche.backoffice.domain.shipping.DepartureUnit.count("departure",loadings.get(0))>0);
  });
 }
 /**
  * The zone imposed by Marie's protection mission still has a free ambient place, so its deposit can be done.
  */
 @Test void protectionDepositZoneHasRoom(){
  QuarkusTransaction.requiringNew().run(()->initializer.seedDemoData());
  QuarkusTransaction.requiringNew().run(()->{
   var marie=Employee.findByBadgeId("100235");
   StockProtectionOrder order=StockProtectionOrder.<StockProtectionOrder>list("assignee",marie).getFirst();
   Buffer buffer=org.hibernate.Hibernate.unproxy(order.protectionBuffer,Buffer.class);
   long used=com.intermarche.backoffice.domain.executing.LoadedTote.count("buffer=?1 and status=?2",buffer,com.intermarche.backoffice.domain.executing.LoadedTote.Status.IN_USE);
   assertTrue(buffer.getCapacityForZone(TemperatureZone.AMBIENT)>used,"free ambient places in "+buffer.bufferCode);
  });
 }
 /**
  * Marie receives team instructions of every kind (to acknowledge, urgent for a mission, for information) and her
  * missions carry line instructions, so the instruction screens show real content; replay adds nothing more.
  */
 @Test void marieHasTeamAndMissionInstructions(){
  QuarkusTransaction.requiringNew().run(()->initializer.seedDemoData());
  QuarkusTransaction.requiringNew().run(()->initializer.seedDemoData());
  QuarkusTransaction.requiringNew().run(()->{
   var notices=com.intermarche.backoffice.domain.operator.OperatorNoticeRecipient.<com.intermarche.backoffice.domain.operator.OperatorNoticeRecipient>list("principal=?1 and notice.eventKey like 'DEMO-CONSIGNE-%'","100235").stream().map(r->r.notice).toList();
   assertEquals(3,notices.size());
   assertTrue(notices.stream().anyMatch(n->n.mandatory),"mandatory");
   assertTrue(notices.stream().anyMatch(n->"URGENT".equals(n.urgency)&&n.orderId!=null),"urgent");
   var pack=BaseHandlingOrder.<BaseHandlingOrder>find("code","MAR-TEST-PACK").firstResult();
   var collect=ProductCollectingOrder.<ProductCollectingOrder>find("assignee.badgeId=?1 and status in ?2","100235",List.of(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED,BaseHandlingOrder.HandlingOrderStatus.PROCESSING)).firstResult();
   assertTrue(com.intermarche.backoffice.domain.fieldops.LineDirective.count("handlingOrder=?1 and instruction<>''",pack)>0,"pack");
   assertTrue(com.intermarche.backoffice.domain.fieldops.LineDirective.count("handlingOrder=?1 and instruction<>''",collect)>0,"collect "+(collect==null?"none":collect.code+" "+org.hibernate.Hibernate.unproxy(collect,BaseHandlingOrder.class).getClass().getSimpleName()));
  });
 }

 /**
  * Marie's protection answers a real shortage: one unit can be secured right away, so the demo mission can be completed.
  */
 @Test void marieProtectionCanSecureAUnit(){
  QuarkusTransaction.requiringNew().run(()->initializer.seedDemoData());
  QuarkusTransaction.requiringNew().run(()->{
   var order=StockProtectionOrder.<StockProtectionOrder>find("protectionRuleKey=?1 and status in ?2","MAR-TEST",List.of(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED,BaseHandlingOrder.HandlingOrderStatus.PROCESSING)).firstResult();
   assertNotNull(order);
   var supply=order.supplies.get(0);
   var account=org.hibernate.Hibernate.unproxy(supply.account,com.intermarche.backoffice.domain.stock.StockAccount.class);
   var timeline=stocks.availability(account.id,org.hibernate.Hibernate.unproxy(order.sourceBuilding,Building.class).id,com.intermarche.backoffice.domain.util.DateTimeProvider.now());
   var tote=new Tote();tote.code="MAR-SEC-CHECK";tote.store=order.store;tote.toteType=Tote.ToteType.STANDARD;tote.volume=new java.math.BigDecimal("35");tote.persistAndFlush();
   String state="physical="+timeline.physical+" safety="+timeline.safetyMargin+" periods="+timeline.periods.stream().map(p->p.from+":"+p.committed+"/"+p.balance).toList();
   assertDoesNotThrow(()->protection.confirm(order,supply,"mar-sec-check",java.math.BigDecimal.ONE,tote.code),state);
  });
 }
}
