package com.intermarche.e2e.engine.fieldops;
import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.GPSPosition;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.shipping.Shipment;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/** Carrier tours: setup, grouping, loading control, seal, documents, single handover and empty tote returns. */
@QuarkusTest @jakarta.enterprise.inject.Typed(TourWorkflowTest.class)
class TourWorkflowTest extends DepartureWorkflowTest {
 /** Google Maps itineraries, unavailable unless a test stubs them. */
 @io.quarkus.test.InjectMock com.intermarche.backoffice.engine.fieldops.GoogleDirections directions;
 /**
  * Posts a tour command.
  *
  * @param token the caller
  * @param path the sub-path
  * @param body the command
  * @return the response
  */
 Response tour(String token,String path,Object body){var r=given().auth().oauth2(token).contentType("application/json").body(body).post("/shipping-tours"+path);if(r.statusCode()>=400)System.out.println(path+" "+r.asString());return r;}
 /**
  * Reads a tour endpoint.
  *
  * @param token the caller
  * @param path the sub-path with its query
  * @return the response
  */
 Response read(String token,String path){return given().auth().oauth2(token).get("/shipping-tours"+path);}
 /**
  * Carrier service and dock created for one test.
  *
  * @param carrier the carrier id
  * @param service the service id
  * @param dock the dock id
  * @param dockCode the dock code to scan
  * @param carrierName the carrier name, recipient of the shipments
  */
 record Setup(long carrier,long service,long dock,String dockCode,String carrierName){}
 /**
  * Creates a carrier, one service and one dock as a manager.
  *
  * @param maxUnits the capacity in units of the service, or null
  * @param maxWeightKg the weight capacity of the service, or null
  * @return the setup
  */
 Setup setup(Integer maxUnits,Double maxWeightKg){String suffix=key().substring(0,6).toUpperCase(Locale.ROOT);String name="Transports "+suffix;
  var carriers=tour(f.manager(),"/carriers",Map.of("storeId",f.store(),"requestKey",key(),"code","TR"+suffix,"name",name)).then().statusCode(200).extract().jsonPath();
  long carrier=carriers.getLong("carriers.find{it.code=='TR"+suffix+"'}.id");
  var service=new HashMap<String,Object>(Map.of("storeId",f.store(),"requestKey",key(),"carrierId",carrier,"code","J1","label","Messagerie J+1","cutoff","23:59"));if(maxUnits!=null)service.put("maxUnits",maxUnits);if(maxWeightKg!=null)service.put("maxWeightKg",maxWeightKg);
  long serviceId=tour(f.manager(),"/services",service).then().statusCode(200).extract().jsonPath().getLong("carriers.find{it.id=="+carrier+"}.services[0].id");
  long dock=tour(f.manager(),"/docks",Map.of("storeId",f.store(),"requestKey",key(),"code","Q"+suffix,"label","Quai "+suffix)).then().statusCode(200).extract().jsonPath().getLong("docks.find{it.code=='Q"+suffix+"'}.id");
  return new Setup(carrier,serviceId,dock,"Q"+suffix,name);}
 /**
  * Plans a tour leaving in one hour.
  *
  * @param token the caller
  * @param s the setup
  * @return the tour
  */
 Map<String,Object> plan(String token,Setup s){return tour(token,"",Map.of("storeId",f.store(),"requestKey",key(),"serviceId",s.service(),"dockId",s.dock(),"plannedDeparture",DateTimeProvider.now().plusMinutes(30).withSecond(0).withNano(0).toString(),"vehicleReference","ab-123-cd")).then().statusCode(200).extract().jsonPath().getMap("$");}
 /**
  * Builds a command on a tour at its current revision.
  *
  * @param t the tour
  * @return the command
  */
 Map<String,Object> on(Map<String,Object> t){return new HashMap<>(Map.of("storeId",f.store(),"tourId",t.get("id"),"expectedRevision",t.get("revision"),"requestKey",key()));}
 /**
  * Attaches departures to a tour.
  *
  * @param t the tour
  * @param departures the departure ids
  * @return the response
  */
 Response attachTo(Map<String,Object> t,Object... departures){var c=on(t);c.put("departureIds",List.of(departures));return tour(f.picker(),"/attach",c);}
 /**
  * Prepares a departure carrying the raw tote of the prepared order.
  *
  * @return the departure
  */
 Map<String,Object> toteDeparture(){prepared();buffer();long tote=QuarkusTransaction.requiringNew().call(()->PickedItem.<PickedItem>findById(f.item()).loadedTote.id);return draft(List.of(Map.of("toteId",tote)));}

 /**
  * Carriers, services and docks are maintained by managers, with unique codes and validated cut-offs and capacities.
  */
 @Test void setupIsManagedByManagersWithUniqueCodesAndValidatedServices(){var s=setup(null,null);
  var carrier=Map.of("storeId",f.store(),"requestKey",key(),"code","XX"+key().substring(0,4),"name","Refusé");tour(f.picker(),"/carriers",carrier).then().statusCode(403);
  String taken=read(f.manager(),"/setup?storeId="+f.store()).then().statusCode(200).extract().jsonPath().getString("carriers.find{it.id=="+s.carrier()+"}.code");
  tour(f.manager(),"/carriers",Map.of("storeId",f.store(),"requestKey",key(),"code",taken,"name","Doublon")).then().statusCode(409);
  tour(f.manager(),"/carriers",Map.of("storeId",f.store(),"requestKey",key(),"code","x","name","Code trop court")).then().statusCode(400);
  for(String cutoff:new String[]{"24:00","7:00",""})tour(f.manager(),"/services",Map.of("storeId",f.store(),"requestKey",key(),"carrierId",s.carrier(),"code","BAD","label","Heure invalide","cutoff",cutoff)).then().statusCode(400);
  for(int units:new int[]{0,1000})tour(f.manager(),"/services",Map.of("storeId",f.store(),"requestKey",key(),"carrierId",s.carrier(),"code","CAP","label","Capacité","cutoff","12:00","maxUnits",units)).then().statusCode(400);
  for(double kg:new double[]{0,100001,1.0005})tour(f.manager(),"/services",Map.of("storeId",f.store(),"requestKey",key(),"carrierId",s.carrier(),"code","KG","label","Poids","cutoff","12:00","maxWeightKg",kg)).then().statusCode(400);
  var rename=Map.of("storeId",f.store(),"requestKey",key(),"carrierId",s.carrier(),"expectedRevision",0,"code",taken,"name","Nouveau nom");tour(f.manager(),"/carriers",rename).then().statusCode(409);
  var ok=new HashMap<>(rename);ok.put("expectedRevision",1);ok.put("requestKey",key());
  tour(f.manager(),"/carriers",ok).then().statusCode(200);tour(f.manager(),"/carriers",ok).then().statusCode(200);
  assertEquals("Nouveau nom",read(f.picker(),"/setup?storeId="+f.store()).then().statusCode(200).extract().jsonPath().getString("carriers.find{it.id=="+s.carrier()+"}.name"));}

 /**
  * Planning requires a free active dock, an active service and a departure before its cut-off.
  */
 @Test void planningRespectsDockAvailabilityServiceCutoffAndActiveSetup(){var s=setup(null,null);var t=plan(f.picker(),s);assertEquals("OPEN",t.get("state"));assertEquals("AB-123-CD",t.get("vehicleReference"));
  var body=new HashMap<String,Object>(Map.of("storeId",f.store(),"serviceId",s.service(),"dockId",s.dock(),"plannedDeparture",DateTimeProvider.now().plusMinutes(30).toString()));
  body.put("requestKey",key());tour(f.picker(),"",body).then().statusCode(409);
  var other=setup(null,null);
  var late=new HashMap<>(body);late.put("requestKey",key());late.put("serviceId",other.service());late.put("dockId",other.dock());late.put("plannedDeparture",DateTimeProvider.now().plusDays(15).toString());tour(f.picker(),"",late).then().statusCode(400);
  late.put("requestKey",key());late.put("plannedDeparture",DateTimeProvider.now().minusHours(2).toString());tour(f.picker(),"",late).then().statusCode(400);
  late.remove("plannedDeparture");late.put("requestKey",key());tour(f.picker(),"",late).then().statusCode(400);
  tour(f.manager(),"/services",Map.of("storeId",f.store(),"requestKey",key(),"carrierId",other.carrier(),"serviceId",other.service(),"expectedRevision",1,"code","J1","label","Matin","cutoff","00:00")).then().statusCode(200);
  var afterCutoff=new HashMap<>(body);afterCutoff.put("requestKey",key());afterCutoff.put("serviceId",other.service());afterCutoff.put("dockId",other.dock());afterCutoff.put("plannedDeparture",DateTimeProvider.now().plusDays(1).withHour(10).toString());tour(f.picker(),"",afterCutoff).then().statusCode(400);
  tour(f.manager(),"/docks",Map.of("storeId",f.store(),"requestKey",key(),"dockId",s.dock(),"expectedRevision",1,"code",s.dockCode(),"label","Occupé","active",false)).then().statusCode(409);
  tour(f.manager(),"/docks",Map.of("storeId",f.store(),"requestKey",key(),"dockId",other.dock(),"expectedRevision",1,"code",other.dockCode(),"label","Fermé","active",false)).then().statusCode(200);
  var closed=new HashMap<>(body);closed.put("requestKey",key());closed.put("dockId",other.dock());tour(f.picker(),"",closed).then().statusCode(400);
  tour(f.manager(),"/carriers",Map.of("storeId",f.store(),"requestKey",key(),"carrierId",other.carrier(),"expectedRevision",1,"code",read(f.manager(),"/setup?storeId="+f.store()).then().extract().jsonPath().getString("carriers.find{it.id=="+other.carrier()+"}.code"),"name","Inactif","active",false)).then().statusCode(200);
  var inactive=new HashMap<>(body);inactive.put("requestKey",key());inactive.put("serviceId",other.service());tour(f.picker(),"",inactive).then().statusCode(400);
  read(f.picker(),"?storeId="+f.store()+"&state=OPEN").then().statusCode(200).body("items.find{it.id=="+t.get("id")+"}.dock.code",org.hamcrest.Matchers.is(s.dockCode()));
  read(f.picker(),"?storeId="+f.store()+"&state=LOST").then().statusCode(400);}

 /**
  * Sealing freezes the cargo and issues the documents; the scanned seal releases the tour in one handover, then empty totes come back.
  */
 @Test void sealedTourFreezesItsCargoIssuesDocumentsAndLeavesInOneHandover(){var s=setup(5,null);var d=toteDeparture();var t=plan(f.picker(),s);
  read(f.picker(),"/candidates?storeId="+f.store()).then().statusCode(200).body("find{it.id=="+d.get("id")+"}.units.size()",org.hamcrest.Matchers.is(1));
  var empty=on(t);empty.put("departureIds",List.of());tour(f.picker(),"/attach",empty).then().statusCode(400);
  var twice=on(t);twice.put("departureIds",List.of(d.get("id"),d.get("id")));tour(f.picker(),"/attach",twice).then().statusCode(400);
  var seal=on(t);seal.putAll(Map.of("scannedDock",s.dockCode(),"sealNumber","PL-0001"));tour(f.picker(),"/seal",seal).then().statusCode(409);
  t=attachTo(t,d.get("id")).then().statusCode(200).extract().jsonPath().getMap("$");attachTo(t,d.get("id")).then().statusCode(409);
  seal=on(t);seal.putAll(Map.of("scannedDock",s.dockCode(),"sealNumber","PL-0001"));tour(f.picker(),"/seal",seal).then().statusCode(409).body("message",org.hamcrest.Matchers.containsString("Chargement incomplet"));
  d=departure(f.picker(),"/"+d.get("id")+"?storeId="+f.store());load(d,0);
  t=read(f.picker(),"/"+t.get("id")+"?storeId="+f.store()).then().statusCode(200).body("loaded",org.hamcrest.Matchers.is(1)).extract().jsonPath().getMap("$");
  seal=on(t);seal.putAll(Map.of("scannedDock","AUTRE","sealNumber","PL-0001"));tour(f.picker(),"/seal",seal).then().statusCode(400);
  seal.put("requestKey",key());seal.put("scannedDock",s.dockCode().toLowerCase(Locale.ROOT));seal.put("sealNumber","p!");tour(f.picker(),"/seal",seal).then().statusCode(400);
  seal.put("requestKey",key());seal.put("sealNumber","pl-0001");t=tour(f.picker(),"/seal",seal).then().statusCode(200).body("state",org.hamcrest.Matchers.is("SEALED")).body("sealNumber",org.hamcrest.Matchers.is("PL-0001")).extract().jsonPath().getMap("$");
  tour(f.picker(),"/seal",seal).then().statusCode(200);
  List<Map<String,Object>> docs=(List<Map<String,Object>>)t.get("documents");assertEquals(Set.of("MANIFEST","DELIVERY_NOTE","CONSIGNMENT_NOTE"),docs.stream().map(x->x.get("kind")).collect(java.util.stream.Collectors.toSet()));
  long manifest=((Number)docs.stream().filter(x->"MANIFEST".equals(x.get("kind"))).findFirst().orElseThrow().get("id")).longValue();
  read(f.picker(),"/"+t.get("id")+"/documents/"+manifest+"?storeId="+f.store()).then().statusCode(200).body("content.tour.sealNumber",org.hamcrest.Matchers.is("PL-0001")).body("content.totals.units",org.hamcrest.Matchers.is(1)).body("content.orders[0].stop",org.hamcrest.Matchers.is(1)).body("content.carrier.name",org.hamcrest.Matchers.is(s.carrierName()));
  long note=((Number)docs.stream().filter(x->"DELIVERY_NOTE".equals(x.get("kind"))).findFirst().orElseThrow().get("id")).longValue();
  read(f.picker(),"/"+t.get("id")+"/documents/"+note+"?storeId="+f.store()).then().statusCode(200).body("content.lines.size()",org.hamcrest.Matchers.greaterThan(0));
  var unit=((List<Map<String,Object>>)d.get("units")).getFirst();d=departure(f.picker(),"/"+d.get("id")+"?storeId="+f.store());
  var unload=inputDeparture(d);unload.putAll(Map.of("unitId",unit.get("id"),"scannedUnit",unit.get("code"),"bufferId",999999L));departure(f.picker(),"/unload",unload).then().statusCode(409).body("code",org.hamcrest.Matchers.is("TOUR_SEALED"));
  var hand=inputDeparture(d);hand.putAll(Map.of("recipient","Chauffeur","unitIds",List.of(unit.get("id"))));departure(f.picker(),"/handover",hand).then().statusCode(409);
  var reopen=on(t);reopen.put("reason","Palette à recharger");tour(f.picker(),"/unseal",reopen).then().statusCode(403);
  var noReason=on(t);tour(f.manager(),"/unseal",noReason).then().statusCode(400);
  t=tour(f.manager(),"/unseal",reopen).then().statusCode(200).body("state",org.hamcrest.Matchers.is("OPEN")).extract().jsonPath().getMap("$");
  assertTrue(((List<Map<String,Object>>)t.get("documents")).stream().allMatch(x->"VOIDED".equals(x.get("state"))&&String.valueOf(x.get("voidedReason")).contains("PL-0001")));
  seal=on(t);seal.putAll(Map.of("scannedDock",s.dockCode(),"sealNumber","PL-0002"));t=tour(f.picker(),"/seal",seal).then().statusCode(200).extract().jsonPath().getMap("$");
  assertTrue(((List<Map<String,Object>>)t.get("documents")).stream().filter(x->"ACTIVE".equals(x.get("state"))).allMatch(x->String.valueOf(x.get("number")).endsWith("/2")));
  var depart=on(t);depart.put("scannedSeal","PL-0001");tour(f.picker(),"/depart",depart).then().statusCode(400);
  depart.put("requestKey",key());depart.put("scannedSeal","pl-0002");t=tour(f.picker(),"/depart",depart).then().statusCode(200).body("state",org.hamcrest.Matchers.is("DEPARTED")).body("emptyTotes.expected",org.hamcrest.Matchers.is(1)).extract().jsonPath().getMap("$");
  tour(f.picker(),"/depart",depart).then().statusCode(200);
  assertEquals("COMPLETED",departure(f.picker(),"/"+d.get("id")+"?storeId="+f.store()).get("state"));
  assertEquals(1L,QuarkusTransaction.requiringNew().call(()->Shipment.count("order.id=?1 and recipient=?2",customer,s.carrierName())));
  String toteCode=String.valueOf(unit.get("code"));
  var returns=on(t);returns.put("toteCodes",List.of(toteCode,"INCONNU"));var r=tour(f.picker(),"/returns",returns).then().statusCode(200).extract().jsonPath();
  assertEquals(List.of(toteCode),r.getList("returned"));assertEquals(List.of("INCONNU"),r.getList("unknown"));assertEquals(0,r.getInt("tour.emptyTotes.outstanding.size()"));
  assertEquals(LoadedTote.Status.DELIVERED,QuarkusTransaction.requiringNew().call(()->PickedItem.<PickedItem>findById(f.item()).loadedTote.status));
  var again=on(t);again.put("toteCodes",List.of(toteCode));assertEquals(List.of(toteCode),tour(f.picker(),"/returns",again).then().statusCode(200).extract().jsonPath().getList("alreadyReturned"));
  var none=on(t);none.put("toteCodes",List.of());tour(f.picker(),"/returns",none).then().statusCode(400);
  t=read(f.picker(),"/"+t.get("id")+"?storeId="+f.store()).then().statusCode(200).extract().jsonPath().getMap("$");
  var cancel=on(t);cancel.put("reason","Trop tard");tour(f.picker(),"/cancel",cancel).then().statusCode(409).body("code",org.hamcrest.Matchers.is("TOUR_CLOSED"));}

 /**
  * Reads a departure.
  *
  * @param token the caller
  * @param path the sub-path
  * @return the departure
  */
 Map<String,Object> departure(String token,String path){return given().auth().oauth2(token).get("/departures"+path).then().statusCode(200).extract().jsonPath().getMap("$");}

 /**
  * Capacity, custody, detaching and cancelling keep the departures consistent.
  */
 @Test void capacityOwnershipDetachAndCancellationKeepDeparturesConsistent(){var p=closedParcel(1);buffer();long tote=QuarkusTransaction.requiringNew().call(()->PickedItem.<PickedItem>findById(f.item()).loadedTote.id);
  var d=draft(List.of(Map.of("parcelId",p.get("id")),Map.of("toteId",tote)));
  var units=plan(f.picker(),setup(1,null));attachTo(units,d.get("id")).then().statusCode(409).body("code",org.hamcrest.Matchers.is("TOUR_FULL"));
  var weight=plan(f.picker(),setup(null,1.0));attachTo(weight,d.get("id")).then().statusCode(409).body("message",org.hamcrest.Matchers.containsString("poids"));
  var s=setup(5,10.0);var t=plan(f.picker(),s);var colleague=recipient();
  var foreign=on(t);foreign.put("departureIds",List.of(d.get("id")));tour(colleague.token(),"/attach",foreign).then().statusCode(403);
  t=attachTo(t,d.get("id")).then().statusCode(200).body("weightKg",org.hamcrest.Matchers.is(1.25f)).body("weightComplete",org.hamcrest.Matchers.is(false)).extract().jsonPath().getMap("$");
  assertEquals(t.get("code"),departure(f.picker(),"/"+d.get("id")+"?storeId="+f.store()).get("tourCode"));
  d=departure(f.picker(),"/"+d.get("id")+"?storeId="+f.store());load(d,0);t=read(f.picker(),"/"+t.get("id")+"?storeId="+f.store()).then().extract().jsonPath().getMap("$");
  var detach=on(t);detach.put("departureId",d.get("id"));tour(f.picker(),"/detach",detach).then().statusCode(409).body("code",org.hamcrest.Matchers.is("CARGO_UNLOAD_REQUIRED"));
  var foreignCancel=on(t);foreignCancel.put("reason","Pas mon camion");tour(colleague.token(),"/cancel",foreignCancel).then().statusCode(403);
  var cancel=on(t);tour(f.picker(),"/cancel",cancel).then().statusCode(400);cancel.put("requestKey",key());cancel.put("reason","Camion en panne");
  tour(f.picker(),"/cancel",cancel).then().statusCode(200).body("state",org.hamcrest.Matchers.is("CANCELLED")).body("attached.size()",org.hamcrest.Matchers.is(0));
  var after=departure(f.picker(),"/"+d.get("id")+"?storeId="+f.store());assertNull(after.get("tourId"));assertEquals("LOADING",after.get("state"));
  var free=plan(f.picker(),s);assertEquals("OPEN",free.get("state"));
  var noTour=on(Map.of("id",free.get("id"),"revision",free.get("revision")));noTour.put("departureId",d.get("id"));tour(f.picker(),"/detach",noTour).then().statusCode(404);
  free=attachTo(free,d.get("id")).then().statusCode(200).extract().jsonPath().getMap("$");
  free=tour(f.picker(),"/sequence",on(free)).then().statusCode(200).body("attached[0].stopSequence",org.hamcrest.Matchers.is(1)).extract().jsonPath().getMap("$");
  var depart=on(free);depart.put("scannedSeal","X");tour(f.picker(),"/depart",depart).then().statusCode(409).body("code",org.hamcrest.Matchers.is("TOUR_NOT_SEALED"));
  d=departure(f.picker(),"/"+d.get("id")+"?storeId="+f.store());
  var u=((List<Map<String,Object>>)d.get("units")).getFirst();var unload=inputDeparture(d);unload.putAll(Map.of("unitId",u.get("id"),"scannedUnit",u.get("code"),"bufferId",buffer()));d=given().auth().oauth2(f.picker()).contentType("application/json").body(unload).post("/departures/unload").then().statusCode(200).extract().jsonPath().getMap("$");
  var cancelDeparture=inputDeparture(d);cancelDeparture.put("reason","Annulation");given().auth().oauth2(f.picker()).contentType("application/json").body(cancelDeparture).post("/departures/cancel").then().statusCode(200).body("tourId",org.hamcrest.Matchers.nullValue());
  read(f.picker(),"/"+free.get("id")+"?storeId="+f.store()).then().statusCode(200).body("attached.size()",org.hamcrest.Matchers.is(0));
  long cancelled=((Number)d.get("id")).longValue();assertNull(QuarkusTransaction.requiringNew().call(()->com.intermarche.backoffice.domain.shipping.LogisticsDeparture.<com.intermarche.backoffice.domain.shipping.LogisticsDeparture>findById(cancelled).stopSequence));}

 /**
  * Places the customer of the prepared order at a position, or removes its position, and locates the store.
  *
  * @param lat the latitude, or null to forget the position
  * @param lon the longitude
  */
 void locate(Double lat,Double lon){QuarkusTransaction.requiringNew().run(()->{Order o=Order.findById(customer);Customer c=o.customer;
  if(c==null){c=new Customer();c.customerId="CLI-"+key().substring(0,8);c.firstName="Zoé";c.lastName="Tournée";c.email=c.customerId.toLowerCase(Locale.ROOT)+"@test.invalid";c.persist();o.customer=c;}
  if(c.address==null){c.address=new Adresse();c.address.streetLine1="3 Place Bellecour";c.address.postalCode="69002";c.address.city="Lyon";}
  c.addressPosition=lat==null?null:new GPSPosition(lat,lon);c.addressPositionSource=lat==null?null:Customer.POSITION_PROVIDED;
  Store s=Store.findById(f.store());for(Building b:s.buildings)b.gpsPosition=new GPSPosition(45.7615,4.856);});}

 /**
  * Proposals group waiting departures within the capacity of a service; applying one plans the tour, attaches its
  * departures and orders its stops at once; the order can then be optimised or set by hand while the tour is open.
  */
 @Test void proposalsComposeAndSequenceSetTheDeliveryOrder(){var p=closedParcel(1);buffer();long tote=QuarkusTransaction.requiringNew().call(()->PickedItem.<PickedItem>findById(f.item()).loadedTote.id);
  var d=draft(List.of(Map.of("parcelId",p.get("id")),Map.of("toteId",tote)));Object id=d.get("id");locate(45.7579,4.833);
  var small=setup(1,null);
  read(f.picker(),"/proposals?storeId="+f.store()+"&serviceId="+small.service()).then().statusCode(200).body("originLocated",org.hamcrest.Matchers.is(true)).body("collectionAt",org.hamcrest.Matchers.notNullValue()).body("origin.lat",org.hamcrest.Matchers.is(45.7615f))
   .body("oversize.find{it.id=="+id+"}.reason",org.hamcrest.Matchers.containsString("supports")).body("tours.findAll{it.departureIds.contains("+id+")}.size()",org.hamcrest.Matchers.is(0))
   .body("freeDocks.find{it.id=="+small.dock()+"}.code",org.hamcrest.Matchers.is(small.dockCode()));
  var light=setup(null,1.0);
  read(f.picker(),"/proposals?storeId="+f.store()+"&serviceId="+light.service()).then().statusCode(200).body("oversize.find{it.id=="+id+"}.reason",org.hamcrest.Matchers.containsString("poids"));
  read(f.picker(),"/proposals?storeId="+f.store()+"&serviceId=999999").then().statusCode(404);
  read(f.picker(),"/map-config").then().statusCode(200).body("googleMapsApiKey",org.hamcrest.Matchers.instanceOf(String.class));
  given().get("/shipping-tours/map-config").then().statusCode(401);
  tour(f.manager(),"/services",Map.of("storeId",f.store(),"requestKey",key(),"carrierId",light.carrier(),"serviceId",light.service(),"expectedRevision",1,"code","J1","label","Fermé","cutoff","23:59","active",false)).then().statusCode(200);
  read(f.picker(),"/proposals?storeId="+f.store()+"&serviceId="+light.service()).then().statusCode(400);
  var both=setup(5,1.0);
  read(f.picker(),"/proposals?storeId="+f.store()+"&serviceId="+both.service()).then().statusCode(200).body("oversize.find{it.id=="+id+"}.reason",org.hamcrest.Matchers.containsString("poids"));
  String bothCode=read(f.manager(),"/setup?storeId="+f.store()).then().extract().jsonPath().getString("carriers.find{it.id=="+both.carrier()+"}.code");
  tour(f.manager(),"/carriers",Map.of("storeId",f.store(),"requestKey",key(),"carrierId",both.carrier(),"expectedRevision",1,"code",bothCode,"name","Inactif","active",false)).then().statusCode(200);
  read(f.picker(),"/proposals?storeId="+f.store()+"&serviceId="+both.service()).then().statusCode(400);
  var s=setup(5,null);
  var proposal=read(f.picker(),"/proposals?storeId="+f.store()+"&serviceId="+s.service()).then().statusCode(200).extract().jsonPath();
  String row="tours.find{it.departureIds.contains("+id+")}";
  assertEquals(2,proposal.getInt(row+".stops.find{it.departures.find{d->d.id=="+id+"}}.departures[0].units"));
  assertEquals(true,proposal.getBoolean(row+".stops.find{it.departures.find{d->d.id=="+id+"}}.located"));
  assertNotNull(proposal.get(row+".stops.find{it.departures.find{d->d.id=="+id+"}}.estimatedArrival"));
  assertTrue(proposal.getDouble(row+".distanceKm")>0);
  var compose=new HashMap<String,Object>(Map.of("storeId",f.store(),"requestKey",key(),"serviceId",s.service(),"dockId",s.dock(),"plannedDeparture",DateTimeProvider.now().plusMinutes(30).withSecond(0).withNano(0).toString(),"departureIds",List.of(id)));
  Map<String,Object> t=tour(f.picker(),"/compose",compose).then().statusCode(200).body("state",org.hamcrest.Matchers.is("OPEN")).body("attached[0].stopSequence",org.hamcrest.Matchers.is(1))
   .body("route.sequenced",org.hamcrest.Matchers.is(true)).body("route.stops[0].located",org.hamcrest.Matchers.is(true)).body("route.stops[0].positionSource",org.hamcrest.Matchers.is("PROVIDED")).body("route.stops[0].position.lat",org.hamcrest.Matchers.is(45.7579f)).body("route.origin.lon",org.hamcrest.Matchers.is(4.856f))
   .body("route.distanceKm",org.hamcrest.Matchers.greaterThan(0f)).extract().jsonPath().getMap("$");
  tour(f.picker(),"/compose",compose).then().statusCode(200).body("id",org.hamcrest.Matchers.is(t.get("id")));
  var other=setup(5,null);var taken=new HashMap<>(compose);taken.putAll(Map.of("requestKey",key(),"serviceId",other.service(),"dockId",other.dock()));
  tour(f.picker(),"/compose",taken).then().statusCode(409).body("code",org.hamcrest.Matchers.is("DEPARTURE_ALREADY_IN_TOUR"));
  read(f.picker(),"/setup?storeId="+f.store()).then().statusCode(200).body("docks.find{it.id=="+other.dock()+"}.busyWith",org.hamcrest.Matchers.nullValue());
  var twice=on(t);twice.put("departureIds",List.of(id,id));tour(f.picker(),"/sequence",twice).then().statusCode(400);
  var foreign=on(t);foreign.put("departureIds",List.of(-1));tour(f.picker(),"/sequence",foreign).then().statusCode(400);
  var stale=on(t);stale.put("expectedRevision",0);tour(f.picker(),"/sequence",stale).then().statusCode(409);
  var manual=on(t);manual.put("departureIds",List.of(id));long revision=((Number)t.get("revision")).longValue();
  t=tour(f.picker(),"/sequence",manual).then().statusCode(200).body("revision",org.hamcrest.Matchers.is((int)revision+1)).extract().jsonPath().getMap("$");
  tour(f.picker(),"/sequence",manual).then().statusCode(200).body("revision",org.hamcrest.Matchers.is((int)revision+1));
  var detach=on(t);detach.put("departureId",id);t=tour(f.picker(),"/detach",detach).then().statusCode(200).body("route.sequenced",org.hamcrest.Matchers.is(false)).body("route.stops.size()",org.hamcrest.Matchers.is(0)).extract().jsonPath().getMap("$");
  tour(f.picker(),"/sequence",on(t)).then().statusCode(409).body("code",org.hamcrest.Matchers.is("TOUR_EMPTY"));
  t=attachTo(t,id).then().statusCode(200).body("attached[0].stopSequence",org.hamcrest.Matchers.nullValue()).body("route.sequenced",org.hamcrest.Matchers.is(false)).extract().jsonPath().getMap("$");
  t=tour(f.picker(),"/sequence",on(t)).then().statusCode(200).body("attached[0].stopSequence",org.hamcrest.Matchers.is(1)).body("route.sequenced",org.hamcrest.Matchers.is(true)).extract().jsonPath().getMap("$");
  locate(null,null);
  t=read(f.picker(),"/"+t.get("id")+"?storeId="+f.store()).then().statusCode(200).body("route.stops[0].located",org.hamcrest.Matchers.is(false)).body("route.unlocated",org.hamcrest.Matchers.is(1))
   .body("route.stops[0].distanceKm",org.hamcrest.Matchers.nullValue()).body("route.stops[0].positionSource",org.hamcrest.Matchers.nullValue()).body("route.stops[0].position",org.hamcrest.Matchers.nullValue()).extract().jsonPath().getMap("$");
  locate(45.7579,4.833);Long orderId=customer;Long buyer=QuarkusTransaction.requiringNew().call(()->{Order o=Order.findById(orderId);Long c=o.customer.id;o.customer=null;return c;});
  read(f.picker(),"/"+t.get("id")+"?storeId="+f.store()).then().statusCode(200).body("route.stops[0].located",org.hamcrest.Matchers.is(false)).body("route.stops[0].consignee.name",org.hamcrest.Matchers.is("Client test")).body("route.stops[0].positionSource",org.hamcrest.Matchers.nullValue());
  QuarkusTransaction.requiringNew().run(()->{Order o=Order.findById(orderId);o.customer=Customer.findById(buyer);});
  var cancel=on(t);cancel.put("reason","Fin du test");t=tour(f.picker(),"/cancel",cancel).then().statusCode(200).extract().jsonPath().getMap("$");
  tour(f.picker(),"/sequence",on(t)).then().statusCode(409).body("code",org.hamcrest.Matchers.is("TOUR_CLOSED"));
  long departureId=((Number)id).longValue();assertNull(QuarkusTransaction.requiringNew().call(()->com.intermarche.backoffice.domain.shipping.LogisticsDeparture.<com.intermarche.backoffice.domain.shipping.LogisticsDeparture>findById(departureId).stopSequence));}

 /**
  * Google Maps orders the stops by road when asked, its itinerary is kept for the delivery order and used for the
  * distances, times and path, until the order or a position changes; a refusal leaves the tour unchanged.
  */
 @Test void googleItineraryIsKeptForTheDeliveryOrderUntilItChanges(){
  var d=toteDeparture();Object id=d.get("id");locate(45.7579,4.833);var s=setup(5,null);
  var road=new com.intermarche.backoffice.engine.fieldops.GoogleDirections.Road(List.of(0),List.of(3.4),List.of(720L),List.of(new double[]{45.7615,4.856},new double[]{45.7579,4.833}));
  org.mockito.Mockito.doReturn(true).when(directions).available();
  org.mockito.Mockito.doThrow(new com.intermarche.backoffice.engine.fieldops.GoogleDirections.Unavailable("Quota Google Maps dépassé : réessayez plus tard.")).when(directions).route(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(true));
  String row="tours.find{it.departureIds.contains("+id+")}";
  read(f.picker(),"/proposals?storeId="+f.store()+"&serviceId="+s.service()).then().statusCode(200).body("googleAvailable",org.hamcrest.Matchers.is(true)).body(row+".source",org.hamcrest.Matchers.is("ESTIMATE")).body(row+".routingNote",org.hamcrest.Matchers.containsString("Quota")).body(row+".path",org.hamcrest.Matchers.nullValue());
  org.mockito.Mockito.doReturn(road).when(directions).route(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyBoolean());
  read(f.picker(),"/proposals?storeId="+f.store()+"&serviceId="+s.service()).then().statusCode(200).body(row+".source",org.hamcrest.Matchers.is("GOOGLE")).body(row+".distanceKm",org.hamcrest.Matchers.is(3.4f)).body(row+".path.size()",org.hamcrest.Matchers.is(2)).body(row+".routingNote",org.hamcrest.Matchers.nullValue());
  var planned=DateTimeProvider.now().plusMinutes(30).withSecond(0).withNano(0);
  var compose=new HashMap<String,Object>(Map.of("storeId",f.store(),"requestKey",key(),"serviceId",s.service(),"dockId",s.dock(),"plannedDeparture",planned.toString(),"departureIds",List.of(id)));
  Map<String,Object> t=tour(f.picker(),"/compose",compose).then().statusCode(200).body("route.source",org.hamcrest.Matchers.is("GOOGLE")).body("route.distanceKm",org.hamcrest.Matchers.is(3.4f)).body("route.path[1][0]",org.hamcrest.Matchers.is(45.7579f))
   .body("route.stops[0].estimatedArrival",org.hamcrest.Matchers.startsWith(planned.plusMinutes(12).toString().substring(0,16))).body("route.googleAvailable",org.hamcrest.Matchers.is(true)).extract().jsonPath().getMap("$");
  org.mockito.Mockito.verify(directions,org.mockito.Mockito.atLeastOnce()).route(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(false));
  var google=on(t);google.put("engine","GOOGLE");
  t=tour(f.picker(),"/sequence",google).then().statusCode(200).body("route.source",org.hamcrest.Matchers.is("GOOGLE")).body("attached[0].stopSequence",org.hamcrest.Matchers.is(1)).extract().jsonPath().getMap("$");
  org.mockito.Mockito.doThrow(new com.intermarche.backoffice.engine.fieldops.GoogleDirections.Unavailable("Quota Google Maps dépassé : réessayez plus tard.")).when(directions).route(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyBoolean());
  var refused=on(t);refused.put("engine","GOOGLE");tour(f.picker(),"/sequence",refused).then().statusCode(409).body("code",org.hamcrest.Matchers.is("ROAD_ROUTING_UNAVAILABLE")).body("message",org.hamcrest.Matchers.containsString("Quota"));
  read(f.picker(),"/"+t.get("id")+"?storeId="+f.store()).then().statusCode(200).body("revision",org.hamcrest.Matchers.is(t.get("revision"))).body("route.source",org.hamcrest.Matchers.is("GOOGLE"));
  locate(45.76,4.84);
  read(f.picker(),"/"+t.get("id")+"?storeId="+f.store()).then().statusCode(200).body("route.source",org.hamcrest.Matchers.is("ESTIMATE")).body("route.path",org.hamcrest.Matchers.nullValue());
  t=tour(f.picker(),"/sequence",on(t)).then().statusCode(200).body("route.source",org.hamcrest.Matchers.is("ESTIMATE")).extract().jsonPath().getMap("$");
  locate(null,null);org.mockito.Mockito.clearInvocations(directions);
  tour(f.picker(),"/sequence",on(t)).then().statusCode(200).body("route.source",org.hamcrest.Matchers.is("ESTIMATE"));
  org.mockito.Mockito.verify(directions,org.mockito.Mockito.never()).route(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyBoolean());}
}
