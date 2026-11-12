package com.intermarche.e2e.engine.replenishment;

import com.intermarche.backoffice.auth.DemoDataInitializer;
import com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.replenishment.ReplenishmentResource;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@io.quarkus.test.junit.TestProfile(ReplenishmentWorkflowTest.Isolated.class)
@QuarkusTest @TestTransaction @TestSecurity(user="100234",roles="MANAGER")
public class ReplenishmentWorkflowTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:isolated_ReplenishmentWorkflowTest;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}}

    @Inject DemoDataInitializer seed;
    @Inject ReplenishmentResource api;
    @Inject TemporalStockService stocks;
    @Inject com.intermarche.backoffice.graphql.store.StorePlanResource plans;
    private ReplenishmentResource.Plan plan(ReplenishmentOrder.Operation op,ExpectedSupply sample,int qty) {
        var p=new ReplenishmentResource.Plan();p.storeId=Store.findByCode("LYO-042").id;p.requestKey=UUID.randomUUID().toString();p.operation=op;p.truckReference="CAM-TEST";
        p.start=DateTimeProvider.now().plusMinutes(15);p.end=p.start.plusMinutes(30);
        var line=new ReplenishmentResource.Line();line.productId=sample.account.product.id;line.quantity=BigDecimal.valueOf(qty);
        line.sourceLocationId=sample.source==null?null:sample.source.id;line.destinationLocationId=sample.destination.id;p.lines=List.of(line);return p;
    }
    /**
     * The demo planning seeds directed transits, which activate the position control of handling execution.
     * These scenarios test replenishment execution in a store without spatial configuration.
     */
    private void withoutTransitControl() {
        com.intermarche.backoffice.domain.workforce.BuildingTransit.delete("store",Store.findByCode("LYO-042"));
    }
    private void receive(Long orderId,Long storeId,Long supplyId,int qty,String eventKey) {
        var r=new ReplenishmentResource.Receipt();r.storeId=storeId;r.eventKey=eventKey;
        var line=new ReplenishmentResource.ReceiptLine();line.supplyId=supplyId;line.quantity=BigDecimal.valueOf(qty);r.lines=List.of(line);api.receive(orderId,r);
    }
    @Test void pickerChoicesKeepPlannedAddressAndOfferOtherProductLocationsInStore() {
        seed.seedDemoData();withoutTransitControl();
        var store=Store.findByCode("LYO-042");
        ReplenishmentOrder order=ReplenishmentOrder.find("code","REA-DEMO-RAY").firstResult();
        var supply=order.supplies.get(0);
        var choices=api.confirmationLocations(order.id,supply.id,store.id,"SOURCE");
        assertTrue(choices.stream().anyMatch(row->Boolean.TRUE.equals(row.get("planned")) && Objects.equals(row.get("id"),supply.source.id)));
        assertEquals(true,choices.get(0).get("sameBuilding"));
        for(var row:choices) {
            BaseLocation location=BaseLocation.findById((Long)row.get("id"));
            assertEquals(supply.account.product.id,location.product.id);
            assertTrue(store.buildings.stream().anyMatch(b->Objects.equals(b.id,row.get("buildingId"))));
        }
        var wrong=ExpectedSupply.<ExpectedSupply>listAll().stream().filter(line->!Objects.equals(line.account.product.id,supply.account.product.id)).findFirst().orElseThrow();
        api.start(order.id,store.id);
        var receipt=new ReplenishmentResource.Receipt();receipt.storeId=store.id;receipt.eventKey="wrong-product-address";
        var line=new ReplenishmentResource.ReceiptLine();line.supplyId=supply.id;line.quantity=BigDecimal.ONE;line.locationId=wrong.destination.id;receipt.lines=List.of(line);
        assertThrows(BadRequestException.class,()->api.pick(order.id,receipt));
    }

    @Test void selectedAddressesAreUsedByOperatorConfirmationsAndTheirReplays() {
        seed.seedDemoData();withoutTransitControl();
        var store=Store.findByCode("LYO-042");
        ReplenishmentOrder sample=ReplenishmentOrder.find("code","REA-DEMO-RAY").firstResult();
        Long orderId=(Long)api.create(plan(ReplenishmentOrder.Operation.SHELF_REPLENISHMENT,sample.supplies.get(0),1)).get("id");
        ReplenishmentOrder order=ReplenishmentOrder.findById(orderId);var supply=order.supplies.get(0);api.start(orderId,store.id);
        var pick=new ReplenishmentResource.Receipt();pick.storeId=store.id;pick.eventKey="chosen-location-pick";
        var line=new ReplenishmentResource.ReceiptLine();line.supplyId=supply.id;line.quantity=BigDecimal.ONE;line.locationId=supply.destination.id;pick.lines=List.of(line);
        api.pick(orderId,pick);api.pick(orderId,pick);
        var movement=StockMovement.<StockMovement>find("account=?1 and eventKey=?2",supply.account,"picking:chosen-location-pick:"+supply.id).firstResult();
        assertEquals(line.locationId,movement.location.id);
        var receipt=new ReplenishmentResource.Receipt();receipt.storeId=store.id;receipt.eventKey="chosen-location-receipt";
        var target=new ReplenishmentResource.ReceiptLine();target.supplyId=supply.id;target.quantity=BigDecimal.ONE;target.locationId=supply.source.id;receipt.lines=List.of(target);
        api.receive(orderId,receipt);api.receive(orderId,receipt);
        movement=StockMovement.<StockMovement>find("account=?1 and eventKey=?2",supply.account,"receipt:chosen-location-receipt:"+supply.id).firstResult();
        assertEquals(target.locationId,movement.location.id);
        assertEquals(ExpectedSupply.SupplyStatus.RECEIVED,supply.status);
    }

    @Test void truckAndShelfPlansCreateHandlingOrders_thenReceiptsUpdatePhysicalStockAtomically() {
        seed.seedDemoData();withoutTransitControl();
        ReplenishmentOrder demo=ReplenishmentOrder.find("code","REA-DEMO-CAM").firstResult();ExpectedSupply sample=demo.supplies.get(0);
        BigDecimal physical=stocks.availability(sample.account.id,null,DateTimeProvider.now()).physical;
        long before=ReplenishmentOrder.count();
        var incoming=plan(ReplenishmentOrder.Operation.TRUCK_UNLOADING,sample,24);
        var result=api.create(incoming);Long truckId=(Long)result.get("id");
        assertEquals(before+1,ReplenishmentOrder.count());assertEquals(truckId,api.create(incoming).get("id"));assertEquals(before+1,ReplenishmentOrder.count());
        ReplenishmentOrder truck=ReplenishmentOrder.findById(truckId);
        assertEquals(1,truck.supplies.size());assertEquals(0,physical.compareTo(stocks.availability(sample.account.id,null,DateTimeProvider.now()).physical));
        assertEquals(ExpectedSupply.SupplyStatus.EXPECTED,truck.supplies.get(0).status);
        ReplenishmentOrder demoShelves=ReplenishmentOrder.find("code","REA-DEMO-RAY").firstResult();
        ExpectedSupply transferSample=demoShelves.supplies.stream().filter(s -> s.account.id.equals(sample.account.id)).findFirst().orElseThrow();
        var transferPlan=plan(ReplenishmentOrder.Operation.SHELF_REPLENISHMENT,transferSample,12);
        Long transferId=(Long)api.create(transferPlan).get("id");ReplenishmentOrder transfer=ReplenishmentOrder.findById(transferId);
        receive(truckId,incoming.storeId,truck.supplies.get(0).id,24,"truck-test-event");
        assertEquals(0,physical.add(BigDecimal.valueOf(24)).compareTo(stocks.availability(sample.account.id,null,DateTimeProvider.now()).physical));
        api.start(transferId,incoming.storeId);
        assertEquals(2,com.intermarche.backoffice.domain.executing.ReplenishmentExecution.count("handlingOrder",transfer));
        var picking=new ReplenishmentResource.Receipt();picking.storeId=incoming.storeId;picking.eventKey="warehouse-picking-event";
        var pickedLine=new ReplenishmentResource.ReceiptLine();pickedLine.supplyId=transfer.supplies.get(0).id;pickedLine.quantity=BigDecimal.valueOf(12);picking.lines=List.of(pickedLine);
        assertThrows(BadRequestException.class,() -> receive(transferId,incoming.storeId,pickedLine.supplyId,1,"before-picking-event"));
        BigDecimal totalBeforePick=stocks.availability(sample.account.id,null,DateTimeProvider.now()).physical;
        api.pick(transferId,picking);api.pick(transferId,picking);
        assertEquals(0,BigDecimal.valueOf(12).compareTo(transfer.supplies.get(0).pickedQuantity()));
        assertEquals(0,totalBeforePick.compareTo(stocks.availability(sample.account.id,null,DateTimeProvider.now()).physical));
        assertEquals(1,com.intermarche.backoffice.domain.executing.ReplenishmentExecution.count("handlingOrder = ?1 and status = ?2",transfer,com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.DONE));
        String event="partial-test-event";receive(transferId,incoming.storeId,transfer.supplies.get(0).id,6,event);receive(transferId,incoming.storeId,transfer.supplies.get(0).id,6,event);
        assertEquals(0,BigDecimal.valueOf(6).compareTo(transfer.supplies.get(0).received));
        assertEquals(com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus.PROCESSING,transfer.status);
        receive(transferId,incoming.storeId,transfer.supplies.get(0).id,6,"final-test-event");
        assertEquals(com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus.DONE,transfer.status);
        assertEquals(0,physical.add(BigDecimal.valueOf(24)).compareTo(stocks.availability(sample.account.id,null,DateTimeProvider.now()).physical));
        BigDecimal reserve=StockMovement.<StockMovement>list("account = ?1 and location = ?2",sample.account,sample.destination).stream().map(m -> m.quantity).reduce(BigDecimal.ZERO,BigDecimal::add);
        assertEquals(0,BigDecimal.valueOf(12).compareTo(reserve));
    }
    @Test void reopeningUsesCompensatingMovementsAndPreservesTransit() {
        seed.seedDemoData();withoutTransitControl();
        var store=Store.findByCode("LYO-042");
        ReplenishmentOrder incoming=ReplenishmentOrder.find("code","REA-DEMO-CAM").firstResult();
        ReplenishmentOrder transfer=ReplenishmentOrder.find("code","REA-DEMO-RAY").firstResult();
        ExpectedSupply source=incoming.supplies.get(0),sample=transfer.supplies.stream().filter(v -> v.account.id.equals(source.account.id)).findFirst().orElseThrow();
        transfer=ReplenishmentOrder.findById((Long)api.create(plan(ReplenishmentOrder.Operation.SHELF_REPLENISHMENT,sample,12)).get("id"));
        ExpectedSupply line=transfer.supplies.get(0);
        receive(incoming.id,store.id,source.id,48,"reopen-truck-receipt");
        api.start(transfer.id,store.id);
        var pick=new ReplenishmentResource.Receipt();pick.storeId=store.id;pick.eventKey="reopen-picking-event";
        var quantity=new ReplenishmentResource.ReceiptLine();quantity.supplyId=line.id;quantity.quantity=line.quantity;pick.lines=List.of(quantity);api.pick(transfer.id,pick);
        receive(transfer.id,store.id,line.id,line.quantity.intValueExact(),"reopen-shelf-receipt");
        var reopen=new ReplenishmentResource.ReopenRequest();reopen.storeId=store.id;reopen.supplyId=line.id;reopen.eventKey="reopen-destination-event";reopen.phase="DESTINATION";
        BigDecimal total=stocks.availability(line.account.id,null,DateTimeProvider.now()).physical;
        long before=StockMovement.count();api.reopen(transfer.id,reopen);api.reopen(transfer.id,reopen);
        assertEquals(before+1,StockMovement.count());assertEquals(0,line.received.signum());
        assertEquals(0,line.quantity.compareTo(stocks.availability(line.account.id,null,DateTimeProvider.now()).inTransit));
        assertEquals(0,total.compareTo(stocks.availability(line.account.id,null,DateTimeProvider.now()).physical));
        reopen.phase="SOURCE";reopen.eventKey="reopen-source-event";api.reopen(transfer.id,reopen);
        assertEquals(0,line.pickedQuantity().signum());assertEquals(0,stocks.availability(line.account.id,null,DateTimeProvider.now()).inTransit.signum());
        assertEquals(0,total.compareTo(stocks.availability(line.account.id,null,DateTimeProvider.now()).physical));
    }
    /**
     * Checks that an integration account granted on the store creates an idempotent supply and reads the catalog.
     */
    @Test @TestSecurity(user="external-erp",roles="SERVICE_CALLER")
    void externalCallerCanCreateAnIdempotentSupply() {
        seed.seedDemoData();ReplenishmentOrder demo=ReplenishmentOrder.find("code","REA-DEMO-CAM").firstResult();
        var request=plan(ReplenishmentOrder.Operation.TRUCK_UNLOADING,demo.supplies.get(0),7);
        // An integration account works for the single store of its grants
        com.intermarche.e2e.domain.util.DomainUtils.grantStore(Store.findById(request.storeId),"external-erp");
        var created=api.create(request);assertEquals(created.get("id"),api.create(request).get("id"));
        assertNotNull(api.catalog(request.storeId));
    }
    /**
     * Checks that each loaded cart of a truck delivery gets its own optimised put-away route, limited to what is on it.
     */
    @SuppressWarnings("unchecked")
    @Test void eachLoadedCartGetsItsOwnOptimisedRouteLimitedToItsContent() {
        seed.seedDemoData();withoutTransitControl();
        ReplenishmentOrder truck=ReplenishmentOrder.find("code","REA-DEMO-CAM").firstResult();Long store=Store.findByCode("LYO-042").id;
        var board=api.carts(truck.id,store);
        var proposed=(List<Map<String,Object>>)((Map<String,Object>)board.get("proposal")).get("loads");
        assertFalse(proposed.isEmpty());
        var first=proposed.getFirst();var route=(Map<String,Object>)first.get("route");
        assertNotNull(route.get("distanceMeters"));assertFalse(((List<String>)route.get("addresses")).isEmpty());
        var line=((List<Map<String,Object>>)first.get("lines")).getFirst();
        var confirmation=new com.intermarche.backoffice.engine.replenishment.UnloadingCartService.Confirmation();confirmation.storeId=store;confirmation.requestKey=UUID.randomUUID().toString();
        var cart=new com.intermarche.backoffice.engine.replenishment.UnloadingCartService.CartInput();cart.cartId=((Number)((Map<String,Object>)first.get("cart")).get("id")).longValue();
        var loaded=new com.intermarche.backoffice.engine.replenishment.UnloadingCartService.LineInput();loaded.supplyId=((Number)line.get("supplyId")).longValue();loaded.quantity=BigDecimal.ONE;cart.lines=List.of(loaded);confirmation.loads=List.of(cart);
        var after=api.loadCarts(truck.id,confirmation);
        Long load=((Number)((List<Map<String,Object>>)after.get("loads")).getFirst().get("id")).longValue();
        var phases=(List<Map<String,Object>>)api.route(truck.id,store,load).get("phases");
        assertEquals(1,phases.size());assertTrue(String.valueOf(phases.getFirst().get("label")).startsWith("Ranger le chariot "));assertEquals(load,phases.getFirst().get("cartLoadId"));
        var plans=(Map<Long,com.intermarche.backoffice.engine.executing.PickingPlanService.PickingPlanResponse>)((Map<String,Object>)phases.getFirst().get("plan")).get("buildingPlans");
        var steps=plans.values().iterator().next().route.stream().flatMap(g->g.steps.stream()).toList();
        assertEquals(1,steps.size());assertEquals(loaded.supplyId,steps.getFirst().pickedItemId);assertEquals(0,BigDecimal.ONE.compareTo(steps.getFirst().quantity));
        assertThrows(BadRequestException.class,()->api.route(truck.id,store,999999L));
    }
    @Test void routesOptimizeBothBuildingsAndExcludeReceivedGoods() {
        seed.seedDemoData();withoutTransitControl();
        ReplenishmentOrder truck=ReplenishmentOrder.find("code","REA-DEMO-CAM").firstResult();
        var truckRoute=api.route(truck.id,Store.findByCode("LYO-042").id);
        var phases=(java.util.List<java.util.Map<String,Object>>)truckRoute.get("phases");
        assertEquals(1,phases.size());assertEquals("DESTINATION",phases.get(0).get("key"));
        var batch=(java.util.Map<String,Object>)phases.get(0).get("plan");
        var plans=(java.util.Map<Long,com.intermarche.backoffice.engine.executing.PickingPlanService.PickingPlanResponse>)batch.get("buildingPlans");
        var warehouse=plans.values().iterator().next();
        assertEquals(0,warehouse.mapRoute.unlocated);assertEquals(0,warehouse.mapRoute.disconnected);
        assertTrue(warehouse.mapRoute.distanceMeters>0);assertFalse(warehouse.mapRoute.segments.isEmpty());
        assertEquals(8,warehouse.route.stream().mapToInt(g -> g.steps.size()).sum());
        api.start(truck.id,Store.findByCode("LYO-042").id);
        assertEquals(com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus.PROCESSING,truck.status);
        assertEquals(1,com.intermarche.backoffice.domain.handling.HandlingExecution.count("handlingOrder",truck));
        api.start(truck.id,Store.findByCode("LYO-042").id);
        assertEquals(1,com.intermarche.backoffice.domain.handling.HandlingExecution.count("handlingOrder",truck));
        receive(truck.id,Store.findByCode("LYO-042").id,truck.supplies.get(0).id,48,"route-receipt-event");
        var remainingPhases=(java.util.List<java.util.Map<String,Object>>)api.route(truck.id,Store.findByCode("LYO-042").id).get("phases");
        var remainingBatch=(java.util.Map<String,Object>)remainingPhases.get(0).get("plan");
        var remainingPlans=(java.util.Map<Long,com.intermarche.backoffice.engine.executing.PickingPlanService.PickingPlanResponse>)remainingBatch.get("buildingPlans");
        assertEquals(7,remainingPlans.values().iterator().next().summary.totalItems);
        ReplenishmentOrder shelf=ReplenishmentOrder.find("code","REA-DEMO-RAY").firstResult();
        var transferPhases=(java.util.List<java.util.Map<String,Object>>)api.route(shelf.id,Store.findByCode("LYO-042").id).get("phases");
        assertEquals(2,transferPhases.size());assertEquals("SOURCE",transferPhases.get(0).get("key"));
        assertThrows(ForbiddenException.class,() -> api.route(truck.id,999999L));
    }
    @Test void warehouseLocationsAreVisibleEditableAndKeepTheirWarehouseType() throws Exception {
        seed.seedDemoData();
        var locations=plans.storeLocations("LYO-042-WH");assertEquals(116,locations.size());
        var inputs=locations.stream().map(d -> {
            var value=new com.intermarche.backoffice.graphql.store.StorePlanResource.StoreLocation();
            value.id=d.id;value.area=d.area;value.aisle=d.aisle;value.unit=d.unit;value.elevation=d.elevation;value.position=d.position;value.productEan=d.productEan;value.aislePosition=d.aislePosition;return value;
        }).toList();
        inputs.get(0).aislePosition=0.25;
        plans.saveStoreLocations("LYO-042-WH",inputs);
        assertEquals(116,WarehouseBuilding.<WarehouseBuilding>find("buildingCode","LYO-042-WH").firstResult().locations.stream().filter(l -> org.hibernate.Hibernate.unproxy(l) instanceof WarehouseShelfLocation).count());assertEquals(116,plans.storeLocations("LYO-042-WH").size());
        assertEquals(0.25,WarehouseShelfLocation.<WarehouseShelfLocation>findById(inputs.get(0).id).aislePosition);
    }
    /**
     * Builds a plan whose lines take the products, locations and source of the given supplies.
     *
     * @param op the requested operation
     * @param samples the supplies giving each line's product and locations
     * @return a plan of one unit per line
     */
    private ReplenishmentResource.Plan plan(ReplenishmentOrder.Operation op,List<ExpectedSupply> samples) {
        var p=plan(op,samples.get(0),1);var lines=new ArrayList<ReplenishmentResource.Line>();
        for(ExpectedSupply sample:samples){var line=new ReplenishmentResource.Line();line.productId=sample.account.product.id;line.quantity=BigDecimal.ONE;
            line.sourceLocationId=op==ReplenishmentOrder.Operation.TRUCK_UNLOADING||sample.source==null?null:sample.source.id;line.destinationLocationId=sample.destination.id;lines.add(line);}
        p.lines=lines;return p;
    }
    /**
     * A shelf replenishment refuses food mixed with household products, while food of several temperatures
     * and a truck unloading carrying both remain accepted.
     */
    @Test void shelfReplenishmentNeverMixesFoodAndNonFood() {
        seed.seedDemoData();
        ReplenishmentOrder shelves=ReplenishmentOrder.find("code","REA-DEMO-RAY").firstResult();
        List<ExpectedSupply> pair=shelves.supplies.stream().filter(x -> x.source!=null).collect(java.util.stream.Collectors.toMap(x -> x.account.product.id,x -> x,(a,b) -> a,LinkedHashMap::new)).values().stream().limit(2).toList();
        assertEquals(2,pair.size());
        pair.get(0).account.product.temperatureZone=TemperatureZone.AMBIENT;pair.get(1).account.product.temperatureZone=TemperatureZone.HOUSEHOLD;
        long before=ReplenishmentOrder.count();
        var refused=assertThrows(BadRequestException.class,() -> api.create(plan(ReplenishmentOrder.Operation.SHELF_REPLENISHMENT,pair)));
        assertEquals("Séparez les produits alimentaires et non alimentaires dans des réachalandages distincts",((Map<?,?>)refused.getResponse().getEntity()).get("message"));
        assertEquals(before,ReplenishmentOrder.count());
        assertNotNull(api.create(plan(ReplenishmentOrder.Operation.TRUCK_UNLOADING,pair)).get("id"));
        pair.get(1).account.product.temperatureZone=TemperatureZone.CHILLED;
        assertNotNull(api.create(plan(ReplenishmentOrder.Operation.SHELF_REPLENISHMENT,pair)).get("id"));
    }
    @Test void refusesOtherStoresAndLocations_withoutCreatingOrders() {
        seed.seedDemoData();long before=ReplenishmentOrder.count();
        ReplenishmentOrder demo=ReplenishmentOrder.find("code","REA-DEMO-CAM").firstResult();var p=plan(ReplenishmentOrder.Operation.TRUCK_UNLOADING,demo.supplies.get(0),24);
        p.lines.get(0).destinationLocationId=999999L;
        assertThrows(BadRequestException.class,() -> api.create(p));assertEquals(before,ReplenishmentOrder.count());
        assertThrows(ForbiddenException.class,() -> api.catalog(999999L));
    }
    @Test void mixedSeedSupportsVariableWeightsAndExplicitStockErrors() {
        seed.seedDemoData();withoutTransitControl();
        ReplenishmentOrder mixed=ReplenishmentOrder.find("code","REA-MIX-CAM").firstResult();
        assertNotNull(mixed);assertEquals(8,mixed.supplies.size());
        assertTrue(mixed.supplies.stream().map(s -> s.quantity).distinct().count()>4);
        ExpectedSupply weighted=mixed.supplies.stream().filter(s -> s.account.product.productType==com.intermarche.backoffice.domain.order.ProductType.WEIGHT).findFirst().orElseThrow();
        BigDecimal before=stocks.availability(weighted.account.id,null,DateTimeProvider.now()).physical;
        var receipt=new ReplenishmentResource.Receipt();receipt.storeId=Store.findByCode("LYO-042").id;receipt.eventKey="weight-receipt-test";
        var line=new ReplenishmentResource.ReceiptLine();line.supplyId=weighted.id;line.quantity=new BigDecimal("2.375");receipt.lines=List.of(line);
        var result=api.receive(mixed.id,receipt);
        assertEquals(0,new BigDecimal("2.375").compareTo(weighted.received));
        assertEquals(0,before.add(new BigDecimal("2.375")).compareTo(stocks.availability(weighted.account.id,null,DateTimeProvider.now()).physical));
        var transfer=ReplenishmentOrder.<ReplenishmentOrder>find("code","REA-MIX-RAY").firstResult();
        var unit=transfer.supplies.stream().filter(s -> s.account.product.productType==com.intermarche.backoffice.domain.order.ProductType.UNIT).findFirst().orElseThrow();
        var failure=assertThrows(BadRequestException.class,() -> receive(transfer.id,receipt.storeId,unit.id,1,"no-warehouse-stock"));
        var error=(java.util.Map<String,String>)failure.getResponse().getEntity();
        assertTrue(error.get("message").contains("Terminez le picking"));
    }
    @Test void refusesTransferWhenReserveHasNotBeenReceived() {
        seed.seedDemoData();ReplenishmentOrder demo=ReplenishmentOrder.find("code","REA-DEMO-RAY").firstResult();
        var p=plan(ReplenishmentOrder.Operation.SHELF_REPLENISHMENT,demo.supplies.get(0),12);Long id=(Long)api.create(p).get("id");ReplenishmentOrder order=ReplenishmentOrder.findById(id);
        long movements=StockMovement.count();
        assertThrows(BadRequestException.class,() -> receive(id,p.storeId,order.supplies.get(0).id,12,"too-early-event"));
        assertEquals(movements,StockMovement.count());assertEquals(0,order.supplies.get(0).received.signum());
    }
    /**
     * Counts the SQL statements prepared by an action.
     *
     * @param action the action
     * @return the number of statements
     */
    private long statements(Runnable action) {
        var statistics=jakarta.enterprise.inject.spi.CDI.current().select(jakarta.persistence.EntityManager.class).get().getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean enabled=statistics.isStatisticsEnabled();statistics.setStatisticsEnabled(true);statistics.clear();
        try{action.run();return statistics.getPrepareStatementCount();}finally{statistics.setStatisticsEnabled(enabled);}
    }
    /**
     * The editor catalog only holds the buildings, buffers and free totes, and its cost does not grow with the number of totes.
     */
    @Test void editorCatalogIsLight_freeTotesAreLoadedInOneQuery() {
        seed.seedDemoData();Store store=Store.findByCode("LYO-042");
        var catalog=api.catalog(store.id);
        assertEquals(Set.of("buildings","buffers","totes"),catalog.keySet());
        long inUse=com.intermarche.backoffice.domain.executing.LoadedTote.count("select count(distinct l.tote) from LoadedTote l where l.tote.store=?1 and l.status=?2",store,com.intermarche.backoffice.domain.executing.LoadedTote.Status.IN_USE);
        assertEquals(Tote.count("store",store)-inUse,((List<?>)catalog.get("totes")).size());
        long few=statements(() -> api.catalog(store.id));
        for(int i=0;i<20;i++){var tote=new Tote();tote.code="CAT-EXTRA-"+i;tote.store=store;tote.toteType=Tote.ToteType.STANDARD;tote.volume=new BigDecimal("40");tote.persist();}
        com.intermarche.backoffice.domain.store.Tote.getEntityManager().flush();
        assertTrue(statements(() -> api.catalog(store.id))<=few,"the free totes must not cost one query each");
        assertEquals(Tote.count("store",store)-inUse,((List<?>)api.catalog(store.id).get("totes")).size());
    }
    /**
     * Products are searched by name or EAN with a bounded page, wildcards taken literally, or loaded by identifier or EAN, within the scope of the user.
     */
    @Test void productsAreSearchedByNameOrEan_withABoundedPage_orLoadedByIdentifier() {
        seed.seedDemoData();Long store=Store.findByCode("LYO-042").id;
        var byName=api.products(store,"NUTELLA",null,null,null);
        assertTrue(((List<?>)byName.get("items")).stream().anyMatch(p -> "3017620422003".equals(((Map<?,?>)p).get("ean"))));assertEquals(false,byName.get("more"));
        var byEan=(List<?>)api.products(store,"3017620422003",null,null,null).get("items");
        assertEquals(1,byEan.size());assertEquals(Set.of("id","ean","name","unit","productType","temperatureZone","brand","characteristics","description","active","packaging"),((Map<?,?>)byEan.getFirst()).keySet());assertEquals(true,((Map<?,?>)byEan.getFirst()).get("active"));
        var first=api.products(store,null,null,null,1);assertEquals(1,((List<?>)first.get("items")).size());assertEquals(true,first.get("more"));
        assertEquals(1,((List<?>)api.products(store,"  ",null,null,0).get("items")).size(),"the page holds at least one product");
        long total=com.intermarche.backoffice.domain.order.Product.count();
        assertEquals(Math.min(total,100),((List<?>)api.products(store,"",null,null,1000).get("items")).size(),"the page holds at most one hundred products");
        var percent=(List<?>)api.products(store,"%",null,null,null).get("items");
        assertFalse(percent.isEmpty());assertTrue(percent.stream().allMatch(p -> String.valueOf(((Map<?,?>)p).get("name")).contains("%")),"wildcards are searched literally");
        assertTrue(((List<?>)api.products(store,"_",null,null,null).get("items")).isEmpty(),"wildcards are searched literally");
        var nutella=(Long)((Map<?,?>)byEan.getFirst()).get("id");var other=com.intermarche.backoffice.domain.order.Product.<com.intermarche.backoffice.domain.order.Product>find("ean <> ?1","3017620422003").firstResult().id;
        var byIds=(List<?>)api.products(store,"ignored",nutella+", "+other+","+nutella,"3017620422003",1).get("items");
        assertEquals(Set.of(nutella,other),byIds.stream().map(p -> ((Map<?,?>)p).get("id")).collect(java.util.stream.Collectors.toSet()));
        assertTrue(((List<?>)api.products(store,null," , ",null,null).get("items")).isEmpty());
        var byEans=(List<?>)api.products(store,"ignored",null," 3017620422003 ,unknown-ean,3017620422003",1).get("items");
        assertEquals(List.of(nutella),byEans.stream().map(p -> ((Map<?,?>)p).get("id")).toList());
        assertTrue(((List<?>)api.products(store,null,null," , ",null).get("items")).isEmpty());
        assertThrows(BadRequestException.class,() -> api.products(store,null,"12,abc",null,null));
        String tooMany=String.join(",",java.util.stream.LongStream.rangeClosed(1,101).mapToObj(String::valueOf).toList());
        assertThrows(BadRequestException.class,() -> api.products(store,null,tooMany,null,null));
        assertThrows(BadRequestException.class,() -> api.products(store,null,null,tooMany,null));
        assertThrows(ForbiddenException.class,() -> api.products(999999L,"a",null,null,null));
    }
    /**
     * Addresses are loaded for the selected products of one building of the store, with the unassigned addresses and without the technical ones.
     */
    @Test void locationsAreLoadedForTheSelectedProductsOfOneBuildingOfTheStore() {
        seed.seedDemoData();Store store=Store.findByCode("LYO-042");
        StoreBuilding shop=StoreBuilding.find("buildingCode","LYO-042-SHOP").firstResult();
        StoreShelfLocation shelf=StoreShelfLocation.<StoreShelfLocation>list("product is not null").stream().filter(l -> shop.locations.stream().anyMatch(x -> x.id.equals(l.id))).findFirst().orElseThrow();
        var generic=new StoreShelfLocation();generic.area="RESERVE";generic.aisle="LIBRE";generic.unit="U1";generic.position="1";generic.elevation="1";generic.persist();shop.locations.add(generic);
        var pending=new PendingSupplyLocation();pending.area="EN_ATTENTE";pending.aisle="REAPPRO";pending.unit="CIBLE";pending.position="1";pending.elevation="1";pending.persist();shop.locations.add(pending);
        StoreShelfLocation.getEntityManager().flush();
        Long product=shelf.product.id;
        var found=api.locations(store.id,shop.id," "+product+" ,");
        assertTrue(found.stream().anyMatch(l -> shelf.id.equals(l.get("id")) && product.equals(l.get("productId")) && shop.id.equals(l.get("buildingId"))));
        assertTrue(found.stream().anyMatch(l -> generic.id.equals(l.get("id")) && l.get("productId")==null));
        assertTrue(found.stream().allMatch(l -> l.get("productId")==null || product.equals(l.get("productId"))));
        assertTrue(found.stream().noneMatch(l -> pending.id.equals(l.get("id"))),"pending supply addresses are not storage addresses");
        assertTrue(api.locations(store.id,shop.id,null).stream().allMatch(l -> l.get("productId")==null));
        assertThrows(BadRequestException.class,() -> api.locations(store.id,999999L,String.valueOf(product)));
        assertThrows(BadRequestException.class,() -> api.locations(store.id,shop.id,"x"));
        String tooMany=String.join(",",java.util.stream.LongStream.rangeClosed(1,101).mapToObj(String::valueOf).toList());
        assertThrows(BadRequestException.class,() -> api.locations(store.id,shop.id,tooMany));
    }
}
