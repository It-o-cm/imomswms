package com.intermarche.backoffice.engine.commerce;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/** Server-to-server commerce contract, version 1: pickup state, arrival announcement and lookup after a lost response. */
@Path("/commerce/v1") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @RolesAllowed("SERVICE_CALLER")
public class CommerceResource {
 static final Logger LOG=Logger.getLogger(CommerceResource.class);
 @Inject PickupContract contract;
 /**
  * Runs a contract operation: refusals keep their structured body, any other failure is reported as a retryable
  * unavailability, never as an empty or false state.
  *
  * @param work the operation
  * @return its result
  */
 private <T> T safe(Supplier<T> work){try{return work.get();}catch(CommerceFailure e){throw e;}catch(RuntimeException e){LOG.error("Commerce contract failure",e);throw CommerceFailure.of(503,"TEMPORARILY_UNAVAILABLE",true);}}
 /**
  * Reads the authoritative pickup state of an order.
  *
  * @param storeCode the store
  * @param orderReference the order reference
  * @return the snapshot, not cacheable
  */
 @GET @Path("/stores/{storeCode}/orders/{orderReference}/pickup") public Response pickup(@PathParam("storeCode")String storeCode,@PathParam("orderReference")String orderReference){return Response.ok(safe(()->contract.pickup(storeCode,orderReference))).header("Cache-Control","no-store").build();}
 /**
  * Announces the arrival of the customer of an order.
  *
  * @param storeCode the store
  * @param orderReference the order reference
  * @param r the announcement
  * @return 201 for a new arrival, 200 for a replay, with the current snapshot
  */
 @POST @Path("/stores/{storeCode}/orders/{orderReference}/arrivals") public Response arrive(@PathParam("storeCode")String storeCode,@PathParam("orderReference")String orderReference,PickupContract.ArrivalRequest r){
  var result=safe(()->contract.arrive(storeCode,orderReference,r));
  var response=result.created()?Response.created(UriBuilder.fromPath("/commerce/v1/stores/{store}/orders/{order}/pickup").build(storeCode,orderReference)):Response.ok();
  return response.entity(result.snapshot()).header("Cache-Control","no-store").build();}
 /**
  * Finds an announcement by its request id after a lost response.
  *
  * @param requestId the request id
  * @return the current snapshot with the original identifiers
  */
 @GET @Path("/arrival-requests/{requestId}") public Response request(@PathParam("requestId")String requestId){return Response.ok(safe(()->contract.request(requestId))).header("Cache-Control","no-store").build();}
}
