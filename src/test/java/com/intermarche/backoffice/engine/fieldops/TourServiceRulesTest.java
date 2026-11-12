package com.intermarche.backoffice.engine.fieldops;

import static org.junit.jupiter.api.Assertions.*;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.GPSPosition;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.shipping.LogisticsDeparture;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.WarehouseBuilding;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Rules of carrier tours that need no database: collection time, numbering, positions and delivery stops. */
class TourServiceRulesTest {

 /**
  * Builds an address.
  *
  * @param street the street line, or null for an empty address
  * @return the address
  */
 static Adresse address(String street){var a=new Adresse();a.streetLine1=street;a.postalCode=street==null?null:"69002";a.city=street==null?null:"Lyon";return a;}

 /**
  * Builds a departure of an order delivered to a customer.
  *
  * @param id the departure id
  * @param customer the customer, or null
  * @return the departure
  */
 static LogisticsDeparture departure(long id,Customer customer){var d=new LogisticsDeparture();d.id=id;d.order=new Order();d.order.customer=customer;return d;}

 /**
  * Builds a customer living at an address.
  *
  * @param street the street line, or null when the customer has no address
  * @return the customer
  */
 static Customer customer(String street){var c=new Customer();c.address=street==null?null:address(street);return c;}

 /**
  * The next collection is today at the cut-off while it is ahead, otherwise tomorrow.
  */
 @Test void nextCollectionIsTodayBeforeTheCutoffAndTomorrowAfter(){
  var morning=LocalDateTime.of(2026,10,29,9,15);
  assertEquals(LocalDateTime.of(2026,10,29,17,30),TourService.nextCollection("17:30",morning));
  assertEquals(LocalDateTime.of(2026,10,30,9,0),TourService.nextCollection("09:00",morning));
  assertEquals(LocalDateTime.of(2026,10,30,9,15),TourService.nextCollection("09:15",morning));}

 /**
  * Departures are numbered from 1 in delivery order.
  */
 @Test void numberingFollowsTheDeliveryOrder(){
  var a=departure(1,null);var b=departure(2,null);a.stopSequence=7;
  TourService.number(List.of(b,a));
  assertEquals(1,b.stopSequence);assertEquals(2,a.stopSequence);
  TourService.number(new ArrayList<>());}

 /**
  * A stored position is a point only with both coordinates.
  */
 @Test void storedPositionNeedsBothCoordinates(){
  assertNull(TourService.point(null));
  assertNull(TourService.point(new GPSPosition(null,4.8)));
  assertNull(TourService.point(new GPSPosition(45.7,null)));
  assertEquals(new TourPlanning.Point(45.7,4.8),TourService.point(new GPSPosition(45.7,4.8)));}

 /**
  * The map configuration gives the configured key, trimmed, and an empty key when none is configured.
  */
 @Test void mapConfigGivesTheConfiguredKeyOrNothing(){
  var resource=new TourResource();
  assertEquals(Map.of("googleMapsApiKey",""),resource.mapConfig());
  resource.mapKey=java.util.Optional.empty();assertEquals(Map.of("googleMapsApiKey",""),resource.mapConfig());
  resource.mapKey=java.util.Optional.of(" browser-key ");assertEquals(Map.of("googleMapsApiKey","browser-key"),resource.mapConfig());}

 /**
  * A point is shown on the map by its latitude and longitude; an unknown point shows nothing.
  */
 @Test void pointViewGivesLatitudeAndLongitude(){
  assertNull(TourService.pointView(null));
  assertEquals(Map.of("lat",45.7,"lon",4.8),TourService.pointView(new TourPlanning.Point(45.7,4.8)));}

 /**
  * Vehicles leave from the store building; another located building only replaces it when it has no position.
  */
 @Test void originPrefersTheLocatedStoreBuilding(){
  var store=new Store();
  var shop=new StoreBuilding();shop.id=1L;var depot=new WarehouseBuilding();depot.id=2L;depot.gpsPosition=new GPSPosition(45.70,4.90);
  store.buildings=new LinkedHashSet<Building>(List.of(depot,shop));
  assertEquals(new TourPlanning.Point(45.70,4.90),TourService.origin(store));
  shop.gpsPosition=new GPSPosition(45.76,4.85);
  assertEquals(new TourPlanning.Point(45.76,4.85),TourService.origin(store));
  depot.gpsPosition=null;shop.gpsPosition=new GPSPosition(null,null);
  assertNull(TourService.origin(store));}

 /**
  * A known position is used as is; an unknown one is looked up only with a session, and kept when found.
  */
 @Test void positionIsLookedUpOnlyWhenUnknown(){
  var known=customer("3 Place Bellecour");known.addressPosition=new GPSPosition(45.75,4.83);
  assertEquals(new TourPlanning.Point(45.75,4.83),TourService.position(known,null));
  var unknown=customer("3 Place Bellecour");
  assertNull(TourService.position(unknown,null));
  var geocoder=new AddressGeocoder(){@Override public Session session(){return new Session(){@Override public GPSPosition locate(Adresse a){return a.streetLine1.startsWith("3")?new GPSPosition(45.7579,4.833):null;}};}};
  var session=geocoder.session();
  assertNull(TourService.position(customer("9 Rue Inconnue"),session));
  assertEquals(new TourPlanning.Point(45.7579,4.833),TourService.position(unknown,session));
  assertEquals(Customer.POSITION_GEOCODED,unknown.addressPositionSource);assertEquals(45.7579,unknown.addressPosition.latitude);}

 /**
  * Departures delivered at the same printed address share a stop number; an unknown address is its own stop.
  */
 @Test void stopsAreNumberedByAddressInDeliveryOrder(){
  var first=departure(10,customer("3 Place  Bellecour"));var other=departure(11,customer("8 Rue de la République"));
  var same=departure(12,customer("3 PLACE BELLECOUR"));var nobody=departure(13,null);var noAddress=departure(14,customer(null));var blank=departure(15,customer(" "));
  Map<Long,Integer> stops=TourDocuments.stops(List.of(first,other,same,nobody,noAddress,blank));
  assertEquals(Map.of(10L,1,11L,2,12L,1,13L,3,14L,4,15L,5),stops);
  assertEquals("departure:13",TourDocuments.addressKey(null,13));
  assertEquals("3 place bellecour|69002 lyon",TourDocuments.addressKey(address("3 Place  Bellecour"),1));}
}
