package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.engine.executing.PickingPlanService.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

/** Bay-level, open picking tour. All graph and search state is local to a request. */
@ApplicationScoped
public class StorePickingRoutePlanner {
    public record Point(double x, double y) {}
    public static class Geometry {
        public List<List<Point>> segments = new ArrayList<>();
        public Double distanceMeters;
        public Double originalDistanceMeters;
        public int disconnected;
        public int unlocated;
    }
    public record Result(List<RouteGroupDTO> route, Geometry geometry) {}
    private record Edge(String id, double cost) {}
    private record QueueEntry(String id, double cost) {}
    private record Tree(Map<String, Double> distances, Map<String, String> previous) {}
    private record Bay(String aisle, String unit, String node, Point point, Point shelf) {}

    public Result plan(Building building, List<RouteStepDTO> steps, String startNode) {
        List<Aisle> aisles = Aisle.listByBuilding(building);
        List<PlanNode> nodes = PlanNode.listByBuilding(building);
        if (nodes.isEmpty()) return null; // Legacy buildings have no geometric plan.
        List<ShelfLocation> locations = building.locations.stream()
                .map(l -> org.hibernate.Hibernate.unproxy(l, ShelfLocation.class)).toList();
        return plan(nodes, aisles, locations, steps, startNode);
    }

    // Separate data loading from the deterministic planner for focused tests.
    Result plan(List<PlanNode> nodes, List<Aisle> aisles, List<? extends ShelfLocation> locations,
                List<RouteStepDTO> steps, String requestedStart) {
        Map<String, List<Edge>> graph = new LinkedHashMap<>();
        Map<String, Point> points = new HashMap<>();
        Map<String, Bay> bays = new LinkedHashMap<>();
        for (Aisle aisle : aisles.stream().sorted(Comparator.comparing(a -> a.code)).toList()) {
            if (aisle.headNode == null || aisle.tailNode == null) continue;
            List<Point> samples = samples(aisle);
            if (samples.isEmpty()) continue;
            List<String> ids = new ArrayList<>();
            for (int i=0; i<samples.size(); i++) {
                String id = i == 0 ? "node:"+aisle.headNode.code : i == samples.size()-1 ? "node:"+aisle.tailNode.code : aisle.code+":"+i;
                ids.add(id); points.put(id, samples.get(i)); graph.computeIfAbsent(id, k -> new ArrayList<>());
                if (i>0) {
                    double weight = aisle.lengthMeters != null && aisle.lengthMeters > 0
                            ? aisle.lengthMeters/(samples.size()-1) : length(samples)/(samples.size()-1);
                    graph.get(ids.get(i-1)).add(new Edge(id, weight));
                    graph.get(id).add(new Edge(ids.get(i-1), weight));
                }
            }
            Map<String,List<ShelfLocation>> units = new TreeMap<>(StorePickingRoutePlanner::compareUnits);
            locations.stream().filter(l -> Objects.equals(l.aisle, aisle.code))
                    .sorted(Comparator.comparing(l -> l.id == null ? Long.MAX_VALUE : l.id))
                    .forEach(l -> units.computeIfAbsent(l.unit == null ? "?" : l.unit, k -> new ArrayList<>()).add(l));
            int unitIndex=0, stations=(units.size()+1)/2;
            for (var entry : units.entrySet()) {
                final int index=unitIndex++;
                double fraction=entry.getValue().stream().filter(l -> l.aislePosition != null)
                        .map(l -> l.aislePosition).findFirst().orElse((index/2+0.5)/stations);
                fraction=Math.max(0, Math.min(1, fraction));
                int sample=(int)Math.round(fraction*(samples.size()-1));
                Point p=samples.get(sample);
                Point before=samples.get(Math.max(0,sample-1)), after=samples.get(Math.min(samples.size()-1,sample+1));
                double angle=Math.atan2(after.y-before.y, after.x-before.x), side=index%2==0 ? 1 : -1;
                Point shelf=new Point(p.x-side*20*Math.sin(angle),p.y+side*20*Math.cos(angle));
                bays.put(key(aisle.code,entry.getKey()),new Bay(aisle.code,entry.getKey(),ids.get(sample),p,shelf));
            }
        }
        // Buffer destinations are explicit access nodes rather than invented shelf coordinates.
        for(var step:steps)if(step.planNodeCode!=null){
            String id="node:"+step.planNodeCode;
            if(graph.containsKey(id))bays.put(key(step.aisle,step.unit),new Bay(step.aisle,step.unit,id,points.get(id),points.get(id)));
        }
        String start;
        if (requestedStart != null && !requestedStart.isBlank()) {
            start="node:"+requestedStart;
            if (!graph.containsKey(start)) throw new IllegalArgumentException("Unknown picking start node: "+requestedStart);
        } else {
            start=nodes.stream().filter(n -> n.nodeType==PlanNodeType.ENTRANCE && graph.containsKey("node:"+n.code))
                    .sorted(Comparator.comparing(n -> n.code)).map(n -> "node:"+n.code).findFirst().orElse(null);
        }
        Geometry geometry=new Geometry();
        Map<String,List<RouteStepDTO>> picks=new LinkedHashMap<>();
        List<RouteStepDTO> missing=new ArrayList<>();
        // Stable results regardless of ORM Set iteration order.
        steps.stream().sorted(Comparator.comparing((RouteStepDTO s) -> s.aisle,Comparator.nullsLast(String::compareTo))
                        .thenComparing(s -> s.unit,Comparator.nullsLast(StorePickingRoutePlanner::compareUnits))
                        .thenComparing(s -> s.sourceOrderId,Comparator.nullsLast(Long::compareTo))
                        .thenComparing(s -> s.pickedItemId,Comparator.nullsLast(Long::compareTo)))
                .forEach(step -> {
                    Bay bay=bays.get(key(step.aisle,step.unit));
                    if (bay==null) { missing.add(step); return; }
                    step.mapPoint=bay.point; step.shelfPoint=bay.shelf;
                    picks.computeIfAbsent(key(step.aisle,step.unit), k -> new ArrayList<>()).add(step);
                });
        java.util.function.ToIntFunction<Bay> priority=bay->picks.get(key(bay.aisle,bay.unit)).stream().mapToInt(step->step.priority).max().orElse(0);
        picks.values().forEach(lines->lines.sort(Comparator.comparingInt((RouteStepDTO step)->step.priority).reversed()));
        List<Bay> targets=picks.keySet().stream().map(bays::get).sorted(Comparator.comparingInt(priority).reversed()).toList();
        // Never manufacture an arbitrary entrance or a zero-distance successful tour.
        if (start==null && !targets.isEmpty()) throw new IllegalStateException("The store plan needs an entrance connected to the aisles.");
        Search search=new Search(graph,points);
        // Try each possible first bay from the real entrance. A single greedy
        // seed can get stuck in a poor tour even after 2-opt.
        double original=search.cost(start, targets);
        List<Bay> ordered=new ArrayList<>(targets);
        double bestCost=original;
        for (Bay first : targets) {
            if(priority.applyAsInt(first)!=priority.applyAsInt(targets.getFirst()))continue;
            List<Bay> remaining=new ArrayList<>(targets), candidate=new ArrayList<>();
            candidate.add(first); remaining.remove(first);
            String cursor=first.node;
            while (!remaining.isEmpty()) {
                final String from=cursor;
                Bay next=remaining.stream().min(Comparator.comparingInt(priority).reversed().thenComparingDouble(b -> search.distance(from,b.node))).orElseThrow();
                candidate.add(next); remaining.remove(next); cursor=next.node;
            }
            double candidateCost=search.cost(start,candidate);
            if (candidateCost<bestCost) { ordered=candidate; bestCost=candidateCost; }
        }
        for (int pass=0;pass<50;pass++) {
            boolean improved=false;
            for (int i=0;i<ordered.size()-1 && !improved;i++) {
                for (int j=i+1;j<ordered.size();j++) {
                    if(priority.applyAsInt(ordered.get(i))!=priority.applyAsInt(ordered.get(j)))continue;
                    String before=i==0 ? start : ordered.get(i-1).node;
                    String after=j+1<ordered.size() ? ordered.get(j+1).node : null;
                    double old=search.distance(before,ordered.get(i).node)+(after==null ? 0 : search.distance(ordered.get(j).node,after));
                    double candidate=search.distance(before,ordered.get(j).node)+(after==null ? 0 : search.distance(ordered.get(i).node,after));
                    if (candidate+1e-6<old) { Collections.reverse(ordered.subList(i,j+1)); improved=true; break; }
                }
            }
            if (!improved) break;
        }
        List<RouteStepDTO> sorted=new ArrayList<>();
        String cursor=start;
        double total=0;
        for (Bay bay : ordered) {
            double distance=search.distance(cursor,bay.node);
            if (!Double.isFinite(distance)) geometry.disconnected++;
            else { total+=distance; geometry.segments.add(search.path(cursor,bay.node)); }
            sorted.addAll(picks.get(key(bay.aisle,bay.unit))); cursor=bay.node;
        }
        sorted.addAll(missing);
        geometry.unlocated=missing.size();
        geometry.distanceMeters=geometry.disconnected==0 && missing.isEmpty() ? total : null;
        geometry.originalDistanceMeters=Double.isFinite(original) ? original : null;
        List<RouteGroupDTO> route=new ArrayList<>();
        int number=1;
        for (RouteStepDTO step : sorted) {
            step.stepNumber=number++;
            RouteGroupDTO group=route.isEmpty() ? null : route.get(route.size()-1);
            if (group==null || !Objects.equals(group.aisleCode,step.aisle)) {
                group=new RouteGroupDTO(); group.aisleCode=step.aisle; group.area=step.area;
                group.steps=new ArrayList<>(); route.add(group);
            }
            group.steps.add(step);
        }
        return new Result(route,geometry);
    }
    private static String key(String aisle,String unit) { return aisle+"\u0000"+unit; }
    private static int compareUnits(String a,String b) {
        var pattern=java.util.regex.Pattern.compile("^([^0-9]*)([0-9]+)$");
        var ma=pattern.matcher(a); var mb=pattern.matcher(b);
        if (ma.matches() && mb.matches() && ma.group(1).equals(mb.group(1))) {
            int value=new java.math.BigInteger(ma.group(2)).compareTo(new java.math.BigInteger(mb.group(2)));
            if (value!=0) return value;
        }
        return a.compareTo(b);
    }
    private static double length(List<Point> points) {
        double result=0;
        for (int i=1;i<points.size();i++) result+=Math.hypot(points.get(i).x-points.get(i-1).x,points.get(i).y-points.get(i-1).y);
        return result;
    }
    private static List<Point> samples(Aisle aisle) {
        List<Point> control=new ArrayList<>();
        control.add(new Point(aisle.headNode.x,aisle.headNode.y));
        if (aisle.waypoints!=null) aisle.waypoints.stream().sorted(Comparator.comparing(w -> w.position))
                .forEach(w -> control.add(new Point(w.x,w.y)));
        control.add(new Point(aisle.tailNode.x,aisle.tailNode.y));
        List<Point> line=control;
        if ("CURVED".equals(aisle.shapeType) && control.size()>=4) {
            line=new ArrayList<>();
            Point a=control.get(0), b=control.get(1), c=control.get(2), d=control.get(control.size()-1);
            for (int i=0;i<=200;i++) {
                double t=i/200.0,m=1-t;
                line.add(new Point(m*m*m*a.x+3*m*m*t*b.x+3*m*t*t*c.x+t*t*t*d.x,
                        m*m*m*a.y+3*m*m*t*b.y+3*m*t*t*c.y+t*t*t*d.y));
            }
        }
        double total=length(line);
        if (total<=0) return List.of();
        List<Point> result=new ArrayList<>();
        for (int i=0;i<=100;i++) {
            double target=total*i/100.0, walked=0;
            for (int j=1;j<line.size();j++) {
                Point a=line.get(j-1),b=line.get(j);
                double segment=Math.hypot(b.x-a.x,b.y-a.y);
                if (walked+segment>=target || j==line.size()-1) {
                    double t=segment==0 ? 0 : Math.max(0,Math.min(1,(target-walked)/segment));
                    result.add(new Point(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t)); break;
                }
                walked+=segment;
            }
        }
        return result;
    }
    private static class Search {
        final Map<String,List<Edge>> graph;
        final Map<String,Point> points;
        final Map<String,Tree> cache=new HashMap<>();
        Search(Map<String,List<Edge>> graph,Map<String,Point> points) { this.graph=graph; this.points=points; }
        Tree tree(String start) {
            return cache.computeIfAbsent(start, source -> {
                Map<String,Double> distances=new HashMap<>(); Map<String,String> previous=new HashMap<>();
                PriorityQueue<QueueEntry> queue=new PriorityQueue<>(Comparator.comparingDouble(QueueEntry::cost).thenComparing(QueueEntry::id));
                distances.put(source,0.0); queue.add(new QueueEntry(source,0));
                while (!queue.isEmpty()) {
                    QueueEntry current=queue.poll();
                    if (current.cost>distances.get(current.id)) continue;
                    for (Edge edge : graph.getOrDefault(current.id,List.of())) {
                        double candidate=current.cost+edge.cost;
                        if (candidate < distances.getOrDefault(edge.id,Double.POSITIVE_INFINITY)) {
                            distances.put(edge.id,candidate); previous.put(edge.id,current.id); queue.add(new QueueEntry(edge.id,candidate));
                        }
                    }
                }
                return new Tree(distances,previous);
            });
        }
        double distance(String from,String to) { return tree(from).distances.getOrDefault(to,Double.POSITIVE_INFINITY); }
        double cost(String start,List<Bay> route) { double cost=0; String previous=start; for (Bay bay : route) { cost+=distance(previous,bay.node); previous=bay.node; } return cost; }
        List<Point> path(String from,String to) { LinkedList<Point> path=new LinkedList<>(); String cursor=to; Tree tree=tree(from); while (cursor!=null) { path.addFirst(points.get(cursor)); if (cursor.equals(from)) break; cursor=tree.previous.get(cursor); } return path; }
    }
}
