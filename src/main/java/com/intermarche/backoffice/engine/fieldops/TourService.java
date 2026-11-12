package com.intermarche.backoffice.engine.fieldops;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;

import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.shipping.*;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import com.intermarche.backoffice.domain.GPSPosition;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.hibernate.Hibernate;

/**
 * Carrier tours: a collection at one dock gathers the departures of several orders, is checked unit by unit, sealed
 * with its transport documents frozen, handed over in one confirmation, then followed until its empty totes come back.
 */
@ApplicationScoped @Transactional
public class TourService {
 @Inject FieldOperationsService field;@Inject StockCommands receipts;@Inject DepartureService departures;@Inject TourDocuments documents;@Inject AddressGeocoder geocoder;@Inject EntityManager em;@Inject GoogleDirections directions;
 static final Pattern CODE=Pattern.compile("[A-Z0-9][A-Z0-9_-]{1,29}"),SEAL=Pattern.compile("[A-Z0-9-]{4,30}"),CUTOFF=Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");
 static final Set<String> ACTIVE=Set.of(DepartureTour.OPEN,DepartureTour.SEALED),STATES=Set.of(DepartureTour.OPEN,DepartureTour.SEALED,DepartureTour.DEPARTED,DepartureTour.CANCELLED);
 static final int MAX_DEPARTURES=50,MAX_TOTES=200;

 /** Command body shared by the tour endpoints; each endpoint reads the fields it needs. */
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="TourInput") public static class Input {
  public Long storeId,carrierId,serviceId,dockId,tourId,departureId,expectedRevision;
  public String requestKey,code,name,label,cutoff,vehicleReference,sealNumber,scannedDock,scannedSeal,reason,engine;
  public Integer maxUnits;public BigDecimal maxWeightKg;public Boolean active;public LocalDateTime plannedDeparture;
  public List<Long> departureIds;public List<String> toteCodes;
 }

 /**
  * Opens a command: validates its key, locks the store and returns the recorded result of a replayed request.
  *
  * @param i the command
  * @return the locked store and the fingerprint, or the previous result
  */
 private Command command(Input i){if(i==null)throw new BadRequestException("Demande requise");FieldOperationsService.key(i.requestKey);Store s=field.store(i.storeId,true);String fp=field.hash(i);var previous=receipts.previous(s,i.requestKey,fp);return new Command(s,fp,previous==null?null:receipts.value(previous));}
 /**
  * An opened command.
  *
  * @param store the locked store
  * @param fingerprint the request fingerprint
  * @param replay the previous result of the same request, or null
  */
 record Command(Store store,String fingerprint,Object replay){}

 /**
  * Normalises and validates a business code.
  *
  * @param value the code typed or scanned
  * @return the upper-case code
  */
 static String code(String value){String c=value==null?"":value.trim().toUpperCase(Locale.ROOT);if(!CODE.matcher(c).matches())throw new BadRequestException("Code requis : lettres, chiffres, tiret ou souligné, 2 à 30 caractères");return c;}
 /**
  * Checks the revision a client saw before changing an object.
  *
  * @param current the current revision
  * @param expected the revision sent by the client
  * @param what the object, with its article, for the message
  */
 static void revision(long current,Long expected,String what){if(expected==null||current!=expected)throw new OperatorFailure(409,"STALE_REVISION",what+" a changé : actualisez avant de confirmer.");}

 // ----- Setup: carriers, services and docks -----

 /**
  * Creates or updates a carrier of the store.
  *
  * @param i code, name, active flag and, for an update, the carrier and its revision
  * @return the store setup
  */
 public Object saveCarrier(Input i){var c=command(i);if(c.replay()!=null)return c.replay();field.requireManager();Store s=c.store();String code=code(i.code);FieldOperationsService.text(i.name,120);
  Carrier carrier=i.carrierId==null?new Carrier():Carrier.find("id=?1 and store=?2",i.carrierId,s).firstResult();if(carrier==null)throw new NotFoundException();
  if(i.carrierId!=null){revision(carrier.revisionNumber,i.expectedRevision,"Ce transporteur");carrier.revisionNumber++;}
  if(Carrier.count("store=?1 and code=?2 and id<>?3",s,code,carrier.id==null?-1L:carrier.id)>0)throw new OperatorFailure(409,"CODE_TAKEN","Le code "+code+" est déjà utilisé par un autre transporteur.");
  carrier.store=s;carrier.code=code;carrier.name=i.name.trim();carrier.active=i.active==null||i.active;carrier.persist();
  return receipts.record(s,i.requestKey,c.fingerprint(),"SAVE_CARRIER",setupView(s));}

 /**
  * Creates or updates a service of a carrier: its cut-off and the capacity of one collection.
  *
  * @param i carrier, code, label, cut-off, optional capacities and, for an update, the service and its revision
  * @return the store setup
  */
 public Object saveService(Input i){var c=command(i);if(c.replay()!=null)return c.replay();field.requireManager();Store s=c.store();String code=code(i.code);FieldOperationsService.text(i.label,120);
  Carrier carrier=Carrier.find("id=?1 and store=?2",i.carrierId,s).firstResult();if(carrier==null)throw new NotFoundException();
  if(i.cutoff==null||!CUTOFF.matcher(i.cutoff).matches())throw new BadRequestException("Heure limite au format HH:mm");
  if(i.maxUnits!=null&&(i.maxUnits<1||i.maxUnits>999))throw new BadRequestException("Capacité en supports entre 1 et 999");
  if(i.maxWeightKg!=null&&(i.maxWeightKg.signum()<=0||i.maxWeightKg.compareTo(new BigDecimal("100000"))>0||i.maxWeightKg.stripTrailingZeros().scale()>3))throw new BadRequestException("Poids maximal positif, 100 000 kg au plus, 3 décimales");
  CarrierServiceLevel service=i.serviceId==null?new CarrierServiceLevel():CarrierServiceLevel.find("id=?1 and carrier=?2",i.serviceId,carrier).firstResult();if(service==null)throw new NotFoundException();
  if(i.serviceId!=null){revision(service.revisionNumber,i.expectedRevision,"Ce service");service.revisionNumber++;}
  if(CarrierServiceLevel.count("carrier=?1 and code=?2 and id<>?3",carrier,code,service.id==null?-1L:service.id)>0)throw new OperatorFailure(409,"CODE_TAKEN","Le code "+code+" est déjà utilisé par un autre service de "+carrier.name+".");
  service.carrier=carrier;service.code=code;service.label=i.label.trim();service.cutoff=i.cutoff;service.maxUnits=i.maxUnits;service.maxWeightKg=i.maxWeightKg;service.active=i.active==null||i.active;service.persist();
  return receipts.record(s,i.requestKey,c.fingerprint(),"SAVE_CARRIER_SERVICE",setupView(s));}

 /**
  * Creates or updates a loading dock of the store.
  *
  * @param i code, label, active flag and, for an update, the dock and its revision
  * @return the store setup
  */
 public Object saveDock(Input i){var c=command(i);if(c.replay()!=null)return c.replay();field.requireManager();Store s=c.store();String code=code(i.code);FieldOperationsService.text(i.label,120);
  ShippingDock dock=i.dockId==null?new ShippingDock():ShippingDock.find("id=?1 and store=?2",i.dockId,s).firstResult();if(dock==null)throw new NotFoundException();
  if(i.dockId!=null){revision(dock.revisionNumber,i.expectedRevision,"Ce quai");dock.revisionNumber++;}
  if(ShippingDock.count("store=?1 and code=?2 and id<>?3",s,code,dock.id==null?-1L:dock.id)>0)throw new OperatorFailure(409,"CODE_TAKEN","Le code "+code+" est déjà utilisé par un autre quai.");
  if(Boolean.FALSE.equals(i.active)&&dock.id!=null&&DepartureTour.count("dock=?1 and state in ?2",dock,ACTIVE)>0)throw new OperatorFailure(409,"DOCK_BUSY","Une tournée est en cours à ce quai : terminez-la avant de le désactiver.");
  dock.store=s;dock.code=code;dock.label=i.label.trim();dock.active=i.active==null||i.active;dock.persist();
  return receipts.record(s,i.requestKey,c.fingerprint(),"SAVE_DOCK",setupView(s));}

 /**
  * Returns the carriers, their services and the docks of the store.
  *
  * @param storeId the store
  * @return the setup
  */
 public Object setup(Long storeId){return setupView(field.store(storeId,false));}

 /**
  * Builds the setup view.
  *
  * @param s the store
  * @return carriers with services, docks and whether the caller may change them
  */
 Map<String,Object> setupView(Store s){var v=new LinkedHashMap<String,Object>();
  var services=CarrierServiceLevel.<CarrierServiceLevel>list("carrier.store=?1 order by code",s);
  v.put("carriers",Carrier.<Carrier>list("store=?1 order by name",s).stream().map(c->{var row=carrierView(c);row.put("revision",c.revisionNumber);row.put("services",services.stream().filter(x->id(x.carrier).equals(c.id)).map(this::serviceView).toList());return row;}).toList());
  v.put("docks",ShippingDock.<ShippingDock>list("store=?1 order by code",s).stream().map(d->{var row=dockView(d);row.put("revision",d.revisionNumber);row.put("busyWith",DepartureTour.<DepartureTour>find("dock=?1 and state in ?2",d,ACTIVE).firstResultOptional().map(t->t.code).orElse(null));return row;}).toList());
  v.put("canManage",field.manager());return v;}

 // ----- Tours -----

 /**
  * Proposes how to group the carrier departures waiting outside any tour into vehicles of one service, for its next
  * collection: the most urgent first, each vehicle within the units and weight of the service, its stops ordered.
  * Addresses without position are looked up once and kept on the customer.
  *
  * @param storeId the store
  * @param serviceId the carrier service
  * @return the proposed tours, the departures too large for the service and the free docks
  */
 public Object proposals(Long storeId,Long serviceId){Store s=field.store(storeId,false);
  CarrierServiceLevel service=CarrierServiceLevel.find("id=?1 and carrier.store=?2",serviceId,s).firstResult();if(service==null)throw new NotFoundException();
  if(!service.active||!service.carrier.active)throw new BadRequestException("Choisissez un service actif d’un transporteur actif");
  LocalDateTime collection=nextCollection(service.cutoff,DateTimeProvider.now());
  var waiting=waiting(s);var byId=new HashMap<Long,LogisticsDeparture>();for(var d:waiting)byId.put(d.id,d);
  var origin=origin(s);var candidates=candidates(waiting,geocoder.session());
  var packing=TourPlanning.pack(candidates,service.maxUnits,service.maxWeightKg,origin);
  var v=new LinkedHashMap<String,Object>();v.put("carrier",carrierView(service.carrier));v.put("service",serviceView(service));v.put("collectionAt",collection);v.put("originLocated",origin!=null);v.put("origin",pointView(origin));v.put("waiting",waiting.size());v.put("googleAvailable",directions.available());
  var tours=new ArrayList<Map<String,Object>>();int index=0;
  for(var group:packing.tours()){var row=new LinkedHashMap<String,Object>();row.put("index",++index);
   var ordered=TourPlanning.order(origin,TourPlanning.stops(group));TourPlanning.Measured measured=null;List<double[]> path=null;String note=null;
   if(directions.available()){try{var road=google(origin,ordered);ordered=road.ordered();measured=road.measured();path=road.road().path();}catch(GoogleDirections.Unavailable e){note=e.getMessage();}}
   var route=TourPlanning.route(origin,ordered,collection,measured);row.put("source",measured==null?ESTIMATE:GOOGLE);row.put("path",pathView(path));row.put("routingNote",note);
   row.put("departureIds",route.legs().stream().flatMap(l->l.stop().departures().stream()).map(TourPlanning.Candidate::id).toList());
   row.put("units",group.stream().mapToInt(TourPlanning.Candidate::units).sum());row.put("weightKg",group.stream().map(TourPlanning.Candidate::weightKg).reduce(BigDecimal.ZERO,BigDecimal::add));
   row.put("weightComplete",group.stream().allMatch(c->complete(byId.get(c.id()))));row.putAll(routeTotals(route));row.put("stops",route.legs().stream().map(l->legView(l,byId)).toList());tours.add(row);}
  v.put("tours",tours);
  v.put("oversize",packing.oversize().stream().map(c->{var row=departureView(byId.get(c.id()));row.put("reason",service.maxUnits!=null&&c.units()>service.maxUnits?c.units()+" supports pour "+service.maxUnits+" au plus par véhicule":"poids connu supérieur à "+service.maxWeightKg.stripTrailingZeros().toPlainString()+" kg par véhicule");return row;}).toList());
  v.put("freeDocks",ShippingDock.<ShippingDock>list("store=?1 and active=true order by code",s).stream().filter(d->DepartureTour.count("dock=?1 and state in ?2",d,ACTIVE)==0).map(this::dockView).toList());
  return v;}

 /**
  * Returns the next collection of a service: today at its cut-off if it is still ahead, otherwise tomorrow.
  *
  * @param cutoff the cut-off, HH:mm
  * @param now the current time
  * @return the collection time
  */
 static LocalDateTime nextCollection(String cutoff,LocalDateTime now){LocalDateTime today=now.toLocalDate().atTime(LocalTime.parse(cutoff));return now.isBefore(today)?today:today.plusDays(1);}

 /**
  * Plans a tour, attaches the chosen departures and keeps their order as the delivery order, all at once: applying a
  * proposal leaves no empty tour behind when a departure has been taken meanwhile. The road itinerary is then asked
  * from Google Maps when it is configured.
  *
  * @param i service, dock, planned departure, optional vehicle and the departures
  * @return the tour
  */
 public Object compose(Input i){var c=command(i);if(c.replay()!=null)return c.replay();Store s=c.store();var t=plan(s,i);join(s,t,i.departureIds);
  var byId=attached(t).stream().collect(Collectors.toMap(d->d.id,d->d));number(i.departureIds.stream().map(byId::get).toList());refreshRoad(t);
  return receipts.record(s,i.requestKey,c.fingerprint(),"COMPOSE_TOUR",view(t));}

 /**
  * Sets the delivery order of an open tour: with engine GOOGLE, the shortest order by road that Google Maps finds;
  * otherwise the optimised order when no departure is given, or the given order, which must list every departure of
  * the tour exactly once. The road itinerary of the new order is kept when Google Maps gives it.
  *
  * @param i the tour, its revision, the engine and, for a manual order, its departures in delivery order
  * @return the tour
  */
 public Object sequence(Input i){var c=command(i);if(c.replay()!=null)return c.replay();Store s=c.store();var t=tour(s,i.tourId);revision(t.revisionNumber,i.expectedRevision,"La tournée");open(t);
  var attached=attached(t);if(attached.isEmpty())throw new OperatorFailure(409,"TOUR_EMPTY","Ajoutez au moins un départ avant d’ordonner les arrêts");
  List<LogisticsDeparture> order;boolean road=GOOGLE.equals(i.engine);
  if(road){try{order=googleOrdered(s,t,attached);}catch(GoogleDirections.Unavailable e){throw new OperatorFailure(409,"ROAD_ROUTING_UNAVAILABLE",e.getMessage());}}
  else if(i.departureIds==null)order=optimised(s,attached);
  else{var byId=attached.stream().collect(Collectors.toMap(d->d.id,d->d));
   if(i.departureIds.size()!=attached.size()||!new HashSet<>(i.departureIds).equals(byId.keySet()))throw new BadRequestException("Donnez une fois chacun des départs de la tournée, dans l’ordre de livraison");
   order=i.departureIds.stream().map(byId::get).toList();}
  number(order);if(!road)refreshRoad(t);t.revisionNumber++;return receipts.record(s,i.requestKey,c.fingerprint(),"SEQUENCE_TOUR",view(t));}

 /** Source of distances and times: Google Maps road itinerary, or straight-line estimate. */
 static final String GOOGLE="GOOGLE",ESTIMATE="ESTIMATE";

 /**
  * A road itinerary applied to stops.
  *
  * @param ordered the stops in delivery order, unlocated ones last
  * @param measured the road legs of the located stops
  * @param road the itinerary as Google gave it
  */
 record RoadRoute(List<TourPlanning.Stop> ordered,TourPlanning.Measured measured,GoogleDirections.Road road){}

 /**
  * Lets Google Maps order the located stops by road; the unlocated stops follow by postal code.
  *
  * @param origin the store
  * @param stops the stops
  * @return the stops in Google's order with their road legs
  * @throws GoogleDirections.Unavailable when Google gives no itinerary
  */
 RoadRoute google(TourPlanning.Point origin,List<TourPlanning.Stop> stops){
  var located=stops.stream().filter(x->x.point()!=null).toList();var road=directions.route(origin,located.stream().map(TourPlanning.Stop::point).toList(),true);
  var ordered=new ArrayList<TourPlanning.Stop>();for(int index:road.order())ordered.add(located.get(index));
  ordered.addAll(TourPlanning.order(null,stops.stream().filter(x->x.point()==null).toList()));
  return new RoadRoute(ordered,new TourPlanning.Measured(road.km(),road.seconds()),road);}

 /**
  * Orders the departures of a tour by the road itinerary Google Maps finds shortest, and keeps that itinerary.
  *
  * @param s the store
  * @param t the tour
  * @param attached its departures
  * @return the departures in delivery order
  * @throws GoogleDirections.Unavailable when Google gives no itinerary
  */
 List<LogisticsDeparture> googleOrdered(Store s,DepartureTour t,List<LogisticsDeparture> attached){var byId=attached.stream().collect(Collectors.toMap(d->d.id,d->d));var origin=origin(s);
  var road=google(origin,TourPlanning.stops(candidates(attached,geocoder.session())));t.roadRoute=encodeRoad(signature(origin,road.ordered()),road.road());
  return road.ordered().stream().flatMap(stop->stop.departures().stream()).map(x->byId.get(x.id())).toList();}

 /**
  * Asks Google Maps for the road itinerary of the current delivery order of a tour and keeps it; without key, store
  * position or located stop, or when Google fails, the tour keeps straight-line estimates.
  *
  * @param t the tour, whose departures are already numbered
  */
 void refreshRoad(DepartureTour t){t.roadRoute=null;if(!directions.available())return;
  var origin=origin(t.store);var stops=TourPlanning.stops(candidates(attached(t),null));var located=stops.stream().filter(x->x.point()!=null).map(TourPlanning.Stop::point).toList();
  if(origin==null||located.isEmpty()||located.size()>GoogleDirections.MAX_STOPS)return;
  try{t.roadRoute=encodeRoad(signature(origin,stops),directions.route(origin,located,false));}catch(GoogleDirections.Unavailable e){org.jboss.logging.Logger.getLogger(TourService.class).warnf("Itinéraire Google Maps non calculé pour %s : %s",t.code,e.getMessage());}}

 /**
  * Identifies the located points of a route in order, so that a kept itinerary is only used for the same order.
  *
  * @param origin the store, or null
  * @param stops the stops in delivery order
  * @return the signature
  */
 static String signature(TourPlanning.Point origin,List<TourPlanning.Stop> stops){
  return java.util.stream.Stream.concat(java.util.stream.Stream.of(origin),stops.stream().map(TourPlanning.Stop::point).filter(java.util.Objects::nonNull)).map(p->p==null?"-":GoogleDirections.coordinates(p)).collect(Collectors.joining(";"));}

 /**
  * A kept road itinerary.
  *
  * @param signature the located points it was computed for
  * @param km the road distance of each leg
  * @param seconds the driving time of each leg
  * @param path the road path
  */
 record StoredRoad(String signature,List<Double> km,List<Long> seconds,List<double[]> path){}

 /**
  * Serialises a road itinerary to keep it on the tour.
  *
  * @param signature the located points it was computed for
  * @param road the itinerary
  * @return the JSON
  */
 String encodeRoad(String signature,GoogleDirections.Road road){var v=new LinkedHashMap<String,Object>();v.put("signature",signature);v.put("km",road.km());v.put("seconds",road.seconds());v.put("path",road.path());return field.encode(v);}

 /**
  * Reads a kept road itinerary.
  *
  * @param json the JSON, or null
  * @return the itinerary, or null when none is kept
  */
 @SuppressWarnings("unchecked")
 StoredRoad storedRoad(String json){if(json==null)return null;Map<String,Object> v=field.decode(json,Map.class);
  return new StoredRoad((String)v.get("signature"),((List<Number>)v.get("km")).stream().map(Number::doubleValue).toList(),((List<Number>)v.get("seconds")).stream().map(Number::longValue).toList(),
   ((List<List<Number>>)v.get("path")).stream().map(p->new double[]{p.get(0).doubleValue(),p.get(1).doubleValue()}).toList());}

 /**
  * Describes a road path for the map.
  *
  * @param path the points, or null
  * @return latitude and longitude pairs, or null
  */
 static List<List<Double>> pathView(List<double[]> path){return path==null?null:path.stream().map(p->List.of(p[0],p[1])).toList();}

 /**
  * Numbers departures in delivery order.
  *
  * @param order the departures in delivery order
  */
 static void number(List<LogisticsDeparture> order){int n=0;for(var d:order)d.stopSequence=++n;}

 /**
  * Orders departures by delivery stop, looking up the addresses still without position.
  *
  * @param s the store
  * @param attached the departures of a tour
  * @return the departures in optimised delivery order
  */
 List<LogisticsDeparture> optimised(Store s,List<LogisticsDeparture> attached){var byId=attached.stream().collect(Collectors.toMap(d->d.id,d->d));var origin=origin(s);
  return TourPlanning.order(origin,TourPlanning.stops(candidates(attached,geocoder.session()))).stream().flatMap(stop->stop.departures().stream()).map(x->byId.get(x.id())).toList();}

 /**
  * Lists the carrier departures still loading outside any tour.
  *
  * @param s the store
  * @return the departures, oldest first, 199 at most
  */
 List<LogisticsDeparture> waiting(Store s){return LogisticsDeparture.<LogisticsDeparture>find("select d from LogisticsDeparture d join fetch d.order join fetch d.owner where d.store=?1 and d.mode='CARRIER' and d.state='LOADING' and d.tour is null order by d.id",s).range(0,199).list();}

 /**
  * Returns the position the vehicles leave from: the store building, or another building of the store.
  *
  * @param s the store
  * @return the position, or null when no building of the store is located
  */
 static TourPlanning.Point origin(Store s){TourPlanning.Point other=null;
  for(Building raw:s.buildings){Building b=Hibernate.unproxy(raw,Building.class);var p=point(b.gpsPosition);if(p==null)continue;if(b instanceof StoreBuilding)return p;if(other==null)other=p;}
  return other;}

 /**
  * Converts a stored position.
  *
  * @param p the position, possibly null or incomplete
  * @return the point, or null
  */
 static TourPlanning.Point point(GPSPosition p){return p==null||p.latitude==null||p.longitude==null?null:new TourPlanning.Point(p.latitude,p.longitude);}

 /**
  * Describes departures for the planning calculations: consignee address, its position, units, known weight and the
  * delivery deadline of the order.
  *
  * @param chosen the departures
  * @param lookup a geocoding session to locate addresses still without position, or null to use stored positions only
  * @return the candidates, in the same order
  */
 List<TourPlanning.Candidate> candidates(List<LogisticsDeparture> chosen,AddressGeocoder.Session lookup){
  var deadlines=deadlines(chosen);var located=new HashMap<Long,TourPlanning.Point>();var list=new ArrayList<TourPlanning.Candidate>();
  for(var d:chosen){Customer customer=d.order.customer==null?null:Hibernate.unproxy(d.order.customer,Customer.class);
   TourPlanning.Point point=null;
   if(customer!=null){point=located.containsKey(customer.id)?located.get(customer.id):position(customer,lookup);located.put(customer.id,point);}
   int units=0;BigDecimal kg=BigDecimal.ZERO;for(var u:departures.units(d)){if(!TRAVELLING.contains(u.state))continue;units++;if(u.parcel!=null&&u.parcel.weightKg!=null)kg=kg.add(u.parcel.weightKg);}
   var address=customer==null?null:customer.address;
   list.add(new TourPlanning.Candidate(d.id,TourDocuments.addressKey(address,d.id),point,address==null?null:address.postalCode,units,kg,deadlines.get(id(d.order))));}
  return list;}

 /** States of the units still travelling with their departure. */
 static final Set<String> TRAVELLING=Set.of("SELECTED","LOADED","HANDED_OVER");

 /**
  * Tells whether every travelling unit of a departure has a known weight.
  *
  * @param d the departure
  * @return true when all are weighed parcels
  */
 boolean complete(LogisticsDeparture d){return departures.units(d).stream().filter(u->TRAVELLING.contains(u.state)).allMatch(u->u.parcel!=null&&u.parcel.weightKg!=null);}

 /**
  * Returns the position of a customer address, looking it up when it is unknown and a session is given.
  *
  * @param customer the customer
  * @param lookup the geocoding session, or null
  * @return the point, or null when unknown
  */
 static TourPlanning.Point position(Customer customer,AddressGeocoder.Session lookup){var known=point(customer.addressPosition);if(known!=null||lookup==null)return known;
  GPSPosition found=lookup.locate(customer.address);if(found==null)return null;customer.addressPosition=found;customer.addressPositionSource=Customer.POSITION_GEOCODED;return point(found);}

 /**
  * Reads the delivery deadline of the orders of departures: the earliest delivery date of their fulfillment chunks.
  *
  * @param chosen the departures
  * @return the deadline per order id, for the orders that have one
  */
 Map<Long,LocalDateTime> deadlines(List<LogisticsDeparture> chosen){var orders=chosen.stream().map(d->id(d.order)).distinct().toList();var result=new HashMap<Long,LocalDateTime>();if(orders.isEmpty())return result;
  for(Object[] row:em.createQuery("select f.order.id,min(c.deliveryDate) from Fulfillment f join f.chunks c where f.order.id in :orders group by f.order.id",Object[].class).setParameter("orders",orders).getResultList())if(row[1]!=null)result.put((Long)row[0],(LocalDateTime)row[1]);
  return result;}

 /**
  * Describes the delivery order of a tour as it stands, with distances and arrival estimates from stored positions.
  *
  * @param t the tour
  * @param attached its departures in delivery order
  * @return the route view
  */
 Map<String,Object> routeView(DepartureTour t,List<LogisticsDeparture> attached){var byId=new HashMap<Long,LogisticsDeparture>();for(var d:attached)byId.put(d.id,d);
  var origin=origin(t.store);var stops=TourPlanning.stops(candidates(attached,null));var stored=storedRoad(t.roadRoute);
  boolean measured=stored!=null&&stored.signature().equals(signature(origin,stops));
  var route=TourPlanning.route(origin,stops,t.departedAt!=null?t.departedAt:t.plannedDeparture,measured?new TourPlanning.Measured(stored.km(),stored.seconds()):null);
  var v=new LinkedHashMap<String,Object>();v.put("sequenced",!attached.isEmpty()&&attached.stream().allMatch(d->d.stopSequence!=null));v.put("originLocated",origin!=null);v.put("origin",pointView(origin));v.putAll(routeTotals(route));
  v.put("source",measured?GOOGLE:ESTIMATE);v.put("path",measured?pathView(stored.path()):null);v.put("googleAvailable",directions.available());
  v.put("stops",route.legs().stream().map(l->legView(l,byId)).toList());return v;}

 /**
  * Describes a point for the map.
  *
  * @param p the point, or null
  * @return latitude and longitude, or null when unknown
  */
 static Map<String,Object> pointView(TourPlanning.Point p){if(p==null)return null;var v=new LinkedHashMap<String,Object>();v.put("lat",p.lat());v.put("lon",p.lon());return v;}

 /**
  * Sums up a route.
  *
  * @param route the route
  * @return its estimated distance, rounded to 0.1 km, and its stop counts
  */
 static Map<String,Object> routeTotals(TourPlanning.Route route){var v=new LinkedHashMap<String,Object>();v.put("distanceKm",route.distanceKm()==null?null:Math.round(route.distanceKm()*10)/10.0);v.put("stopCount",route.legs().size());v.put("unlocated",route.unlocated());v.put("late",route.legs().stream().filter(TourPlanning.Leg::late).count());return v;}

 /**
  * Describes one stop of a route.
  *
  * @param l the stop with its estimates
  * @param byId the departures by id
  * @return the view
  */
 Map<String,Object> legView(TourPlanning.Leg l,Map<Long,LogisticsDeparture> byId){var v=new LinkedHashMap<String,Object>();var first=byId.get(l.stop().departures().getFirst().id());
  v.put("sequence",l.sequence());v.put("consignee",TourDocuments.consignee(first));v.put("located",l.stop().point()!=null);v.put("position",pointView(l.stop().point()));
  Customer customer=first.order.customer==null?null:Hibernate.unproxy(first.order.customer,Customer.class);v.put("positionSource",l.stop().point()==null||customer==null?null:customer.addressPositionSource);
  v.put("distanceKm",l.distanceKm()==null?null:Math.round(l.distanceKm()*10)/10.0);v.put("estimatedArrival",l.arrival());v.put("deadline",l.stop().deadline());v.put("late",l.late());
  v.put("departures",l.stop().departures().stream().map(c->{var d=byId.get(c.id());var row=new LinkedHashMap<String,Object>();row.put("id",d.id);row.put("code",d.code);row.put("orderCode",d.order.orderId);row.put("units",c.units());row.put("weightKg",c.weightKg());return row;}).toList());
  return v;}

 /**
  * Plans a tour: a service of a carrier collecting at a free dock before the service cut-off.
  *
  * @param i service, dock, planned departure and optional vehicle
  * @return the tour
  */
 public Object create(Input i){var c=command(i);if(c.replay()!=null)return c.replay();var t=plan(c.store(),i);return receipts.record(c.store(),i.requestKey,c.fingerprint(),"CREATE_TOUR",view(t));}

 /**
  * Validates and persists a new open tour.
  *
  * @param s the locked store
  * @param i service, dock, planned departure, optional vehicle and the request key the code derives from
  * @return the tour
  */
 DepartureTour plan(Store s,Input i){
  CarrierServiceLevel service=CarrierServiceLevel.find("id=?1 and carrier.store=?2",i.serviceId,s).firstResult();if(service==null)throw new NotFoundException();
  if(!service.active||!service.carrier.active)throw new BadRequestException("Choisissez un service actif d’un transporteur actif");
  ShippingDock dock=ShippingDock.find("id=?1 and store=?2",i.dockId,s).firstResult();if(dock==null)throw new NotFoundException();if(!dock.active)throw new BadRequestException("Ce quai est désactivé");
  var busy=DepartureTour.<DepartureTour>find("dock=?1 and state in ?2",dock,ACTIVE).firstResult();if(busy!=null)throw new OperatorFailure(409,"DOCK_BUSY","Le quai "+dock.code+" est occupé par la tournée "+busy.code+".");
  LocalDateTime now=DateTimeProvider.now();if(i.plannedDeparture==null||i.plannedDeparture.isBefore(now.minusHours(1))||i.plannedDeparture.isAfter(now.plusDays(14)))throw new BadRequestException("Heure de départ requise, entre maintenant et 14 jours");
  if(i.plannedDeparture.toLocalTime().isAfter(LocalTime.parse(service.cutoff)))throw new BadRequestException("Le service "+service.label+" n’enlève plus après "+service.cutoff);
  String vehicle=vehicle(i.vehicleReference,false);
  var t=new DepartureTour();t.store=s;t.serviceLevel=service;t.dock=dock;t.plannedDeparture=i.plannedDeparture;t.vehicleReference=vehicle;t.createdBy=field.actor();
  String digest=sha256(s.id+":"+i.requestKey).toUpperCase(Locale.ROOT);t.code="TRN-"+digest.substring(0,8);if(DepartureTour.count("code",t.code)>0)t.code="TRN-"+digest.substring(0,16);
  t.persistAndFlush();return t;}

 /**
  * Validates an optional or required vehicle reference.
  *
  * @param value the reference
  * @param required whether it must be given
  * @return the trimmed upper-case reference, or null
  */
 static String vehicle(String value,boolean required){if(value==null||value.isBlank()){if(required)throw new BadRequestException("Immatriculation du véhicule requise");return null;}String v=value.trim().toUpperCase(Locale.ROOT);if(v.length()>40)throw new BadRequestException("Immatriculation : 40 caractères au plus");return v;}

 /**
  * Attaches carrier departures, still loading, to an open tour within the capacity of its service.
  *
  * @param i the tour, its revision and the departures
  * @return the tour
  */
 public Object attach(Input i){var c=command(i);if(c.replay()!=null)return c.replay();Store s=c.store();var t=tour(s,i.tourId);revision(t.revisionNumber,i.expectedRevision,"La tournée");open(t);
  join(s,t,i.departureIds);t.revisionNumber++;return receipts.record(s,i.requestKey,c.fingerprint(),"ATTACH_TOUR",view(t));}

 /**
  * Attaches carrier departures, still loading and outside any tour, to an open tour, then checks the capacity of its
  * service; the new departures come last in the delivery order until it is set again.
  *
  * @param s the locked store
  * @param t the open tour
  * @param departureIds the departures
  */
 void join(Store s,DepartureTour t,List<Long> departureIds){
  if(departureIds==null||departureIds.isEmpty()||departureIds.size()>MAX_DEPARTURES||new HashSet<>(departureIds).size()!=departureIds.size())throw new BadRequestException("Choisissez de 1 à "+MAX_DEPARTURES+" départs distincts");
  for(Long key:departureIds){var d=LogisticsDeparture.<LogisticsDeparture>find("id=?1 and store=?2",key,s).firstResult();if(d==null)throw new NotFoundException();
   if(!"CARRIER".equals(d.mode))throw new BadRequestException(d.code+" est une remise au client : seule une expédition transporteur rejoint une tournée");
   if(!"LOADING".equals(d.state))throw new OperatorFailure(409,"DEPARTURE_PHASE_CHANGED",d.code+" n’est plus en chargement");
   if(d.tour!=null)throw new OperatorFailure(409,"DEPARTURE_ALREADY_IN_TOUR",d.code+" part déjà avec la tournée "+d.tour.code);
   if(DepartureCustodyTransfer.count("departure=?1 and state='PENDING'",d)>0)throw new OperatorFailure(409,"CUSTODY_TRANSFER_PENDING","La garde de "+d.code+" est en cours de transmission");
   if(!field.manager()&&!field.actor().equals(d.owner.badgeId))throw new OperatorFailure(403,"NOT_DEPARTURE_OWNER",d.code+" est sous la garde de "+d.owner.getFullName()+" : demandez-lui la transmission ou un responsable");
   d.tour=t;d.stopSequence=null;d.revisionNumber++;}
  field.flush();var load=load(t);var service=t.serviceLevel;
  if(service.maxUnits!=null&&load.units()>service.maxUnits)throw new OperatorFailure(409,"TOUR_FULL","La tournée dépasserait la capacité du service : "+load.units()+" supports pour "+service.maxUnits+" au plus");
  if(service.maxWeightKg!=null&&load.weightKg().compareTo(service.maxWeightKg)>0)throw new OperatorFailure(409,"TOUR_FULL","La tournée dépasserait le poids maximal du service : "+load.weightKg().stripTrailingZeros().toPlainString()+" kg pour "+service.maxWeightKg.stripTrailingZeros().toPlainString()+" kg");}

 /**
  * Removes a departure from an open tour; its loaded units must be unloaded first.
  *
  * @param i the tour, its revision and the departure
  * @return the tour
  */
 public Object detach(Input i){var c=command(i);if(c.replay()!=null)return c.replay();Store s=c.store();var t=tour(s,i.tourId);revision(t.revisionNumber,i.expectedRevision,"La tournée");open(t);
  var d=LogisticsDeparture.<LogisticsDeparture>find("id=?1 and store=?2 and tour=?3",i.departureId,s,t).firstResult();if(d==null)throw new NotFoundException();
  if(!field.manager()&&!field.actor().equals(d.owner.badgeId)&&!field.actor().equals(t.createdBy))throw new OperatorFailure(403,"NOT_DEPARTURE_OWNER",d.code+" est sous la garde de "+d.owner.getFullName());
  if(departures.units(d).stream().anyMatch(u->"LOADED".equals(u.state)))throw new OperatorFailure(409,"CARGO_UNLOAD_REQUIRED","Déchargez d’abord les supports de "+d.code+" du véhicule");
  d.tour=null;d.stopSequence=null;d.revisionNumber++;t.revisionNumber++;return receipts.record(s,i.requestKey,c.fingerprint(),"DETACH_TOUR",view(t));}

 /**
  * Seals the vehicle: every unit of every departure must be loaded, the dock is scanned and the transport
  * documents are frozen.
  *
  * @param i the tour, its revision, the scanned dock, the seal and the vehicle
  * @return the tour
  */
 public Object seal(Input i){var c=command(i);if(c.replay()!=null)return c.replay();Store s=c.store();departures.requireAvailable(s);var t=tour(s,i.tourId);revision(t.revisionNumber,i.expectedRevision,"La tournée");open(t);
  if(i.scannedDock==null||!t.dock.code.equalsIgnoreCase(i.scannedDock.trim()))throw new BadRequestException("Le quai scanné ne correspond pas au quai "+t.dock.code+" de la tournée");
  String seal=i.sealNumber==null?"":i.sealNumber.trim().toUpperCase(Locale.ROOT);if(!SEAL.matcher(seal).matches())throw new BadRequestException("Numéro de plomb : 4 à 30 lettres, chiffres ou tirets");
  String vehicle=vehicle(i.vehicleReference==null||i.vehicleReference.isBlank()?t.vehicleReference:i.vehicleReference,true);
  var attached=attached(t);if(attached.isEmpty())throw new OperatorFailure(409,"TOUR_EMPTY","Ajoutez au moins un départ avant de plomber");
  var missing=new ArrayList<String>();for(var d:attached){var units=departures.units(d);for(var u:units)if("SELECTED".equals(u.state))missing.add(departures.code(u));if(units.stream().noneMatch(u->"LOADED".equals(u.state)))missing.add(d.code);}
  if(!missing.isEmpty())throw new OperatorFailure(409,"TOUR_INCOMPLETE","Chargement incomplet : "+String.join(", ",missing.subList(0,Math.min(10,missing.size())))+(missing.size()>10?" et "+(missing.size()-10)+" autre(s)":"")+". Chargez-les ou retirez leur départ de la tournée.");
  if(DepartureTour.count("sealNumber=?1 and store=?2 and state in ?3 and id<>?4",seal,s,Set.of(DepartureTour.SEALED,DepartureTour.DEPARTED),t.id)>0)throw new OperatorFailure(409,"SEAL_ALREADY_USED","Le plomb "+seal+" est déjà enregistré sur une autre tournée");
  t.sealNumber=seal;t.vehicleReference=vehicle;t.sealedBy=field.actor();t.sealedAt=DateTimeProvider.now();t.state=DepartureTour.SEALED;t.revisionNumber++;
  documents.issue(t,attached);
  return receipts.record(s,i.requestKey,c.fingerprint(),"SEAL_TOUR",view(t));}

 /**
  * Breaks the seal of a tour that has not left: a manager reopens loading and the documents are voided.
  *
  * @param i the tour, its revision and the reason
  * @return the tour
  */
 public Object unseal(Input i){var c=command(i);if(c.replay()!=null)return c.replay();field.requireManager();Store s=c.store();var t=tour(s,i.tourId);revision(t.revisionNumber,i.expectedRevision,"La tournée");
  if(!DepartureTour.SEALED.equals(t.state))throw new OperatorFailure(409,"TOUR_NOT_SEALED","Seule une tournée plombée et pas encore partie peut être rouverte");FieldOperationsService.text(i.reason,255);
  String reason="Plomb "+t.sealNumber+" rompu par "+field.actor()+" : "+i.reason.trim();if(reason.length()>255)reason=reason.substring(0,255);
  for(var d:TourDocument.<TourDocument>list("tour=?1 and state=?2",t,TourDocument.ACTIVE)){d.state=TourDocument.VOIDED;d.voidedReason=reason;}
  t.state=DepartureTour.OPEN;t.sealNumber=null;t.sealedAt=null;t.sealedBy=null;t.revisionNumber++;
  return receipts.record(s,i.requestKey,c.fingerprint(),"UNSEAL_TOUR",view(t));}

 /**
  * Hands the whole sealed tour over to the carrier after scanning its seal: one shipment per departure, stock
  * leaves the store once.
  *
  * @param i the tour, its revision and the scanned seal
  * @return the tour
  */
 public Object depart(Input i){var c=command(i);if(c.replay()!=null)return c.replay();Store s=c.store();departures.requireAvailable(s);var t=tour(s,i.tourId);revision(t.revisionNumber,i.expectedRevision,"La tournée");
  if(!DepartureTour.SEALED.equals(t.state))throw new OperatorFailure(409,"TOUR_NOT_SEALED","Plombez la tournée avant son départ");
  if(i.scannedSeal==null||!t.sealNumber.equalsIgnoreCase(i.scannedSeal.trim()))throw new BadRequestException("Le plomb scanné ne correspond pas au plomb "+t.sealNumber+" enregistré");
  var attached=attached(t);var foreign=attached.stream().filter(d->!field.actor().equals(d.owner.badgeId)).map(d->d.code+" ("+d.owner.getFullName()+")").toList();
  if(!field.manager()&&!foreign.isEmpty())throw new OperatorFailure(403,"NOT_DEPARTURE_OWNER","Départs sous la garde d’un collègue : "+String.join(", ",foreign)+". Un responsable ou leur détenteur confirme le départ.");
  String recipient=t.serviceLevel.carrier.name;
  for(var d:attached){var loaded=departures.units(d).stream().filter(u->"LOADED".equals(u.state)).toList();if(loaded.isEmpty())continue;departures.handOver(s,d,loaded,recipient,shipmentKey(t,d));}
  t.state=DepartureTour.DEPARTED;t.departedBy=field.actor();t.departedAt=DateTimeProvider.now();t.revisionNumber++;
  return receipts.record(s,i.requestKey,c.fingerprint(),"DEPART_TOUR",view(t));}

 /**
  * Derives the stable idempotency key of the shipment of one departure of a tour.
  *
  * @param t the tour
  * @param d the departure
  * @return a 32-character key
  */
 static String shipmentKey(DepartureTour t,LogisticsDeparture d){return sha256("tour:"+t.id+":departure:"+d.id).substring(0,32);}

 /**
  * Hashes a text.
  *
  * @param value the text
  * @return the lower-case SHA-256
  */
 static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}

 /**
  * Cancels an open tour: its departures stay loading on their own, with what is already loaded.
  *
  * @param i the tour, its revision and the reason
  * @return the tour
  */
 public Object cancel(Input i){var c=command(i);if(c.replay()!=null)return c.replay();Store s=c.store();var t=tour(s,i.tourId);revision(t.revisionNumber,i.expectedRevision,"La tournée");open(t);
  if(!field.manager()&&!field.actor().equals(t.createdBy))throw new OperatorFailure(403,"NOT_TOUR_OWNER","Seul son créateur ou un responsable annule la tournée");FieldOperationsService.text(i.reason,255);
  for(var d:attached(t)){d.tour=null;d.stopSequence=null;d.revisionNumber++;}
  t.state=DepartureTour.CANCELLED;t.closingReason=i.reason.trim();t.revisionNumber++;
  return receipts.record(s,i.requestKey,c.fingerprint(),"CANCEL_TOUR",view(t));}

 /**
  * Records the empty totes brought back by the carrier of a departed tour; unknown or already returned codes are
  * reported, never guessed.
  *
  * @param i the tour and the scanned tote codes
  * @return what was recorded and the tour
  */
 public Object returns(Input i){var c=command(i);if(c.replay()!=null)return c.replay();Store s=c.store();var t=tour(s,i.tourId);
  if(!DepartureTour.DEPARTED.equals(t.state))throw new OperatorFailure(409,"TOUR_NOT_DEPARTED","Les bacs vides reviennent d’une tournée partie");
  if(i.toteCodes==null||i.toteCodes.isEmpty()||i.toteCodes.size()>MAX_TOTES)throw new BadRequestException("Scannez de 1 à "+MAX_TOTES+" bacs");
  var codes=new LinkedHashSet<String>();for(String code:i.toteCodes){if(code==null||code.isBlank()||code.trim().length()>60)throw new BadRequestException("Code de bac invalide");codes.add(code.trim());}
  var lines=shipped(t);var returned=new ArrayList<String>();var already=new ArrayList<String>();var unknown=new ArrayList<String>();
  for(String code:codes){var matching=lines.stream().filter(l->code.equalsIgnoreCase(l.toteCode)).toList();
   if(matching.isEmpty()){unknown.add(code);continue;}
   if(matching.stream().allMatch(l->l.toteReturnedAt!=null)){already.add(code);continue;}
   for(var l:matching)if(l.toteReturnedAt==null){l.toteReturnedAt=DateTimeProvider.now();l.toteReturnedBy=field.actor();l.loadedTote.status=LoadedTote.Status.DELIVERED;}
   returned.add(matching.getFirst().toteCode);}
  t.revisionNumber++;var result=new LinkedHashMap<String,Object>();result.put("returned",returned);result.put("alreadyReturned",already);result.put("unknown",unknown);result.put("tour",view(t));
  return receipts.record(s,i.requestKey,c.fingerprint(),"RETURN_TOUR_TOTES",result);}

 /**
  * Lists the tours of the store, most recent first.
  *
  * @param storeId the store
  * @param state an optional state
  * @param before the id to page before, 0 for the first page
  * @param limit the page size
  * @return a page of tour summaries
  */
 public Object list(Long storeId,String state,long before,int limit){Store s=field.store(storeId,false);if(limit<1||limit>100||before<0)throw new BadRequestException("Pagination invalide");
  if(state!=null&&!state.isBlank()&&!STATES.contains(state))throw new BadRequestException("État de tournée invalide");
  var rows=DepartureTour.<DepartureTour>find("select t from DepartureTour t join fetch t.serviceLevel l join fetch l.carrier join fetch t.dock where t.store=?1 and (?2='' or t.state=?2) and (?3=0 or t.id<?3) order by t.id desc",s,state==null?"":state,before).range(0,limit).list();
  boolean more=DepartureTour.count("store=?1 and (?2='' or state=?2) and id<?3",s,state==null?"":state,rows.isEmpty()?0L:rows.getLast().id)>0;
  return Map.of("items",rows.stream().map(this::summary).toList(),"hasMore",!rows.isEmpty()&&more,"nextBefore",rows.isEmpty()?before:rows.getLast().id);}

 /**
  * Reads one tour.
  *
  * @param storeId the store
  * @param tourId the tour
  * @return the tour
  */
 public Object read(Long storeId,Long tourId){return view(tour(field.store(storeId,false),tourId));}

 /**
  * Lists the carrier departures still loading that may join a tour.
  *
  * @param storeId the store
  * @return the departures with their units
  */
 public Object candidates(Long storeId){Store s=field.store(storeId,false);
  return LogisticsDeparture.<LogisticsDeparture>find("select d from LogisticsDeparture d join fetch d.order join fetch d.owner where d.store=?1 and d.mode='CARRIER' and d.state='LOADING' and d.tour is null order by d.id",s).range(0,199).list().stream().map(this::departureView).toList();}

 /**
  * Reads one transport document of a tour, exactly as it was frozen.
  *
  * @param storeId the store
  * @param tourId the tour
  * @param documentId the document
  * @return the document and its content
  */
 public Object document(Long storeId,Long tourId,Long documentId){var t=tour(field.store(storeId,false),tourId);TourDocument d=TourDocument.find("id=?1 and tour=?2",documentId,t).firstResult();if(d==null)throw new NotFoundException();
  var v=documentView(d);v.put("sha256",d.sha256);v.put("content",field.decode(d.content,Map.class));return v;}

 /**
  * Finds a tour of the store.
  *
  * @param s the store
  * @param key the tour id
  * @return the tour
  */
 DepartureTour tour(Store s,Long key){DepartureTour t=DepartureTour.find("id=?1 and store=?2",key,s).firstResult();if(t==null)throw new NotFoundException();return t;}
 /**
  * Requires an open tour.
  *
  * @param t the tour
  */
 static void open(DepartureTour t){if(!DepartureTour.OPEN.equals(t.state))throw new OperatorFailure(409,"TOUR_CLOSED","La tournée "+t.code+" n’est plus modifiable ("+label(t.state)+")");}
 /**
  * Labels a tour state.
  *
  * @param state the state
  * @return the French label
  */
 static String label(String state){return switch(state){case DepartureTour.OPEN->"en chargement";case DepartureTour.SEALED->"plombée";case DepartureTour.DEPARTED->"partie";default->"annulée";};}
 /**
  * Lists the departures of a tour.
  *
  * @param t the tour
  * @return the departures
  */
 List<LogisticsDeparture> attached(DepartureTour t){return LogisticsDeparture.list("select d from LogisticsDeparture d join fetch d.order join fetch d.owner where d.tour=?1 order by d.stopSequence nulls last,d.id",t);}
 /**
  * Lists the tote lines shipped by a tour.
  *
  * @param t the tour
  * @return the shipment lines carried in totes
  */
 List<ShipmentLine> shipped(DepartureTour t){return ShipmentLine.list("select l from DepartureHandover h join h.shipment s join s.lines l join fetch l.loadedTote where h.departure.tour=?1 order by l.id",t);}

 /**
  * Load of a tour.
  *
  * @param units the units still travelling (selected or loaded)
  * @param loaded the units loaded
  * @param weightKg the known weight of the parcels
  * @param weightComplete whether every unit has a known weight
  */
 record Load(int units,int loaded,BigDecimal weightKg,boolean weightComplete){}
 /**
  * Measures the load of a tour.
  *
  * @param t the tour
  * @return the load
  */
 Load load(DepartureTour t){int units=0,loaded=0;BigDecimal kg=BigDecimal.ZERO;boolean complete=true;
  for(var d:attached(t))for(var u:departures.units(d)){if(!Set.of("SELECTED","LOADED","HANDED_OVER").contains(u.state))continue;units++;if(!"SELECTED".equals(u.state))loaded++;if(u.parcel!=null&&u.parcel.weightKg!=null)kg=kg.add(u.parcel.weightKg);else complete=false;}
  return new Load(units,loaded,kg,complete);}

 /**
  * Summarises a tour for lists.
  *
  * @param t the tour
  * @return the summary
  */
 Map<String,Object> summary(DepartureTour t){var v=new LinkedHashMap<String,Object>();v.put("id",t.id);v.put("code",t.code);v.put("state",t.state);v.put("revision",t.revisionNumber);
  v.put("carrier",carrierView(t.serviceLevel.carrier));v.put("service",serviceView(t.serviceLevel));v.put("dock",dockView(t.dock));
  v.put("plannedDeparture",t.plannedDeparture);v.put("vehicleReference",t.vehicleReference);v.put("sealNumber",t.sealNumber);v.put("sealedAt",t.sealedAt);v.put("sealedBy",t.sealedBy);v.put("departedAt",t.departedAt);v.put("departedBy",t.departedBy);v.put("closingReason",t.closingReason);v.put("createdBy",t.createdBy);
  var load=load(t);v.put("departures",LogisticsDeparture.count("tour",t));v.put("units",load.units());v.put("loaded",load.loaded());v.put("weightKg",load.weightKg());v.put("weightComplete",load.weightComplete());
  v.put("late",ACTIVE.contains(t.state)&&t.plannedDeparture.isBefore(DateTimeProvider.now()));v.put("canManage",field.manager());v.put("mine",field.actor().equals(t.createdBy));return v;}

 /**
  * Describes a tour with its departures, documents and empty totes.
  *
  * @param t the tour
  * @return the view
  */
 Map<String,Object> view(DepartureTour t){var v=summary(t);var attached=attached(t);v.put("attached",attached.stream().map(this::departureView).toList());v.put("route",routeView(t,attached));
  v.put("documents",TourDocument.<TourDocument>list("select d from TourDocument d left join fetch d.order where d.tour=?1 order by d.state,d.id",t).stream().map(this::documentView).toList());
  if(DepartureTour.DEPARTED.equals(t.state)){var totes=new LinkedHashMap<String,Boolean>();for(var l:shipped(t))totes.merge(l.toteCode,l.toteReturnedAt!=null,Boolean::logicalAnd);
   v.put("emptyTotes",Map.of("expected",totes.size(),"returned",totes.values().stream().filter(Boolean::booleanValue).count(),"outstanding",totes.entrySet().stream().filter(e->!e.getValue()).map(Map.Entry::getKey).toList()));}
  return v;}

 /**
  * Describes a departure and its units.
  *
  * @param d the departure
  * @return the view
  */
 Map<String,Object> departureView(LogisticsDeparture d){var v=new LinkedHashMap<String,Object>();v.put("id",d.id);v.put("code",d.code);v.put("state",d.state);v.put("revision",d.revisionNumber);v.put("orderId",id(d.order));v.put("orderCode",d.order.orderId);
  v.put("recipient",d.recipient);v.put("transportReference",d.transportReference);v.put("destinationCode",d.destinationCode);v.put("owner",d.owner.getFullName());v.put("mine",field.actor().equals(d.owner.badgeId));v.put("stopSequence",d.stopSequence);
  v.put("units",departures.units(d).stream().map(u->{var row=new LinkedHashMap<String,Object>();row.put("id",u.id);row.put("kind",u.parcel==null?"TOTE":"PARCEL");row.put("code",departures.code(u));row.put("state",u.state);row.put("weightKg",u.parcel==null?null:u.parcel.weightKg);return row;}).toList());return v;}

 /**
  * Describes a carrier.
  *
  * @param c the carrier
  * @return the view
  */
 Map<String,Object> carrierView(Carrier c){var v=new LinkedHashMap<String,Object>();v.put("id",c.id);v.put("code",c.code);v.put("name",c.name);v.put("active",c.active);return v;}
 /**
  * Describes a carrier service.
  *
  * @param l the service
  * @return the view
  */
 Map<String,Object> serviceView(CarrierServiceLevel l){var v=new LinkedHashMap<String,Object>();v.put("id",l.id);v.put("code",l.code);v.put("label",l.label);v.put("cutoff",l.cutoff);v.put("maxUnits",l.maxUnits);v.put("maxWeightKg",l.maxWeightKg);v.put("active",l.active);v.put("revision",l.revisionNumber);return v;}
 /**
  * Describes a dock.
  *
  * @param d the dock
  * @return the view
  */
 Map<String,Object> dockView(ShippingDock d){var v=new LinkedHashMap<String,Object>();v.put("id",d.id);v.put("code",d.code);v.put("label",d.label);v.put("active",d.active);return v;}
 /**
  * Describes a document without its content.
  *
  * @param d the document
  * @return the view
  */
 Map<String,Object> documentView(TourDocument d){var v=new LinkedHashMap<String,Object>();v.put("id",d.id);v.put("kind",d.kind);v.put("number",d.number);v.put("state",d.state);v.put("orderCode",d.order==null?null:d.order.orderId);v.put("voidedReason",d.voidedReason);v.put("issuedAt",d.createdAt);return v;}
}
