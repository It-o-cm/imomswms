package com.intermarche.backoffice.engine.fieldops;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Carrier tours, carriers and docks of a store. */
@Path("/shipping-tours") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class TourResource {
 @Inject TourService tours;
 /** Google Maps browser key, from GMAP_API_KEY (environment or the .env file of the project); absent without map. */
 @ConfigProperty(name="gmap.api.key") Optional<String> mapKey;
 /**
  * Runs a tour operation and turns validation errors into operator failures.
  *
  * @param work the operation
  * @return its result
  */
 private Object safe(java.util.function.Supplier<Object> work){try{return work.get();}catch(BadRequestException e){throw new OperatorFailure(400,"INVALID_TOUR",e.getMessage()==null?"Vérifiez la tournée":e.getMessage());}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 /**
  * Lists the tours of a store.
  *
  * @param store the store
  * @param state an optional state
  * @param before the id to page before
  * @param limit the page size
  * @return a page of tours
  */
 @GET public Object list(@QueryParam("storeId")Long store,@QueryParam("state")String state,@QueryParam("before")@DefaultValue("0")long before,@QueryParam("limit")@DefaultValue("30")int limit){return safe(()->tours.list(store,state,before,limit));}
 /**
  * Returns the carriers, services and docks of a store.
  *
  * @param store the store
  * @return the setup
  */
 @GET @Path("/setup") public Object setup(@QueryParam("storeId")Long store){return safe(()->tours.setup(store));}
 /**
  * Returns what StorePilot needs to draw tours on Google Maps: the browser key, empty when none is configured.
  *
  * @return the map configuration
  */
 @GET @Path("/map-config") public Object mapConfig(){return Map.of("googleMapsApiKey",mapKey==null?"":mapKey.map(String::trim).orElse(""));}
 /**
  * Lists the carrier departures that may join a tour.
  *
  * @param store the store
  * @return the departures
  */
 @GET @Path("/candidates") public Object candidates(@QueryParam("storeId")Long store){return safe(()->tours.candidates(store));}
 /**
  * Proposes tours of one carrier service for the departures waiting outside any tour.
  *
  * @param store the store
  * @param service the carrier service
  * @return the proposed tours
  */
 @GET @Path("/proposals") public Object proposals(@QueryParam("storeId")Long store,@QueryParam("serviceId")Long service){return safe(()->tours.proposals(store,service));}
 /**
  * Reads a tour.
  *
  * @param id the tour
  * @param store the store
  * @return the tour
  */
 @GET @Path("/{id}") public Object read(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->tours.read(store,id));}
 /**
  * Reads a frozen transport document of a tour.
  *
  * @param id the tour
  * @param document the document
  * @param store the store
  * @return the document
  */
 @GET @Path("/{id}/documents/{document}") public Object document(@PathParam("id")Long id,@PathParam("document")Long document,@QueryParam("storeId")Long store){return safe(()->tours.document(store,id,document));}
 /**
  * Creates or updates a carrier.
  *
  * @param i the command
  * @return the setup
  */
 @POST @Path("/carriers") public Object carrier(TourService.Input i){return safe(()->tours.saveCarrier(i));}
 /**
  * Creates or updates a carrier service.
  *
  * @param i the command
  * @return the setup
  */
 @POST @Path("/services") public Object service(TourService.Input i){return safe(()->tours.saveService(i));}
 /**
  * Creates or updates a dock.
  *
  * @param i the command
  * @return the setup
  */
 @POST @Path("/docks") public Object dock(TourService.Input i){return safe(()->tours.saveDock(i));}
 /**
  * Plans a tour.
  *
  * @param i the command
  * @return the tour
  */
 @POST public Object create(TourService.Input i){return safe(()->tours.create(i));}
 /**
  * Attaches departures to a tour.
  *
  * @param i the command
  * @return the tour
  */
 @POST @Path("/attach") public Object attach(TourService.Input i){return safe(()->tours.attach(i));}
 /**
  * Plans a tour with its departures and its optimised delivery order in one confirmation.
  *
  * @param i the command
  * @return the tour
  */
 @POST @Path("/compose") public Object compose(TourService.Input i){return safe(()->tours.compose(i));}
 /**
  * Sets the delivery order of an open tour, optimised or given.
  *
  * @param i the command
  * @return the tour
  */
 @POST @Path("/sequence") public Object sequence(TourService.Input i){return safe(()->tours.sequence(i));}
 /**
  * Removes a departure from a tour.
  *
  * @param i the command
  * @return the tour
  */
 @POST @Path("/detach") public Object detach(TourService.Input i){return safe(()->tours.detach(i));}
 /**
  * Seals a tour and issues its documents.
  *
  * @param i the command
  * @return the tour
  */
 @POST @Path("/seal") public Object seal(TourService.Input i){return safe(()->tours.seal(i));}
 /**
  * Breaks the seal of a tour that has not left.
  *
  * @param i the command
  * @return the tour
  */
 @POST @Path("/unseal") public Object unseal(TourService.Input i){return safe(()->tours.unseal(i));}
 /**
  * Hands a sealed tour over to its carrier.
  *
  * @param i the command
  * @return the tour
  */
 @POST @Path("/depart") public Object depart(TourService.Input i){return safe(()->tours.depart(i));}
 /**
  * Cancels an open tour.
  *
  * @param i the command
  * @return the tour
  */
 @POST @Path("/cancel") public Object cancel(TourService.Input i){return safe(()->tours.cancel(i));}
 /**
  * Records the empty totes brought back from a tour.
  *
  * @param i the command
  * @return what was recorded and the tour
  */
 @POST @Path("/returns") public Object returns(TourService.Input i){return safe(()->tours.returns(i));}
}
