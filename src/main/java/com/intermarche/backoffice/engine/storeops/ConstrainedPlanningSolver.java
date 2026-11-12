package com.intermarche.backoffice.engine.storeops;
import java.time.*;
import java.util.*;
/** Bounded enumeration with forward checking. No external optimization dependency. */
public final class ConstrainedPlanningSolver {
 public record Slot(LocalDateTime start,LocalDateTime end,Long building) {public Slot(LocalDateTime start,LocalDateTime end){this(start,end,null);}}
 public record Person(long id,String name,String role,List<Slot> shifts,List<Slot> absences,Long initialBuilding,boolean available) {public Person(long id,String name,String role,List<Slot> shifts,List<Slot> absences,Long initialBuilding){this(id,name,role,shifts,absences,initialBuilding,true);}}
 public record Job(String id,String title,Long employeeId,LocalDateTime start,LocalDateTime end,LocalDateTime earliest,LocalDateTime latest,String role,Long from,Long to,String predecessor,boolean movable,LocalDateTime due) {public Job(String id,String title,Long employeeId,LocalDateTime start,LocalDateTime end,LocalDateTime earliest,LocalDateTime latest,String role,Long from,Long to,String predecessor,boolean movable){this(id,title,employeeId,start,end,earliest,latest,role,from,to,predecessor,movable,end);}}
 public record Move(String id,String title,long employeeId,String employee,LocalDateTime start,LocalDateTime end,Long from,Long to,Long previousEmployeeId,LocalDateTime previousStart,LocalDateTime previousEnd) {}
 public record Transit(long employeeId,Long from,Long to,LocalDateTime start,LocalDateTime end,long minutes) {}
 public record Unscheduled(String id,String title,String reason,Long previousEmployeeId) {}
 public record Delay(String id,String title,long minutes) {}
 public record Solution(List<Move> assignments,List<Transit> transits,long transitMinutes,int changed,long deviationMinutes,List<Unscheduled> unscheduled,List<Delay> delays,long delayMinutes) {public Solution(List<Move> assignments,List<Transit> transits,long transitMinutes,int changed,long deviationMinutes){this(assignments,transits,transitMinutes,changed,deviationMinutes,List.of(),List.of(),0);}}
 public record Result(List<Solution> solutions,boolean limited,long visited,String message) {}
 private final List<Person> people;private final List<Job> jobs;private final Map<String,Integer> edges;
 private final Map<String,List<Move>> domains=new HashMap<>();private final List<Solution> best=new ArrayList<>();private long visited;private boolean limited;private final int limit;private long deadline;private boolean partial;private long nodeLimit=150000;private final LocalDateTime notBefore;
 public ConstrainedPlanningSolver(List<Person> people,List<Job> jobs,Map<String,Integer> edges,int limit){this(people,jobs,edges,limit,null);}
 public ConstrainedPlanningSolver(List<Person> people,List<Job> jobs,Map<String,Integer> edges,int limit,LocalDateTime notBefore){this.notBefore=notBefore;this.people=people;this.jobs=jobs;this.edges=edges;this.limit=limit;this.deadline=System.nanoTime()+2_000_000_000L;}
 public static String edge(Long a,Long b){return a+":"+b;}
 private int travel(Long a,Long b){if(a==null || b==null)return -1;if(a.equals(b))return 0;return edges.getOrDefault(edge(a,b),-1);}
 private boolean covers(Person p,LocalDateTime start,LocalDateTime end){var cursor=start;for(var s:p.shifts.stream().sorted(Comparator.comparing(Slot::start)).toList()){if(s.start.isAfter(cursor))break;if(s.end.isAfter(cursor))cursor=s.end;if(!cursor.isBefore(end))break;}return !cursor.isBefore(end) && p.absences.stream().noneMatch(s->s.start.isBefore(end)&&s.end.isAfter(start));}
 public Result solve(){
  if(jobs.size()>80)return new Result(List.of(),false,0,"Plus de 80 opérations : réduisez la période de recherche.");
  var blockedPersons=new HashSet<Long>();for(var j:jobs)if(j.id.startsWith("anchor-")&&(j.from==null||j.to==null))blockedPersons.add(j.employeeId);
  for(var j:jobs){if(!j.id.startsWith("anchor-")&&(j.from==null || j.to==null)){if(canDefer(j)){domains.put(j.id,List.of());continue;}return new Result(List.of(),false,0,"Bâtiment manquant pour « "+j.title+" ».");}var values=new ArrayList<Move>();Duration duration=Duration.between(j.start,j.end);if(duration.isNegative()||duration.isZero())return new Result(List.of(),false,0,"Durée invalide : "+j.title);
   for(var p:people){if(j.movable&&!p.available)continue;if(System.nanoTime()>deadline)return new Result(List.of(),true,visited,"Limite de calcul atteinte pendant la préparation des créneaux. Réduisez la période ou la flexibilité.");if(!j.id.startsWith("anchor-")&&blockedPersons.contains(p.id))continue;if(j.role!=null && !(j.role.equals("HANDLING")?Set.of("PICKER","MANAGER").contains(p.role):j.role.equals(p.role)))continue;
    if(!j.movable && !Objects.equals(j.employeeId,p.id))continue;
    var starts=new TreeSet<LocalDateTime>();if(!j.movable)starts.add(j.start);else{starts.add(j.start);for(var t=j.earliest;!t.plus(duration).isAfter(j.latest);t=t.plusMinutes(15))starts.add(t);}
    for(var t:starts){var end=t.plus(duration);if(t.isBefore(j.earliest)||end.isAfter(j.latest)||(!j.id.startsWith("anchor-")&&!covers(p,t,end)))continue;values.add(new Move(j.id,j.title,p.id,p.name,t,end,j.from,j.to,j.employeeId,j.start,j.end));}
   }
   values.sort(Comparator.comparingLong((Move m)->(Objects.equals(m.employeeId,j.employeeId)?0:60)+Math.abs(Duration.between(j.start,m.start).toMinutes())).thenComparing(Move::start).thenComparingLong(Move::employeeId));domains.put(j.id,values);
   if(values.isEmpty() && !canDefer(j))return new Result(List.of(),false,0,"Aucune affectation valide pour « "+j.title+" ». Vérifiez présence, bâtiment de départ et compétence.");
  }
  search(new LinkedHashMap<>(),new HashSet<>());
  if(best.isEmpty()){partial=true;nodeLimit=visited+150000;deadline=System.nanoTime()+2_000_000_000L;search(new LinkedHashMap<>(),new HashSet<>());}
  return new Result(List.copyOf(best),limited,visited,best.isEmpty()?"Aucune solution trouvée : vérifiez horaires, absences, bâtiments, transits et dépendances.":partial?"Solutions partielles : certains ordres restent à affecter. Les tâches réservées et opérations fixes sont conservées.":limited?"Recherche bornée : solutions valides trouvées, optimalité non garantie.":"Recherche terminée sur les créneaux de 15 minutes (et horaires initiaux).");
 }
 private boolean canDefer(Job j){return j.movable && !j.id.startsWith("entry-") && !j.id.startsWith("anchor-");}
 private Comparator<Solution> ranking(){return Comparator.comparingInt((Solution s)->s.unscheduled.size()).thenComparingLong(Solution::delayMinutes).thenComparingLong(Solution::transitMinutes).thenComparingInt(Solution::changed).thenComparingLong(Solution::deviationMinutes);}
 private void search(Map<String,Move> assigned,Set<String> skipped){
  if(++visited>nodeLimit || System.nanoTime()>deadline){limited=true;return;}
  if(!best.isEmpty() && skipped.size()>best.getLast().unscheduled.size())return;
  if(assigned.size()+skipped.size()==jobs.size()){
   for(var j:jobs)if(assigned.containsKey(j.id)&&j.predecessor!=null&&jobs.stream().anyMatch(p->p.id.equals(j.predecessor))&&!assigned.containsKey(j.predecessor))return;
   var solution=evaluate(assigned);if(solution!=null){var missing=jobs.stream().filter(j->skipped.contains(j.id)).map(j->new Unscheduled(j.id,j.title,domains.get(j.id).isEmpty()?"Aucun créneau compatible avec la présence, la compétence, le bâtiment et la fenêtre autorisée":"Conflit de capacité, de dépendance ou de transit dans cette proposition",j.employeeId)).toList();solution=new Solution(solution.assignments,solution.transits,solution.transitMinutes,solution.changed,solution.deviationMinutes,missing,solution.delays,solution.delayMinutes);best.add(solution);best.sort(ranking());if(best.size()>limit)best.removeLast();}return;
  }
  String next=null;List<Move> choices=null;
  for(var job:jobs){if(assigned.containsKey(job.id)||skipped.contains(job.id))continue;var valid=domains.get(job.id).stream().filter(m->!skipped.contains(job.predecessor)&&compatible(job,m,assigned)).toList();if(valid.isEmpty()&&!(partial&&canDefer(job)))return;if(choices==null || valid.size()<choices.size()){next=job.id;choices=valid;}}
  for(var choice:choices){assigned.put(next,choice);search(assigned,skipped);assigned.remove(next);if(System.nanoTime()>deadline || visited>nodeLimit)return;}
  final String selected=next;if(partial&&jobs.stream().anyMatch(j->j.id.equals(selected)&&canDefer(j))){skipped.add(next);search(assigned,skipped);skipped.remove(next);}
 }
 private boolean compatible(Job job,Move m,Map<String,Move> assigned){
  for(var other:assigned.values()){if(other.employeeId==m.employeeId && other.start.isBefore(m.end)&&m.start.isBefore(other.end))return false;}
  if(job.predecessor!=null && assigned.containsKey(job.predecessor)&&assigned.get(job.predecessor).end.isAfter(m.start))return false;
  for(var child:jobs)if(job.id.equals(child.predecessor)&&assigned.containsKey(child.id)&&m.end.isAfter(assigned.get(child.id).start))return false;
  // Transit feasibility is checked on the complete chronological sequence: inserting a job may change its neighbours.
  return true;
 }
 private Solution evaluate(Map<String,Move> assigned){
  var transits=new ArrayList<Transit>();long total=0;int changed=0;long deviation=0;
  for(var p:people){var chain=assigned.values().stream().filter(m->m.employeeId==p.id).sorted(Comparator.comparing(Move::start)).toList();if(chain.stream().allMatch(m->m.id.startsWith("anchor-")))continue;Move previous=null;
   for(var m:chain){if(m.id.startsWith("anchor-")&&previous==null){previous=m;continue;}var shift=p.shifts.stream().filter(s->!s.start.isAfter(m.start)&&s.end.isAfter(m.start)).max(Comparator.comparing(Slot::start)).orElse(null);Long from=previous==null?p.initialBuilding:previous.to;if(shift!=null && (previous==null || previous.end.toLocalDate().isBefore(shift.start.toLocalDate())))from=shift.building;int minutes=travel(from,m.from);if(minutes<0)return null;var departure=m.start.minusMinutes(minutes);if(minutes>0 && notBefore!=null && departure.isBefore(notBefore)&&m.start.isAfter(notBefore))return null;if(previous!=null && departure.isBefore(previous.end))return null;if(minutes>0 && !covers(p,departure,m.start))return null;if(minutes>0){transits.add(new Transit(p.id,from,m.from,departure,m.start,minutes));total+=minutes;}int internal=travel(m.from,m.to);if(internal<0 || Duration.between(m.start,m.end).toMinutes()<internal)return null;if(internal>0&&!m.id.startsWith("anchor-")){total+=internal;transits.add(new Transit(p.id,m.from,m.to,null,null,internal));}previous=m;}
  }
  for(var m:assigned.values()){if(m.id.startsWith("anchor-"))continue;if(!Objects.equals(m.previousEmployeeId,m.employeeId)||!m.start.equals(m.previousStart))changed++;deviation+=Math.abs(Duration.between(m.previousStart,m.start).toMinutes());}
  var delays=assigned.values().stream().filter(m->!m.id.startsWith("anchor-")).map(m->{var job=jobs.stream().filter(j->j.id.equals(m.id)).findFirst().orElseThrow();return new Delay(m.id,m.title,Math.max(0,Duration.between(job.due,m.end).toMinutes()));}).filter(d->d.minutes>0).toList();
  return new Solution(assigned.values().stream().filter(m->!m.id.startsWith("anchor-")).sorted(Comparator.comparing(Move::start).thenComparing(Move::id)).toList(),transits,total,changed,deviation,List.of(),delays,delays.stream().mapToLong(Delay::minutes).sum());
 }
}
