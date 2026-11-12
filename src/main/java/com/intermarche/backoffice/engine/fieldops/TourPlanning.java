package com.intermarche.backoffice.engine.fieldops;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Pure calculations behind carrier tours: grouping departures into vehicles within the capacity of a service, and
 * ordering the delivery stops of one vehicle.
 * <p>
 * Distances are straight-line (haversine) distances multiplied by {@link #ROAD_FACTOR} to approach the road distance;
 * travel times assume {@link #SPEED_KMH} on average plus {@link #STOP_MINUTES} at each stop. These are estimates, not
 * an itinerary computed on the road network.
 */
public final class TourPlanning {
 /** Ratio between the road distance and the straight-line distance, usual in urban areas. */
 static final double ROAD_FACTOR=1.3;
 /** Average speed of a delivery van in town, in km/h. */
 static final double SPEED_KMH=25;
 /** Time spent at each stop to hand the goods over, in minutes. */
 static final long STOP_MINUTES=4;
 /** Mean radius of the Earth, in km. */
 static final double EARTH_KM=6371.0088;
 /** Upper bound of improvement passes, far above what 50 stops need. */
 static final int MAX_PASSES=200;

 /**
  * Builds nothing: static calculations only.
  */
 private TourPlanning(){}

 /**
  * A GPS point.
  *
  * @param lat latitude in degrees
  * @param lon longitude in degrees
  */
 public record Point(double lat,double lon){}

 /**
  * A departure to place in a vehicle.
  *
  * @param id the departure id
  * @param addressKey the normalised consignee address: departures sharing it are delivered at the same stop
  * @param point the position of that address, or null when unknown
  * @param postalCode the postal code, used to place stops that could not be located
  * @param units the units it carries
  * @param weightKg the known weight it carries
  * @param deadline the delivery deadline of its order, or null
  */
 record Candidate(long id,String addressKey,Point point,String postalCode,int units,BigDecimal weightKg,LocalDateTime deadline){}

 /**
  * A delivery stop: the departures handed over at one address.
  *
  * @param addressKey the normalised address
  * @param point its position, or null when unknown
  * @param postalCode its postal code
  * @param departures the departures delivered there
  */
 record Stop(String addressKey,Point point,String postalCode,List<Candidate> departures){
  /**
   * Returns the earliest deadline of the departures of this stop.
   *
   * @return the deadline, or null when none has one
   */
  LocalDateTime deadline(){return departures.stream().map(Candidate::deadline).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);}
 }

 /**
  * One stop of a route with its estimates.
  *
  * @param sequence the position of the stop, from 1
  * @param stop the stop
  * @param distanceKm the estimated distance from the previous stop (or the store), or null when unknown
  * @param arrival the estimated arrival time, or null when unknown
  * @param late whether the estimated arrival is after the deadline of the stop
  */
 record Leg(int sequence,Stop stop,Double distanceKm,LocalDateTime arrival,boolean late){}

 /**
  * An ordered route.
  *
  * @param legs the stops in delivery order
  * @param distanceKm the estimated distance from the store to the last located stop, or null without store position
  * @param unlocated the number of stops without position
  */
 record Route(List<Leg> legs,Double distanceKm,int unlocated){}

 /**
  * Result of grouping departures into vehicles.
  *
  * @param tours the departures of each proposed vehicle, most urgent vehicle first
  * @param oversize the departures that alone exceed the capacity of the service
  */
 record Packing(List<List<Candidate>> tours,List<Candidate> oversize){}

 /**
  * Estimates the road distance between two points.
  *
  * @param a the first point
  * @param b the second point
  * @return the straight-line distance multiplied by {@link #ROAD_FACTOR}, in km
  */
 static double km(Point a,Point b){
  double dLat=Math.toRadians(b.lat()-a.lat()),dLon=Math.toRadians(b.lon()-a.lon());
  double h=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(Math.toRadians(a.lat()))*Math.cos(Math.toRadians(b.lat()))*Math.sin(dLon/2)*Math.sin(dLon/2);
  return 2*EARTH_KM*Math.asin(Math.min(1,Math.sqrt(h)))*ROAD_FACTOR;
 }

 /**
  * Groups departures into stops, one per address, keeping the order in which addresses first appear.
  *
  * @param departures the departures
  * @return the stops
  */
 static List<Stop> stops(List<Candidate> departures){
  var byAddress=new LinkedHashMap<String,List<Candidate>>();for(var d:departures)byAddress.computeIfAbsent(d.addressKey(),k->new ArrayList<>()).add(d);
  return byAddress.values().stream().map(group->{var first=group.getFirst();Point point=group.stream().map(Candidate::point).filter(Objects::nonNull).findFirst().orElse(null);return new Stop(first.addressKey(),point,first.postalCode(),List.copyOf(group));}).toList();
 }

 /**
  * Orders the stops of one vehicle leaving the store: nearest unvisited stop first, then 2-opt exchanges while they
  * shorten the route. Stops without position follow, by postal code. Without store position, every stop is ordered
  * by postal code.
  *
  * @param origin the position of the store, or null
  * @param stops the stops
  * @return the stops in delivery order
  */
 static List<Stop> order(Point origin,List<Stop> stops){
  Comparator<Stop> byPostalCode=Comparator.comparing((Stop s)->s.postalCode()==null?"":s.postalCode()).thenComparing(s->s.departures().getFirst().id());
  var located=new ArrayList<Stop>();var unlocated=new ArrayList<Stop>();
  for(var s:stops)(origin!=null&&s.point()!=null?located:unlocated).add(s);
  var route=new ArrayList<Stop>();Point at=origin;
  while(!located.isEmpty()){Point from=at;var next=located.stream().min(Comparator.comparingDouble((Stop s)->km(from,s.point())).thenComparing(s->s.departures().getFirst().id())).orElseThrow();route.add(next);located.remove(next);at=next.point();}
  improve(origin,route);
  unlocated.sort(byPostalCode);route.addAll(unlocated);return route;
 }

 /**
  * Applies 2-opt exchanges to an open route starting at the store: reverses a section whenever that shortens the
  * route, until no reversal helps.
  *
  * @param origin the store
  * @param route the located stops, reordered in place
  */
 static void improve(Point origin,List<Stop> route){
  int n=route.size();
  for(int pass=0;pass<MAX_PASSES;pass++){boolean better=false;
   for(int i=0;i<n-1;i++)for(int k=i+1;k<n;k++){
    Point before=i==0?origin:route.get(i-1).point(),first=route.get(i).point(),last=route.get(k).point();
    double removed=km(before,first),added=km(before,last);
    if(k<n-1){Point after=route.get(k+1).point();removed+=km(last,after);added+=km(first,after);}
    if(added<removed-1e-9){Collections.reverse(route.subList(i,k+1));better=true;}}
   if(!better)return;}
 }

 /**
  * Estimates distances and arrival times along stops already in delivery order.
  *
  * @param origin the store, or null
  * @param ordered the stops in delivery order
  * @param departure the planned departure from the store
  * @return the route
  */
 static Route route(Point origin,List<Stop> ordered,LocalDateTime departure){return route(origin,ordered,departure,null);}

 /**
  * Road distances and driving times measured for the located stops of a route, in delivery order.
  *
  * @param km the distance of each leg, from the store to the first located stop then between located stops
  * @param seconds the driving time of each leg
  */
 record Measured(List<Double> km,List<Long> seconds){}

 /**
  * Estimates or, when measured legs are given, computes distances and arrival times along stops already in delivery
  * order.
  *
  * @param origin the store, or null
  * @param ordered the stops in delivery order
  * @param departure the planned departure from the store
  * @param measured the road legs of the located stops, or null to estimate them
  * @return the route
  */
 static Route route(Point origin,List<Stop> ordered,LocalDateTime departure,Measured measured){
  var legs=new ArrayList<Leg>();Point at=origin;double total=0,minutes=0;int unlocated=0;int sequence=0;int located=0;
  for(var s:ordered){sequence++;
   if(at==null||s.point()==null){if(s.point()==null)unlocated++;legs.add(new Leg(sequence,s,null,null,false));continue;}
   double leg=measured==null?km(at,s.point()):measured.km().get(located);total+=leg;minutes+=measured==null?leg/SPEED_KMH*60:measured.seconds().get(located)/60.0;located++;
   LocalDateTime arrival=departure.plusSeconds(Math.round(minutes*60));var deadline=s.deadline();
   legs.add(new Leg(sequence,s,leg,arrival,deadline!=null&&arrival.isAfter(deadline)));
   minutes+=STOP_MINUTES;at=s.point();}
  return new Route(List.copyOf(legs),origin==null?null:total,unlocated);
 }

 /**
  * Groups departures into vehicles of one service: the most urgent first (deadline day, then direction from the
  * store so that a vehicle serves one area, then id), each placed in the first vehicle that still has room.
  *
  * @param departures the departures
  * @param maxUnits the units one vehicle takes, or null when unlimited
  * @param maxWeightKg the known weight one vehicle takes, or null when unlimited
  * @param origin the store, or null
  * @return the vehicles and the departures too large for any
  */
 static Packing pack(List<Candidate> departures,Integer maxUnits,BigDecimal maxWeightKg,Point origin){
  Comparator<Candidate> urgency=Comparator.comparing((Candidate c)->c.deadline()==null?null:c.deadline().toLocalDate(),Comparator.nullsLast(Comparator.naturalOrder()))
   .thenComparingDouble(c->bearing(origin,c.point())).thenComparingLong(Candidate::id);
  var sorted=new ArrayList<>(departures);sorted.sort(urgency);
  var tours=new ArrayList<List<Candidate>>();var units=new ArrayList<Integer>();var weights=new ArrayList<BigDecimal>();var oversize=new ArrayList<Candidate>();
  for(var c:sorted){
   if(!fits(0,BigDecimal.ZERO,c,maxUnits,maxWeightKg)){oversize.add(c);continue;}
   int slot=-1;for(int i=0;i<tours.size()&&slot<0;i++)if(fits(units.get(i),weights.get(i),c,maxUnits,maxWeightKg))slot=i;
   if(slot<0){tours.add(new ArrayList<>());units.add(0);weights.add(BigDecimal.ZERO);slot=tours.size()-1;}
   tours.get(slot).add(c);units.set(slot,units.get(slot)+c.units());weights.set(slot,weights.get(slot).add(c.weightKg()));}
  return new Packing(tours.stream().map(List::copyOf).toList(),List.copyOf(oversize));
 }

 /**
  * Tells whether a departure still fits in a vehicle.
  *
  * @param units the units already in the vehicle
  * @param weightKg the known weight already in the vehicle
  * @param c the departure
  * @param maxUnits the units limit, or null
  * @param maxWeightKg the weight limit, or null
  * @return true when both limits are respected
  */
 static boolean fits(int units,BigDecimal weightKg,Candidate c,Integer maxUnits,BigDecimal maxWeightKg){
  return (maxUnits==null||units+c.units()<=maxUnits)&&(maxWeightKg==null||weightKg.add(c.weightKg()).compareTo(maxWeightKg)<=0);
 }

 /**
  * Measures the direction of a point seen from the store, clockwise from north.
  *
  * @param origin the store, or null
  * @param point the point, or null
  * @return the bearing in degrees within [0, 360), or 360 when either point is unknown
  */
 static double bearing(Point origin,Point point){
  if(origin==null||point==null)return 360;
  double lat1=Math.toRadians(origin.lat()),lat2=Math.toRadians(point.lat()),dLon=Math.toRadians(point.lon()-origin.lon());
  double degrees=Math.toDegrees(Math.atan2(Math.sin(dLon)*Math.cos(lat2),Math.cos(lat1)*Math.sin(lat2)-Math.sin(lat1)*Math.cos(lat2)*Math.cos(dLon)));
  return (degrees+360)%360;
 }
}
