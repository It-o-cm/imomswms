package com.intermarche.backoffice.engine.fieldops;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.Adresse;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Lookup of address positions in the Base Adresse Nationale, without reaching the real service. */
class AddressGeocoderTest {
 final ObjectMapper json=new ObjectMapper();

 /**
  * Builds an address.
  *
  * @param street the street line
  * @param postalCode the postal code
  * @param city the city
  * @param country the country
  * @return the address
  */
 static Adresse address(String street,String postalCode,String city,String country){var a=new Adresse();a.streetLine1=street;a.postalCode=postalCode;a.city=city;a.country=country;return a;}

 /**
  * Builds a geocoder calling the given URL.
  *
  * @param url the search URL
  * @return the geocoder, enabled
  */
 AddressGeocoder geocoder(String url){var g=new AddressGeocoder();g.json=json;g.enabled=true;g.url=url;g.timeoutMs=2000;return g;}

 /**
  * Only French addresses with a street and a city or postal code are looked up.
  */
 @Test void queryNeedsAFrenchStreetAndPlace(){
  assertNull(AddressGeocoder.query(null));
  assertNull(AddressGeocoder.query(address(" ","69002","Lyon",null)));
  assertNull(AddressGeocoder.query(address(null,"69002","Lyon",null)));
  assertNull(AddressGeocoder.query(address("3 Place Bellecour",null," ",null)));
  assertNull(AddressGeocoder.query(address("3 Place Bellecour","69002","Lyon","Italie")));
  assertEquals("3 Place Bellecour 69002",AddressGeocoder.query(address("3 Place Bellecour"," 69002 ",null,null)));
  assertEquals("3 Place Bellecour Lyon",AddressGeocoder.query(address("3 Place Bellecour",null,"Lyon"," ")));
  assertEquals("3 Place Bellecour 69002 Lyon",AddressGeocoder.query(address("3 Place Bellecour","69002","Lyon","fr")));
  assertEquals("3 Place Bellecour 69002 Lyon",AddressGeocoder.query(address("3 Place Bellecour","69002","Lyon","France")));}

 /**
  * The first feature is read as longitude then latitude, and only when it scores enough.
  *
  * @throws Exception when the JSON cannot be read
  */
 @Test void parseKeepsTheFirstConfidentFeature() throws Exception{
  var found=AddressGeocoder.parse(json.readTree("{\"features\":[{\"geometry\":{\"coordinates\":[4.833,45.7579]},\"properties\":{\"score\":0.93}}]}"));
  assertEquals(45.7579,found.latitude);assertEquals(4.833,found.longitude);
  assertNotNull(AddressGeocoder.parse(json.readTree("{\"features\":[{\"geometry\":{\"coordinates\":[4.8,45.7]},\"properties\":{\"score\":0.5}}]}")));
  assertNull(AddressGeocoder.parse(null));
  assertNull(AddressGeocoder.parse(json.readTree("{\"features\":[]}")));
  assertNull(AddressGeocoder.parse(json.readTree("{}")));
  assertNull(AddressGeocoder.parse(json.readTree("{\"features\":[{\"geometry\":{\"coordinates\":[4.8]},\"properties\":{\"score\":0.9}}]}")));
  assertNull(AddressGeocoder.parse(json.readTree("{\"features\":[{\"geometry\":{\"coordinates\":[\"4.8\",45.7]},\"properties\":{\"score\":0.9}}]}")));
  assertNull(AddressGeocoder.parse(json.readTree("{\"features\":[{\"geometry\":{\"coordinates\":[4.8,45.7]},\"properties\":{\"score\":0.49}}]}")));
  assertNull(AddressGeocoder.parse(json.readTree("{\"features\":[{\"geometry\":{\"coordinates\":[4.8,45.7]},\"properties\":{}}]}")));}

 /**
  * A disabled geocoder never calls the service.
  */
 @Test void disabledGeocoderLocatesNothing(){var g=new AddressGeocoder();g.json=json;var session=g.session();
  assertTrue(session.down());assertNull(session.locate(address("3 Place Bellecour","69002","Lyon",null)));}

 /**
  * A located address is read from the answer of the service, with its postal code as filter; an address that
  * cannot be queried is not sent.
  *
  * @throws Exception when the local server cannot start
  */
 @Test void locatesThroughTheServiceAndFiltersByPostalCode() throws Exception{
  var queries=new ArrayList<String>();var server=serve(200,"{\"features\":[{\"geometry\":{\"coordinates\":[4.833,45.7579]},\"properties\":{\"score\":0.9}}]}",queries);
  try{var session=geocoder("http://127.0.0.1:"+server.getAddress().getPort()+"/search/").session();
   var found=session.locate(address("3 Place Bellecour","69002","Lyon",null));
   assertEquals(45.7579,found.latitude);assertFalse(session.down());
   assertTrue(queries.getFirst().contains("q=3+Place+Bellecour+69002+Lyon"));assertTrue(queries.getFirst().contains("&postcode=69002"));
   assertNotNull(session.locate(address("3 Place Bellecour",null,"Lyon",null)));assertFalse(queries.get(1).contains("postcode"));
   assertNull(session.locate(address("Via Roma","00100","Roma","Italia")));assertEquals(2,queries.size());}
  finally{server.stop(0);}}

 /**
  * A failing answer marks the service down for the rest of the session: no further call is made.
  *
  * @throws Exception when the local server cannot start
  */
 @Test void failingServiceIsCalledOncePerSession() throws Exception{
  var queries=new ArrayList<String>();var server=serve(503,"busy",queries);
  try{var session=geocoder("http://127.0.0.1:"+server.getAddress().getPort()+"/search/").session();
   assertNull(session.locate(address("3 Place Bellecour","69002","Lyon",null)));assertTrue(session.down());
   assertNull(session.locate(address("8 Rue de la République","69002","Lyon",null)));assertEquals(1,queries.size());}
  finally{server.stop(0);}}

 /**
  * An unreachable service or an unreadable answer leaves the address unlocated and stops the session.
  *
  * @throws Exception when the local server cannot start
  */
 @Test void unreachableOrUnreadableServiceLocatesNothing() throws Exception{
  var closed=new java.net.ServerSocket(0);int port=closed.getLocalPort();closed.close();
  var session=geocoder("http://127.0.0.1:"+port+"/search/").session();
  assertNull(session.locate(address("3 Place Bellecour","69002","Lyon",null)));assertTrue(session.down());
  var server=serve(200,"not json",new ArrayList<>());
  try{var broken=geocoder("http://127.0.0.1:"+server.getAddress().getPort()+"/search/").session();
   assertNull(broken.locate(address("3 Place Bellecour","69002","Lyon",null)));assertTrue(broken.down());}
  finally{server.stop(0);}}

 /**
  * An interrupted lookup restores the interrupt flag and stops the session.
  *
  * @throws Exception when the local server cannot start
  */
 @Test void interruptedLookupKeepsTheInterruptFlag() throws Exception{
  var server=serve(200,"{\"features\":[]}",new ArrayList<>());
  try{var session=geocoder("http://127.0.0.1:"+server.getAddress().getPort()+"/search/").session();
   Thread.currentThread().interrupt();
   assertNull(session.locate(address("3 Place Bellecour","69002","Lyon",null)));
   assertTrue(Thread.interrupted());assertTrue(session.down());}
  finally{server.stop(0);}}

 /**
  * Starts a local HTTP server answering every request the same way.
  *
  * @param status the HTTP status
  * @param body the body
  * @param queries receives the query string of each request
  * @return the started server
  * @throws Exception when it cannot start
  */
 static HttpServer serve(int status,String body,List<String> queries) throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/search/",exchange->{queries.add(exchange.getRequestURI().getRawQuery());var bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});
  server.start();return server;}
}
