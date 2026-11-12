package com.intermarche.backoffice.engine.storeops;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.intermarche.backoffice.engine.storeops.ConstrainedPlanningSolver.*;
class ConstrainedPlanningSolverTest {
 LocalDateTime t(int h,int m){return LocalDateTime.of(2030,1,7,h,m);}
 Person person(long id,Long building,List<Slot> absences){return new Person(id,"Employé "+id,"PICKER",List.of(new Slot(t(8,0),t(18,0),building)),absences,building);}
 Job job(String id,Long employee,int hour,Long building){return new Job(id,id,employee,t(hour,0),t(hour+1,0),t(hour,0),t(hour+1,0),"HANDLING",building,building,null,true);}
 @Test void proposesDistinctAssignmentsAndPreservesDurations(){var result=new ConstrainedPlanningSolver(List.of(person(1,10L,List.of()),person(2,10L,List.of())),List.of(job("a",1L,9,10L)),Map.of(),5).solve();assertEquals(2,result.solutions().size());assertEquals(2,result.solutions().stream().map(s->s.assignments().getFirst().employeeId()).distinct().count());assertTrue(result.solutions().stream().allMatch(s->Duration.between(s.assignments().getFirst().start(),s.assignments().getFirst().end()).toMinutes()==60));}
 @Test void ranksAvoidingTransitAheadOfEmployeeStability(){var result=new ConstrainedPlanningSolver(List.of(person(1,20L,List.of()),person(2,10L,List.of())),List.of(job("a",1L,9,10L)),Map.of(edge(20L,10L),15),5).solve();assertEquals(2,result.solutions().size());assertEquals(2,result.solutions().getFirst().assignments().getFirst().employeeId());assertEquals(0,result.solutions().getFirst().transitMinutes());assertEquals(15,result.solutions().getLast().transitMinutes());}
 @Test void requiresDirectedTransitAndCoversItByPresence(){var job=job("a",1L,8,20L);assertTrue(new ConstrainedPlanningSolver(List.of(person(1,10L,List.of())),List.of(job),Map.of(edge(10L,20L),15),5).solve().solutions().stream().noneMatch(s->s.unscheduled().isEmpty()));assertTrue(new ConstrainedPlanningSolver(List.of(person(1,10L,List.of())),List.of(job("a",1L,9,20L)),Map.of(edge(20L,10L),15),5).solve().solutions().stream().noneMatch(s->s.unscheduled().isEmpty()));}
 @Test void shiftsOperationsGloballyAndChecksDependency(){var a=new Job("a","a",1L,t(9,0),t(10,0),t(9,0),t(11,0),"HANDLING",10L,10L,null,true);var b=new Job("b","b",1L,t(9,0),t(10,0),t(9,0),t(11,0),"HANDLING",10L,10L,"a",true);var result=new ConstrainedPlanningSolver(List.of(person(1,10L,List.of())),List.of(a,b),Map.of(),5).solve();assertFalse(result.solutions().isEmpty());for(var s:result.solutions()){var first=s.assignments().stream().filter(x->x.id().equals("a")).findFirst().orElseThrow();var second=s.assignments().stream().filter(x->x.id().equals("b")).findFirst().orElseThrow();assertFalse(first.end().isAfter(second.start()));}}
 @Test void respectsAbsencesAndRole(){assertTrue(new ConstrainedPlanningSolver(List.of(person(1,10L,List.of(new Slot(t(9,0),t(10,0))))),List.of(job("a",1L,9,10L)),Map.of(),5).solve().solutions().stream().noneMatch(s->s.unscheduled().isEmpty()));var cashier=new Person(1,"Caisse","CASHIER",List.of(new Slot(t(8,0),t(18,0),10L)),List.of(),10L);assertTrue(new ConstrainedPlanningSolver(List.of(cashier),List.of(job("a",1L,9,10L)),Map.of(),5).solve().solutions().stream().noneMatch(s->s.unscheduled().isEmpty()));}
 @Test void fixedOperationCannotChangeEmployeeOrTime(){var j=new Job("a","a",1L,t(9,0),t(10,0),t(9,0),t(10,0),"HANDLING",10L,10L,null,false);var result=new ConstrainedPlanningSolver(List.of(person(1,10L,List.of()),person(2,10L,List.of())),List.of(j),Map.of(),5).solve();assertEquals(1,result.solutions().size());assertEquals(1,result.solutions().getFirst().assignments().getFirst().employeeId());}
 @Test void historicBoundaryDoesNotRequireOldAttendanceAndIsNotReturnedAsAnAssignment(){var anchor=new Job("anchor-old","Historique",1L,t(6,0),t(7,0),t(6,0),t(7,0),null,10L,10L,null,false);var result=new ConstrainedPlanningSolver(List.of(person(1,10L,List.of())),List.of(anchor,job("a",1L,9,10L)),Map.of(),5).solve();assertEquals(1,result.solutions().size());assertEquals(List.of("a"),result.solutions().getFirst().assignments().stream().map(Move::id).toList());}
 @Test void followingFixedOperationRequiresEnoughTransitTime(){var anchor=new Job("anchor-next","Après la période",1L,t(10,5),t(11,5),t(10,5),t(11,5),null,20L,20L,null,false);assertTrue(new ConstrainedPlanningSolver(List.of(person(1,10L,List.of())),List.of(job("a",1L,9,10L),anchor),Map.of(edge(10L,20L),15),5).solve().solutions().stream().noneMatch(s->s.unscheduled().isEmpty()));}
 @Test void unknownBuildingNeverCreatesInstantTeleport(){var result=new ConstrainedPlanningSolver(List.of(person(1,10L,List.of())),List.of(job("a",1L,9,null)),Map.of(),5).solve();assertTrue(result.solutions().stream().allMatch(s->s.assignments().isEmpty()));assertEquals(1,result.solutions().getFirst().unscheduled().size());}

 @Test void proposesPartialAlternativesWithoutOverlapsWhenCapacityIsInsufficient(){
  var result=new ConstrainedPlanningSolver(List.of(person(1,10L,List.of())),List.of(job("order-a",null,9,10L),job("order-b",null,9,10L)),Map.of(),5).solve();
  assertEquals(2,result.solutions().size());for(var s:result.solutions()){assertEquals(1,s.assignments().size());assertEquals(1,s.unscheduled().size());}
 }
 @Test void canMoveAnotherTaskSlightlyLateToFitBoth(){
  var other=new Job("entry-1","Caisse",1L,t(9,0),t(10,0),t(9,0),t(10,30),null,10L,10L,null,true);
  var urgent=new Job("order-1","Urgent",null,t(9,0),t(9,30),t(9,0),t(9,30),"HANDLING",10L,10L,null,true);
  var result=new ConstrainedPlanningSolver(List.of(person(1,10L,List.of())),List.of(other,urgent),Map.of(),5).solve();
  assertFalse(result.solutions().isEmpty());var best=result.solutions().getFirst();assertTrue(best.unscheduled().isEmpty());assertEquals(30,best.delayMinutes());assertEquals(t(9,30),best.assignments().stream().filter(m->m.id().equals("entry-1")).findFirst().orElseThrow().start());
 }
 @Test void partialSolutionsDoNotKeepAChildWithoutItsPredecessor(){
  var parent=new Job("order-parent","Parent",null,t(9,0),t(10,0),t(9,0),t(10,0),"HANDLING",20L,20L,null,true);
  var child=new Job("order-child","Enfant",null,t(10,0),t(11,0),t(10,0),t(11,0),"HANDLING",10L,10L,"order-parent",true);
  var result=new ConstrainedPlanningSolver(List.of(person(1,10L,List.of())),List.of(parent,child),Map.of(),5).solve();assertTrue(result.solutions().stream().allMatch(s->s.assignments().isEmpty()&&s.unscheduled().size()==2));
 }
 @Test void partialSolutionCannotDiscardReservedTasks(){
  var reserved=new Job("entry-1","Caisse",1L,t(9,0),t(10,0),t(9,0),t(10,0),null,10L,10L,null,true);
  var result=new ConstrainedPlanningSolver(List.of(person(1,10L,List.of())),List.of(reserved,job("order-1",null,9,10L)),Map.of(),5).solve();assertTrue(result.solutions().stream().allMatch(s->s.assignments().getFirst().id().equals("entry-1")&&s.unscheduled().getFirst().id().equals("order-1")));
 }
 @Test void unavailableOperatorKeepsFixedWorkButReceivesNoNewMovableWork(){var paused=new Person(1,"Pause","PICKER",List.of(new Slot(t(8,0),t(18,0),10L)),List.of(),10L,false);var fixed=new Job("fixed","Mission suspendue",1L,t(9,0),t(10,0),t(9,0),t(10,0),"HANDLING",10L,10L,null,false);var result=new ConstrainedPlanningSolver(List.of(paused,person(2,10L,List.of())),List.of(fixed,job("order-new",null,11,10L)),Map.of(),5).solve();assertFalse(result.solutions().isEmpty());for(var solution:result.solutions()){assertEquals(1,solution.assignments().stream().filter(m->m.id().equals("fixed")).findFirst().orElseThrow().employeeId());assertEquals(2,solution.assignments().stream().filter(m->m.id().equals("order-new")).findFirst().orElseThrow().employeeId());}}
}
