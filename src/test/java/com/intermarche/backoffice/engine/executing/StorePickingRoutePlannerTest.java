package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.engine.executing.PickingPlanService.RouteStepDTO;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StorePickingRoutePlannerTest {
    private final StorePickingRoutePlanner planner=new StorePickingRoutePlanner();
    private PlanNode node(String code,double x,double y,PlanNodeType type) {
        PlanNode n=new PlanNode(); n.code=code;n.x=x;n.y=y;n.nodeType=type;return n;
    }
    private Aisle aisle(String code,PlanNode head,PlanNode tail,double length) {
        Aisle a=new Aisle();a.code=code;a.headNode=head;a.tailNode=tail;a.lengthMeters=length;return a;
    }
    private StoreShelfLocation shelf(String aisle,String unit,double fraction) {
        StoreShelfLocation l=new StoreShelfLocation();l.aisle=aisle;l.unit=unit;l.aislePosition=fraction;return l;
    }
    private RouteStepDTO step(long id,String aisle,String unit) {
        RouteStepDTO s=new RouteStepDTO();s.pickedItemId=id;s.aisle=aisle;s.unit=unit;s.stepNumber=(int)id;return s;
    }
    @Test void startsAtEntranceAndOptimizesActualBaysWithConsecutivePicks() {
        PlanNode a=node("ENTRY",0,0,PlanNodeType.ENTRANCE), b=node("B",100,0,PlanNodeType.INTERSECTION), c=node("C",100,100,PlanNodeType.DEADEND);
        var result=planner.plan(List.of(a,b,c),List.of(aisle("AB",a,b,10),aisle("BC",b,c,10)),
                List.of(shelf("AB","NEAR",.2),shelf("BC","FAR",.8)),
                List.of(step(1,"BC","FAR"),step(2,"AB","NEAR"),step(3,"AB","NEAR")),null);
        var steps=result.route().stream().flatMap(g -> g.steps.stream()).toList();
        assertEquals(List.of("NEAR","NEAR","FAR"),steps.stream().map(s -> s.unit).toList());
        assertEquals(List.of(1,2,3),steps.stream().map(s -> s.stepNumber).toList());
        assertEquals(18,result.geometry().distanceMeters,1e-6);
        assertEquals(new StorePickingRoutePlanner.Point(0,0),result.geometry().segments.get(0).get(0));
        assertTrue(result.geometry().segments.get(1).stream().allMatch(p -> p.y()==0 || p.x()==100));
        assertNotNull(steps.get(0).shelfPoint);
    }
    @Test void improvesNearestNeighborTourAndUsesPhysicalLengths() {
        PlanNode entry=node("ENTRY",0,0,PlanNodeType.ENTRANCE),west=node("W",-100,0,PlanNodeType.DEADEND),east=node("E",100,0,PlanNodeType.DEADEND);
        var result=planner.plan(List.of(entry,west,east),List.of(aisle("W",entry,west,10),aisle("E",entry,east,10)),
                List.of(shelf("W","W3",.3),shelf("W","W4",.4),shelf("E","E2",.2),shelf("E","E7",.7)),
                List.of(step(1,"E","E2"),step(2,"E","E7"),step(3,"W","W3"),step(4,"W","W4")),null);
        assertEquals(15,result.geometry().distanceMeters,1e-6);
        assertTrue(result.geometry().distanceMeters<=result.geometry().originalDistanceMeters);
    }
    @Test void honorsAnExplicitStartingNode() {
        PlanNode a=node("ENTRY",0,0,PlanNodeType.ENTRANCE),b=node("END",100,0,PlanNodeType.DEADEND);
        var result=planner.plan(List.of(a,b),List.of(aisle("AB",a,b,10)),List.of(shelf("AB","U1",.2),shelf("AB","U2",.8)),
                List.of(step(1,"AB","U1"),step(2,"AB","U2")),"END");
        assertEquals("U2",result.route().get(0).steps.get(0).unit);
        assertEquals(new StorePickingRoutePlanner.Point(100,0),result.geometry().segments.get(0).get(0));
        assertThrows(IllegalArgumentException.class,() -> planner.plan(List.of(a,b),List.of(aisle("AB",a,b,10)),List.of(),List.of(),"UNKNOWN"));
    }
    @Test void reportsMissingAndDisconnectedStopsWithoutInventingShortcuts() {
        PlanNode a=node("ENTRY",0,0,PlanNodeType.ENTRANCE),b=node("B",100,0,PlanNodeType.DEADEND),c=node("C",0,100,PlanNodeType.INTERSECTION),d=node("D",100,100,PlanNodeType.DEADEND);
        var result=planner.plan(List.of(a,b,c,d),List.of(aisle("AB",a,b,10),aisle("CD",c,d,10)),List.of(shelf("CD","U1",.5)),
                List.of(step(1,"CD","U1"),step(2,"AB","MISSING")),null);
        assertNull(result.geometry().distanceMeters);
        assertEquals(1,result.geometry().disconnected);
        assertEquals(1,result.geometry().unlocated);
        assertTrue(result.geometry().segments.isEmpty());
        assertEquals(2,result.route().stream().mapToInt(g -> g.steps.size()).sum());
    }
    @Test void followsCurvedAislesAndDoesNotShareStateBetweenPlans() {
        PlanNode a=node("ENTRY",0,0,PlanNodeType.ENTRANCE),b=node("B",100,0,PlanNodeType.DEADEND);
        Aisle curved=aisle("AB",a,b,20);curved.shapeType="CURVED";
        AisleWaypoint w1=new AisleWaypoint();w1.position=0;w1.x=0.0;w1.y=100.0;
        AisleWaypoint w2=new AisleWaypoint();w2.position=1;w2.x=100.0;w2.y=100.0;
        curved.waypoints=new ArrayList<>(List.of(w1,w2));
        var result=planner.plan(List.of(a,b),List.of(curved),List.of(shelf("AB","U1",.5)),List.of(step(1,"AB","U1")),null);
        assertTrue(result.geometry().segments.get(0).stream().anyMatch(p -> p.y()>60));
        assertEquals(10,result.geometry().distanceMeters,1e-6);
        var second=planner.plan(List.of(a,b),List.of(aisle("AB",a,b,2)),List.of(shelf("AB","U1",.5)),List.of(step(1,"AB","U1")),null);
        assertEquals(1,second.geometry().distanceMeters,1e-6);
    }
    @Test void failsClearlyWhenTheSavedPlanHasNoConnectedEntrance() {
        PlanNode a=node("A",0,0,PlanNodeType.INTERSECTION),b=node("B",100,0,PlanNodeType.DEADEND);
        assertThrows(IllegalStateException.class,() -> planner.plan(List.of(a,b),List.of(aisle("AB",a,b,10)),List.of(shelf("AB","U1",.5)),List.of(step(1,"AB","U1")),null));
    }
    @Test void urgentBayIsVisitedBeforeNearerOrdinaryBay(){
        var entry=node("ENTRY",0,0,PlanNodeType.ENTRANCE);var end=node("END",100,0,PlanNodeType.DEADEND);var near=step(1,"A","NEAR");var urgent=step(2,"A","FAR");urgent.priority=90;
        var result=planner.plan(List.of(entry,end),List.of(aisle("A",entry,end,10)),List.of(shelf("A","NEAR",.2),shelf("A","FAR",.8)),List.of(near,urgent),null);
        assertEquals(List.of("FAR","NEAR"),result.route().stream().flatMap(g->g.steps.stream()).map(x->x.unit).toList());assertEquals(14,result.geometry().distanceMeters,1e-6);
    }
}
