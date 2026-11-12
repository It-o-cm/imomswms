package com.intermarche.e2e.auth;

import com.intermarche.backoffice.auth.DemoDataInitializer;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.workforce.*;
import com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.stock.StockMovement;
import com.intermarche.backoffice.engine.storeops.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@io.quarkus.test.junit.TestProfile(DemoPlanningInitializerTest.Isolated.class)
@QuarkusTest @TestTransaction @TestSecurity(user="seed-tester",roles="ADMIN")
class DemoPlanningInitializerTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:isolated_DemoPlanningInitializerTest;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}}

    java.time.LocalDateTime previousClock;
    @org.junit.jupiter.api.BeforeEach void stableDemoClock(){previousClock=com.intermarche.backoffice.domain.util.DateTimeProvider.getFixedDateTime();com.intermarche.backoffice.domain.util.DateTimeProvider.setFixedDateTime(java.time.LocalDate.now().atTime(12,0));}
    @org.junit.jupiter.api.AfterEach void restoreDemoClock(){if(previousClock==null)com.intermarche.backoffice.domain.util.DateTimeProvider.clear();else com.intermarche.backoffice.domain.util.DateTimeProvider.setFixedDateTime(previousClock);}
    @Inject DemoDataInitializer initializer;
    @Inject GlobalPlanningService planning;
    @Inject StoreOperationsResource operations;

    @Test void threeBuildingsHaveRealFlowsAndSeveralValidComparableSchedules(){
        initializer.seedDemoData();Store store=Store.findByCode("LYO-042");
        assertEquals(3,store.buildings.size());assertEquals(6,BuildingTransit.count("store",store));
        Employee alice=Employee.findByBadgeId("DEMO-P01"),karim=Employee.findByBadgeId("DEMO-P02");assertNotNull(alice);assertNotNull(karim);
        assertEquals(4,PlanningEntry.count("employee in ?1 and kind='SHIFT'",List.of(alice,karim)));assertEquals(4,PlanningEntry.count("employee in ?1 and kind='TASK'",List.of(alice,karim)));assertEquals(10,PlanningEntry.count("employee in ?1 and kind='ABSENCE'",List.of(alice,karim)));
        WarehouseBuilding annex=WarehouseBuilding.find("buildingCode","LYO-042-ANNEXE").firstResult();assertNotNull(annex.planImage);assertFalse(annex.locations.isEmpty());assertEquals(2,Buffer.count("building",annex));
        ReplenishmentOrder truck=ReplenishmentOrder.find("code","OPT-ANN-CAM").firstResult(),shelves=ReplenishmentOrder.find("code","OPT-ANN-RAY").firstResult();
        assertEquals(truck,shelves.predecessor);assertNull(truck.assignee);assertNull(shelves.assignee);assertEquals(4,truck.supplies.size());assertEquals(4,shelves.supplies.size());assertTrue(truck.supplies.stream().anyMatch(s->s.account.product.productType==ProductType.WEIGHT));assertTrue(truck.supplies.stream().map(s->s.quantity).distinct().count()>1);
        var marker=PlanningEntry.<PlanningEntry>find("employee=?1 and kind='SHIFT'",alice).firstResult();
        var request=new GlobalPlanningService.Request();request.storeId=store.id;request.start=marker.startsAt;request.end=marker.endsAt;request.flexibilityMinutes=60;
        var result=(ConstrainedPlanningSolver.Result)planning.preview(store,request).get("result");assertTrue(result.solutions().size()>=2,result.message());assertTrue(result.solutions().getFirst().transitMinutes()<=40,"Le scénario doit permettre d’éviter les affectations croisées");
        assertTrue(result.solutions().stream().allMatch(s->s.assignments().size()==6));assertTrue(result.solutions().stream().allMatch(s->s.assignments().stream().filter(m->m.id().equals("order-"+shelves.id)).allMatch(m->s.assignments().stream().filter(p->p.id().equals("order-"+truck.id)).noneMatch(p->p.end().isAfter(m.start())))));
        var rows=(List<Map<String,Object>>)operations.planning(store.id,request.start.toString(),request.end.toString()).get("entries");assertTrue(rows.stream().filter(row->"HANDLING".equals(row.get("kind"))).allMatch(row->annex.id.equals(row.get("buildingId"))));
        var assignment=new StoreOperationsResource.PlanningInput();assignment.storeId=store.id;assignment.kind="HANDLING";assignment.start=marker.startsAt.toLocalDate().minusDays(1).atTime(16,9);assignment.end=assignment.start.plusMinutes(30);
        var candidates=(List<PlanningAssignmentService.Candidate>)operations.assignmentOptions(assignment).get("candidates");assertTrue(candidates.stream().anyMatch(c->c.employeeId().equals(karim.id)));assertTrue(candidates.stream().noneMatch(c->c.employeeId().equals(alice.id)));
        var positions=new HashSet<>(result.solutions().getFirst().assignments().stream().map(ConstrainedPlanningSolver.Move::from).toList());assertEquals(3,positions.size());
    }
    @Test void reseedingPreservesEditedTransitHoursNamesAndStock(){
        initializer.seedDemoData();Store store=Store.findByCode("LYO-042");Employee alice=Employee.findByBadgeId("DEMO-P01");
        var transit=BuildingTransit.<BuildingTransit>find("store=?1 order by id",store).firstResult();transit.minutes=19;alice.firstName="Alice modifiée";
        var shift=PlanningEntry.<PlanningEntry>find("employee=?1 and kind='SHIFT'",alice).firstResult();var changedStart=shift.startsAt.plusMinutes(10);shift.startsAt=changedStart;
        long movements=StockMovement.count(),entries=PlanningEntry.count(),orders=ReplenishmentOrder.count();initializer.seedDemoData();
        assertEquals(19,transit.minutes);assertEquals("Alice modifiée",alice.firstName);assertEquals(changedStart,shift.startsAt);assertEquals(movements,StockMovement.count());assertEquals(entries,PlanningEntry.count());assertEquals(orders,ReplenishmentOrder.count());assertEquals(6,BuildingTransit.count("store",store));
    }
}
