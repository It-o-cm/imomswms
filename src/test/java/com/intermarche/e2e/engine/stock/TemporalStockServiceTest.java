package com.intermarche.e2e.engine.stock;

import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.stock.*;
import com.intermarche.backoffice.graphql.stock.TemporalStockResource;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@io.quarkus.test.junit.TestProfile(TemporalStockServiceTest.Isolated.class)
@QuarkusTest @TestTransaction
class TemporalStockServiceTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:isolated_TemporalStockServiceTest;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}}

    @Inject TemporalStockService service;
    @Inject TemporalStockResource api;
    @Inject EntityManager em;
    @Inject com.intermarche.backoffice.engine.alerts.StockAvailabilityAlerts alerts;
    LocalDateTime previous;
    static LocalDateTime at(int h,int m) {return LocalDateTime.of(2026,10,20,h,m);}
    static BigDecimal q(int n) {return BigDecimal.valueOf(n);}
    @BeforeEach void clock() {previous=DateTimeProvider.getFixedDateTime();DateTimeProvider.setFixedDateTime(at(8,35));}
    @AfterEach void restoreClock() {if(previous==null) DateTimeProvider.clear();else DateTimeProvider.setFixedDateTime(previous);}
    record Fixture(Store store,StoreBuilding shop,WarehouseBuilding warehouse,StoreShelfLocation shelf,WarehouseShelfLocation bin,StockAccount account) {}
    Fixture fixture() {
        Product product=new Product();product.ean="9988776655443";product.name="Nutella";product.productType=ProductType.UNIT;product.temperatureZone=TemperatureZone.AMBIENT;product.persist();
        Store store=new Store();store.code="TEMPORAL";store.name="Temporal stock";
        StoreBuilding shop=new StoreBuilding();shop.buildingCode="TEMPORAL-SHOP";
        WarehouseBuilding warehouse=new WarehouseBuilding();warehouse.buildingCode="TEMPORAL-WH";
        StoreShelfLocation shelf=new StoreShelfLocation();shelf.product=product;shelf.area="GROCERY";shelf.aisle="A1";shelf.unit="B1";shelf.elevation="1";shelf.position="1";shelf.persist();
        WarehouseShelfLocation bin=new WarehouseShelfLocation();bin.product=product;bin.area="AMBIENT";bin.aisle="A1";bin.unit="R1";bin.elevation="1";bin.position="1";bin.persist();
        shop.locations.add(shelf);warehouse.locations.add(bin);shop.persist();warehouse.persist();
        store.buildings.add(shop);store.buildings.add(warehouse);store.persistAndFlush();
        return new Fixture(store,shop,warehouse,shelf,bin,service.account(store.id,product.id));
    }
    void eq(int expected,BigDecimal actual) {assertEquals(0,q(expected).compareTo(actual),"Expected "+expected+", got "+actual);}
    StockAvailability.Timeline timeline(Fixture f) {return service.availability(f.account.id,f.shop.id,DateTimeProvider.now());}
    StockCommitment reserve(Fixture f,String ref,int qty,int h,int m) {return service.reserve(f.account.id,ref,f.shop.id,q(qty),at(h,m),false);}

    StockSegment split(Fixture f,int quantity,LocalDateTime time){
        var original=StockSegments.legacy(f.account,f.shelf);var traced=new StockSegment();traced.account=f.account;traced.location=f.shelf;traced.segmentKey="qualified";traced.quality="AVAILABLE";traced.persist();
        for(int sign:new int[]{-1,1}){var m=new StockMovement();m.account=f.account;m.location=f.shelf;m.segment=sign<0?original:traced;m.eventKey="qualify:"+sign;m.operationKey="qualify";m.quantity=q(quantity*sign);m.reason="SEGMENT_RECLASSIFICATION";m.effectiveAt=time;m.persist();}em.flush();return traced;
    }
    @Test void signedMovementConsumesSeveralSegmentsAndReplaysTheWholeOperation(){var f=fixture();service.adjust(f.account.id,f.shelf.id,"initial",q(20),at(8,0));var qualified=split(f,5,at(8,10));service.adjust(f.account.id,f.shelf.id,"sale-split",q(-17),at(8,20));service.adjust(f.account.id,f.shelf.id,"sale-split",q(-17),at(8,20));eq(3,timeline(f).physical);assertEquals(2,StockMovement.count("account=?1 and operationKey='sale-split'",f.account));eq(3,StockSegments.balance(qualified,at(8,35)));eq(0,StockSegments.balance(StockSegments.legacy(f.account,f.shelf),at(8,35)));}
    @Test void backdatedCorrectionCannotMakeAFutureSegmentBalanceNegative(){var f=fixture();service.adjust(f.account.id,f.shelf.id,"initial",q(20),at(8,0));split(f,10,at(8,20));assertThrows(IllegalArgumentException.class,()->service.adjust(f.account.id,f.shelf.id,"backdated",q(-15),at(8,10)));}

    @Test void batchKeepsIndependentMarginsReservationsAndPartiallyReceivedTransfers() {
        var f=fixture();
        Product other=new Product();other.ean="9988776655444";other.name="Second product";other.productType=ProductType.UNIT;other.temperatureZone=TemperatureZone.AMBIENT;other.persist();
        var bin=new WarehouseShelfLocation();bin.product=other;bin.area="AMBIENT";bin.aisle="A2";bin.unit="R2";bin.elevation="1";bin.position="1";bin.persist();f.warehouse.locations.add(bin);
        var shelf=new StoreShelfLocation();shelf.product=other;shelf.area="GROCERY";shelf.aisle="A2";shelf.unit="B2";shelf.elevation="1";shelf.position="1";shelf.persist();f.shop.locations.add(shelf);em.flush();
        var second=service.account(f.store.id,other.id);
        service.setMargin(f.account.id,q(1));service.setMargin(second.id,q(2));
        service.adjust(f.account.id,f.bin.id,"opening-bin",q(20),at(8,35));service.adjust(f.account.id,f.shelf.id,"opening-shop",q(3),at(8,35));
        service.adjust(second.id,bin.id,"opening-second",q(10),at(8,35));
        service.reserve(f.account.id,"first-order",f.shop.id,q(5),at(10,0),false);
        service.reserve(second.id,"second-order",f.shop.id,q(2),at(10,0),false);
        var firstSupply=service.planSupply(f.account.id,"first-transfer",f.bin.id,f.shelf.id,q(6),at(9,0));
        var secondSupply=service.planSupply(second.id,"second-transfer",bin.id,shelf.id,q(3),at(9,0));
        service.planSupply(f.account.id,"external",null,f.shelf.id,q(4),at(11,0));
        service.pick(firstSupply.id,"first-pick",q(6),at(8,35));service.receive(firstSupply.id,"first-partial",q(2),at(8,35),false);
        service.pick(secondSupply.id,"second-pick",q(3),at(8,35));service.receive(secondSupply.id,"second-partial",q(1),at(8,35),false);
        em.flush();em.clear();
        var accounts=List.of(StockAccount.<StockAccount>findById(f.account.id),StockAccount.<StockAccount>findById(second.id));
        var all=service.availabilityBatch(accounts,null,at(8,35));
        eq(23,all.get(f.account.id).physical);eq(4,all.get(f.account.id).inTransit);eq(1,all.get(f.account.id).safetyMargin);
        eq(10,all.get(second.id).physical);eq(2,all.get(second.id).inTransit);eq(2,all.get(second.id).safetyMargin);
        var shop=service.availabilityBatch(accounts,f.shop.id,at(8,35));
        eq(5,shop.get(f.account.id).physical);eq(1,shop.get(second.id).physical);
        assertEquals(at(10,0),shop.get(f.account.id).periods.stream().filter(p->p.committed.signum()>0).findFirst().orElseThrow().from);
    }

    @Test void observedPickingIsRecordedDespiteInsufficientKnownStockAndRemainsIdempotent() {
        var f=fixture();
        var supply=service.planSupply(f.account.id,"observed-pick",f.bin.id,f.shelf.id,q(4),at(9,0));
        service.pick(supply.id,"observed-pick-001",q(3),at(8,35));
        service.pick(supply.id,"observed-pick-001",q(3),at(8,35));
        eq(3,supply.pickedQuantity());
        eq(-3,service.availability(f.account.id,f.warehouse.id,at(8,35)).physical);
        assertEquals(1,StockMovement.count("account = ?1 and location = ?2",f.account,f.bin));
        assertThrows(IllegalArgumentException.class,()->service.pick(supply.id,"observed-pick-too-much",q(2),at(8,35)));
    }

    @Test void alternateAddressesPreservePartialReceiptsTransitAndCorrections() {
        var f=fixture();
        var bin=new WarehouseShelfLocation();bin.product=f.account.product;bin.area="AMBIENT";bin.aisle="ALT";bin.unit="1";bin.elevation="1";bin.position="1";bin.persist();f.warehouse.locations.add(bin);
        var shelf=new StoreShelfLocation();shelf.product=f.account.product;shelf.area="GROCERY";shelf.aisle="ALT";shelf.unit="1";shelf.elevation="1";shelf.position="1";shelf.persist();f.shop.locations.add(shelf);em.flush();
        var supply=service.planSupply(f.account.id,"alternate-addresses",f.bin.id,f.shelf.id,q(4),at(9,0));
        service.pick(supply.id,"alternate-source-1",q(2),at(8,35),bin.id);
        service.pick(supply.id,"alternate-source-2",q(2),at(8,35),f.bin.id);
        service.receive(supply.id,"alternate-destination-1",q(2),at(8,35),false,shelf.id);
        service.receive(supply.id,"alternate-destination-1",q(2),at(8,35),false,shelf.id);
        eq(2,service.availability(f.account.id,null,at(8,35)).inTransit);
        service.receive(supply.id,"alternate-destination-2",q(2),at(8,35),false,f.shelf.id);
        eq(0,service.availability(f.account.id,null,at(8,35)).inTransit);
        service.reopen(supply.id,"alternate-reopen-destination",false,at(8,35));
        service.reopen(supply.id,"alternate-reopen-destination",false,at(8,35));
        eq(4,service.availability(f.account.id,null,at(8,35)).inTransit);
        service.reopen(supply.id,"alternate-reopen-source",true,at(8,35));
        eq(0,service.availability(f.account.id,null,at(8,35)).inTransit);
        for(var place:List.of(bin,f.bin,shelf,f.shelf)) {
            var balance=StockMovement.<StockMovement>list("account=?1 and location=?2",f.account,place).stream().map(m->m.quantity).reduce(BigDecimal.ZERO,BigDecimal::add);
            eq(0,balance);
        }
    }

    /** disponibilite.docx: 37 + 60 + 80; 14/17/3 orders; POS -4; order +3; orders +58. */
    @Test void documentNutellaScenarioFromInventoryToFinalReceipt() {
        Fixture f=fixture();Long id=f.account.id;service.setMargin(id,q(3));
        service.adjust(id,f.shelf.id,"opening",q(37),at(8,35));
        var receipt1=service.planSupply(id,"truck-1130",null,f.shelf.id,q(60),at(11,30));
        var receipt2=service.planSupply(id,"truck-1600",null,f.shelf.id,q(80),at(16,0));
        var early=reserve(f,"early",14,10,0);var noon=reserve(f,"noon",17,13,0);var late=reserve(f,"late",3,17,0);
        eq(20,timeline(f).periods.get(0).availableToPromise);
        service.adjust(id,f.shelf.id,"pos-4",q(-4),at(8,35));
        eq(16,timeline(f).periods.get(0).availableToPromise);
        var newOrder=reserve(f,"new-1015",3,10,15);
        eq(13,timeline(f).periods.get(0).availableToPromise);
        var extra=reserve(f,"additional-58",58,14,0);
        var blocked=timeline(f);eq(0,blocked.periods.get(0).availableToPromise);
        eq(30,blocked.quantityToProtect);assertTrue(blocked.protectionRequired);assertEquals(at(16,0),blocked.nextAvailableAt);
        var quote=service.quote(id,f.shop.id,q(3),at(10,0));
        assertFalse(quote.canPromise);assertEquals(at(16,0),quote.nextAvailableAt);
        var before16=blocked.periods.stream().filter(p -> p.from.equals(at(14,0))).findFirst().orElseThrow();
        eq(93,before16.physicalAndExpected);eq(92,before16.committed);eq(-2,before16.balance);
        // Atomic preparation removes the 17 units and their commitments together.
        service.prepare(early.id,f.shelf.id,"pick-14",q(14),at(8,35));
        service.prepare(newOrder.id,f.shelf.id,"pick-3",q(3),at(8,35));
        service.prepare(noon.id,f.shelf.id,"pick-16",q(16),at(8,35));
        eq(0,timeline(f).physical);
        DateTimeProvider.setFixedDateTime(at(11,30));
        service.receive(receipt1.id,"receive-60",q(60),at(11,30),true);
        eq(60,timeline(f).physical);assertTrue(timeline(f).protectionRequired);
        // These 60 are already needed: complete the 1 + 58 pending units before 16h.
        service.prepare(noon.id,f.shelf.id,"pick-1",q(1),at(11,30));
        service.prepare(extra.id,f.shelf.id,"pick-58",q(58),at(11,30));
        DateTimeProvider.setFixedDateTime(at(16,0));
        service.receive(receipt2.id,"receive-80",q(80),at(16,0),true);
        var available=timeline(f);eq(81,available.physical);eq(75,available.periods.get(0).availableToPromise);
        assertFalse(available.protectionRequired);assertEquals(at(16,0),available.nextAvailableAt);
        em.flush();em.clear();
        eq(81,service.availability(id,f.shop.id,at(16,0)).physical);
        assertEquals(StockCommitment.CommitmentStatus.ACTIVE,StockCommitment.<StockCommitment>findById(late.id).status);
    }
    @Test void receiptsAndPreparationsAreIdempotentAndLeaveNoDoubleCount() {
        Fixture f=fixture();Long id=f.account.id;
        var supply=service.planSupply(id,"delivery",null,f.shelf.id,q(10),at(8,35));
        service.receive(supply.id,"r1",q(4),at(8,35),false);
        service.receive(supply.id,"r1",q(4),at(8,35),false);
        eq(4,timeline(f).physical);eq(10,timeline(f).periods.get(0).physicalAndExpected);
        service.receive(supply.id,"r2",q(3),at(8,35),true);
        eq(7,timeline(f).physical);eq(7,timeline(f).periods.get(0).physicalAndExpected);
        var c=reserve(f,"order",5,9,0);
        service.prepare(c.id,f.shelf.id,"pick",q(2),at(8,35));
        service.prepare(c.id,f.shelf.id,"pick",q(2),at(8,35));
        eq(5,timeline(f).physical);eq(3,c.remainingQuantity);eq(2,timeline(f).periods.get(0).availableToPromise);
        service.cancelCommitment(c.id);eq(5,timeline(f).periods.get(0).availableToPromise);
    }
    @Test void internalTransferConservesGlobalStockAndChangesEachBuilding() {
        Fixture f=fixture();Long id=f.account.id;service.adjust(id,f.bin.id,"opening",q(100),at(8,35));
        var transfer=service.planSupply(id,"wh-shop",f.bin.id,f.shelf.id,q(30),at(11,30));
        var global=service.availability(id,null,at(8,35));eq(100,global.periods.get(global.periods.size()-1).physicalAndExpected);
        eq(30,timeline(f).periods.get(1).availableToPromise);
        var source=service.availability(id,f.warehouse.id,at(8,35));eq(70,source.periods.get(0).availableToPromise);
        DateTimeProvider.setFixedDateTime(at(11,30));service.receive(transfer.id,"move",q(30),at(11,30),true);
        eq(30,timeline(f).physical);eq(70,service.availability(id,f.warehouse.id,at(11,30)).physical);
        eq(100,service.availability(id,null,at(11,30)).physical);
    }
    @Test void delayedShortCancelledSupplyAndCommitmentReleaseRecomputeAvailability() {
        Fixture f=fixture();var supply=service.planSupply(f.account.id,"late",null,f.shelf.id,q(10),at(9,0));
        reserve(f,"order",6,10,0);
        assertEquals(at(9,0),timeline(f).nextAvailableAt);
        service.reviseSupply(supply.id,q(10),at(12,0),false);
        assertEquals(at(12,0),timeline(f).nextAvailableAt);
        DateTimeProvider.setFixedDateTime(at(12,1));assertNull(timeline(f).nextAvailableAt);
        service.reviseSupply(supply.id,q(4),at(13,0),false);assertNull(timeline(f).nextAvailableAt);
        service.reviseSupply(supply.id,q(4),at(13,0),true);assertNull(timeline(f).nextAvailableAt);
    }
    @Test void newPromiseChecksFutureBottleneckInsteadOfOnlyImmediateStock() {
        Fixture f=fixture();service.adjust(f.account.id,f.shelf.id,"opening",q(10),at(8,35));reserve(f,"future",8,14,0);
        assertThrows(IllegalArgumentException.class,() -> service.reserve(f.account.id,"too-much",f.shop.id,q(3),at(9,0),true));
    }
    @Test @TestSecurity(user="other-manager",roles="MANAGER")
    void managerCannotReadOrWriteStockOutsideHisStore() {
        Fixture f=fixture();Store other=new Store();other.code="OTHER-STOCK";other.name="Other";other.persist();
        Employee e=new Employee();e.badgeId="other-manager";e.store=other;e.firstName="Other";e.lastName="Manager";e.email="other-stock@example.test";e.password="x".repeat(60);e.role=Employee.EmployeeRole.MANAGER;e.persistAndFlush();
        assertThrows(SecurityException.class,() -> api.stockAvailability(f.account.id,null));
    }
    @Test void transferIsNotPromisedIfSourceStockHasBeenConsumed() {
        Fixture f=fixture();service.adjust(f.account.id,f.bin.id,"opening",q(30),at(8,35));
        service.planSupply(f.account.id,"transfer",f.bin.id,f.shelf.id,q(30),at(11,30));
        service.adjust(f.account.id,f.bin.id,"sale",q(-25),at(8,35));
        var projected=timeline(f);eq(25,projected.unsupportedTransferQuantity);
        eq(5,projected.periods.get(projected.periods.size()-1).availableToPromise);
    }
    @Test void commitmentCanBeRescheduledAndReducedAfterPartialPreparation() {
        Fixture f=fixture();service.adjust(f.account.id,f.shelf.id,"opening",q(20),at(8,35));
        var c=reserve(f,"order",10,10,0);service.prepare(c.id,f.shelf.id,"pick",q(4),at(8,35));
        service.reviseCommitment(c.id,q(7),at(11,0),true);
        eq(3,c.remainingQuantity);eq(13,timeline(f).periods.get(0).availableToPromise);
        assertEquals(at(11,0),c.preparationAt);
        assertEquals(1,service.positions(f.account.id).size());eq(16,service.positions(f.account.id).get(0).quantity);
    }
    @Test void replayingAdjustmentWithDifferentPayloadIsRejected() {
        Fixture f=fixture();service.adjust(f.account.id,f.shelf.id,"opening",q(20),at(8,35));
        assertThrows(IllegalArgumentException.class,() -> service.adjust(f.account.id,f.shelf.id,"opening",q(21),at(8,35)));
    }
    @Test void negativeInventoryIsRejected() {
        Fixture f=fixture();
        assertThrows(IllegalArgumentException.class,() -> service.adjust(f.account.id,f.shelf.id,"negative",q(-1),at(8,35)));
    }

    @Test void aBackdatedCorrectionCannotInvalidateLaterPhysicalMovements() {
        Fixture f=fixture();service.adjust(f.account.id,f.shelf.id,"opening",q(10),at(8,0));
        service.adjust(f.account.id,f.shelf.id,"sale",q(-5),at(8,20));
        assertThrows(IllegalArgumentException.class,() -> service.adjust(f.account.id,f.shelf.id,"correction",q(-6),at(8,10)));
    }
    @Test void stockCannotMoveIntoAnotherStoresLocation() {
        Fixture f=fixture();Store other=new Store();other.code="OTHER-LOCATION";other.name="Other";
        StoreBuilding b=new StoreBuilding();b.buildingCode="OTHER-LOCATION";
        StoreShelfLocation loc=new StoreShelfLocation();loc.area="TEST";loc.aisle="A1";loc.unit="B1";loc.elevation="1";loc.position="1";loc.persist();
        b.locations.add(loc);b.persist();other.buildings.add(b);other.persistAndFlush();
        assertThrows(IllegalArgumentException.class,() -> service.adjust(f.account.id,loc.id,"foreign",q(1),at(8,35)));
    }

    @Test @TestSecurity(user="stock-manager",roles="MANAGER")
    void managerCanOperateStockInHisStore() {
        Fixture f=fixture();Employee e=new Employee();e.badgeId="stock-manager";e.store=f.store;
        e.firstName="Stock";e.lastName="Manager";e.email="stock-manager@example.test";e.password="x".repeat(60);e.role=Employee.EmployeeRole.MANAGER;e.persistAndFlush();
        api.adjustStock(f.account.id,f.shelf.id,"opening",q(12),at(8,35));
        eq(12,api.stockAvailability(f.account.id,f.shop.id).physical);
        assertEquals(1,api.stockPositions(f.account.id).size());
    }

    @Test @TestSecurity(user="integration",roles="SERVICE_CALLER")
    void overviewReturnsSeveralProductsAndTheirServerAvailability() {
        var f=fixture();com.intermarche.e2e.domain.util.DomainUtils.grantStore(f.store,"integration");service.adjust(f.account.id,f.shelf.id,"overview-opening",q(10),at(8,35));
        Product other=new Product();other.ean="9988776655444";other.name="Riz";other.productType=ProductType.UNIT;other.temperatureZone=TemperatureZone.AMBIENT;other.persist();
        service.account(f.store.id,other.id);
        var rows=api.stockOverview(f.store.code,null,false);
        assertEquals(2,rows.size());assertEquals("Riz",rows.get(0).product.name);assertTrue(rows.get(0).inTension);
        eq(10,rows.stream().filter(r->r.id.equals(f.account.id)).findFirst().orElseThrow().physical);
        assertEquals(1,api.stockOverview(f.store.code,null,true).size());
        assertThrows(SecurityException.class,()->api.stockOverview(f.store.code,999999L,false));
    }
    @Test void physicalInventoryIsAbsoluteAuditedIdempotentAndRejectsConcurrentChanges() {
        var f=fixture();service.adjust(f.account.id,f.shelf.id,"inventory-opening",q(10),at(8,35));
        var commitment=reserve(f,"inventory-commitment",3,10,0);
        var correction=service.setPhysical(f.account.id,f.shelf.id,"inventory-correction-001",q(10),q(7),"Comptage rayon");
        eq(10,correction.previousQuantity);eq(7,correction.quantity);eq(7,timeline(f).physical);eq(3,commitment.remainingQuantity);
        long movements=StockMovement.count();assertEquals(correction.id,service.setPhysical(f.account.id,f.shelf.id,"inventory-correction-001",q(10),q(7),"Comptage rayon").id);assertEquals(movements,StockMovement.count());
        assertThrows(IllegalArgumentException.class,()->service.setPhysical(f.account.id,f.shelf.id,"inventory-correction-002",q(10),q(6),"Ancien comptage"));
        assertThrows(IllegalArgumentException.class,()->service.setPhysical(f.account.id,f.shelf.id,"inventory-correction-003",q(7),new BigDecimal("1.5"),"Quantité fractionnée"));
        eq(7,timeline(f).physical);
    }
    @Test @TestSecurity(user="integration",roles="SERVICE_CALLER")
    void cataloguePagesAreBoundedAndDistinguishPresentAndFutureShortages() {
        var f=fixture();com.intermarche.e2e.domain.util.DomainUtils.grantStore(f.store,"integration");f.account.product.brand="Marque test";service.adjust(f.account.id,f.shelf.id,"paged-opening",q(10),at(8,35));reserve(f,"paged-future",10,14,0);
        for(int i=0;i<205;i++){Product p=new Product();p.ean="99000000"+String.format("%05d",i);p.name="Article "+i;p.brand="Marque test";p.productType=ProductType.UNIT;p.temperatureZone=TemperatureZone.AMBIENT;p.persist();StockAccount a=new StockAccount();a.store=f.store;a.product=p;a.persist();}em.flush();
        var first=api.stockPage(f.store.code,null,null,"ALL",null,null,"Marque test",null,null,null,null,null,false,24,null,2);assertEquals(2,first.items.size());assertTrue(first.hasMore);assertEquals("TENSION",first.items.get(0).availabilityStatus);assertEquals("UNAVAILABLE",first.items.get(1).availabilityStatus);
        var second=api.stockPage(f.store.code,null,null,"ALL",null,null,"Marque test",null,null,null,null,null,false,24,first.nextCursor,2);assertTrue(second.items.stream().noneMatch(a->first.items.stream().anyMatch(b->a.id.equals(b.id))));
        var tension=api.stockPage(f.store.code,null,null,"TENSION",null,null,null,null,null,null,null,null,false,24,null,30);assertEquals(1,tension.items.size());assertEquals(f.account.id,tension.items.get(0).id);
        var combined=api.stockPage(f.store.code,null,null,"TENSION,UNAVAILABLE",null,null,null,null,null,null,null,null,false,24,null,30);assertTrue(combined.items.stream().anyMatch(a->"TENSION".equals(a.availabilityStatus)));assertTrue(combined.items.stream().anyMatch(a->"UNAVAILABLE".equals(a.availabilityStatus)));assertTrue(combined.items.stream().noneMatch(a->"AVAILABLE".equals(a.availabilityStatus)));
        assertTrue(api.stockPage(f.store.code,null,null,"NONE",null,null,null,null,null,null,null,null,false,24,null,30).items.isEmpty());
        var shortHorizon=api.stockPage(f.store.code,null,"Nutella","AVAILABLE",null,null,null,null,null,null,null,null,false,1,null,30);assertEquals(1,shortHorizon.items.size());
        var search=api.stockPage(f.store.code,null,"Article 204","UNAVAILABLE","AMBIENT","UNIT","Marque",null,null,null,null,null,false,24,null,30);assertEquals(1,search.items.size());assertEquals("Article 204",search.items.get(0).product.name);
        var located=api.stockPage(f.store.code,f.shop.id,null,"ALL",null,null,null,"grocery",null,null,null,null,false,24,null,30);
        assertEquals(1,located.items.size());assertEquals(f.account.id,located.items.get(0).id);
        assertTrue(api.stockPage(f.store.code,f.warehouse.id,null,"ALL",null,null,null,"grocery",null,null,null,null,false,24,null,30).items.isEmpty());
        assertTrue(api.stockPage(f.store.code,null,null,"ALL",null,null,null,"absent",null,null,null,null,false,24,null,30).items.isEmpty());
        assertTrue(api.stockPage(f.store.code,null,null,"ALL",null,null,null,"%",null,null,null,null,false,24,null,30).items.isEmpty());
        var sparse=api.stockPage(f.store.code,null,null,"ALL",null,null,"Marque test",null,null,null,null,null,true,24,null,30);assertEquals(200,sparse.scanned);assertEquals(1,sparse.items.size());assertTrue(sparse.hasMore);assertNotNull(sparse.nextCursor);
        assertThrows(SecurityException.class,()->api.stockPage(f.store.code,999999L,null,"ALL",null,null,"Marque test",null,null,null,null,null,false,24,null,30));
        assertThrows(IllegalArgumentException.class,()->api.stockPage(f.store.code,null,null,"ALL",null,null,"Marque test",null,null,null,null,null,false,24,null,40000));
    }
    @Test void tensionFilterFindsAFutureBottleneckAndRespectsTheBuilding() {
        Fixture f=fixture();service.adjust(f.account.id,f.shelf.id,"opening",q(10),at(8,35));
        assertTrue(service.accountsInTension(f.store.id,f.shop.id).isEmpty());
        reserve(f,"future",10,14,0);
        assertEquals(List.of(f.account.id),service.accountsInTension(f.store.id,f.shop.id).stream().map(a -> a.id).toList());
        assertEquals(1,service.accountsInTension(f.store.id,null).size());
        service.adjust(f.account.id,f.bin.id,"warehouse-stock",q(30),at(8,35));
        assertTrue(service.accountsInTension(f.store.id,f.warehouse.id).isEmpty());
        service.cancelCommitment(StockCommitment.<StockCommitment>find("reference","future").firstResult().id);
        assertTrue(service.accountsInTension(f.store.id,f.shop.id).isEmpty());
    }

    @Test void stockAlertsFollowTransitionsAndClockWithoutRepeatingTheSameState(){
        var f=fixture();service.adjust(f.account.id,f.shelf.id,"alert-initial",q(10),at(8,35));alerts.evaluate(f.account.id);
        assertEquals("AVAILABLE",f.account.alertStatus);
        var commitment=reserve(f,"alert-reservation",10,14,0);
        assertTrue(f.account.alertDirty);alerts.evaluate(f.account.id);
        assertEquals(1,com.intermarche.backoffice.domain.Event.count("store=?1 and label=?2",f.store,"Produit en tension"));
        alerts.evaluate(f.account.id);assertEquals(1,com.intermarche.backoffice.domain.Event.count("store=?1 and label=?2",f.store,"Produit en tension"));
        DateTimeProvider.setFixedDateTime(at(14,0));assertTrue(alerts.dueAccounts().contains(f.account.id));alerts.evaluate(f.account.id);
        assertEquals(1,com.intermarche.backoffice.domain.Event.count("store=?1 and label=?2",f.store,"Produit indisponible"));
        service.cancelCommitment(commitment.id);alerts.evaluate(f.account.id);assertEquals("AVAILABLE",f.account.alertStatus);
        reserve(f,"alert-new-episode",10,16,0);alerts.evaluate(f.account.id);
        assertEquals(2,com.intermarche.backoffice.domain.Event.count("store=?1 and label=?2",f.store,"Produit en tension"));
    }

    @Test @TestSecurity(user="svc",roles={"SERVICE_CALLER"})
    void spatialStockUsesExplicitBayPositionsRatherThanProductOrCodeOrder(){
        var f=fixture();com.intermarche.e2e.domain.util.DomainUtils.grantStore(f.store,"svc");f.shelf.aislePosition=0.9;
        var second=new StoreShelfLocation();second.product=f.account.product;second.area="GROCERY";second.aisle="A1";second.unit="B10";second.elevation="1";second.position="1";second.aislePosition=0.1;second.persist();f.shop.locations.add(second);em.flush();
        var options=api.stockPlacementOptions(f.store.code,f.shop.id);
        assertEquals(List.of("B10","B1"),options.addresses.stream().map(a->a.bay).toList());
        var page=api.stockPage(f.store.code,f.shop.id,null,"ALL",null,null,null,null,"GROCERY","A1",null,null,false,24,null,30);
        assertEquals(1,page.items.size());assertEquals(2,page.items.getFirst().placements.size());
        var placements=new ArrayList<>(page.items.getFirst().placements);placements.sort(Comparator.comparingInt(a->a.spatialOrder));
        assertEquals("B10",placements.getFirst().bay);
    }

}
