package com.intermarche.backoffice.engine.fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Road itineraries computed by the Google Maps Routes web service: real distances and driving times between the
 * store and the delivery stops, the road path to draw, and, on request, the stop order Google finds shortest.
 * The key is {@code imomswms.directions.key} (GMAP_SERVER_API_KEY, otherwise GMAP_API_KEY); it must not be
 * restricted to browsers (HTTP referrers), since imomswms calls the service itself.
 */
@ApplicationScoped
public class GoogleDirections {
 /** Stops one request takes: Google accepts 25 intermediate waypoints. */
 static final int MAX_STOPS=25;
 @Inject ObjectMapper json;
 @ConfigProperty(name="imomswms.directions.key") Optional<String> key;
 @ConfigProperty(name="imomswms.directions.url",defaultValue="https://routes.googleapis.com/directions/v2:computeRoutes") String url;
 @ConfigProperty(name="imomswms.directions.timeout-ms",defaultValue="8000") int timeoutMs;

 /**
  * A road itinerary through located stops.
  *
  * @param order for each position of the itinerary, the index of the stop in the request
  * @param km the road distance of each leg, from the store to the first stop then between stops
  * @param seconds the driving time of each leg
  * @param path the road path to draw, as latitude and longitude pairs
  */
 public record Road(List<Integer> order,List<Double> km,List<Long> seconds,List<double[]> path){}

 /** Raised when Google cannot give an itinerary; the message is shown to the user. */
 public static class Unavailable extends RuntimeException {
  /**
   * Builds the failure.
   *
   * @param message the French explanation
   */
  public Unavailable(String message){super(message);}
 }

 /**
  * Tells whether a key is configured.
  *
  * @return true when itineraries can be requested
  */
 public boolean available(){return key!=null&&key.map(String::trim).filter(k->!k.isEmpty()).isPresent();}

 /**
  * Computes the road itinerary from the store through stops.
  *
  * @param origin the store
  * @param stops the located stops, in delivery order unless {@code optimize}
  * @param optimize true to let Google choose the shortest order (computed as a round trip, the return leg dropped)
  * @return the itinerary
  * @throws Unavailable when no key is configured, there are too many or no stops, or Google refuses or fails
  */
 public Road route(TourPlanning.Point origin,List<TourPlanning.Point> stops,boolean optimize){
  if(!available())throw new Unavailable("Google Maps n’est pas configuré : renseignez GMAP_API_KEY dans le .env d’imomswms.");
  if(origin==null)throw new Unavailable("La position du magasin est inconnue : Google Maps ne peut pas tracer la tournée.");
  if(stops.isEmpty())throw new Unavailable("Aucun arrêt localisé à faire tracer par Google Maps.");
  if(stops.size()>MAX_STOPS)throw new Unavailable("Google Maps trace "+MAX_STOPS+" arrêts au plus par tournée ; celle-ci en compte "+stops.size()+".");
  var body=json.createObjectNode();body.set("origin",waypoint(origin));
  body.set("destination",waypoint(optimize?origin:stops.getLast()));
  var intermediates=body.putArray("intermediates");
  for(var stop:optimize?stops:stops.subList(0,stops.size()-1))intermediates.add(waypoint(stop));
  body.put("travelMode","DRIVE").put("routingPreference","TRAFFIC_UNAWARE").put("optimizeWaypointOrder",optimize).put("languageCode","fr").put("regionCode","FR").put("units","METRIC");
  JsonNode answer;
  try{var client=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
   var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMs))
    .header("Content-Type","application/json").header("X-Goog-Api-Key",key.get().trim())
    .header("X-Goog-FieldMask","routes.legs.distanceMeters,routes.legs.duration,routes.legs.polyline.encodedPolyline,routes.optimizedIntermediateWaypointIndex")
    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
   var response=client.send(request,HttpResponse.BodyHandlers.ofString());
   if(response.statusCode()!=200){
    try{var failure=json.readTree(response.body());if(failure.has("error"))throw failure(failure.path("error"));}catch(Unavailable e){throw e;}catch(Exception ignored){}
    throw new Unavailable("Google Maps ne répond pas correctement (HTTP "+response.statusCode()+").");
   }
   answer=json.readTree(response.body());}
  catch(Unavailable e){throw e;}
  catch(InterruptedException e){Thread.currentThread().interrupt();throw new Unavailable("Calcul d’itinéraire Google Maps interrompu.");}
  catch(Exception e){throw new Unavailable("Google Maps est injoignable depuis imomswms.");}
  return read(answer,stops.size(),optimize);
 }
 private com.fasterxml.jackson.databind.node.ObjectNode waypoint(TourPlanning.Point point){
  var node=json.createObjectNode();node.putObject("location").putObject("latLng").put("latitude",point.lat()).put("longitude",point.lon());return node;
 }
 private static Unavailable failure(JsonNode error){
  String status=error.path("status").asText();int code=error.path("code").asInt();
  if(code==403||"PERMISSION_DENIED".equals(status))return new Unavailable("Google Maps refuse le calcul : activez l’API Routes pour le projet de la clé serveur et vérifiez les restrictions de cette clé.");
  if(code==429||"RESOURCE_EXHAUSTED".equals(status))return new Unavailable("Quota Google Maps dépassé : réessayez plus tard.");
  if(code==400||"INVALID_ARGUMENT".equals(status))return new Unavailable("Google Maps refuse les paramètres de la tournée. Vérifiez les coordonnées et les arrêts.");
  return new Unavailable("Google Maps ne répond pas correctement"+(code>0?" (HTTP "+code+")":"")+".");
 }

 /**
 * Reads a Directions answer.
  *
  * @param answer the JSON answer
  * @param stops the number of stops requested
  * @param optimize whether the order was left to Google, the last leg then being the return to the store
  * @return the itinerary
  * @throws Unavailable when Google reports an error or the answer is incomplete
  */
 static Road read(JsonNode answer,int stops,boolean optimize){
  if(answer.has("error"))throw failure(answer.path("error"));
  JsonNode route=answer.path("routes").path(0),legs=route.path("legs");
  if(route.isMissingNode())throw new Unavailable("Google Maps ne trouve pas d’itinéraire routier entre ces arrêts.");
  int expected=optimize?stops+1:stops;
  if(legs.size()!=expected)throw new Unavailable("Réponse Google Maps incomplète : "+legs.size()+" étape(s) pour "+stops+" arrêt(s).");
  List<Integer> order=new ArrayList<>();
  if(optimize){JsonNode chosen=route.path("optimizedIntermediateWaypointIndex");
   if(!chosen.isArray()||chosen.size()!=stops)throw new Unavailable("Réponse Google Maps incomplète : ordre des arrêts absent.");
   for(JsonNode n:chosen){int index=n.asInt(-1);if(!n.isIntegralNumber()||index<0||index>=stops||order.contains(index))throw new Unavailable("Réponse Google Maps invalide : ordre des arrêts incohérent.");order.add(index);}}
  else order.addAll(IntStream.range(0,stops).boxed().toList());
  var km=new ArrayList<Double>();var seconds=new ArrayList<Long>();var path=new ArrayList<double[]>();
  for(int i=0;i<stops;i++){
   JsonNode leg=legs.get(i);String duration=leg.path("duration").asText();
   if(!leg.path("distanceMeters").isNumber()||leg.path("distanceMeters").asDouble()<0||!duration.matches("[0-9]+(?:\\.[0-9]+)?s"))throw new Unavailable("Réponse Google Maps incomplète : distance ou durée absente.");
   km.add(leg.path("distanceMeters").asDouble()/1000);seconds.add((long)Math.ceil(Double.parseDouble(duration.substring(0,duration.length()-1))));
   path.addAll(decode(leg.path("polyline").path("encodedPolyline").asText("")));
  }
  return new Road(List.copyOf(order),List.copyOf(km),List.copyOf(seconds),List.copyOf(path));
 }

 /**
  * Writes a point as Google expects it.
  *
  * @param p the point
  * @return "latitude,longitude"
  */
 static String coordinates(TourPlanning.Point p){return String.format(Locale.ROOT,"%.6f,%.6f",p.lat(),p.lon());}

 /**
  * Decodes a Google encoded polyline.
  *
  * @param encoded the encoded polyline
  * @return its points as latitude and longitude pairs
  */
 static List<double[]> decode(String encoded){
  var points=new ArrayList<double[]>();int index=0,lat=0,lon=0;
  while(index<encoded.length()){
   int[] delta=new int[2];
   for(int axis=0;axis<2;axis++){int shift=0,value=0,b;do{b=encoded.charAt(index++)-63;value|=(b&0x1f)<<shift;shift+=5;}while(b>=0x20&&index<encoded.length());delta[axis]=(value&1)!=0?~(value>>1):value>>1;}
   lat+=delta[0];lon+=delta[1];points.add(new double[]{lat/1e5,lon/1e5});}
  return points;
 }
}
