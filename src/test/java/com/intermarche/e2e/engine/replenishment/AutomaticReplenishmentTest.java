package com.intermarche.e2e.engine.replenishment;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.backoffice.auth.DemoDataInitializer;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder;
import com.intermarche.backoffice.engine.replenishment.*;
import com.intermarche.backoffice.engine.handling.ReplenishmentOrderManagerFactory;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import com.intermarche.backoffice.rules.RuleSchemaRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@io.quarkus.test.junit.TestProfile(AutomaticReplenishmentTest.Isolated.class)
@QuarkusTest @TestTransaction
public class AutomaticReplenishmentTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:isolated_AutomaticReplenishmentTest;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}}

    @Inject DemoDataInitializer seed;
    @Inject AutomaticReplenishmentService service;
    @Inject TemporalStockService stocks;
    @Inject ObjectMapper mapper;
    @Inject RuleSchemaRegistry schemas;
    @Inject ReplenishmentOrderManagerFactory factory;
    private Store store;
    private List<StockAccount> accounts;
    private ObjectNode setup(int count,int shelf,int reserve) throws Exception {
        seed.seedDemoData();store=Store.findByCode("LYO-042");accounts=new ArrayList<>();
        Set<Long> seeded=new HashSet<>();ReplenishmentOrder.<ReplenishmentOrder>listAll().forEach(o -> o.supplies.forEach(s -> seeded.add(s.account.product.id)));
        // Products of the activity supervision demo carry orders, reservations and warehouse stock of their own.
        Order.<Order>list("orderId like ?1","ACT-%").forEach(o -> o.lines.forEach(l -> seeded.add(l.product.id)));
        // Products picked for the operational demo orders are held in totes inside the shop, which covers their shelf need.
        Order.<Order>list("orderId like ?1","DEMO-V14-%").forEach(o -> o.lines.forEach(l -> seeded.add(l.product.id)));
        // Products whose stock the operational demo scenarios receive or count (inventory, quality) without an order.
        com.intermarche.backoffice.domain.stock.ManufacturerLot.<com.intermarche.backoffice.domain.stock.ManufacturerLot>list("code like ?1","DEMO-V14-%").forEach(lot -> seeded.add(lot.product.id));
        // Products free of any other flow: planned supplies and commitments of other scenarios change the need to replenish.
        ExpectedSupply.<ExpectedSupply>list("account.store",store).forEach(e -> seeded.add(e.account.product.id));
        StockCommitment.<StockCommitment>list("account.store",store).forEach(c -> seeded.add(c.account.product.id));
        StoreBuilding shop=StoreBuilding.find("buildingCode","LYO-042-SHOP").firstResult();
        Set<Long> shelves=new HashSet<>();shop.locations.forEach(l -> shelves.add(l.id));
        var candidates=StoreShelfLocation.<StoreShelfLocation>list("product is not null order by id").stream().filter(l -> shelves.contains(l.id) && l.product.productType==ProductType.UNIT && !seeded.contains(l.product.id) && !l.product.ean.equals("3017620422003")).limit(count).toList();
        assertEquals(count,candidates.size());
        for(var location:candidates) {
            Product p=location.product;p.temperatureZone=TemperatureZone.AMBIENT;p.referenceWeight=BigDecimal.ONE;p.referenceVolume=BigDecimal.ONE;
            StockAccount account=stocks.account(store.id,p.id);stocks.setMargin(account.id,BigDecimal.ZERO);accounts.add(account);
            BigDecimal physical=stocks.positions(account.id).stream().filter(pos -> pos.locationId.equals(location.id)).map(pos -> pos.quantity).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal delta=BigDecimal.valueOf(shelf).subtract(physical);
            if(delta.signum()!=0)stocks.adjust(account.id,location.id,UUID.randomUUID().toString(),delta,DateTimeProvider.now());
            WarehouseShelfLocation warehouse=WarehouseShelfLocation.find("product",p).firstResult();
            if(reserve>0)stocks.adjust(account.id,warehouse.id,UUID.randomUUID().toString(),BigDecimal.valueOf(reserve),DateTimeProvider.now());
        }
        ObjectNode rule=(ObjectNode)mapper.readTree("""
          {"type":"Replenishment","name":"Réachalandage test","sourceBuildingCode":"LYO-042-WH","destinationBuildingCode":"LYO-042-SHOP","minimumStock":5,"targetStock":20,"horizonMinutes":120,"completionMinutes":30,"maxWeightKg":100,"maxVolumeLiters":300,"minimumBatchWeightKg":0,"maxLines":20,"maxOrdersPerCycle":3,"allowPartial":true,"enabled":true,"separateTemperatureZones":true}
          """);
        var eans=rule.putArray("eans");accounts.forEach(a -> eans.add(a.product.ean));return rule;
    }
    @Test void consolidatesProductsWithoutMovingPhysicalStock_andDoesNotDuplicateOpenWork() throws Exception {
        var rule=setup(2,2,60);schemas.validate("ORDER_HANDLER",rule,List.of(store));
        long movements=StockMovement.count();var orders=service.evaluate(store,rule);
        assertEquals(1,orders.size());assertEquals("Réachalandage test",orders.get(0).generatedByRule);assertEquals(2,orders.get(0).supplies.size());assertEquals(movements,StockMovement.count());
        assertTrue(orders.get(0).supplies.stream().allMatch(s -> s.quantity.compareTo(BigDecimal.valueOf(18))==0));
        assertTrue(service.evaluate(store,rule).isEmpty());
        assertEquals(ReplenishmentOrder.Operation.SHELF_REPLENISHMENT,orders.get(0).operation);
    }
    @Test void anticipatesFutureDemandAndRespectsLoadAndCycleLimits() throws Exception {
        var rule=setup(1,25,60);Long destination=AutomaticReplenishmentService.ownedBuilding(store,"LYO-042-SHOP",false).id;
        stocks.reserve(accounts.get(0).id,"future-rule-demand",destination,BigDecimal.valueOf(23),DateTimeProvider.now().plusMinutes(60),true);
        rule.put("maxWeightKg",5);rule.put("maxVolumeLiters",4);rule.put("maxOrdersPerCycle",2);
        var orders=service.evaluate(store,rule);assertEquals(2,orders.size());
        assertTrue(orders.stream().allMatch(o -> o.supplies.stream().map(s -> s.quantity).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(BigDecimal.valueOf(4))<=0));
        assertTrue(service.evaluate(store,rule).isEmpty());
    }
    @Test void noOrderWithoutAvailableReserveOrBelowMinimumBatch_andDisabledRulesDoNothing() throws Exception {
        var rule=setup(1,2,2);rule.put("allowPartial",false);assertTrue(service.evaluate(store,rule).isEmpty());
        rule.put("allowPartial",true);rule.put("minimumBatchWeightKg",3);assertTrue(service.evaluate(store,rule).isEmpty());
        rule.put("minimumBatchWeightKg",0);rule.put("enabled",false);assertTrue(service.evaluate(store,rule).isEmpty());
        rule.put("enabled",true);assertEquals(1,service.evaluate(store,rule).size());
    }
    @Test void incomingStockAtDestinationPreventsUnnecessaryHandling() throws Exception {
        var rule=setup(1,2,60);var account=accounts.get(0);
        StoreShelfLocation destination=StoreShelfLocation.find("product",account.product).firstResult();
        stocks.planSupply(account.id,"incoming-already-covers",null,destination.id,BigDecimal.valueOf(18),DateTimeProvider.now().plusMinutes(10));
        assertTrue(service.evaluate(store,rule).isEmpty());
    }
    @Test void futureWarehouseDeliveriesAreNotMovedBeforePhysicalReceipt() throws Exception {
        var rule=setup(1,2,0);var account=accounts.get(0);
        WarehouseShelfLocation destination=WarehouseShelfLocation.find("product",account.product).firstResult();
        stocks.planSupply(account.id,"future-warehouse-only",null,destination.id,BigDecimal.valueOf(100),DateTimeProvider.now().plusMinutes(10));
        assertTrue(service.evaluate(store,rule).isEmpty());
    }
    @Test void temperatureSeparationIsConfigurable() throws Exception {
        var rule=setup(2,2,60);accounts.get(1).product.temperatureZone=TemperatureZone.CHILLED;
        assertEquals(2,service.evaluate(store,rule).size());
    }
    /**
     * Food and household products are split into separate tours even when the rule does not split temperatures.
     *
     * @throws Exception when the test rule cannot be built
     */
    @Test void foodAndNonFoodAreAlwaysSeparated() throws Exception {
        var rule=setup(2,2,60);rule.put("separateTemperatureZones",false);accounts.get(1).product.temperatureZone=TemperatureZone.HOUSEHOLD;
        var orders=service.evaluate(store,rule);
        assertEquals(2,orders.size());
        assertTrue(orders.stream().allMatch(o -> o.supplies.stream().map(x -> TemperatureZone.isFood(x.account.product.temperatureZone)).distinct().count()==1));
    }
    /**
     * Without temperature separation, food products of different temperatures share one tour.
     *
     * @throws Exception when the test rule cannot be built
     */
    @Test void foodTemperaturesShareATourWhenTheirSeparationIsOff() throws Exception {
        var rule=setup(2,2,60);rule.put("separateTemperatureZones",false);accounts.get(1).product.temperatureZone=TemperatureZone.CHILLED;
        var orders=service.evaluate(store,rule);
        assertEquals(1,orders.size());assertEquals(2,orders.get(0).supplies.size());
    }
    /**
     * Only household products are non-food; a missing zone counts as food.
     */
    @Test void onlyHouseholdIsNonFood() {
        assertTrue(TemperatureZone.isFood(TemperatureZone.AMBIENT));assertTrue(TemperatureZone.isFood(TemperatureZone.CHILLED));
        assertTrue(TemperatureZone.isFood(TemperatureZone.FROZEN));assertTrue(TemperatureZone.isFood(null));
        assertFalse(TemperatureZone.isFood(TemperatureZone.HOUSEHOLD));
    }
    @Test void validatesBuildingRolesAndThresholds_andRegistersInExistingEngine() throws Exception {
        var rule=setup(1,2,60);
        rule.put("sourceBuildingCode","LYO-042-SHOP");assertThrows(IllegalArgumentException.class,() -> schemas.validate("ORDER_HANDLER",rule,List.of(store)));
        rule.put("sourceBuildingCode","FOREIGN");assertThrows(IllegalArgumentException.class,() -> schemas.validate("ORDER_HANDLER",rule,List.of(store)));
        rule.put("sourceBuildingCode","LYO-042-WH");rule.put("targetStock",3);assertThrows(IllegalArgumentException.class,() -> factory.build(rule));
        rule.put("targetStock",20);factory.build(rule).process(store);assertTrue(ReplenishmentOrder.count()>2);
    }
}
