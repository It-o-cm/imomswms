package com.intermarche.backoffice.engine.fieldops;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.engine.fieldops.TourPlanning.Point;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Road itineraries asked from the Google Maps Directions web service, without reaching Google. */
class GoogleDirectionsTest {
 final ObjectMapper json=new ObjectMapper();
 static final Point STORE=new Point(45.7615,4.856),A=new Point(45.75,4.83),B=new Point(45.77,4.82),C=new Point(45.74,4.91);
 /** The example polyline of the Google documentation and its three points. */
 static final String POLYLINE="_p~iF~ps|U_ulLnnqC_mqNvxq`@";

 /**
  * Builds a geocoder-free directions client calling the given URL.
  *
  * @param url the service URL
  * @param key the configured key
  * @return the client
  */
 GoogleDirections directions(String url,String key){var d=new GoogleDirections();d.json=json;d.url=url;d.key=Optional.ofNullable(key);d.timeoutMs=2000;return d;}

 /**
  * Builds a Directions answer.
  *
  * @param legs the number of legs
  * @param order the waypoint order, or null
  * @return the JSON
  */
 static String answer(int legs,List<Integer> order){
  var leg="{\"distanceMeters\":%d,\"duration\":\"%ds\",\"polyline\":{\"encodedPolyline\":\"%s\"}}";
  var all=IntStream.range(0,legs).mapToObj(i->String.format(leg,1500*(i+1),300*(i+1),POLYLINE)).toList();
  return "{\"routes\":[{\"legs\":["+String.join(",",all)+"]"+(order==null?"":",\"optimizedIntermediateWaypointIndex\":"+order)+"}]}";
 }

 /**
  * Starts a local server answering every request the same way and recording the decoded queries.
  *
  * @param status the HTTP status
  * @param body the body
  * @param queries receives each decoded query string
  * @return the started server
  * @throws Exception when it cannot start
  */
 static HttpServer serve(int status,String body,List<String> queries) throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/directions",exchange->{assertEquals("POST",exchange.getRequestMethod());assertEquals("application/json",exchange.getRequestHeaders().getFirst("Content-Type"));assertNotNull(exchange.getRequestHeaders().getFirst("X-Goog-Api-Key"));assertTrue(exchange.getRequestHeaders().getFirst("X-Goog-FieldMask").contains("optimizedIntermediateWaypointIndex"));assertNull(exchange.getRequestURI().getRawQuery());queries.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));var bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});
  server.start();return server;}

 /**
  * Builds the URL of a local server.
  *
  * @param server the server
  * @return its directions URL
  */
 static String url(HttpServer server){return "http://127.0.0.1:"+server.getAddress().getPort()+"/directions";}

 /**
  * Without key, store position or stop, or with too many stops, nothing is asked and the reason is given.
  */
 @Test void requestsNeedAKeyTheStoreAndAFewStops(){
  var none=directions("http://127.0.0.1:1/directions",null);assertFalse(none.available());
  assertTrue(assertThrows(GoogleDirections.Unavailable.class,()->none.route(STORE,List.of(A),false)).getMessage().contains("GMAP_API_KEY"));
  assertFalse(directions("x"," ").available());
  var unset=new GoogleDirections();assertFalse(unset.available());
  var ready=directions("http://127.0.0.1:1/directions","k");assertTrue(ready.available());
  assertTrue(assertThrows(GoogleDirections.Unavailable.class,()->ready.route(null,List.of(A),false)).getMessage().contains("magasin"));
  assertTrue(assertThrows(GoogleDirections.Unavailable.class,()->ready.route(STORE,List.of(),false)).getMessage().contains("Aucun arrêt"));
  assertTrue(assertThrows(GoogleDirections.Unavailable.class,()->ready.route(STORE,Collections.nCopies(26,A),true)).getMessage().contains("25 arrêts"));}

 /**
  * In delivery order, the last stop is the destination and the others are plain waypoints.
  *
  * @throws Exception when the local server cannot start
  */
 @Test void keepsTheDeliveryOrderWithTheLastStopAsDestination() throws Exception{
  var queries=new ArrayList<String>();var server=serve(200,answer(3,null),queries);
  try{var road=directions(url(server)," secret ").route(STORE,List.of(A,B,C),false);
   assertEquals(List.of(0,1,2),road.order());assertEquals(List.of(1.5,3.0,4.5),road.km());assertEquals(List.of(300L,600L,900L),road.seconds());
   assertEquals(9,road.path().size());assertArrayEquals(new double[]{38.5,-120.2},road.path().getFirst(),1e-9);
   var q=json.readTree(queries.getFirst());
   assertEquals(STORE.lat(),q.at("/origin/location/latLng/latitude").asDouble());assertEquals(C.lon(),q.at("/destination/location/latLng/longitude").asDouble());
   assertEquals(2,q.path("intermediates").size());assertFalse(q.path("optimizeWaypointOrder").asBoolean());}

  finally{server.stop(0);}}

 /**
  * A single stop is the destination, without waypoint.
  *
  * @throws Exception when the local server cannot start
  */
 @Test void singleStopIsTheDestination() throws Exception{
  var queries=new ArrayList<String>();var server=serve(200,answer(1,null),queries);
  try{var road=directions(url(server),"k").route(STORE,List.of(A),false);
   assertEquals(List.of(0),road.order());assertEquals(A.lat(),json.readTree(queries.getFirst()).at("/destination/location/latLng/latitude").asDouble());assertEquals(0,json.readTree(queries.getFirst()).path("intermediates").size());}
  finally{server.stop(0);}}

 /**
  * Optimised, the itinerary is a round trip whose stops Google orders; the return leg is dropped.
  *
  * @throws Exception when the local server cannot start
  */
 @Test void optimisedRouteIsARoundTripOrderedByGoogle() throws Exception{
  var queries=new ArrayList<String>();var server=serve(200,answer(4,List.of(2,0,1)),queries);
  try{var road=directions(url(server),"k").route(STORE,List.of(A,B,C),true);
   assertEquals(List.of(2,0,1),road.order());assertEquals(3,road.km().size());assertEquals(9,road.path().size());
   var q=json.readTree(queries.getFirst());assertTrue(q.path("optimizeWaypointOrder").asBoolean());assertEquals(3,q.path("intermediates").size());assertEquals(q.path("origin"),q.path("destination"));}

  finally{server.stop(0);}}

 /**
  * Google errors and incomplete answers are explained.
  *
  * @throws Exception when the JSON cannot be read
  */
 @Test void errorsAreExplained() throws Exception{
  assertTrue(message("{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\"}}",1,false).contains("API Routes"));
  assertTrue(message("{\"routes\":[]}",1,false).contains("ne trouve pas"));
  assertTrue(message("{\"error\":{\"code\":429}}",1,false).contains("Quota"));
  assertTrue(message("{\"error\":{\"code\":400}}",1,false).contains("paramètres"));
  assertTrue(message("{}",1,false).contains("ne trouve pas"));
  assertTrue(message(answer(1,null),2,false).contains("1 étape(s) pour 2 arrêt(s)"));
  assertTrue(message(answer(3,null),2,true).contains("ordre des arrêts absent"));
  assertTrue(message(answer(3,List.of(0)),2,true).contains("ordre des arrêts absent"));
  assertTrue(message(answer(3,List.of(0,0)),2,true).contains("incohérent"));
  assertTrue(message(answer(3,List.of(0,2)),2,true).contains("incohérent"));
  assertTrue(message("{\"routes\":[{\"legs\":[{}]}]}",1,false).contains("distance ou durée"));
  assertEquals(List.of(301L),GoogleDirections.read(json.readTree(answer(1,null).replace("300s","300.125s")),1,false).seconds());}

 /**
  * Reads an answer expected to fail.
  *
  * @param body the JSON answer
  * @param stops the stops requested
  * @param optimize whether the order was left to Google
  * @return the failure message
  * @throws Exception when the JSON cannot be read
  */
 String message(String body,int stops,boolean optimize) throws Exception{var tree=json.readTree(body);return assertThrows(GoogleDirections.Unavailable.class,()->GoogleDirections.read(tree,stops,optimize)).getMessage();}

 /**
  * An HTTP error, an unreadable answer, an unreachable service and an interruption are reported without itinerary.
  *
  * @throws Exception when a local server cannot start
  */
 @Test void transportFailuresAreReported() throws Exception{
  var failing=serve(500,"boom",new ArrayList<>());
  try{assertTrue(assertThrows(GoogleDirections.Unavailable.class,()->directions(url(failing),"k").route(STORE,List.of(A),false)).getMessage().contains("HTTP 500"));}finally{failing.stop(0);}
  var garbage=serve(200,"not json",new ArrayList<>());
  try{assertTrue(assertThrows(GoogleDirections.Unavailable.class,()->directions(url(garbage),"k").route(STORE,List.of(A),false)).getMessage().contains("injoignable"));
   Thread.currentThread().interrupt();
   assertTrue(assertThrows(GoogleDirections.Unavailable.class,()->directions(url(garbage),"k").route(STORE,List.of(A),false)).getMessage().contains("interrompu"));
   assertTrue(Thread.interrupted());}
  finally{garbage.stop(0);}
  var closed=new java.net.ServerSocket(0);int port=closed.getLocalPort();closed.close();
  assertTrue(assertThrows(GoogleDirections.Unavailable.class,()->directions("http://127.0.0.1:"+port+"/directions","k").route(STORE,List.of(A),false)).getMessage().contains("injoignable"));}

 /**
  * Encoded polylines decode to the documented points; an empty polyline has none.
  */
 @Test void decodesEncodedPolylines(){
  var points=GoogleDirections.decode(POLYLINE);
  assertEquals(3,points.size());
  assertArrayEquals(new double[]{38.5,-120.2},points.get(0),1e-9);assertArrayEquals(new double[]{40.7,-120.95},points.get(1),1e-9);assertArrayEquals(new double[]{43.252,-126.453},points.get(2),1e-9);
  assertTrue(GoogleDirections.decode("").isEmpty());
  assertEquals("45.761500,4.856000",GoogleDirections.coordinates(STORE));}
}
