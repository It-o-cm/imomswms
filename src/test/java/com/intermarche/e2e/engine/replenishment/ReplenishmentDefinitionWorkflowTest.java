package com.intermarche.e2e.engine.replenishment;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.replenishment.*;
import com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.engine.replenishment.*;
import com.intermarche.backoffice.engine.executing.BufferOccupancyService;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import com.intermarche.backoffice.graphql.stock.TemporalStockResource;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.BadRequestException;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
@QuarkusTest @TestTransaction @TestSecurity(user="buffer-test",roles={"SERVICE_CALLER","MANAGER"})
class ReplenishmentDefinitionWorkflowTest {
    @Inject ReplenishmentDefinitionResource definitions;
    @Inject ReplenishmentResource orders;
    @Inject TemporalStockService stocks;
    @Inject BufferOccupancyService buffers;
    @Inject TemporalStockResource stockApi;
    @Inject EntityManager em;
    @Inject com.intermarche.backoffice.engine.StoreActivityEngine activity;
    record Fixture(Store store,StoreBuilding building,StoreShelfLocation shelf,Product product,Buffer buffer,Tote tote){}
    Fixture fixture(){
        Store store=new Store();store.code="BUFFER-TEST";store.name="Buffers";store.persist();
        StoreBuilding b=new StoreBuilding();b.buildingCode="BUFFER-TEST-SHOP";b.persist();
        Product p=new Product();p.ean="9990102030405";p.name="Produit test";p.productType=ProductType.UNIT;p.temperatureZone=TemperatureZone.AMBIENT;p.referenceVolume=BigDecimal.ONE;p.persist();
        StoreShelfLocation l=new StoreShelfLocation();l.product=p;l.area="EPICERIE";l.aisle="A1";l.unit="B1";l.elevation="1";l.position="1";l.persist();b.locations.add(l);store.buildings.add(b);
        Buffer buffer=new Buffer();buffer.building=b;buffer.bufferCode="BUF-TEST";buffer.locationDescription="Dépôt";buffer.active=true;buffer.ambientCapacity=1;buffer.chilledCapacity=0;buffer.frozenCapacity=0;buffer.persist();
        // Handling execution is performed by an identified employee of the store.
        Employee operator=new Employee();operator.badgeId="buffer-test";operator.firstName="Opérateur";operator.lastName="Buffer";operator.email="buffer-test@example.test";operator.password=org.mindrot.jbcrypt.BCrypt.hashpw("test-password",org.mindrot.jbcrypt.BCrypt.gensalt(4));operator.role=Employee.EmployeeRole.MANAGER;operator.store=store;operator.persist();
        Tote tote=new Tote();tote.code="BAC-TEST";tote.store=store;tote.toteType=Tote.ToteType.STANDARD;tote.volume=new BigDecimal("50");tote.persistAndFlush();
        return new Fixture(store,b,l,p,buffer,tote);
    }
    ReplenishmentDefinitionResource.DefinitionRequest request(Fixture f){
        var r=new ReplenishmentDefinitionResource.DefinitionRequest();r.storeId=f.store.id;r.requestKey="definition-test-0001";r.operation=ReplenishmentOrder.Operation.TRUCK_UNLOADING;r.truckReference="TRUCK-1";r.destinationBuildingId=f.building.id;r.start=DateTimeProvider.now().plusHours(1);r.end=r.start.plusHours(1);
        var line=new ReplenishmentDefinitionResource.DefinitionLine();line.productId=f.product.id;line.quantity=BigDecimal.TEN;r.lines=List.of(line);return r;
    }
    ReplenishmentDefinitionResource.Allocation allocation(Fixture f,int qty,boolean buffer){
        var a=new ReplenishmentDefinitionResource.Allocation();a.productId=f.product.id;a.quantity=BigDecimal.valueOf(qty);
        if(buffer){a.bufferId=f.buffer.id;a.toteCode=f.tote.code;}else a.destinationLocationId=f.shelf.id;return a;
    }
    @Test void forecastThenSeparateHandlingSplitsOneProductAcrossShelfAndBufferWithoutDoubleCounting(){
        var f=fixture();var r=request(f);var created=definitions.create(r);Long id=(Long)created.get("id");
        assertNull(created.get("handlingOrder"));assertEquals(0,ReplenishmentOrder.count("store",f.store));
        var line=(Map<?,?>)((List<?>)created.get("lines")).getFirst();assertEquals(f.product.temperatureZone,line.get("temperatureZone"));
        assertEquals(id,definitions.create(r).get("id"));
        var account=stocks.account(f.store.id,f.product.id);
        assertEquals(0,BigDecimal.TEN.compareTo(stocks.availability(account.id,f.building.id,DateTimeProvider.now()).periods.getLast().physicalAndExpected));
        var handling=new ReplenishmentDefinitionResource.HandlingRequest();handling.storeId=f.store.id;handling.requestKey="handling-test-0001";handling.allocations=List.of(allocation(f,6,false),allocation(f,4,true));
        var linked=definitions.createHandling(id,handling);assertNotNull(linked.get("handlingOrder"));
        assertEquals(linked.get("handlingOrder"),definitions.createHandling(id,handling).get("handlingOrder"));
        assertEquals(0,BigDecimal.TEN.compareTo(stocks.availability(account.id,f.building.id,DateTimeProvider.now()).periods.getLast().physicalAndExpected));
        var definition=ReplenishmentDefinition.<ReplenishmentDefinition>findById(id);var order=definition.handlingOrder;
        PlanNode entrance=new PlanNode();entrance.building=f.building;entrance.code="ENTRY";entrance.nodeType=PlanNodeType.ENTRANCE;entrance.x=0d;entrance.y=0d;entrance.persist();
        PlanNode exit=new PlanNode();exit.building=f.building;exit.code="END";exit.nodeType=PlanNodeType.DEADEND;exit.x=100d;exit.y=0d;exit.persist();
        Aisle aisle=new Aisle();aisle.building=f.building;aisle.code="A1";aisle.lengthMeters=100d;aisle.headNode=entrance;aisle.tailNode=exit;aisle.flowType="LINEAR";aisle.shapeType="STRAIGHT";aisle.persistAndFlush();
        assertEquals(1,((List<?>)orders.route(order.id,f.store.id).get("phases")).size(),"An unpositioned buffer must not crash the execution route");
        var plannedLines=(List<Map<String,Object>>)orders.detail(order.id,f.store.id).get("lines");
        assertEquals("BAC-TEST",plannedLines.stream().filter(v->v.containsKey("bufferId")).findFirst().orElseThrow().get("destinationToteCode"),"The picker receives the actual destination tote identity without parsing its stock address");
        assertEquals(2,order.supplies.size());assertTrue(buffers.occupancy(f.store).getFirst().totes.isEmpty(),"A planned tote is not physically deposited yet");
        var receipt=new ReplenishmentResource.Receipt();receipt.storeId=f.store.id;receipt.eventKey="receipt-test-0001";receipt.lines=order.supplies.stream().map(s->{var l=new ReplenishmentResource.ReceiptLine();l.supplyId=s.id;l.quantity=s.quantity;return l;}).toList();
        orders.receive(order.id,receipt);em.flush();
        var occupied=buffers.occupancy(f.store).getFirst();assertEquals(1,occupied.totes.size());assertEquals("BAC-TEST",occupied.totes.getFirst().code);assertEquals(0,occupied.zones.getFirst().remaining);
        assertEquals(0,BigDecimal.TEN.compareTo(stocks.availability(account.id,f.building.id,DateTimeProvider.now()).physical));
        assertEquals(1,stockApi.stockPage(f.store.code,f.building.id,null,"ALL",null,null,null,null,null,null,null,f.buffer.id,false,24,null,30).items.size());
        assertEquals(1,stockApi.stockPage(f.store.code,f.building.id,null,"ALL",null,null,null,null,"EPICERIE","A1","B1",null,false,24,null,30).items.size());
        assertTrue(stockApi.stockPage(f.store.code,f.building.id,null,"ALL",null,null,null,null,"ABSENT",null,null,null,false,24,null,30).items.isEmpty());
        var opts=stockApi.stockPlacementOptions(f.store.code,f.building.id);assertEquals(1,opts.addresses.size());assertEquals(1,opts.buffers.size());
    }
    /**
     * The list shows what is still to handle, soonest first; closed replenishments are read page by page, latest first.
     */
    @Test void listShowsOpenReplenishmentsAndPagesTheClosedOnes(){
        var f=fixture();var ids=new ArrayList<Long>();
        for(int i=0;i<4;i++){var r=request(f);r.requestKey="definition-list-000"+i;r.start=DateTimeProvider.now().plusHours(4-i);r.end=r.start.plusHours(1);ids.add((Long)definitions.create(r).get("id"));}
        for(int i=0;i<2;i++){var handling=new ReplenishmentDefinitionResource.HandlingRequest();handling.storeId=f.store.id;handling.requestKey="handling-list-000"+i;handling.allocations=List.of(allocation(f,10,false));definitions.createHandling(ids.get(i),handling);}
        ReplenishmentDefinition.<ReplenishmentDefinition>findById(ids.get(0)).handlingOrder.status=com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus.DONE;
        ReplenishmentDefinition.<ReplenishmentDefinition>findById(ids.get(1)).handlingOrder.status=com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus.CANCELLED;em.flush();
        var open=definitions.list(f.store.id,false,0,30).stream().filter(v->ids.contains(v.get("id"))).map(v->v.get("id")).toList();
        assertEquals(List.of(ids.get(3),ids.get(2)),open);
        var closed=definitions.list(f.store.id,true,0,30).stream().filter(v->ids.contains(v.get("id"))).map(v->v.get("id")).toList();
        assertEquals(List.of(ids.get(0),ids.get(1)),closed);
        assertEquals(1,definitions.list(f.store.id,true,0,1).size());
        assertEquals(definitions.list(f.store.id,true,1,1).getFirst().get("id"),definitions.list(f.store.id,true,0,2).get(1).get("id"));
        for(int[] bad:new int[][]{{-1,30},{0,0},{0,101}})assertThrows(BadRequestException.class,()->definitions.list(f.store.id,true,bad[0],bad[1]));
    }
    @Test void incompleteDistributionIsRejectedAndForecastRemains(){
        var f=fixture();Long id=(Long)definitions.create(request(f)).get("id");var r=new ReplenishmentDefinitionResource.HandlingRequest();r.storeId=f.store.id;r.requestKey="handling-test-0002";r.allocations=List.of(allocation(f,3,false));
        assertThrows(BadRequestException.class,()->definitions.createHandling(id,r));assertEquals(0,ReplenishmentOrder.count("store",f.store));
    }
    @Test void depositChecksCapacityTemperatureStoreAndCountsOnlyActiveTotes(){
        var f=fixture();var load=LoadedTote.useAnEmptyTote(f.tote);load.storageType=TemperatureZone.AMBIENT;load.persistAndFlush();buffers.deposit(load,f.buffer.id,f.store);
        buffers.deposit(load,f.buffer.id,f.store);assertEquals(1,buffers.occupancy(f.store).getFirst().zones.getFirst().occupied);
        Tote t=new Tote();t.code="BAC-TEST-2";t.store=f.store;t.volume=new BigDecimal("50");t.toteType=Tote.ToteType.STANDARD;t.persist();
        var second=LoadedTote.useAnEmptyTote(t);second.storageType=TemperatureZone.AMBIENT;second.persistAndFlush();
        assertThrows(IllegalArgumentException.class,()->buffers.deposit(second,f.buffer.id,f.store));
        load.status=LoadedTote.Status.DELIVERED;em.flush();buffers.deposit(second,f.buffer.id,f.store);
        assertEquals("BAC-TEST-2",buffers.occupancy(f.store).getFirst().totes.getFirst().code);
        second.storageType=TemperatureZone.FROZEN;assertThrows(IllegalArgumentException.class,()->buffers.deposit(second,f.buffer.id,f.store));
    }
    @Test void scheduledOrderPastItsWindowEmitsOneEventButDoneOrFullyExecutedOrdersDoNot(){
        var f=fixture();
        var late=new ReplenishmentOrder();late.store=f.store;late.code="LATE-TEST";late.operation=ReplenishmentOrder.Operation.TRUCK_UNLOADING;late.destinationBuilding=f.building;late.requestedStart=DateTimeProvider.now().minusHours(2);late.requestedEnd=DateTimeProvider.now().minusHours(1);late.status=com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;late.persistAndFlush();
        activity.checkAndEmitLateHandlingOrders(f.store);activity.checkAndEmitLateHandlingOrders(f.store);
        assertEquals(1,com.intermarche.backoffice.domain.Event.count("store=?1 and label=?2",f.store,"Retard Handling Order"));
        var done=new ReplenishmentOrder();done.store=f.store;done.code="DONE-TEST";done.operation=late.operation;done.destinationBuilding=f.building;done.requestedEnd=late.requestedEnd;done.status=com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus.DONE;done.persistAndFlush();
        activity.checkAndEmitLateHandlingOrders(f.store);
        assertEquals(1,com.intermarche.backoffice.domain.Event.count("store=?1 and label=?2",f.store,"Retard Handling Order"));
    }

}
