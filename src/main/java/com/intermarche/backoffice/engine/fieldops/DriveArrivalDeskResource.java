package com.intermarche.backoffice.engine.fieldops;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

/** Drive arrivals as the store team handles them. */
@Path("/drive-arrivals") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class DriveArrivalDeskResource {
 @Inject DriveArrivalDesk desk;@Inject SecurityIdentity identity;
 /**
  * Runs a desk operation and turns validation errors into operator failures.
  *
  * @param work the operation
  * @return its result
  */
 private Object safe(java.util.function.Supplier<Object> work){try{return work.get();}catch(BadRequestException e){throw new OperatorFailure(400,"INVALID_DRIVE_ARRIVAL",e.getMessage()==null?"Vérifiez l’arrivée":e.getMessage());}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 /**
  * Lists the arrivals of a store.
  *
  * @param store the store
  * @param closed true for the closed arrivals
  * @return the queue
  */
 @GET public Object queue(@QueryParam("storeId")Long store,@QueryParam("closed")@DefaultValue("false")boolean closed){return safe(()->desk.queue(store,closed));}
 /**
  * Takes an arrival in charge.
  *
  * @param i the command
  * @return the arrival
  */
 @POST @Path("/acknowledge") public Object acknowledge(DriveArrivalDesk.Input i){return safe(()->desk.acknowledge(i));}
 /**
  * Closes a handed-over arrival.
  *
  * @param i the command
  * @return the arrival
  */
 @POST @Path("/complete") public Object complete(DriveArrivalDesk.Input i){return safe(()->desk.complete(i));}
 /**
  * Cancels an arrival.
  *
  * @param i the command
  * @return the arrival
  */
 @POST @Path("/cancel") public Object cancel(DriveArrivalDesk.Input i){return safe(()->desk.cancel(i));}
 /**
  * Sets the arrival window of a store.
  *
  * @param i the command
  * @return the window
  */
 @POST @Path("/policy") public Object policy(DriveArrivalDesk.Input i){return safe(()->desk.policy(i));}
 /**
  * Lists the commerce identities allowed on a store.
  *
  * @param store the store
  * @return the grants
  */
 @GET @Path("/grants") public Object grants(@QueryParam("storeId")Long store){return safe(()->desk.grants(store));}
 /**
  * Opens or closes a store to a commerce identity.
  *
  * @param i the command
  * @return the grants
  */
 @POST @Path("/grants") public Object grant(DriveArrivalDesk.Input i){return safe(()->desk.grant(i,identity.hasRole("ADMIN")));}
}
