package com.intermarche.e2e.engine.executing;

import com.intermarche.backoffice.auth.AuthenticationService;
import com.intermarche.backoffice.auth.DemoDataInitializer;
import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
import com.intermarche.backoffice.engine.executing.*;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Real seed -> route -> batch report -> persisted execution, rolled back after the test. */
@io.quarkus.test.junit.TestProfile(CollectingWorkflowTest.Isolated.class)
@QuarkusTest
class CollectingWorkflowTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:isolated_CollectingWorkflowTest;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}}

    @Inject DemoDataInitializer seed;
    @Inject PickingPlanService plans;
    @Inject HandlingOrderService executions;
    @Inject ProductPickingLocations locations;
    @Inject EntityManager em;
    @InjectMock AuthenticationService authentication;

    @Test @TestTransaction
    void complexCollectionShouldPersistAll63PicksIntoCompatibleTotes() { collectionReport(false); }
    @Test @TestTransaction
    void collectionReportKeepsTheWarehouseSelectedForEveryOriginalLine() { collectionReport(true); }
    private void collectionReport(boolean useWarehouse) {
        seed.seedDemoData();
        ProductCollectingOrder order=ProductCollectingOrder.find("code","PRO-DEMO-OPT").firstResult();
        Employee employee=order.assignee;
        var position=new com.intermarche.backoffice.domain.workforce.EmployeePosition();position.employee=employee;position.building=order.building;position.availableAt=com.intermarche.backoffice.domain.util.DateTimeProvider.now();position.persist();
        when(authentication.getConnectedAccount()).thenReturn(employee);
        var plan=plans.generatePickingPlan(List.of(order.id),null);
        Map<TemperatureZone,HandlingOrderService.ToteReportDTO> byZone=new LinkedHashMap<>();
        Map<Tote.ToteType,Iterator<Tote>> available=new EnumMap<>(Tote.ToteType.class);
        for (Tote.ToteType type:Tote.ToteType.values()) available.put(type,Tote.<Tote>list("select t from Tote t where t.store = ?1 and t.toteType = ?2 and not exists (select l.id from LoadedTote l where l.tote=t and l.status=?3) order by t.code",order.store,type,com.intermarche.backoffice.domain.executing.LoadedTote.Status.IN_USE).iterator());
        for (var building:plan.buildingPlans.values()) for (var group:building.route) for (var step:group.steps) {
            var report=byZone.computeIfAbsent(step.temperatureZone,zone -> {
                var toteReport=new HandlingOrderService.ToteReportDTO();
                var type=zone==TemperatureZone.CHILLED || zone==TemperatureZone.FROZEN ? Tote.ToteType.INSULATED:Tote.ToteType.STANDARD;
                toteReport.code=available.get(type).next().code;toteReport.zone=zone;toteReport.items=new ArrayList<>();return toteReport;
            });
            var item=new HandlingOrderService.ToteItemReportDTO();item.pickedItemId=step.pickedItemId;item.qty=step.quantity;
            if(useWarehouse) {
                var product=com.intermarche.backoffice.domain.order.Product.<com.intermarche.backoffice.domain.order.Product>findById(step.productId);
                item.sourceLocationId=(Long)locations.choices(order.store,product,step.sourceLocation,building.buildingCode).stream().filter(row->row.get("buildingCode").toString().endsWith("-WH")).findFirst().orElseThrow().get("id");
            }
            report.items.add(item);
        }
        Buffer buffer=Buffer.find("select b from Buffer b where b.building.id in (select x.id from Store s join s.buildings x where s=?1)",order.store).firstResult();
        assertNotNull(buffer);buffer.active=true;buffer.ambientCapacity=100;buffer.chilledCapacity=100;buffer.frozenCapacity=100;
        // The demo parks its own supports in this buffer, cabinets included: free it so that only this collection occupies it
        com.intermarche.backoffice.domain.executing.LoadedTote.update("buffer=null,cabinet=null,cellRow=null,cellColumn=null where buffer=?1",buffer);
        com.intermarche.backoffice.domain.store.BufferCabinet.delete("buffer",buffer);
        byZone.values().forEach(t->t.bufferId=buffer.id);
        var report=new HandlingOrderService.OrderReportDTO();report.orderId=order.id;report.totes=new ArrayList<>(byZone.values());
        var request=new HandlingOrderService.BatchValidationRequest();request.orderIds=List.of(order.id);request.reports=List.of(report);
        Long orderId=order.id;
        executions.createAndConcludeBatch(request);
        em.flush();em.clear();
        PickingExecution execution=PickingExecution.find("handlingOrder.id",orderId).firstResult();
        assertNotNull(execution);
        for(var tote:execution.loadedTotes)assertEquals(buffer.id,org.hibernate.Hibernate.unproxy(tote.buffer,Buffer.class).id);
        assertEquals(HandlingExecution.ExecutionStatus.DONE,execution.status);
        assertEquals(63,execution.itemsToPick.size());
        assertEquals(byZone.size(),execution.loadedTotes.size());
        assertEquals(63,execution.loadedTotes.stream().mapToInt(t -> t.items.size()).sum());
        for (PickedItem item:execution.itemsToPick) {
            assertEquals(PickedItem.PickStatus.PICKED,item.status);
            assertNotNull(item.loadedTote);
            if(useWarehouse) {
                assertNotNull(item.sourceLocation);
                assertTrue(BaseLocation.findBuildingsByLocations(List.of(org.hibernate.Hibernate.unproxy(item.sourceLocation,BaseLocation.class))).get(0).buildingCode.endsWith("-WH"));
            }
            assertEquals(0,item.lotLine.quantity.compareTo(item.quantity));
            assertTrue(item.loadedTote.tote.isUsageAllowed(item.product.temperatureZone));
        }
    }
}
