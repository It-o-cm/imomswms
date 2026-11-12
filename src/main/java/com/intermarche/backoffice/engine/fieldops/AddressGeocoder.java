package com.intermarche.backoffice.engine.fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.GPSPosition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Looks up the GPS position of a French postal address in the Base Adresse Nationale
 * (api-adresse.data.gouv.fr: public, free, no key). A result scoring below {@link #MIN_SCORE} is not trusted.
 * The lookup can be disabled with {@code imomswms.geocoding.enabled=false}; it is disabled in tests.
 */
@ApplicationScoped
public class AddressGeocoder {
 private static final Logger LOGGER=Logger.getLogger(AddressGeocoder.class);
 /** Lowest BAN score accepted: below it the match is too uncertain to place a delivery stop. */
 static final double MIN_SCORE=0.5;
 /** Country names and codes the national address base covers; a blank country is taken as France. */
 static final Set<String> FRANCE=Set.of("FR","FRA","FRANCE");
 @Inject ObjectMapper json;
 @ConfigProperty(name="imomswms.geocoding.enabled",defaultValue="true") boolean enabled;
 @ConfigProperty(name="imomswms.geocoding.url",defaultValue="https://api-adresse.data.gouv.fr/search/") String url;
 @ConfigProperty(name="imomswms.geocoding.timeout-ms",defaultValue="3000") int timeoutMs;

 /**
  * Opens a lookup session: after the first unreachable or failing call, the session stops calling the service, so
  * that a network outage costs one timeout and not one per stop.
  *
  * @return the session
  */
 public Session session(){return new Session();}

 /** A sequence of lookups sharing one client and stopping at the first outage. */
 public class Session {
  private final HttpClient client=enabled?HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build():null;
  private boolean down=!enabled;

  /**
   * Locates an address.
   *
   * @param address the postal address
   * @return its position, or null when it is not French, incomplete, not found, uncertain or the service is down
   */
  public GPSPosition locate(Adresse address){
   String query=query(address);if(down||query==null)return null;
   try{
    var request=HttpRequest.newBuilder(URI.create(url+"?limit=1&q="+URLEncoder.encode(query,StandardCharsets.UTF_8)+(blank(address.postalCode)?"":"&postcode="+URLEncoder.encode(address.postalCode.trim(),StandardCharsets.UTF_8)))).timeout(Duration.ofMillis(timeoutMs)).header("Accept","application/json").GET().build();
    var response=client.send(request,HttpResponse.BodyHandlers.ofString());
    if(response.statusCode()!=200){down=true;LOGGER.warnf("Géocodage indisponible (HTTP %d) : arrêts non localisés pour cette opération",response.statusCode());return null;}
    return parse(json.readTree(response.body()));
   }catch(InterruptedException e){Thread.currentThread().interrupt();down=true;return null;}
   catch(Exception e){down=true;LOGGER.warnf("Géocodage indisponible (%s) : arrêts non localisés pour cette opération",e.getClass().getSimpleName());return null;}
  }

  /**
   * Tells whether the service was found unavailable during this session.
   *
   * @return true once a lookup has failed, or when geocoding is disabled
   */
  public boolean down(){return down;}
 }

 /**
  * Builds the free-text query of an address the national base can answer.
  *
  * @param address the postal address, possibly null
  * @return street and city, or null when the address is not French or lacks its street or city
  */
 static String query(Adresse address){
  if(address==null||blank(address.streetLine1)||blank(address.city)&&blank(address.postalCode))return null;
  if(!blank(address.country)&&!FRANCE.contains(address.country.trim().toUpperCase(Locale.ROOT)))return null;
  return Stream.of(address.streetLine1,address.postalCode,address.city).filter(v->!blank(v)).map(String::trim).collect(Collectors.joining(" "));
 }

 /**
  * Reads the best feature of a BAN answer.
  *
  * @param answer the GeoJSON answer
  * @return the position of the first feature, or null when there is none or it scores below {@link #MIN_SCORE}
  */
 static GPSPosition parse(JsonNode answer){
  JsonNode feature=answer==null?null:answer.path("features").path(0);
  if(feature==null||feature.isMissingNode())return null;
  JsonNode coordinates=feature.path("geometry").path("coordinates");
  if(!coordinates.path(0).isNumber()||!coordinates.path(1).isNumber())return null;
  if(feature.path("properties").path("score").asDouble(0)<MIN_SCORE)return null;
  return new GPSPosition(coordinates.path(1).asDouble(),coordinates.path(0).asDouble());
 }

 /**
  * Tells whether a text is missing.
  *
  * @param value the text
  * @return true when it is null or blank
  */
 static boolean blank(String value){return value==null||value.isBlank();}
}
