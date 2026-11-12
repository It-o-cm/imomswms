package com.intermarche.backoffice.engine.storeops;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.workforce.PlanningEntry;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.*;
import java.util.*;
import org.hibernate.Hibernate;
@ApplicationScoped
public class PlanningAssignmentService {
 public record Candidate(Long employeeId,String name,long bookedMinutes){}
 /** Planning data of one store preloaded once for a window, so that several slots can be evaluated without new queries. */
 public record Context(Store store,LocalDateTime from,LocalDateTime to,List<PlanningEntry> entries,List<BaseHandlingOrder> orders,List<Employee> employees,Map<String,String> operatorStates){}
 /**
  * One reason preventing an employee from taking a slot.
  * Codes: ADMIN, INACTIVE, ROLE, NO_SHIFT (no hours entered that day), OUT_OF_SHIFT, ABSENCE, TASK, TRANSIT, HANDLING.
  */
 public record Reason(String code,String message,Long entryId,Long orderId,LocalDateTime start,LocalDateTime end){}
 /**
  * Loads, in three queries, the planning entries, assigned open orders and active employees of a store overlapping a window.
  *
  * @param store the store
  * @param from window start
  * @param to window end
  * @return the preloaded context
  */
 public Context context(Store store,LocalDateTime from,LocalDateTime to){
  var entries=PlanningEntry.<PlanningEntry>list("employee.store=?1 and cancelled=false and startsAt<?2 and endsAt>?3",store,to,from);
  var orders=BaseHandlingOrder.<BaseHandlingOrder>list("store=?1 and assignee is not null and requestedStart<?2 and requestedEnd>?3 and status not in ?4",store,to,from,List.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED));
  return new Context(store,from,to,entries,orders,Employee.<Employee>list("store=?1 and active=true",store),com.intermarche.backoffice.domain.operator.OperatorProfile.<com.intermarche.backoffice.domain.operator.OperatorProfile>list("store",store).stream().collect(java.util.stream.Collectors.toMap(p->p.principal,p->p.state)));
 }
 /**
  * Validates a slot request shared by every evaluation.
  *
  * @param start slot start
  * @param end slot end
  * @param kind TASK or HANDLING
  * @param requiredRole optional required role
  */
 private static void validate(LocalDateTime start,LocalDateTime end,String kind,String requiredRole){
  if(start==null || end==null || !end.isAfter(start) || end.isAfter(start.plusDays(31)))throw new IllegalArgumentException("Créneau invalide");
  if(!Set.of("TASK","HANDLING").contains(kind))throw new IllegalArgumentException("Seules les tâches et manutentions peuvent être proposées");
  if(requiredRole!=null && !requiredRole.isBlank() && !Set.of("PICKER","CASHIER").contains(requiredRole))throw new IllegalArgumentException("Compétence inconnue");
 }
 /**
  * Returns the valid employees for a slot, loading the planning of the slot's days.
  *
  * @param store the store
  * @param start slot start
  * @param end slot end
  * @param entryId planning entry being edited, excluded from conflicts
  * @param orderId handling order being edited, excluded from conflicts
  * @param kind TASK or HANDLING
  * @param requiredRole optional required role
  * @return candidates ordered by booked minutes, then name
  */
 public List<Candidate> candidates(Store store,LocalDateTime start,LocalDateTime end,Long entryId,Long orderId,String kind,String requiredRole){
  validate(start,end,kind,requiredRole);
  return candidates(context(store,start.toLocalDate().atStartOfDay(),end.toLocalDate().plusDays(1).atStartOfDay()),start,end,entryId,orderId,kind,requiredRole);
 }
 /**
  * Returns the valid employees for a slot from a preloaded context, with the same rules as {@link #candidates(Store,LocalDateTime,LocalDateTime,Long,Long,String,String)}.
  *
  * @param context preloaded planning, which must cover the slot's days
  * @param start slot start
  * @param end slot end
  * @param entryId planning entry being edited, excluded from conflicts
  * @param orderId handling order being edited, excluded from conflicts
  * @param kind TASK or HANDLING
  * @param requiredRole optional required role
  * @return candidates ordered by booked minutes, then name
  */
 public List<Candidate> candidates(Context context,LocalDateTime start,LocalDateTime end,Long entryId,Long orderId,String kind,String requiredRole){
  validate(start,end,kind,requiredRole);
  var dayStart=start.toLocalDate().atStartOfDay();var dayEnd=end.toLocalDate().plusDays(1).atStartOfDay();
  var entries=context.entries().stream().filter(e->e.startsAt.isBefore(dayEnd) && e.endsAt.isAfter(dayStart)).toList();
  var orders=context.orders().stream().filter(o->o.requestedStart.isBefore(dayEnd) && o.requestedEnd.isAfter(dayStart)).toList();
  var result=new ArrayList<Candidate>();
  for(var person:context.employees()){
   if(!"AVAILABLE".equals(context.operatorStates().getOrDefault(person.badgeId,"AVAILABLE")))continue;
   if(!evaluate(person,entries,orders,start,end,entryId,orderId,kind,requiredRole).isEmpty())continue;
   var own=entries.stream().filter(e->Hibernate.unproxy(e.employee,Employee.class).id.equals(person.id)).toList();
   var assigned=orders.stream().filter(o->Hibernate.unproxy(o.assignee,Employee.class).id.equals(person.id) && !Objects.equals(o.id,orderId)).toList();
   long minutes=own.stream().filter(e->"TASK".equals(e.kind) && !Objects.equals(e.id,entryId)).mapToLong(e->Duration.between(e.startsAt.isBefore(dayStart)?dayStart:e.startsAt,e.endsAt.isAfter(dayEnd)?dayEnd:e.endsAt).toMinutes()).sum()+assigned.stream().mapToLong(o->Duration.between(o.requestedStart.isBefore(dayStart)?dayStart:o.requestedStart,o.requestedEnd.isAfter(dayEnd)?dayEnd:o.requestedEnd).toMinutes()).sum();
   result.add(new Candidate(person.id,person.firstName+" "+person.lastName,minutes));
  }
  result.sort(Comparator.comparingLong(Candidate::bookedMinutes).thenComparing(Candidate::name).thenComparing(Candidate::employeeId));return result;
 }
 /**
  * Explains why one employee cannot take a slot, with the same rules as the candidate search. An empty list means the assignment is valid.
  *
  * @param context preloaded planning, which must cover the slot's days
  * @param person the employee, possibly inactive
  * @param start slot start
  * @param end slot end
  * @param entryId planning entry being edited, excluded from conflicts
  * @param orderId handling order being edited, excluded from conflicts
  * @param kind TASK or HANDLING
  * @param requiredRole optional required role
  * @return the blocking reasons, in a stable order
  */
 public List<Reason> reasons(Context context,Employee person,LocalDateTime start,LocalDateTime end,Long entryId,Long orderId,String kind,String requiredRole){
  validate(start,end,kind,requiredRole);
  var dayStart=start.toLocalDate().atStartOfDay();var dayEnd=end.toLocalDate().plusDays(1).atStartOfDay();
  if(!"AVAILABLE".equals(context.operatorStates().getOrDefault(person.badgeId,"AVAILABLE")))return List.of(new Reason("OPERATOR_UNAVAILABLE","Préparateur en pause ou en fin de poste ; présence et mission conservées",null,orderId,start,end));
  return evaluate(person,context.entries().stream().filter(e->e.startsAt.isBefore(dayEnd) && e.endsAt.isAfter(dayStart)).toList(),context.orders().stream().filter(o->o.requestedStart.isBefore(dayEnd) && o.requestedEnd.isAfter(dayStart)).toList(),start,end,entryId,orderId,kind,requiredRole);
 }
 /**
  * Evaluates one employee against the entries and orders of the slot's days.
  *
  * @param person the employee
  * @param entries planning entries of the slot's days
  * @param orders assigned open orders of the slot's days
  * @param start slot start
  * @param end slot end
  * @param entryId excluded planning entry
  * @param orderId excluded handling order
  * @param kind TASK or HANDLING
  * @param requiredRole optional required role
  * @return blocking reasons; empty when the employee is valid
  */
 private List<Reason> evaluate(Employee person,List<PlanningEntry> entries,List<BaseHandlingOrder> orders,LocalDateTime start,LocalDateTime end,Long entryId,Long orderId,String kind,String requiredRole){
  var reasons=new ArrayList<Reason>();String name=person.firstName+" "+person.lastName;
  if(person.role==Employee.EmployeeRole.ADMIN){reasons.add(new Reason("ADMIN",name+" est un compte d’administration",null,null,null,null));return reasons;}
  if(!person.active)reasons.add(new Reason("INACTIVE","Le compte de "+name+" est inactif",null,null,null,null));
  if("HANDLING".equals(kind) && person.role!=Employee.EmployeeRole.PICKER && person.role!=Employee.EmployeeRole.MANAGER)reasons.add(new Reason("ROLE",name+" n’a pas la compétence de préparation",null,null,null,null));
  if(requiredRole!=null && !requiredRole.isBlank() && !person.role.name().equals(requiredRole))reasons.add(new Reason("ROLE",name+" n’a pas la compétence requise",null,null,null,null));
  var own=entries.stream().filter(e->Hibernate.unproxy(e.employee,Employee.class).id.equals(person.id)).toList();
  var shifts=own.stream().filter(e->"SHIFT".equals(e.kind) && !Objects.equals(e.id,entryId)).sorted(Comparator.comparing(e->e.startsAt)).toList();LocalDateTime covered=start;
  for(var shift:shifts){if(shift.startsAt.isAfter(covered))break;if(shift.endsAt.isAfter(covered))covered=shift.endsAt;if(!covered.isBefore(end))break;}
  if(covered.isBefore(end))reasons.add(shifts.isEmpty()?new Reason("NO_SHIFT","Aucun horaire de présence renseigné pour "+name+" ce jour-là",null,null,start,end):new Reason("OUT_OF_SHIFT","Les horaires de "+name+" ne couvrent pas tout le créneau",null,null,covered.isAfter(start)?covered:start,end));
  for(var e:own)if(!Objects.equals(e.id,entryId) && !"SHIFT".equals(e.kind) && e.startsAt.isBefore(end) && e.endsAt.isAfter(start))reasons.add(new Reason(e.kind,("ABSENCE".equals(e.kind)?"Absence":"TRANSIT".equals(e.kind)?"Transit":"Tâche")+" « "+e.title+" »",e.id,null,e.startsAt,e.endsAt));
  for(var o:orders)if(Hibernate.unproxy(o.assignee,Employee.class).id.equals(person.id) && !Objects.equals(o.id,orderId) && o.requestedStart.isBefore(end) && o.requestedEnd.isAfter(start))reasons.add(new Reason("HANDLING","Déjà affecté à "+o.code,null,o.id,o.requestedStart,o.requestedEnd));
  return reasons;
 }
}
