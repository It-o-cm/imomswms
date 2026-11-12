package com.intermarche.e2e.engine.stock;
import com.fasterxml.jackson.databind.*;
import com.intermarche.backoffice.auth.DemoDataInitializer;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.handling.*;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.stock.*;
import com.intermarche.backoffice.engine.replenishment.ReplenishmentResource;
import com.intermarche.backoffice.engine.handling.StockProtectionOrderManagerFactory;
import com.intermarche.backoffice.graphql.handling.StockProtectionOrderResource;
import com.intermarche.backoffice.rules.RuleSchemaRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@io.quarkus.test.junit.TestProfile(StockProtectionWorkflowTest.Isolated.class)
@QuarkusTest @TestTransaction @TestSecurity(user="100234",roles="MANAGER")
public class StockProtectionWorkflowTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:isolated_StockProtectionWorkflowTest;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}}

    @Inject DemoDataInitializer seed;
    @Inject TemporalStockService stocks;
    @Inject StockProtectionService protection;
    @Inject StockProtectionOrderManagerFactory factory;
    @Inject StockProtectionOrderResource query;
    @Inject ReplenishmentResource execution;
    @Inject ObjectMapper mapper;
    @Inject RuleSchemaRegistry schemas;
    @Inject jakarta.persistence.EntityManager em;
    LocalDateTime previous;
    Store store;StoreBuilding shop;StockAccount account;JsonNode rule;
    @BeforeEach void clock(){previous=DateTimeProvider.getFixedDateTime();DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026,10,20,8,35));}
    void fixture() throws Exception {
        seed.seedDemoData();
        // The demo planning seeds directed transits, which activate the position control; this scenario tests protection without spatial configuration.
        com.intermarche.backoffice.domain.workforce.BuildingTransit.delete("store",Store.findByCode("LYO-042"));
        store=Store.findByCode("LYO-042");shop=StoreBuilding.find("buildingCode","LYO-042-SHOP").firstResult();
        Product product=Product.findByEan("3017620422003");product.referenceVolume=new BigDecimal("0.5");account=stocks.account(store.id,product.id);
        for(ExpectedSupply supply:ExpectedSupply.<ExpectedSupply>list("account",account)) {
            if(supply.reference.equals("DEMO-NUTELLA-ARRIVAL-60"))stocks.reviseSupply(supply.id,supply.quantity,DateTimeProvider.now().withHour(11).withMinute(30),false);
            if(supply.reference.equals("DEMO-NUTELLA-ARRIVAL-80"))stocks.reviseSupply(supply.id,supply.quantity,DateTimeProvider.now().withHour(16).withMinute(0),false);
        }
        for(StockCommitment c:StockCommitment.<StockCommitment>list("account",account)) {
            if(c.reference.equals("DEMO-NUTELLA-EARLY"))stocks.reviseCommitment(c.id,c.quantity,DateTimeProvider.now().withHour(10).withMinute(15),false);
            if(c.reference.equals("DEMO-NUTELLA-NOON"))stocks.reviseCommitment(c.id,c.quantity,DateTimeProvider.now().withHour(14).withMinute(0),false);
            if(c.reference.equals("DEMO-NUTELLA-LATE"))stocks.reviseCommitment(c.id,c.quantity,DateTimeProvider.now().withHour(17).withMinute(0),false);
        }
        // The demo fills its buffers to show full zones; this scenario needs room for the protection tote.
        var target=Buffer.<Buffer>find("bufferCode","LYO-042-BUF-01").firstResult();
        if(BufferCabinet.count("buffer=?1 and zone=?2",target,TemperatureZone.AMBIENT)>0){var room=new BufferCabinet();room.buffer=target;room.code="SEC-TEST-ROOM";room.columns=10;room.rows=10;room.zone=TemperatureZone.AMBIENT;room.persist();}
        else target.ambientCapacity+=100;
        Tote tote=new Tote();tote.code="SEC-TEST-01";tote.store=store;tote.toteType=Tote.ToteType.STANDARD;tote.volume=new BigDecimal("35");tote.persistAndFlush();
        rule=mapper.readTree("""
          {"type":"StockProtection","name":"Protection Nutella","buildingCode":"LYO-042-SHOP","targetBufferCode":"LYO-042-BUF-01","eans":["3017620422003"],"horizonMinutes":0,"interventionMinutes":5,"maxLinesPerOrder":20,"enabled":true}
          """);
    }
    @AfterEach void restore(){if(previous==null)DateTimeProvider.clear();else DateTimeProvider.setFixedDateTime(previous);}
    private void qty(int expected,BigDecimal value){assertEquals(0,BigDecimal.valueOf(expected).compareTo(value));}
    private void confirm(StockProtectionOrder order,int amount,String key){
        var receipt=new ReplenishmentResource.Receipt();receipt.storeId=store.id;receipt.eventKey=key;
        var line=new ReplenishmentResource.ReceiptLine();line.supplyId=order.supplies.get(0).id;line.quantity=BigDecimal.valueOf(amount);line.toteCode="SEC-TEST-01";receipt.lines=List.of(line);execution.receive(order.id,receipt);
    }
    @Test void documentScenarioCreatesUrgentHandlingOrderVisibleInStorepilot_withoutDuplicateOrPhysicalIssue() throws Exception {
        fixture();schemas.validate("ORDER_HANDLER",rule,List.of(store));factory.build(rule).process(store);
        // The demo also seeds Marie's own protection mission: only the orders of this rule count here.
        assertEquals(1,StockProtectionOrder.count("protectionRuleKey is null or protectionRuleKey<>?1","MAR-TEST"));var order=StockProtectionOrder.<StockProtectionOrder>find("protectionRuleKey is null or protectionRuleKey<>?1","MAR-TEST").firstResult();
        assertTrue(order.urgent);assertEquals(ReplenishmentOrder.Operation.STOCK_PROTECTION,order.operation);assertTrue(order.code.contains("SEC"));
        qty(30,order.supplies.get(0).quantity);qty(33,stocks.availability(account.id,shop.id,DateTimeProvider.now()).physical);
        assertTrue(query.stockProtectionOrdersByDateRange(DateTimeProvider.now().minusMinutes(1),DateTimeProvider.now().plusMinutes(1)).stream().anyMatch(o->o.id.equals(order.id)));
        assertEquals(order.id,query.stockProtectionOrder(order.id).id);assertTrue(protection.evaluate(store,rule).isEmpty());
        assertTrue(protection.totes(order).stream().noneMatch(t->String.valueOf(t.get("code")).startsWith("AFF-COL-")||String.valueOf(t.get("code")).startsWith("AFF-DEP-")));
        var route=execution.route(order.id,store.id);assertEquals(1,((List<?>)route.get("phases")).size());
        assertFalse((Boolean)stocks.quote(account.id,shop.id,BigDecimal.ONE,DateTimeProvider.now().plusHours(1)).canPromise);
    }
    @Test void actualTransferToPickingToteConservesStockAndReservations_andIsIdempotent() throws Exception {
        fixture();var order=protection.evaluate(store,rule).get(0);var line=order.supplies.get(0);long commitments=StockCommitment.count("account",account);
        execution.start(order.id,store.id);confirm(order,10,"secure-first-10");confirm(order,10,"secure-first-10");qty(10,line.received);
        confirm(order,20,"secure-final-20");assertEquals(BaseHandlingOrder.HandlingOrderStatus.DONE,order.status);
        qty(33,stocks.availability(account.id,shop.id,DateTimeProvider.now()).physical);qty(3,stocks.positions(account.id).stream().filter(p -> p.locationId.equals(line.source.id)).findFirst().orElseThrow().quantity);
        assertEquals(commitments,StockCommitment.count("account",account));assertTrue(protection.evaluate(store,rule).isEmpty());
        var place=StockProtectionPlacement.<StockProtectionPlacement>list("supply",line).get(0).destination;
        var commitment=StockCommitment.<StockCommitment>find("reference","DEMO-NUTELLA-EARLY").firstResult();
        stocks.prepare(commitment.id,place.id,"prepare-secured",new BigDecimal("17"),DateTimeProvider.now());
        qty(16,stocks.availability(account.id,shop.id,DateTimeProvider.now()).physical);assertTrue(protection.evaluate(store,rule).isEmpty());
    }
    @Test void cancellationsWithdrawUnstartedProtectionAndDisabledOrShortHorizonRulesDoNotCreateOrders() throws Exception {
        fixture();var disabled=rule.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)disabled).put("enabled",false);assertTrue(protection.evaluate(store,disabled).isEmpty());
        var shortRule=rule.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)shortRule).put("horizonMinutes",30);assertTrue(protection.evaluate(store,shortRule).isEmpty());
        var order=protection.evaluate(store,rule).get(0);
        for(StockCommitment commitment:StockCommitment.<StockCommitment>list("account",account))stocks.cancelCommitment(commitment.id);
        assertTrue(protection.evaluate(store,rule).isEmpty());assertEquals(BaseHandlingOrder.HandlingOrderStatus.CANCELLED,order.status);qty(33,stocks.availability(account.id,shop.id,DateTimeProvider.now()).physical);
    }
    @Test void worksAcrossFreshPersistenceContextsLikeThePollingWorker() throws Exception {
        fixture();Long storeId=store.id,accountId=account.id,shopId=shop.id;em.flush();em.clear();
        store=Store.findById(storeId);account=StockAccount.findById(accountId);shop=StoreBuilding.findById(shopId);
        var created=protection.evaluate(store,rule);assertEquals(1,created.size());Long orderId=created.get(0).id;em.flush();em.clear();
        var order=StockProtectionOrder.<StockProtectionOrder>findById(orderId);execution.start(orderId,storeId);confirm(order,10,"fresh-context-first");em.flush();em.clear();
        order=StockProtectionOrder.findById(orderId);confirm(order,10,"fresh-context-first");qty(10,order.supplies.get(0).received);confirm(order,20,"fresh-context-final");
        qty(33,stocks.availability(accountId,shopId,DateTimeProvider.now()).physical);
    }
    @Test void rejectsForeignBufferAndOverCapacityTote_withoutLosingStock() throws Exception {
        fixture();var foreign=rule.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)foreign).put("targetBufferCode","FOREIGN");assertThrows(IllegalArgumentException.class,() -> schemas.validate("ORDER_HANDLER",foreign,List.of(store)));
        var order=protection.evaluate(store,rule).get(0);Tote tote=Tote.findByCode("SEC-TEST-01");tote.volume=BigDecimal.ONE;
        long before=StockMovement.count();assertThrows(jakarta.ws.rs.BadRequestException.class,() -> confirm(order,30,"over-capacity"));assertEquals(before,StockMovement.count());qty(33,stocks.availability(account.id,shop.id,DateTimeProvider.now()).physical);
    }
}
