package com.intermarche.e2e.engine.replenishment;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.stock.StockMovement;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.oms.*;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.shipping.*;
import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.storeops.StoreOperationsResource;
import com.intermarche.backoffice.engine.shipping.ShipmentResource;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import com.intermarche.backoffice.engine.executing.BufferOccupancyService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@io.quarkus.test.junit.TestProfile(StoreOperationsWorkflowTest.Isolated.class)
@QuarkusTest @TestTransaction @TestSecurity(user="ops-user",roles={"ADMIN"})
class StoreOperationsWorkflowTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:isolated_StoreOperationsWorkflowTest;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}}

    @Inject com.intermarche.backoffice.auth.DemoBufferInitializer demoBuffers;
    @Inject StoreOperationsResource operations;@Inject ShipmentResource shipping;@Inject TemporalStockService stocks;@Inject BufferOccupancyService buffers;@Inject EntityManager em;
    record Fixture(Store store,StoreBuilding building,Buffer buffer,Employee employee){}
    Fixture fixture(){
        var s=new Store();s.code="OPS-STORE";s.name="Opérations";s.persist();
        var b=new StoreBuilding();b.buildingCode="OPS-SHOP";b.persist();s.buildings.add(b);
        var buffer=new Buffer();buffer.building=b;buffer.bufferCode="OPS-BUFFER";buffer.active=true;buffer.ambientCapacity=20;buffer.persist();
        var e=new Employee();e.firstName="Marie";e.lastName="Test";e.badgeId="ops-user";e.email="ops@example.test";e.password=org.mindrot.jbcrypt.BCrypt.hashpw("test-password",org.mindrot.jbcrypt.BCrypt.gensalt());e.role=Employee.EmployeeRole.MANAGER;e.store=s;e.persistAndFlush();
        return new Fixture(s,b,buffer,e);
    }
    @Inject com.intermarche.backoffice.engine.storeops.EmployeeTransitService employeeTransit;
    @Test void toteContentSeparatesActualLotBalancesDatesAndSerials(){
        var f=fixture();var product=new Product();product.ean="AUDIT-LOT-P";product.name="Produit lot";product.productType=ProductType.UNIT;product.temperatureZone=TemperatureZone.AMBIENT;product.persist();
        var physical=new Tote();physical.store=f.store;physical.code="AUDIT-LOT-BAC";physical.toteType=Tote.ToteType.STANDARD;physical.volume=new BigDecimal("40");physical.persist();
        var load=LoadedTote.useAnEmptyTote(physical);load.storageType=TemperatureZone.AMBIENT;load.buffer=f.buffer;load.persist();
        var loc=new BufferToteStockLocation();loc.product=product;loc.loadedTote=load;loc.buffer=f.buffer;loc.area="BUFFER";loc.aisle="A";loc.unit="B1";loc.position="1";loc.elevation="1";loc.persistAndFlush();f.building.locations.add(loc);var account=stocks.account(f.store.id,product.id);
        for(int n=1;n<=2;n++){
            var lot=new com.intermarche.backoffice.domain.stock.ManufacturerLot();lot.store=f.store;lot.product=product;lot.code="LOT-"+n;if(n==1)lot.useBy=java.time.LocalDate.of(2030,1,1);else lot.bestBefore=java.time.LocalDate.of(2030,2,1);lot.persist();
            var segment=new com.intermarche.backoffice.domain.stock.StockSegment();segment.account=account;segment.location=loc;segment.lot=lot;segment.quality="AVAILABLE";segment.segmentKey="AUDIT-LOT-"+n;
            if(n==2){var serial=new com.intermarche.backoffice.domain.stock.ProductSerial();serial.store=f.store;serial.product=product;serial.lot=lot;serial.code="SERIE-1";serial.claimKey="AUDIT-SERIAL";serial.persist();segment.serial=serial;}segment.persist();
            var movement=new StockMovement();movement.account=account;movement.location=loc;movement.segment=segment;movement.eventKey="AUDIT-LOT-"+n;movement.reason="ADJUSTMENT";movement.quantity=BigDecimal.valueOf(n==1?2:1);movement.effectiveAt=DateTimeProvider.now();movement.persist();
        }em.flush();
        var view=operations.toteContents(physical.id,f.store.id);var rows=(List<Map<String,Object>>)view.get("traceItems");assertEquals(2,rows.size());
        var first=rows.stream().filter(r->"LOT-1".equals(r.get("manufacturerLotCode"))).findFirst().orElseThrow();var second=rows.stream().filter(r->"LOT-2".equals(r.get("manufacturerLotCode"))).findFirst().orElseThrow();
        assertEquals(0,new BigDecimal("2").compareTo((BigDecimal)first.get("quantity")));assertEquals(java.time.LocalDate.of(2030,1,1),first.get("useBy"));assertEquals(java.time.LocalDate.of(2030,2,1),second.get("bestBefore"));assertEquals(List.of("SERIE-1"),second.get("serialCodes"));
        var original=(Map<?,?>)((List<?>)view.get("items")).getFirst();assertEquals(loc.id,original.get("id"));assertEquals(0,new BigDecimal("3").compareTo((BigDecimal)original.get("quantity")));
    }
    @Test void toteContentIdentifiesItsCartSlotWithoutChangingCustody(){
        var f=fixture();
        var type=new com.intermarche.backoffice.domain.fieldops.SupportType();type.store=f.store;type.code="AUDIT-TYPE";type.label="Chariot";type.kind=com.intermarche.backoffice.domain.fieldops.SupportType.Kind.CART;type.persist();
        var cartTote=new Tote();cartTote.store=f.store;cartTote.code="AUDIT-CART";cartTote.toteType=Tote.ToteType.STANDARD;cartTote.volume=new BigDecimal("100");cartTote.persist();
        var tote=new Tote();tote.store=f.store;tote.code="AUDIT-TOTE";tote.toteType=Tote.ToteType.STANDARD;tote.volume=new BigDecimal("40");tote.persist();
        var cart=new com.intermarche.backoffice.domain.fieldops.PhysicalSupport();cart.store=f.store;cart.code=cartTote.code;cart.tote=cartTote;cart.type=type;cart.persist();
        var support=new com.intermarche.backoffice.domain.fieldops.PhysicalSupport();support.store=f.store;support.code=tote.code;support.tote=tote;support.type=type;support.persist();
        var cell=new com.intermarche.backoffice.domain.fieldops.CartCompartment();cell.cart=cart;cell.rowNumber=1;cell.columnNumber=1;cell.label="1";cell.occupant=support;cell.persist();em.flush();
        var view=operations.toteContents(tote.id,f.store.id);var slot=(Map<?,?>)view.get("cartSlot");
        assertEquals(cart.id,slot.get("cartId"));assertEquals(cell.id,slot.get("compartmentId"));assertSame(support,cell.occupant);assertTrue(((List<?>)view.get("items")).isEmpty());
    }
    @Test void declaredMovementKeepsDurationWithoutBlockingOperatorWork(){
        var f=fixture();var warehouse=new WarehouseBuilding();warehouse.buildingCode="OPS-TRANSIT-WH";warehouse.persistAndFlush();f.store.buildings.add(warehouse);var transit=new StoreOperationsResource.TransitInput();transit.storeId=f.store.id;transit.originId=f.building.id;transit.destinationId=warehouse.id;transit.minutes=12;operations.saveTransit(transit);
        var original=DateTimeProvider.getFixedDateTime();var now=DateTimeProvider.now();DateTimeProvider.setFixedDateTime(now);
        try{var position=new StoreOperationsResource.PositionInput();position.storeId=f.store.id;position.buildingId=f.building.id;operations.moveEmployee(f.employee.id,position);var order=new com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder();order.store=f.store;order.assignee=f.employee;order.sourceBuilding=warehouse;order.destinationBuilding=warehouse;order.code="OPS-TRANSIT";order.persist();
            assertDoesNotThrow(()->employeeTransit.require(f.employee,order,warehouse));position.buildingId=warehouse.id;var response=(Map<?,?>)operations.moveEmployee(f.employee.id,position);assertEquals(now.plusMinutes(12),response.get("availableAt"));assertDoesNotThrow(()->employeeTransit.require(f.employee,order,warehouse));assertThrows(jakarta.ws.rs.BadRequestException.class,()->{position.buildingId=f.building.id;operations.moveEmployee(f.employee.id,position);});DateTimeProvider.setFixedDateTime(now.plusMinutes(12));assertDoesNotThrow(()->employeeTransit.require(f.employee,order,warehouse));assertThrows(jakarta.ws.rs.BadRequestException.class,()->operations.moveEmployee(f.employee.id,position));
        }finally{if(original==null)DateTimeProvider.clear();else DateTimeProvider.setFixedDateTime(original);}
    }
    @Test void workDoesNotRequireAnEmployeePositionEvenWithConfiguredRoutes(){
        var f=fixture();var warehouse=new WarehouseBuilding();warehouse.buildingCode="OPS-NO-POSITION-WH";warehouse.persistAndFlush();f.store.buildings.add(warehouse);
        var route=new com.intermarche.backoffice.domain.workforce.BuildingTransit();route.store=f.store;route.origin=f.building;route.destination=warehouse;route.minutes=12;route.persistAndFlush();
        var order=new com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder();order.store=f.store;order.assignee=f.employee;order.sourceBuilding=warehouse;order.destinationBuilding=warehouse;order.code="OPS-NO-POSITION";order.persistAndFlush();
        assertNull(employeeTransit.position(f.employee));
        assertDoesNotThrow(()->employeeTransit.require(f.employee,order,warehouse));
        assertNull(employeeTransit.position(f.employee));
    }
    @Test void globalPlanningPreviewsApplyAtomicallyAndRejectStaleConstraints(){
        var f=fixture();var warehouse=new WarehouseBuilding();warehouse.buildingCode="OPS-WH";warehouse.persistAndFlush();f.store.buildings.add(warehouse);
        var day=DateTimeProvider.now().toLocalDate().plusDays(2);var shift=new StoreOperationsResource.PlanningInput();shift.storeId=f.store.id;shift.employeeId=f.employee.id;shift.kind="SHIFT";shift.title="Présence magasin";shift.start=day.atTime(8,0);shift.end=day.atTime(18,0);shift.buildingId=f.building.id;operations.savePlanning(shift);
        var transit=new StoreOperationsResource.TransitInput();transit.storeId=f.store.id;transit.originId=f.building.id;transit.destinationId=warehouse.id;transit.minutes=15;operations.saveTransit(transit);transit.originId=warehouse.id;transit.destinationId=f.building.id;transit.minutes=15;operations.saveTransit(transit);
        for(int i=0;i<2;i++){var task=new StoreOperationsResource.PlanningInput();task.storeId=f.store.id;task.employeeId=f.employee.id;task.kind="TASK";task.title="Inventaire "+i;task.start=day.atTime(9,0);task.end=day.atTime(10,0);task.buildingId=i==0?f.building.id:warehouse.id;operations.savePlanning(task);}
        var request=new com.intermarche.backoffice.engine.storeops.GlobalPlanningService.Request();request.storeId=f.store.id;request.start=day.atTime(8,0);request.end=day.atTime(18,0);request.flexibilityMinutes=90;
        var preview=(Map<String,Object>)operations.planningSolutions(request);var result=(com.intermarche.backoffice.engine.storeops.ConstrainedPlanningSolver.Result)preview.get("result");assertTrue(result.solutions().size()>1);assertEquals(15,result.solutions().getFirst().transitMinutes());
        var apply=new com.intermarche.backoffice.engine.storeops.GlobalPlanningService.Apply();apply.storeId=f.store.id;apply.previewId=(String)preview.get("previewId");apply.solution=0;
        // No writes during preview: the two original tasks still overlap.
        var tasks=com.intermarche.backoffice.domain.workforce.PlanningEntry.<com.intermarche.backoffice.domain.workforce.PlanningEntry>list("employee=?1 and kind='TASK' order by id",f.employee);assertEquals(tasks.get(0).startsAt,tasks.get(1).startsAt);
        var absence=new StoreOperationsResource.PlanningInput();absence.storeId=f.store.id;absence.employeeId=f.employee.id;absence.kind="ABSENCE";absence.title="Indisponible";absence.start=day.atTime(16,0);absence.end=day.atTime(17,0);var saved=operations.savePlanning(absence);assertThrows(jakarta.ws.rs.BadRequestException.class,()->operations.applyPlanning(apply));operations.cancelEntry((Long)saved.get("entryId"),f.store.id);
        preview=(Map<String,Object>)operations.planningSolutions(request);apply.previewId=(String)preview.get("previewId");assertEquals(true,((Map<?,?>)operations.applyPlanning(apply)).get("applied"));
        assertEquals(1,com.intermarche.backoffice.domain.workforce.PlanningEntry.count("employee=?1 and kind='TRANSIT' and cancelled=false",f.employee));assertNotEquals(tasks.get(0).startsAt,tasks.get(1).startsAt);assertEquals(true,((Map<?,?>)operations.applyPlanning(apply)).get("replayed"));
        var other=new Store();other.code="OTHER-SCOPE";other.name="Autre";other.persistAndFlush();apply.storeId=other.id;assertThrows(jakarta.ws.rs.BadRequestException.class,()->operations.applyPlanning(apply));
    }
    @Test void assignmentProposalsExcludeAbsenceConflictsAndUnqualifiedEmployeesAndRevalidateOnSave(){
        var f=fixture();var start=DateTimeProvider.now().toLocalDate().atTime(10,0);
        var second=new Employee();second.firstName="Alex";second.lastName="Disponible";second.badgeId="ops-second";second.email="second@example.test";second.password=f.employee.password;second.store=f.store;second.active=true;second.role=Employee.EmployeeRole.PICKER;second.persist();
        var cashier=new Employee();cashier.firstName="Camille";cashier.lastName="Caisse";cashier.badgeId="ops-cashier";cashier.email="cashier@example.test";cashier.password=f.employee.password;cashier.store=f.store;cashier.active=true;cashier.role=Employee.EmployeeRole.CASHIER;cashier.persistAndFlush();
        for(var e:List.of(f.employee,second,cashier)){var shift=new StoreOperationsResource.PlanningInput();shift.storeId=f.store.id;shift.employeeId=e.id;shift.kind="SHIFT";shift.title="Présence";shift.start=start.minusHours(1);shift.end=start.plusHours(7);operations.savePlanning(shift);}
        var task=new StoreOperationsResource.PlanningInput();task.storeId=f.store.id;task.employeeId=f.employee.id;task.kind="TASK";task.title="Inventaire";task.start=start.minusHours(1);task.end=start;operations.savePlanning(task);
        var request=new StoreOperationsResource.PlanningInput();request.storeId=f.store.id;request.kind="HANDLING";request.start=start;request.end=start.plusHours(1);
        var candidates=(List<com.intermarche.backoffice.engine.storeops.PlanningAssignmentService.Candidate>)operations.assignmentOptions(request).get("candidates");assertEquals(2,candidates.size());assertEquals(second.id,candidates.getFirst().employeeId());assertTrue(candidates.stream().noneMatch(c->c.employeeId().equals(cashier.id)));
        request.kind="TASK";request.requiredRole="CASHIER";candidates=(List<com.intermarche.backoffice.engine.storeops.PlanningAssignmentService.Candidate>)operations.assignmentOptions(request).get("candidates");assertEquals(List.of(cashier.id),candidates.stream().map(c->c.employeeId()).toList());
        var absence=new StoreOperationsResource.PlanningInput();absence.storeId=f.store.id;absence.employeeId=cashier.id;absence.kind="ABSENCE";absence.title="Absence";absence.start=start;absence.end=start.plusHours(2);operations.savePlanning(absence);
        assertTrue(((List<?>)operations.assignmentOptions(request).get("candidates")).isEmpty());request.employeeId=cashier.id;request.title="Caisse 3";request.validatedAssignment=true;assertThrows(jakarta.ws.rs.BadRequestException.class,()->operations.savePlanning(request));
        request.kind="HANDLING";request.requiredRole=null;request.end=start.plusHours(8);assertTrue(((List<?>)operations.assignmentOptions(request).get("candidates")).isEmpty());
    }
    @Test void toteStockCorrectionsAreAuditedIdempotentAndRejectStaleContents(){
        var f=fixture();var p=new Product();p.ean="9988776600777";p.name="Produit correction";p.productType=ProductType.UNIT;p.temperatureZone=TemperatureZone.AMBIENT;p.persist();
        var physical=new Tote();physical.code="CORRECTION-STOCK-BAC";physical.store=f.store;physical.toteType=Tote.ToteType.STANDARD;physical.volume=new BigDecimal("40");physical.persist();var load=LoadedTote.useAnEmptyTote(physical);load.storageType=TemperatureZone.AMBIENT;load.buffer=f.buffer;load.persist();
        var loc=new BufferToteStockLocation();loc.product=p;loc.loadedTote=load;loc.buffer=f.buffer;loc.area="BUFFER";loc.aisle="A";loc.unit="B1";loc.position="1";loc.elevation="1";loc.persistAndFlush();f.building.locations.add(loc);var account=stocks.account(f.store.id,p.id);stocks.adjust(account.id,loc.id,"tote-edit-initial",BigDecimal.TEN,DateTimeProvider.now());
        var line=new StoreOperationsResource.ToteCorrectionLine();line.id=loc.id;line.kind="STOCK";line.expectedQuantity=BigDecimal.TEN;line.quantity=new BigDecimal("7");var r=new StoreOperationsResource.ToteCorrection();r.storeId=f.store.id;r.sessionId=load.id;r.requestKey="test-tote-edit-001";r.reason="Erreur de comptage";r.lines=List.of(line);
        operations.correctTote(physical.id,r);operations.correctTote(physical.id,r);assertEquals(0,new BigDecimal("7").compareTo(stocks.availability(account.id,null,DateTimeProvider.now()).physical));assertEquals(1,ToteContentCorrection.count("loadedTote",load));
        var row=(Map<?,?>)((List<?>)operations.toteContents(physical.id,f.store.id).get("items")).getFirst();assertEquals("STOCK",row.get("kind"));assertEquals(loc.id,row.get("id"));
        r.requestKey="test-tote-edit-002";assertThrows(jakarta.ws.rs.BadRequestException.class,()->operations.correctTote(physical.id,r));
        line.expectedQuantity=new BigDecimal("7");line.quantity=BigDecimal.ZERO;r.requestKey="test-tote-edit-003";operations.correctTote(physical.id,r);assertTrue(((List<?>)operations.toteContents(physical.id,f.store.id).get("items")).isEmpty());assertFalse(BufferOccupancyService.hasContents(load));
        var added=new Product();added.ean="9988776600784";added.name="Article additionnel";added.productType=ProductType.UNIT;added.temperatureZone=TemperatureZone.AMBIENT;added.persistAndFlush();assertEquals(1,operations.bufferProducts(f.store.id,"additionnel").size());
        line.kind="ADD";line.id=added.id;line.expectedQuantity=BigDecimal.ZERO;line.quantity=new BigDecimal("4");r.requestKey="test-tote-add-001";operations.correctTote(physical.id,r);operations.correctTote(physical.id,r);
        var addedAccount=stocks.account(f.store.id,added.id);assertEquals(0,new BigDecimal("4").compareTo(stocks.availability(addedAccount.id,null,DateTimeProvider.now()).physical));assertEquals(1,BufferToteStockLocation.count("loadedTote=?1 and product=?2",load,added));assertTrue(BufferOccupancyService.hasContents(load));
        var cold=new Product();cold.ean="9988776600791";cold.name="Article froid";cold.productType=ProductType.UNIT;cold.temperatureZone=TemperatureZone.CHILLED;cold.persistAndFlush();line.id=cold.id;r.requestKey="test-tote-add-002";assertThrows(jakarta.ws.rs.BadRequestException.class,()->operations.correctTote(physical.id,r));

    }
    @Test void demoSeedsSixFilledUnassignedTotesAndTheirStockFollowsPlacement(){
        var f=fixture();for(var zone:new TemperatureZone[]{TemperatureZone.AMBIENT,TemperatureZone.CHILLED,TemperatureZone.FROZEN}){var p=new Product();p.ean="WAIT-"+zone;p.name="Produit à déposer "+zone;p.productType=ProductType.UNIT;p.temperatureZone=zone;p.persist();}em.flush();
        demoBuffers.seed(f.store);demoBuffers.seed(f.store);var available=operations.findTotes(f.store.id,"AFF-",true);assertEquals(6,available.size());assertTrue(available.stream().allMatch(t->t.get("buffer")==null&&Boolean.TRUE.equals(t.get("nonEmpty"))));
        var physical=Tote.<Tote>find("store=?1 and code=?2",f.store,"AFF-AMBIENT-1").firstResult();var load=LoadedTote.<LoadedTote>find("tote",physical).firstResult();var location=PickingToteLocation.<PickingToteLocation>find("protectedTote",load).firstResult();assertFalse(((List<?>)operations.toteContents(physical.id,f.store.id).get("items")).isEmpty());
        var warehouse=new WarehouseBuilding();warehouse.buildingCode="WAIT-WH";warehouse.persist();f.store.buildings.add(warehouse);var target=new Buffer();target.bufferCode="WAIT-BUF";target.building=warehouse;target.active=true;target.ambientCapacity=10;target.persistAndFlush();
        var cabinet=new StoreOperationsResource.CabinetInput();cabinet.storeId=f.store.id;cabinet.bufferId=target.id;cabinet.code="WAIT-CAB";cabinet.zone="AMBIENT";cabinet.rows=2;cabinet.columns=2;Long cabinetId=(Long)operations.saveCabinet(cabinet).get("id");
        var move=new StoreOperationsResource.CellInput();move.storeId=f.store.id;move.physicalToteId=physical.id;move.bufferId=target.id;move.cabinetId=cabinetId;move.cellRow=1;move.cellColumn=1;operations.positionTote(move);em.flush();
        var moved=PickingToteLocation.<PickingToteLocation>list("protectedTote=?1 and traceOnly=false",load).stream().filter(loc->warehouse.id.equals(BaseLocation.findBuildingsByLocations(List.of(loc)).getFirst().id)).findFirst().orElseThrow();assertNotEquals(location.id,moved.id);assertEquals(0,StockMovement.<StockMovement>list("location",location).stream().map(m->m.quantity).reduce(BigDecimal.ZERO,BigDecimal::add).signum());assertEquals(target,load.buffer);long movements=com.intermarche.backoffice.domain.stock.StockMovement.count();physical.code="AFFECTER-AMBIENT-1";em.flush();demoBuffers.seed(f.store);assertEquals("AFF-AMBIENT-1",physical.code);assertEquals(target,load.buffer);assertEquals(movements,com.intermarche.backoffice.domain.stock.StockMovement.count());assertEquals(1,buffers.occupancy(f.store).stream().filter(b->b.id.equals(target.id)).findFirst().orElseThrow().totes.getFirst().productLines);
        assertTrue(operations.findTotes(f.store.id,"AFF-AMBIENT-1",true).stream().allMatch(t->target.bufferCode.equals(t.get("buffer"))));
    }
    @Test void demoSeedsThreeFilledTotesAndDoesNotDuplicateThem(){
        var f=fixture();for(var zone:new TemperatureZone[]{TemperatureZone.AMBIENT,TemperatureZone.CHILLED,TemperatureZone.FROZEN}){var p=new Product();p.ean="DEMO-"+zone;p.name="Produit "+zone;p.productType=ProductType.UNIT;p.temperatureZone=zone;p.persist();}em.flush();
        demoBuffers.seed(f.store);demoBuffers.seed(f.store);
        assertEquals(3,operations.findTotes(f.store.id,"DEMO-BAC",true).size());
        assertEquals(3,BufferCabinet.count("buffer",f.buffer));var view=buffers.occupancy(f.store).getFirst();assertEquals("STAGING",view.purpose);assertEquals(3,view.totes.size());assertTrue(view.totes.stream().allMatch(t->t.productLines>0));for(var t:view.totes)assertFalse(((List<?>)operations.toteContents(t.physicalToteId,f.store.id).get("items")).isEmpty());
    }
    @Test void correctionCanPlaceAnyEmptyToteAndMoveItBetweenCells(){
        var f=fixture();var c=new StoreOperationsResource.CabinetInput();c.storeId=f.store.id;c.bufferId=f.buffer.id;c.code="CORRECTION";c.zone="AMBIENT";c.columns=2;c.rows=1;Long cabinetId=(Long)operations.saveCabinet(c).get("id");
        var physical=new Tote();physical.code="CORRECTION-BAC";physical.store=f.store;physical.toteType=Tote.ToteType.STANDARD;physical.volume=new BigDecimal("40");physical.persistAndFlush();
        assertEquals(1,operations.findTotes(f.store.id,"CORRECTION-BAC").size());assertTrue(operations.findTotes(f.store.id,"CORRECTION-BAC",true).isEmpty());assertTrue(((List<?>)operations.toteContents(physical.id,f.store.id).get("items")).isEmpty());
        var r=new StoreOperationsResource.CellInput();r.storeId=f.store.id;r.bufferId=f.buffer.id;r.physicalToteId=physical.id;r.cabinetId=cabinetId;r.cellRow=1;r.cellColumn=1;operations.positionTote(r);
        assertEquals(1,buffers.occupancy(f.store).getFirst().totes.getFirst().cellColumn);r.cellColumn=2;operations.positionTote(r);
        assertEquals(2,buffers.occupancy(f.store).getFirst().totes.getFirst().cellColumn);assertEquals(1,LoadedTote.count("tote",physical));
    }
    @Test void cabinetCellsPersistRejectOccupiedAndProtectResize(){
        var f=fixture();var r=new StoreOperationsResource.CabinetInput();r.storeId=f.store.id;r.bufferId=f.buffer.id;r.code="ARMOIRE-A";r.zone="AMBIENT";r.columns=3;r.rows=2;
        Long id=(Long)operations.saveCabinet(r).get("id");
        var physical=new Tote();physical.code="CELL-BAC1";physical.store=f.store;physical.toteType=Tote.ToteType.STANDARD;physical.volume=new BigDecimal("40");physical.persist();
        var first=LoadedTote.useAnEmptyTote(physical);first.storageType=TemperatureZone.AMBIENT;first.persistAndFlush();
        buffers.deposit(first,f.buffer.id,id,2,3,f.store);
        var snapshot=buffers.occupancy(f.store).getFirst();assertEquals(6,snapshot.zones.getFirst().capacity);assertEquals(3,snapshot.cabinets.getFirst().columns);assertEquals(id,snapshot.totes.getFirst().cabinetId);assertEquals(2,snapshot.totes.getFirst().cellRow);
        var other=new Tote();other.code="CELL-BAC2";other.store=f.store;other.toteType=Tote.ToteType.STANDARD;other.volume=new BigDecimal("40");other.persist();var second=LoadedTote.useAnEmptyTote(other);second.storageType=TemperatureZone.AMBIENT;second.persistAndFlush();
        assertThrows(IllegalArgumentException.class,()->buffers.deposit(second,f.buffer.id,id,2,3,f.store));
        assertThrows(IllegalArgumentException.class,()->buffers.deposit(second,f.buffer.id,id,3,3,f.store));
        r.id=id;r.rows=1;assertThrows(jakarta.ws.rs.BadRequestException.class,()->operations.saveCabinet(r));
        buffers.deposit(first,f.buffer.id,null,null,null,f.store);assertNull(first.cabinet);
        buffers.deposit(second,f.buffer.id,id,2,3,f.store);assertEquals(3,second.cellColumn);
    }
    @Test void placesAndInteractivePlanningPersistHoursAbsenceTaskAndManutentionWithConflicts(){
        var f=fixture();
        var building=new StoreOperationsResource.BuildingInput();building.storeId=f.store.id;building.code="OPS-WAREHOUSE";building.type="WAREHOUSE";
        operations.saveBuilding(building);
        var p=operations.places(f.store.id);assertEquals(2,((List<?>)p.get("buildings")).size());
        var buffer=new StoreOperationsResource.BufferInput();buffer.storeId=f.store.id;buffer.id=f.buffer.id;buffer.buildingId=f.building.id;buffer.code=f.buffer.bufferCode;buffer.active=true;buffer.ambientCapacity=7;
        operations.saveBuffer(buffer);assertEquals(7,f.buffer.ambientCapacity);
        var day=DateTimeProvider.now().toLocalDate().atStartOfDay();
        var entry=new StoreOperationsResource.PlanningInput();entry.storeId=f.store.id;entry.employeeId=f.employee.id;entry.kind="SHIFT";entry.title="Présence";entry.start=day.plusHours(8);entry.end=day.plusHours(17);
        operations.savePlanning(entry);
        entry=new StoreOperationsResource.PlanningInput();entry.storeId=f.store.id;entry.employeeId=f.employee.id;entry.kind="ABSENCE";entry.title="Absence";entry.start=day.plusHours(12);entry.end=day.plusHours(14);operations.savePlanning(entry);
        entry=new StoreOperationsResource.PlanningInput();entry.storeId=f.store.id;entry.employeeId=f.employee.id;entry.kind="TASK";entry.title="Inventaire";entry.start=day.plusHours(13);entry.end=day.plusHours(15);
        var saved=operations.savePlanning(entry);Long id=(Long)saved.get("entryId");
        var snapshot=operations.planning(f.store.id,day.toString(),day.plusDays(7).toString());assertEquals(3,((List<?>)snapshot.get("entries")).size());assertFalse(((List<?>)snapshot.get("conflicts")).isEmpty());
        entry.id=id;entry.start=day.plusHours(15);entry.end=day.plusHours(16);operations.savePlanning(entry);
        snapshot=operations.planning(f.store.id,day.toString(),day.plusDays(7).toString());assertTrue(((List<?>)snapshot.get("conflicts")).isEmpty());
        var copy=new StoreOperationsResource.CopyWeek();copy.storeId=f.store.id;copy.weekStart=day;
        assertEquals(1,operations.copyWeek(copy).get("copied"));assertEquals(0,operations.copyWeek(copy).get("copied"));
        operations.cancelEntry(id,f.store.id);assertEquals(2,((List<?>)operations.planning(f.store.id,day.toString(),day.plusDays(7).toString()).get("entries")).size());
        var order=new com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder();order.store=f.store;order.code="OPS-REA";order.destinationBuilding=f.building;order.operation=com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder.Operation.TRUCK_UNLOADING;order.status=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;order.requestedStart=day.plusHours(9);order.requestedEnd=day.plusHours(10);order.persistAndFlush();
        entry.start=day.plusHours(10);entry.end=day.plusHours(11);operations.scheduleOrder(order.id,entry);assertEquals(f.employee.id,order.assignee.id);assertEquals(entry.start,order.requestedStart);
    }
    @Test void shipmentKeepsFulfillmentTraceDebitsStockOnceAndLocksToteUntilItsEmptyReturn(){
        var f=fixture();
        var product=new Product();product.ean="9988776600123";product.name="Produit expédié";product.productType=ProductType.UNIT;product.temperatureZone=TemperatureZone.AMBIENT;product.persist();
        var shelf=new StoreShelfLocation();shelf.product=product;shelf.area="EPICERIE";shelf.aisle="A1";shelf.unit="B1";shelf.elevation="1";shelf.position="1";shelf.persist();f.building.locations.add(shelf);
        var customer=new Customer();customer.customerId="OPS-CUSTOMER";customer.firstName="Client";customer.lastName="Test";customer.email="client@example.test";customer.persist();
        var order=new Order();order.orderId="OPS-ORDER";order.orderDate=DateTimeProvider.now();order.channel="DRIVE";order.status="READY";order.store=f.store;order.customer=customer;order.persist();
        var fulfillment=new Fulfillment();fulfillment.order=order;fulfillment.persist();
        var chunk=new FulfillmentChunk();chunk.fulfillment=fulfillment;chunk.type="READY";chunk.building=f.building;chunk.persist();
        var line=new FulfillmentLine();line.lineNumber=1;line.product=product;line.requestedQuantity=new BigDecimal("3");line.location=shelf;line.persist();chunk.lines.add(line);fulfillment.chunks.add(chunk);
        var lotLine=new LotLine();lotLine.fulfillmentLine=line;lotLine.product=product;lotLine.location=shelf;lotLine.persist();
        var physical=new Tote();physical.code="OPS-BAC";physical.store=f.store;physical.toteType=Tote.ToteType.STANDARD;physical.volume=new BigDecimal("40");physical.persist();
        var load=LoadedTote.useAnEmptyTote(physical);load.storageType=TemperatureZone.AMBIENT;load.buffer=f.buffer;load.persist();
        var picked=new PickedItem();picked.product=product;picked.lotLine=lotLine;picked.quantity=new BigDecimal("3");picked.loadedTote=load;picked.status=PickedItem.PickStatus.PICKED;picked.persist();load.items.add(picked);em.flush();
        var account=stocks.account(f.store.id,product.id);stocks.adjust(account.id,shelf.id,"shipping-initial",BigDecimal.TEN,DateTimeProvider.now());
        var r=new ShipmentResource.Dispatch();r.storeId=f.store.id;r.orderId=order.id;r.mode="CARRIER";r.recipient="Client Test";r.transportReference="TRUCK-0042";r.requestKey="shipping-test-0001";r.toteIds=List.of(load.id);
        assertEquals(List.of("OPS-ORDER"),shipping.orders(f.store.id,null).stream().map(o->o.get("code")).toList());assertEquals(true,shipping.orders(f.store.id,null).getFirst().get("shippable"));
        var editLine=new StoreOperationsResource.ToteCorrectionLine();editLine.id=picked.id;editLine.kind="PICKED";editLine.expectedQuantity=new BigDecimal("3");editLine.quantity=new BigDecimal("2");var edit=new StoreOperationsResource.ToteCorrection();edit.storeId=f.store.id;edit.sessionId=load.id;edit.requestKey="picked-edit-001";edit.reason="Erreur de préparation";edit.lines=List.of(editLine);operations.correctTote(physical.id,edit);assertEquals(new BigDecimal("2"),picked.quantity);
        editLine.expectedQuantity=new BigDecimal("2");editLine.quantity=new BigDecimal("3");edit.requestKey="picked-edit-002";operations.correctTote(physical.id,edit);
        var tracking=shipping.lineTracking(f.store.id,order.orderId,1,product.ean);assertEquals("PICKED",tracking.get("state"));assertEquals(1,((List<?>)tracking.get("totes")).size());assertEquals(f.buffer.bufferCode,((Map<?,?>)((List<?>)tracking.get("totes")).getFirst()).get("buffer"));
        assertEquals("NOT_PICKED",shipping.lineTracking(f.store.id,"UNRECORDED",1,product.ean).get("state"));
        var response=shipping.dispatch(r);var again=shipping.dispatch(r);assertEquals(response.get("id"),again.get("id"));
        assertEquals("SHIPPED",shipping.lineTracking(f.store.id,order.orderId,1,product.ean).get("state"));
        assertEquals("SHIPPED",order.status);assertNull(load.buffer);assertEquals(LoadedTote.Status.IN_USE,load.status);
        assertEquals(0,new BigDecimal("7").compareTo(stocks.availability(account.id,null,DateTimeProvider.now()).physical));
        assertEquals(1,Event.count("store=?1 and label=?2",f.store,"Commande expédiée"));
        assertTrue(buffers.occupancy(f.store).getFirst().totes.isEmpty());
        assertTrue(shipping.orders(f.store.id,null).isEmpty());assertEquals(1,shipping.list(f.store.id).size());
        var shippedOrder=shipping.orders(f.store.id,"OPS-ORDER");assertEquals(1,shippedOrder.size());assertEquals(false,shippedOrder.getFirst().get("shippable"));
        var ret=new ShipmentResource.Return();ret.storeId=f.store.id;ret.toteId=load.id;shipping.returnTote((Long)response.get("id"),ret);
        assertEquals(LoadedTote.Status.DELIVERED,load.status);assertEquals(0,new BigDecimal("7").compareTo(stocks.availability(account.id,null,DateTimeProvider.now()).physical));
        assertNotNull(ShipmentLine.<ShipmentLine>find("loadedTote",load).firstResult().toteReturnedAt);
    }
    /**
     * Creates an order of a store with one line on a shelf and, optionally, the line prepared in a tote deposited in
     * the buffer of the fixture.
     *
     * @param f fixture
     * @param store store of the order
     * @param shelf shelf of the product
     * @param code business identifier of the order
     * @param orderDate reception date of the order
     * @param deadline delivery date of its chunk
     * @param toteCode code of the tote holding the prepared line, or null if nothing is prepared
     * @return the order
     */
    private Order shippingOrder(Fixture f,Store store,StoreShelfLocation shelf,String code,LocalDateTime orderDate,LocalDateTime deadline,String toteCode){
        var order=new Order();order.orderId=code;order.orderDate=orderDate;order.channel="DRIVE";order.status="READY";order.store=store;order.persist();
        var fulfillment=new Fulfillment();fulfillment.order=order;fulfillment.persist();
        var chunk=new FulfillmentChunk();chunk.fulfillment=fulfillment;chunk.type="READY";chunk.building=f.building();chunk.deliveryDate=deadline;chunk.persist();
        var line=new FulfillmentLine();line.lineNumber=1;line.product=shelf.product;line.requestedQuantity=BigDecimal.ONE;line.location=shelf;line.persist();chunk.lines.add(line);fulfillment.chunks.add(chunk);
        if(toteCode!=null){
            var lotLine=new LotLine();lotLine.fulfillmentLine=line;lotLine.product=shelf.product;lotLine.location=shelf;lotLine.persist();
            var physical=new Tote();physical.code=toteCode;physical.store=store;physical.toteType=Tote.ToteType.STANDARD;physical.volume=new BigDecimal("40");physical.persist();
            var load=LoadedTote.useAnEmptyTote(physical);load.storageType=TemperatureZone.AMBIENT;load.buffer=f.buffer();load.persist();
            var picked=new PickedItem();picked.product=shelf.product;picked.lotLine=lotLine;picked.quantity=BigDecimal.ONE;picked.loadedTote=load;picked.status=PickedItem.PickStatus.PICKED;picked.persist();load.items.add(picked);
        }
        return order;
    }
    /**
     * The orders to ship are those with prepared totes, earliest deadline first, whatever their age and with grouped queries; any order of the store is found by its code.
     */
    @Test void shippingListsEveryOrderWithPreparedTotesByDeadlineWhateverItsAge_andFindsAnyOrderByCode(){
        var f=fixture();var other=new Store();other.code="OPS-ELSEWHERE";other.name="Ailleurs";other.persist();
        var product=new Product();product.ean="9988776600456";product.name="Produit à expédier";product.productType=ProductType.UNIT;product.temperatureZone=TemperatureZone.AMBIENT;product.persist();
        var shelf=new StoreShelfLocation();shelf.product=product;shelf.area="EPICERIE";shelf.aisle="A1";shelf.unit="B1";shelf.elevation="1";shelf.position="2";shelf.persist();f.building().locations.add(shelf);
        var now=DateTimeProvider.now();
        shippingOrder(f,f.store(),shelf,"OPS-OLD",now.minusDays(400),now.plusHours(5),"OPS-BAC-OLD");
        shippingOrder(f,f.store(),shelf,"OPS-SOON",now,now.plusHours(1),"OPS-BAC-SOON");
        shippingOrder(f,f.store(),shelf,"OPS-NODATE",now,null,"OPS-BAC-NODATE");
        shippingOrder(f,f.store(),shelf,"OPS-NOT-PICKED",now,now.plusHours(2),null);
        shippingOrder(f,other,shelf,"OPS-FOREIGN",now,now.plusHours(1),"OPS-BAC-FOREIGN");
        // More than two hundred newer orders do not hide the old prepared one any longer.
        for(int i=0;i<210;i++){var recent=new Order();recent.orderId="OPS-RECENT-"+i;recent.orderDate=now.plusMinutes(i);recent.channel="DRIVE";recent.status="VALIDATED";recent.store=f.store();recent.persist();}
        em.flush();em.clear();

        var statistics=em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();boolean enabled=statistics.isStatisticsEnabled();statistics.setStatisticsEnabled(true);statistics.clear();
        List<Map<String,Object>> orders;
        try{orders=shipping.orders(f.store().id,null);assertTrue(statistics.getPrepareStatementCount()<=10,"grouped queries, not one per order: "+statistics.getPrepareStatementCount());}
        finally{statistics.setStatisticsEnabled(enabled);}
        assertEquals(List.of("OPS-SOON","OPS-OLD","OPS-NODATE"),orders.stream().map(o->o.get("code")).toList());
        assertTrue(orders.stream().allMatch(o->Boolean.TRUE.equals(o.get("shippable"))));
        var tote=(Map<?,?>)((List<?>)((Map<?,?>)((List<?>)orders.getFirst().get("lines")).getFirst()).get("totes")).getFirst();
        assertEquals("OPS-BAC-SOON",tote.get("code"));assertEquals("OPS-BUFFER",tote.get("buffer"));assertEquals(false,tote.get("shipped"));

        var notPicked=shipping.orders(f.store().id,"OPS-NOT-PICKED");
        assertEquals(1,notPicked.size());assertEquals(false,notPicked.getFirst().get("shippable"));
        assertTrue(((List<?>)((Map<?,?>)((List<?>)notPicked.getFirst().get("lines")).getFirst()).get("totes")).isEmpty());
        var bare=shipping.orders(f.store().id,"OPS-RECENT-0");
        assertEquals(1,bare.size());assertTrue(((List<?>)bare.getFirst().get("lines")).isEmpty());assertNull(bare.getFirst().get("deadline"));
        assertTrue(shipping.orders(f.store().id,"OPS-FOREIGN").isEmpty());
        assertTrue(shipping.orders(f.store().id,"OPS-UNKNOWN").isEmpty());
        assertEquals(3,shipping.orders(f.store().id,"  ").size(),"a blank code lists the orders to ship");
    }
    /** A manager edits their own record, but not the record (mail, badge, name) of another manager of the store. */
    @Test @TestSecurity(user="ops-user",roles={"MANAGER"}) void managerCannotTakeOverAnotherManagerOfTheStore(){
        var f=fixture();
        var peer=new Employee();peer.firstName="Paul";peer.lastName="Pair";peer.badgeId="ops-peer";peer.email="peer@example.test";peer.password=org.mindrot.jbcrypt.BCrypt.hashpw("test-password",org.mindrot.jbcrypt.BCrypt.gensalt(4));peer.role=Employee.EmployeeRole.MANAGER;peer.store=f.store;peer.persistAndFlush();
        var takeover=new StoreOperationsResource.EmployeeInput();takeover.storeId=f.store.id;takeover.id=peer.id;takeover.badgeId="ops-peer";takeover.firstName="Paul";takeover.lastName="Pair";takeover.email="attacker@example.test";takeover.role="MANAGER";
        assertThrows(jakarta.ws.rs.ForbiddenException.class,()->operations.saveEmployee(takeover));
        em.clear();assertEquals("peer@example.test",Employee.<Employee>findById(peer.id).email);
        var self=new StoreOperationsResource.EmployeeInput();self.storeId=f.store.id;self.id=f.employee.id;self.badgeId="ops-user";self.firstName="Marie";self.lastName="Renommée";self.email="ops@example.test";self.role="MANAGER";
        assertEquals("Renommée",operations.saveEmployee(self).get("lastName"));
    }
    @Test @TestSecurity(user="ops-user",roles={"MANAGER"}) void managerCannotAccessAnotherStoresPlacesOrEmployees(){
        var f=fixture();var other=new Store();other.code="OPS-OTHER";other.name="Autre";other.persistAndFlush();
        assertThrows(jakarta.ws.rs.ForbiddenException.class,()->operations.places(other.id));
        assertThrows(jakarta.ws.rs.ForbiddenException.class,()->operations.employees(other.id));
    }

    @Test void partialPlanningApplicationKeepsOmittedOrdersVisibleAndUnassigned(){
        var f=fixture();var day=DateTimeProvider.now().toLocalDate().plusDays(2);
        var shift=new StoreOperationsResource.PlanningInput();shift.storeId=f.store.id;shift.employeeId=f.employee.id;shift.kind="SHIFT";shift.title="Présence";shift.start=day.atTime(8,0);shift.end=day.atTime(18,0);shift.buildingId=f.building.id;operations.savePlanning(shift);
        for(int i=0;i<2;i++){var order=new com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder();order.code="PARTIAL-"+i;order.store=f.store;order.building=f.building;order.assignee=f.employee;order.status=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;order.requestedStart=day.atTime(9,0);order.requestedEnd=day.atTime(10,0);order.persist();}
        var request=new com.intermarche.backoffice.engine.storeops.GlobalPlanningService.Request();request.storeId=f.store.id;request.start=day.atTime(8,0);request.end=day.atTime(18,0);request.flexibilityMinutes=0;request.latenessMinutes=0;
        var preview=(Map<String,Object>)operations.planningSolutions(request);var result=(com.intermarche.backoffice.engine.storeops.ConstrainedPlanningSolver.Result)preview.get("result");assertEquals(2,result.solutions().size());assertEquals(1,result.solutions().getFirst().unscheduled().size());
        var apply=new com.intermarche.backoffice.engine.storeops.GlobalPlanningService.Apply();apply.storeId=f.store.id;apply.previewId=(String)preview.get("previewId");apply.solution=0;operations.applyPlanning(apply);
        assertEquals(2,BaseHandlingOrder.count("store",f.store));assertEquals(1,BaseHandlingOrder.count("store=?1 and assignee is null and status=?2",f.store,BaseHandlingOrder.HandlingOrderStatus.OPEN));
    }

 @Test void failedOrdersCannotBeProposedOrScheduled(){var f=fixture();var o=new com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder();o.store=f.store;o.code="OPS-FAILED";o.status=BaseHandlingOrder.HandlingOrderStatus.FAILED;o.persistAndFlush();var input=new StoreOperationsResource.PlanningInput();input.storeId=f.store.id;input.orderId=o.id;input.employeeId=f.employee.id;input.kind="HANDLING";input.start=DateTimeProvider.now().plusHours(1);input.end=input.start.plusMinutes(30);
 assertThrows(jakarta.ws.rs.BadRequestException.class,()->operations.assignmentOptions(input));assertThrows(jakarta.ws.rs.BadRequestException.class,()->operations.scheduleOrder(o.id,input));assertEquals(BaseHandlingOrder.HandlingOrderStatus.FAILED,o.status);
 }

 @Test void planningKeepsExecutedWorkWithItsActualEmployeeWithoutReassigningOrders(){var f=fixture();var start=DateTimeProvider.now().plusDays(1).withHour(9);var end=start.plusMinutes(30);var ids=new ArrayList<Long>();
 for(var state:List.of(BaseHandlingOrder.HandlingOrderStatus.PROCESSING,BaseHandlingOrder.HandlingOrderStatus.DONE)){var o=new com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder();o.store=f.store;o.code="OPS-EXEC-"+ids.size();o.status=state;o.requestedStart=start;o.requestedEnd=end;o.persist();var e=new com.intermarche.backoffice.client.PickingExecution();e.handlingOrder=o;e.employee=f.employee;e.status=state==BaseHandlingOrder.HandlingOrderStatus.DONE?com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.DONE:com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.IN_PROGRESS;e.persistAndFlush();ids.add(o.id);}
 var entries=(List<Map<String,Object>>)operations.planning(f.store.id,start.minusHours(1).toString(),end.plusHours(1).toString()).get("entries");for(Long id:ids){var row=entries.stream().filter(e->id.equals(e.get("orderId"))).findFirst().orElseThrow();assertEquals(f.employee.id,row.get("employeeId"));assertNull(BaseHandlingOrder.<BaseHandlingOrder>findById(id).assignee);}
 }
    @Test void serviceKeepsTheChosenNonFoodUsageAndRefusesAnIncompatibleTemperature(){
        var f=fixture();var cabinet=new StoreOperationsResource.CabinetInput();cabinet.storeId=f.store.id;cabinet.bufferId=f.buffer.id;cabinet.code="SERVICE";cabinet.zone="AMBIENT";cabinet.rows=1;cabinet.columns=2;Long cabinetId=(Long)operations.saveCabinet(cabinet).get("id");
        var physical=new Tote();physical.code="SERVICE-NONFOOD";physical.store=f.store;physical.toteType=Tote.ToteType.STANDARD;physical.volume=new BigDecimal("40");physical.persistAndFlush();
        var input=new StoreOperationsResource.CellInput();input.storeId=f.store.id;input.physicalToteId=physical.id;input.bufferId=f.buffer.id;input.cabinetId=cabinetId;input.cellRow=1;input.cellColumn=1;input.zone="HOUSEHOLD";
        operations.positionTote(input);var load=LoadedTote.<LoadedTote>find("tote=?1 and status=?2",physical,LoadedTote.Status.IN_USE).singleResult();assertEquals(TemperatureZone.HOUSEHOLD,load.storageType);operations.positionTote(input);assertEquals(1,LoadedTote.count("tote",physical));
        var other=new Tote();other.code="SERVICE-INVALID";other.store=f.store;other.toteType=Tote.ToteType.STANDARD;other.volume=new BigDecimal("40");other.persistAndFlush();input.physicalToteId=other.id;input.cellColumn=2;input.zone="CHILLED";
        assertThrows(jakarta.ws.rs.BadRequestException.class,()->operations.positionTote(input));assertEquals(0,LoadedTote.count("tote",other));
    }

}
