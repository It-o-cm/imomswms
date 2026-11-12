package com.intermarche.backoffice.engine.fieldops;

import static org.junit.jupiter.api.Assertions.*;

import com.intermarche.backoffice.engine.fieldops.TourPlanning.Candidate;
import com.intermarche.backoffice.engine.fieldops.TourPlanning.Point;
import com.intermarche.backoffice.engine.fieldops.TourPlanning.Stop;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Grouping of carrier departures into vehicles and ordering of their delivery stops. */
class TourPlanningTest {
 static final Point STORE=new Point(45.7615,4.8560);
 static final LocalDateTime NOW=LocalDateTime.of(2026,10,29,14,0);

 /**
  * Builds a departure delivered at its own address.
  *
  * @param id the departure id, also naming its address
  * @param point the position of the address, or null
  * @param units the units it carries
  * @param kg the known weight it carries
  * @param deadline the deadline of its order, or null
  * @return the candidate
  */
 static Candidate c(long id,Point point,int units,String kg,LocalDateTime deadline){return new Candidate(id,"address-"+id,point,"6900"+(id%10),units,new BigDecimal(kg),deadline);}

 /**
  * Measures an open route from the store through stops in order.
  *
  * @param route the stops
  * @return the estimated distance
  */
 static double length(List<Stop> route){double total=0;Point at=STORE;for(var s:route){total+=TourPlanning.km(at,s.point());at=s.point();}return total;}

 /**
  * Lists the ids of the first departure of each stop.
  *
  * @param route the stops
  * @return the ids in order
  */
 static List<Long> ids(List<Stop> route){return route.stream().map(s->s.departures().getFirst().id()).toList();}

 /**
  * Distances are symmetric haversine distances lengthened by the road factor.
  */
 @Test void distancesAreRoadEstimatesOfTheStraightLine(){
  Point bellecour=new Point(45.7579,4.8330);
  double km=TourPlanning.km(STORE,bellecour);
  assertEquals(km,TourPlanning.km(bellecour,STORE),1e-9);
  assertEquals(1.84*TourPlanning.ROAD_FACTOR,km,0.05);
  assertEquals(0,TourPlanning.km(STORE,STORE),1e-12);}

 /**
  * Departures sharing an address make one stop placed where the address first appears, located by any of them.
  */
 @Test void departuresAtTheSameAddressShareOneStop(){
  Point p=new Point(45.75,4.84);
  var a=new Candidate(1,"x",null,"69002",1,BigDecimal.ONE,null);var b=new Candidate(2,"y",p,"69003",1,BigDecimal.ONE,NOW.plusHours(5));var a2=new Candidate(3,"x",p,"69002",2,BigDecimal.ONE,NOW.plusHours(2));
  var stops=TourPlanning.stops(List.of(a,b,a2));
  assertEquals(List.of("x","y"),stops.stream().map(Stop::addressKey).toList());
  assertEquals(List.of(1L,3L),stops.getFirst().departures().stream().map(Candidate::id).toList());
  assertEquals(p,stops.getFirst().point());
  assertEquals(NOW.plusHours(2),stops.getFirst().deadline());
  assertNull(TourPlanning.stops(List.of(a)).getFirst().deadline());}

 /**
  * The nearest stop comes first and stops lined up away from the store are visited outwards.
  */
 @Test void nearestStopsComeFirstAlongALine(){
  Candidate far=c(1,new Point(45.7615,4.90),1,"0",null),near=c(2,new Point(45.7615,4.87),1,"0",null),middle=c(3,new Point(45.7615,4.885),1,"0",null);
  assertEquals(List.of(2L,3L,1L),ids(TourPlanning.order(STORE,TourPlanning.stops(List.of(far,near,middle)))));}

 /**
  * 2-opt removes a crossing: three corners of a square end up visited side by side.
  */
 @Test void exchangesRemoveACrossing(){
  var s1=TourPlanning.stops(List.of(c(1,new Point(45.7615,4.8660),1,"0",null))).getFirst();
  var s2=TourPlanning.stops(List.of(c(2,new Point(45.7715,4.8660),1,"0",null))).getFirst();
  var s3=TourPlanning.stops(List.of(c(3,new Point(45.7715,4.8560),1,"0",null))).getFirst();
  var route=new ArrayList<>(List.of(s2,s1,s3));double before=length(route);
  TourPlanning.improve(STORE,route);
  assertTrue(length(route)<before-0.1);
  assertTrue(ids(route).equals(List.of(1L,2L,3L))||ids(route).equals(List.of(3L,2L,1L)),ids(route).toString());
  var single=new ArrayList<>(List.of(s1));TourPlanning.improve(STORE,single);assertEquals(List.of(1L),ids(single));}

 /**
  * The optimised order is never longer than the nearest-neighbour order on a scattered set of stops.
  */
 @Test void optimisedRouteIsNoLongerThanTheGreedyOne(){
  var random=new java.util.Random(42);var all=new ArrayList<Candidate>();
  for(int i=1;i<=30;i++)all.add(c(i,new Point(45.70+random.nextDouble()*0.12,4.78+random.nextDouble()*0.16),1,"0",null));
  var stops=TourPlanning.stops(all);var ordered=TourPlanning.order(STORE,stops);
  var greedy=new ArrayList<Stop>();var left=new ArrayList<>(stops);Point at=STORE;
  while(!left.isEmpty()){Point from=at;var next=left.stream().min(java.util.Comparator.comparingDouble(s->TourPlanning.km(from,s.point()))).orElseThrow();greedy.add(next);left.remove(next);at=next.point();}
  assertEquals(30,ordered.size());
  assertTrue(length(ordered)<=length(greedy)+1e-9);}

 /**
  * Stops without position follow the located ones by postal code; without store position all go by postal code.
  */
 @Test void unlocatedStopsComeLastByPostalCode(){
  var located=c(1,new Point(45.76,4.86),1,"0",null);
  Candidate lost9=new Candidate(2,"b",null,"69009",1,BigDecimal.ZERO,null),lost3=new Candidate(3,"c",null,"69003",1,BigDecimal.ZERO,null),noCode=new Candidate(4,"d",null,null,1,BigDecimal.ZERO,null);
  assertEquals(List.of(1L,4L,3L,2L),ids(TourPlanning.order(STORE,TourPlanning.stops(List.of(lost9,located,lost3,noCode)))));
  Candidate a=new Candidate(5,"e",new Point(45.70,4.80),"69008",1,BigDecimal.ZERO,null),b=new Candidate(6,"f",new Point(45.80,4.90),"69001",1,BigDecimal.ZERO,null);
  assertEquals(List.of(6L,5L),ids(TourPlanning.order(null,TourPlanning.stops(List.of(a,b)))));}

 /**
  * Arrivals add the driving time and the time spent at the previous stops; a stop reached after its deadline is late.
  */
 @Test void routeEstimatesArrivalsAndLateness(){
  Point p1=new Point(45.7615,4.8860),p2=new Point(45.7615,4.9160);
  double leg1=TourPlanning.km(STORE,p1),leg2=TourPlanning.km(p1,p2);
  long minutes1=Math.round(leg1/TourPlanning.SPEED_KMH*3600);
  Candidate first=c(1,p1,1,"0",NOW.plusHours(1)),second=c(2,p2,1,"0",NOW.plusMinutes(5)),lost=new Candidate(3,"lost",null,"69001",1,BigDecimal.ZERO,NOW),third=c(4,new Point(45.7615,4.9200),1,"0",null);
  var route=TourPlanning.route(STORE,TourPlanning.stops(List.of(first,second,lost,third)),NOW);
  var legs=route.legs();
  assertEquals(NOW.plusSeconds(minutes1),legs.get(0).arrival());assertFalse(legs.get(0).late());
  assertEquals(NOW.plusSeconds(Math.round((leg1+leg2)/TourPlanning.SPEED_KMH*3600+TourPlanning.STOP_MINUTES*60)),legs.get(1).arrival());assertTrue(legs.get(1).late());
  assertNull(legs.get(2).arrival());assertNull(legs.get(2).distanceKm());assertFalse(legs.get(2).late());assertEquals(3,legs.get(2).sequence());
  assertFalse(legs.get(3).late());assertEquals(TourPlanning.km(p2,new Point(45.7615,4.9200)),legs.get(3).distanceKm(),1e-9);
  assertEquals(1,route.unlocated());
  assertEquals(leg1+leg2+legs.get(3).distanceKm(),route.distanceKm(),1e-9);
  var blind=TourPlanning.route(null,TourPlanning.stops(List.of(first)),NOW);
  assertNull(blind.distanceKm());assertNull(blind.legs().getFirst().arrival());assertEquals(0,blind.unlocated());}

 /**
  * Measured road legs replace the estimates of the located stops; unlocated stops keep no distance.
  */
 @Test void measuredLegsReplaceTheEstimates(){
  Candidate first=c(1,new Point(45.77,4.86),1,"0",NOW.plusMinutes(10)),lost=new Candidate(2,"lost",null,"69001",1,BigDecimal.ZERO,null),second=c(3,new Point(45.75,4.84),1,"0",NOW.plusHours(2));
  var route=TourPlanning.route(STORE,TourPlanning.stops(List.of(first,lost,second)),NOW,new TourPlanning.Measured(List.of(3.2,5.5),List.of(900L,600L)));
  var legs=route.legs();
  assertEquals(3.2,legs.get(0).distanceKm());assertEquals(NOW.plusMinutes(15),legs.get(0).arrival());assertTrue(legs.get(0).late());
  assertNull(legs.get(1).distanceKm());
  assertEquals(5.5,legs.get(2).distanceKm());assertEquals(NOW.plusMinutes(15+TourPlanning.STOP_MINUTES+10),legs.get(2).arrival());assertFalse(legs.get(2).late());
  assertEquals(8.7,route.distanceKm(),1e-9);assertEquals(1,route.unlocated());}

 /**
  * Vehicles are filled within the units of the service, the most urgent departures first.
  */
 @Test void packingRespectsUnitsAndUrgency(){
  var today=c(1,new Point(45.77,4.86),2,"0",NOW.plusHours(3));var later=c(2,new Point(45.75,4.84),2,"0",NOW.plusHours(5));
  var tomorrow=c(3,new Point(45.76,4.88),2,"0",NOW.plusDays(1));var tomorrowSmall=c(4,new Point(45.75,4.83),1,"0",NOW.plusDays(1));var open=c(5,new Point(45.76,4.87),2,"0",null);
  var p=TourPlanning.pack(List.of(open,tomorrow,later,today,tomorrowSmall),8,null,STORE);
  assertEquals(2,p.tours().size());assertTrue(p.oversize().isEmpty());
  assertEquals(7,p.tours().getFirst().stream().mapToInt(Candidate::units).sum());
  assertTrue(p.tours().getFirst().stream().map(Candidate::id).toList().containsAll(List.of(1L,2L,3L,4L)));
  assertEquals(List.of(5L),p.tours().get(1).stream().map(Candidate::id).toList());
  assertEquals(1,TourPlanning.pack(List.of(open,tomorrow,later,today),null,null,STORE).tours().size());
  assertTrue(TourPlanning.pack(List.of(),8,null,STORE).tours().isEmpty());}

 /**
  * Within one deadline day, departures are taken in the order of their direction from the store, so that a vehicle
  * serves one area; departures without position come last.
  */
 @Test void packingGroupsByDirectionWithinADay(){
  Candidate east=c(1,new Point(45.7615,4.90),1,"0",NOW.plusHours(2)),north=c(2,new Point(45.80,4.8560),1,"0",NOW.plusHours(3)),west=c(3,new Point(45.7615,4.80),1,"0",NOW.plusHours(1)),lost=c(4,null,1,"0",NOW.plusHours(1));
  var p=TourPlanning.pack(List.of(west,lost,east,north),1,null,STORE);
  assertEquals(List.of(2L,1L,3L,4L),p.tours().stream().map(t->t.getFirst().id()).toList());}

 /**
  * The weight limit applies with the units limit; a departure too large alone is set aside.
  */
 @Test void packingRespectsWeightAndSetsOversizeAside(){
  Candidate heavy=c(1,null,1,"30",null),light=c(2,null,1,"15",null),light2=c(3,null,1,"10",null),bulky=c(4,null,9,"1",null),overweight=c(5,null,1,"41",null);
  var p=TourPlanning.pack(List.of(heavy,light,light2,bulky,overweight),8,new BigDecimal("40"),STORE);
  assertEquals(List.of(4L,5L),p.oversize().stream().map(Candidate::id).toList());
  assertEquals(List.of(List.of(1L,3L),List.of(2L)),p.tours().stream().map(t->t.stream().map(Candidate::id).toList()).toList());}

 /**
  * Both limits are checked, each optional.
  */
 @Test void fitsChecksEachLimit(){
  var one=c(1,null,2,"5",null);
  assertTrue(TourPlanning.fits(0,BigDecimal.ZERO,one,null,null));
  assertTrue(TourPlanning.fits(6,BigDecimal.ZERO,one,8,null));
  assertFalse(TourPlanning.fits(7,BigDecimal.ZERO,one,8,null));
  assertTrue(TourPlanning.fits(0,new BigDecimal("5"),one,null,new BigDecimal("10")));
  assertFalse(TourPlanning.fits(0,new BigDecimal("5.1"),one,null,new BigDecimal("10")));
  assertFalse(TourPlanning.fits(7,BigDecimal.ZERO,one,8,new BigDecimal("10")));}

 /**
  * Bearings are measured clockwise from north; an unknown point or store gives the last direction.
  */
 @Test void bearingIsClockwiseFromNorth(){
  assertEquals(0,TourPlanning.bearing(STORE,new Point(45.80,4.8560)),0.01);
  assertEquals(90,TourPlanning.bearing(STORE,new Point(45.7615,4.90)),0.1);
  assertEquals(180,TourPlanning.bearing(STORE,new Point(45.70,4.8560)),0.01);
  assertEquals(270,TourPlanning.bearing(STORE,new Point(45.7615,4.80)),0.1);
  assertEquals(360,TourPlanning.bearing(null,STORE));
  assertEquals(360,TourPlanning.bearing(STORE,null));}
}
