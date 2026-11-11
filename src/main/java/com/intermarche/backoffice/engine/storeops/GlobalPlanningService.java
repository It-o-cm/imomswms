package com.intermarche.backoffice.engine.storeops;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.workforce.*;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.wms.handling.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hibernate.Hibernate;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.intermarche.backoffice.engine.storeops.ConstrainedPlanningSolver.*;
@ApplicationScoped
public class GlobalPlanningService {
 @Inject com.fasterxml.jackson.databind.ObjectMapper json;
 public static class Request {public Long storeId;public LocalDateTime start,end;public int flexibilityMinutes=60;public int latenessMinutes=30;}
 public static class Apply {public Long storeId;public String previewId;public int solution;}
 private record Model(List<Person> people,List<Job> jobs,Map<String,Integer> edges,List<String> warnings,List<String> revisions){}
 private record Preview(Long storeId,Request request,Model model,String fingerprint,Result result,LocalDateTime created){}
 private final Map<String,Preview> previews=new ConcurrentHashMap<>();
 private Long id(Building b){return b==null?null:Hibernate.unproxy(b,Building.class).id;}
 private Long employee(Employee e){return e==null?null:Hibernate.unproxy(e,Employee.class).id;}
 public static Building startBuilding(BaseHandlingOrder raw){var o=Hibernate.unproxy(raw,BaseHandlingOrder.class);if(o instanceof com.intermarche.backoffice.domain.wms.handling.InventoryOrder inventory)return inventory.building;if(o instanceof CancellationDispositionOrder d)return d.building;if(o instanceof ReplenishmentOrder r)return r.sourceBuilding==null?r.destinationBuilding:r.sourceBuilding;if(o instanceof CollectingOrder c)return c.building;if(o instanceof GroupingOrder g)return g.buffer==null?null:g.buffer.building;return null;}
 public static Building endBuilding(BaseHandlingOrder raw){var o=Hibernate.unproxy(raw,BaseHandlingOrder.class);return o instanceof ReplenishmentOrder r?r.destinationBuilding:startBuilding(o);}
 private Model model(Store store,Request request){
  var start=request.start.minusDays(1);var end=request.end.plusDays(1);
  var entries=PlanningEntry.<PlanningEntry>list("employee.store=?1 and cancelled=false and startsAt<?2 and endsAt>?3 order by id",store,end,start);
  var orders=BaseHandlingOrder.<BaseHandlingOrder>list("store=?1 and status<>?2 and id not in (select fo.id from FabricationOrder fo where fo.proposalState = 'PROPOSED') and requestedStart<?3 and requestedEnd>?4 order by id",store,BaseHandlingOrder.HandlingOrderStatus.CANCELLED,end,start);
  // Snapshot shared constraints once. Query count must not grow with staff or jobs.
  var positions=EmployeePosition.<EmployeePosition>list("employee.store=?1 order by id",store);
  var positionsByEmployee=new HashMap<Long,EmployeePosition>();
  for(var position:positions)positionsByEmployee.put(employee(position.employee),position);
  var executions=com.intermarche.backoffice.domain.handling.HandlingExecution.getEntityManager()
    .createQuery("select distinct e.handlingOrder.id from HandlingExecution e where e.handlingOrder.store=:store and e.status<>:cancelled",Long.class)
    .setParameter("store",store).setParameter("cancelled",com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.CANCELLED).getResultList();
  var executingOrders=new HashSet<Long>(executions);
  var operationalStates=com.intermarche.backoffice.domain.fieldops.MissionOperationalState.<com.intermarche.backoffice.domain.fieldops.MissionOperationalState>list("store=?1 order by id",store);
  var suspendedOrders=new HashSet<Long>();
  for(var state:operationalStates)if(!"ACTIVE".equals(state.state))suspendedOrders.add(com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(state.handlingOrder));
  var profiles=com.intermarche.backoffice.domain.operator.OperatorProfile.<com.intermarche.backoffice.domain.operator.OperatorProfile>list("store=?1 order by id",store);
  var unavailable=profiles.stream().filter(p->!"AVAILABLE".equals(p.state)).map(p->p.principal).collect(java.util.stream.Collectors.toSet());
  var people=new ArrayList<Person>();
  for(var e:Employee.<Employee>list("store=?1 and active=true order by id",store)){if(e.role==Employee.EmployeeRole.ADMIN)continue;var own=entries.stream().filter(x->Objects.equals(employee(x.employee),e.id)).toList();
   var position=positionsByEmployee.get(e.id);var absences=new ArrayList<Slot>(own.stream().filter(x->"ABSENCE".equals(x.kind)).map(x->new Slot(x.startsAt,x.endsAt)).toList());if(position!=null && position.availableAt.isAfter(DateTimeProvider.now()))absences.add(new Slot(position.updatedAt,position.availableAt));
   var first=own.stream().filter(x->"SHIFT".equals(x.kind)&&x.building!=null).min(Comparator.comparing(x->x.startsAt)).orElse(null);
   people.add(new Person(e.id,e.firstName+" "+e.lastName,e.role.name(),own.stream().filter(x->"SHIFT".equals(x.kind)).map(x->new Slot(x.startsAt,x.endsAt,position!=null && x.startsAt.toLocalDate().equals(DateTimeProvider.now().toLocalDate())?id(position.building):id(x.building))).toList(),absences,first==null?null:id(first.building),!unavailable.contains(e.badgeId)));
  }
  var jobs=new ArrayList<Job>();var warnings=new ArrayList<String>();
  for(var e:entries){if(!"TASK".equals(e.kind))continue;boolean move=!e.startsAt.isBefore(request.start)&&!e.endsAt.isAfter(request.end)&&e.startsAt.isAfter(DateTimeProvider.now());
   jobs.add(new Job("entry-"+e.id,e.title,employee(e.employee),e.startsAt,e.endsAt,move?max(request.start,e.startsAt.minusMinutes(request.flexibilityMinutes)):e.startsAt,move?min(request.end,e.endsAt.plusMinutes(Math.min(request.flexibilityMinutes,request.latenessMinutes))):e.endsAt,e.requiredRole==null||e.requiredRole.isBlank()?null:e.requiredRole,id(e.building),id(e.building),null,move));
  }
  for(var raw:orders){var o=Hibernate.unproxy(raw,BaseHandlingOrder.class);boolean move=Set.of(BaseHandlingOrder.HandlingOrderStatus.OPEN,BaseHandlingOrder.HandlingOrderStatus.SCHEDULED).contains(o.status)&&!o.requestedStart.isBefore(request.start)&&!o.requestedEnd.isAfter(request.end)&&o.requestedStart.isAfter(DateTimeProvider.now());
   if(executingOrders.contains(o.id)||suspendedOrders.contains(o.id)||o.assignee!=null&&unavailable.contains(o.assignee.badgeId))move=false;
   LocalDateTime due=o.requestedEnd;
   LocalDateTime latest=move?min(request.end,o.requestedEnd.plusMinutes(request.flexibilityMinutes)):o.requestedEnd;String predecessor=null;
   if(o instanceof PackingOrder p && p.deadLine!=null){due=min(due,p.deadLine);}
   if(o instanceof ReplenishmentOrder r){for(var supply:r.supplies){due=min(due,supply.availableAt);if(supply.pickedQuantity().signum()>0 || supply.status!=com.intermarche.backoffice.domain.stock.ExpectedSupply.SupplyStatus.EXPECTED)move=false;}if(r.predecessor!=null){if(orders.stream().anyMatch(x->x.id.equals(r.predecessor.id)))predecessor="order-"+r.predecessor.id;else if(r.predecessor.requestedEnd!=null && r.predecessor.requestedEnd.isAfter(o.requestedStart.minusMinutes(request.flexibilityMinutes)))throw new IllegalArgumentException("Incluez le prédécesseur de "+o.code+" dans la période.");}}
   if(move)latest=min(latest,due.plusMinutes(request.latenessMinutes));
   if(!move && o.assignee==null){if(o.requestedStart.isBefore(request.end)&&o.requestedEnd.isAfter(request.start))warnings.add("Ordre non affecté dont le créneau a déjà commencé : "+o.code);continue;}
   LocalDateTime occupiedUntil=suspendedOrders.contains(o.id)?max(o.requestedEnd,request.end):o.requestedEnd;
   if(suspendedOrders.contains(o.id))warnings.add("Mission suspendue : "+o.code+" conserve son préparateur jusqu’à reprise ou transmission acceptée.");
   jobs.add(new Job("order-"+o.id,o.code,employee(o.assignee),o.requestedStart,occupiedUntil,move?max(request.start,o.requestedStart.minusMinutes(request.flexibilityMinutes)):o.requestedStart,move?latest:occupiedUntil,"HANDLING",id(startBuilding(o)),id(endBuilding(o)),predecessor,move,due));
  }
  // Historic and later work supplies boundary locations, without revalidating old attendance.
  var allJobs=new ArrayList<>(jobs);jobs.removeIf(j->!j.start().isBefore(request.end)||!j.end().isAfter(request.start));
  var anchors=new HashMap<String,String>();
  for(var person:people){var own=allJobs.stream().filter(j->Objects.equals(j.employeeId(),person.id())).toList();var before=own.stream().filter(j->!j.end().isAfter(request.start)).max(Comparator.comparing(Job::end)).orElse(null);var after=own.stream().filter(j->!j.start().isBefore(request.end)).min(Comparator.comparing(Job::start)).orElse(null);
   for(var j:Arrays.asList(before,after)){if(j==null)continue;var declared=positionsByEmployee.get(person.id());if(j==before&&declared!=null&&declared.updatedAt.isAfter(j.end()))continue;String anchor="anchor-"+j.id();anchors.put(j.id(),anchor);jobs.add(new Job(anchor,j.title(),j.employeeId(),j.start(),j.end(),j.start(),j.end(),null,j.from(),j.to(),null,false));}}
  for(int i=0;i<jobs.size();i++){var j=jobs.get(i);if(j.predecessor()!=null && anchors.containsKey(j.predecessor()))jobs.set(i,new Job(j.id(),j.title(),j.employeeId(),j.start(),j.end(),j.earliest(),j.latest(),j.role(),j.from(),j.to(),anchors.get(j.predecessor()),j.movable(),j.due()));}
  for(var j:jobs)if(j.from()==null||j.to()==null)warnings.add("Bâtiment à renseigner : "+j.title());
  var edges=new TreeMap<String,Integer>();for(var t:BuildingTransit.<BuildingTransit>list("store=?1 order by id",store))edges.put(edge(id(t.origin),id(t.destination)),t.minutes);
  var revisions=new ArrayList<String>();for(var profile:profiles)revisions.add("profile:"+profile.id+":"+profile.version+":"+profile.state);for(var handover:com.intermarche.backoffice.domain.fieldops.MissionHandover.<com.intermarche.backoffice.domain.fieldops.MissionHandover>list("store=?1 and handlingOrder.requestedStart<?2 and handlingOrder.requestedEnd>?3 order by id",store,end,start))revisions.add("handover:"+handover.id+":"+handover.version+":"+handover.state);for(var state:operationalStates)revisions.add("mission:"+state.id+":"+state.version+":"+state.state);for(var execution:executions)revisions.add("execution:"+execution);for(var position:positions)revisions.add("p:"+employee(position.employee)+":"+id(position.building)+":"+position.availableAt);for(var e:entries)revisions.add("e:"+e.id+":"+e.version+":"+e.kind+":"+e.startsAt+":"+e.endsAt+":"+employee(e.employee)+":"+id(e.building));for(var raw:orders){var o=Hibernate.unproxy(raw,BaseHandlingOrder.class);revisions.add("o:"+o.id+":"+o.version+":"+o.status);if(o instanceof ReplenishmentOrder r)for(var supply:r.supplies)revisions.add("s:"+supply.id+":"+supply.version+":"+supply.status+":"+supply.availableAt+":"+supply.pickedQuantity());}
  return new Model(people,jobs,edges,warnings,revisions);
 }
 private static LocalDateTime min(LocalDateTime a,LocalDateTime b){return a.isBefore(b)?a:b;}
 private static LocalDateTime max(LocalDateTime a,LocalDateTime b){return a.isAfter(b)?a:b;}
 private String fingerprint(Model m){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(m)));}catch(Exception e){throw new IllegalStateException(e);}}
 public Map<String,Object> preview(Store store,Request r){
  if(r==null||r.start==null||r.end==null||!r.end.isAfter(r.start)||r.end.isAfter(r.start.plusDays(7))||r.flexibilityMinutes<0||r.flexibilityMinutes>240||r.latenessMinutes<0||r.latenessMinutes>240)throw new IllegalArgumentException("Période de 7 jours maximum ; décalage et retard toléré de 0 à 240 minutes.");
  var m=model(store,r);Result result=m.jobs.stream().noneMatch(j->!j.id().startsWith("anchor-"))?new Result(List.of(),false,0,"Aucune opération sur cette période."):new ConstrainedPlanningSolver(m.people,m.jobs,m.edges,5,max(r.start,DateTimeProvider.now())).solve();
  previews.entrySet().removeIf(e->e.getValue().created.plusMinutes(15).isBefore(DateTimeProvider.now()));if(previews.size()>200)throw new IllegalArgumentException("Trop de prévisualisations ouvertes. Réessayez plus tard.");
  String key=UUID.randomUUID().toString();previews.put(key,new Preview(store.id,r,m,fingerprint(m),result,DateTimeProvider.now()));return Map.of("previewId",key,"result",result,"warnings",m.warnings,"buildings",store.buildings.stream().map(b->Map.of("id",id(b),"code",b.buildingCode,"buildingType",b.getBuildingType())).sorted(Comparator.comparing(b->b.get("code").toString())).toList());
 }
 public Map<String,Object> apply(Store store,Apply r,String actor){
  if(r.previewId==null)throw new IllegalArgumentException("Prévisualisation requise");
  var saved=PlanningRevision.<PlanningRevision>find("previewId",r.previewId).firstResult();if(saved!=null){if(!Hibernate.unproxy(saved.store,Store.class).id.equals(store.id)||saved.solutionIndex!=r.solution)throw new IllegalArgumentException("Prévisualisation déjà utilisée pour une autre solution");return Map.of("applied",true,"replayed",true);}
  if(r.previewId==null)throw new IllegalArgumentException("Prévisualisation requise");Preview p=previews.get(r.previewId);if(p==null||!p.storeId.equals(store.id)||p.created.plusMinutes(15).isBefore(DateTimeProvider.now()))throw new IllegalArgumentException("Prévisualisation expirée. Relancez la recherche.");
  if(r.solution<0||r.solution>=p.result.solutions().size())throw new IllegalArgumentException("Solution inconnue");
  if(!fingerprint(model(store,p.request)).equals(p.fingerprint))throw new IllegalArgumentException("Le planning ou ses contraintes ont changé. Relancez la recherche.");
  var solution=p.result.solutions().get(r.solution);
  for(var omitted:solution.unscheduled()){var j=p.model.jobs.stream().filter(x->x.id().equals(omitted.id())).findFirst().orElseThrow();if(!j.movable()||!j.id().startsWith("order-"))throw new IllegalArgumentException("Une tâche réservée ne peut pas être retirée du planning.");var o=BaseHandlingOrder.<BaseHandlingOrder>findById(Long.valueOf(j.id().substring(6)));o.assignee=null;o.status=BaseHandlingOrder.HandlingOrderStatus.OPEN;}
  for(var m:solution.assignments()){var j=p.model.jobs.stream().filter(x->x.id().equals(m.id())).findFirst().orElseThrow();if(!j.movable())continue;if(!m.start().isAfter(DateTimeProvider.now()))throw new IllegalArgumentException("Un créneau proposé a commencé. Relancez la recherche.");if(m.id().startsWith("entry-")){var e=PlanningEntry.<PlanningEntry>findById(Long.valueOf(m.id().substring(6)));e.employee=Employee.findById(m.employeeId());e.startsAt=m.start();e.endsAt=m.end();}else{var o=BaseHandlingOrder.<BaseHandlingOrder>findById(Long.valueOf(m.id().substring(6)));o.assignee=Employee.findById(m.employeeId());o.requestedStart=m.start();o.requestedEnd=m.end();if(o instanceof ReplenishmentOrder replenishment)for(var supply:replenishment.supplies)if(supply.availableAt.isBefore(m.end()))supply.availableAt=m.end();}}
  for(var t:solution.transits())if(t.start()!=null&&t.start().isBefore(DateTimeProvider.now())&&t.end().isAfter(DateTimeProvider.now()))throw new IllegalArgumentException("Un transit devrait déjà avoir commencé. Relancez la recherche.");
  var transitStart=solution.transits().stream().filter(t->t.start()!=null).map(Transit::start).min(LocalDateTime::compareTo).map(t->min(t,p.request.start)).orElse(p.request.start);var transitEnd=solution.transits().stream().filter(t->t.end()!=null).map(Transit::end).max(LocalDateTime::compareTo).map(t->max(t,p.request.end)).orElse(p.request.end);var affectedEmployees=solution.assignments().stream().map(Move::employeeId).collect(java.util.stream.Collectors.toSet());
  solution.unscheduled().stream().map(Unscheduled::previousEmployeeId).filter(Objects::nonNull).forEach(affectedEmployees::add);
  // Replace generated transit blocks only inside the searched horizon, in the same transaction.
  for(var e:PlanningEntry.<PlanningEntry>list("employee.store=?1 and kind='TRANSIT' and startsAt<?2 and endsAt>?3 and cancelled=false",store,transitEnd,transitStart))if(affectedEmployees.contains(employee(e.employee)))e.cancelled=true;
  for(var t:solution.transits()){if(t.start()==null || !t.end().isAfter(DateTimeProvider.now()))continue;var e=new PlanningEntry();e.employee=Employee.findById(t.employeeId());e.kind="TRANSIT";e.title="Transit "+Building.<Building>findById(t.from()).buildingCode+" → "+Building.<Building>findById(t.to()).buildingCode;e.building=Building.findById(t.to());e.startsAt=t.start();e.endsAt=t.end();e.persist();}
  var revision=new PlanningRevision();revision.store=store;revision.previewId=r.previewId;revision.solutionIndex=r.solution;revision.actor=actor;revision.snapshotHash=p.fingerprint;try{revision.solutionJson=json.writeValueAsString(solution);}catch(Exception e){throw new IllegalStateException(e);}revision.persist();
  return Map.of("applied",true,"changed",solution.changed(),"transitMinutes",solution.transitMinutes());
 }
}
