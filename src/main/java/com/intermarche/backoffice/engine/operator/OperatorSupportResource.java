package com.intermarche.backoffice.engine.operator;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
@Path("/handling-orders/operator/support") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class OperatorSupportResource {
 @Inject OperatorSupportService service;
 @Inject OperatorContext context;
 private <T>T safe(java.util.function.Supplier<T> work){try{context.account();return work.get();}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 @GET @Path("/session") @Transactional public Object session(@QueryParam("storeId")Long store){return safe(()->service.session(store));}
 @PUT @Path("/session") @Transactional public Object session(OperatorSupportService.SessionInput input){return safe(()->service.session(input));}
 @GET @Path("/events") @Transactional public Object feed(@QueryParam("storeId")Long store,@QueryParam("after")@DefaultValue("0")long after,@QueryParam("limit")@DefaultValue("30")int limit,@QueryParam("category")String category){return safe(()->service.feed(store,after,limit,category));}
 @POST @Consumes(MediaType.WILDCARD) @Path("/events/{id}/received") @Transactional public Object received(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->service.acknowledge(store,id,false));}
 @POST @Consumes(MediaType.WILDCARD) @Path("/events/{id}/ack") @Transactional public Object ack(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->service.acknowledge(store,id,true));}
 @GET @Path("/instructions") @RolesAllowed({"ADMIN","MANAGER"}) @Transactional public Object instructions(@QueryParam("storeId")Long store,@QueryParam("after")@DefaultValue("0")long after,@QueryParam("limit")@DefaultValue("30")int limit){return safe(()->service.instructions(store,after,limit));}
 @POST @Path("/instructions") @RolesAllowed({"ADMIN","MANAGER"}) @Transactional public Object instruction(OperatorSupportService.InstructionInput input){return safe(()->service.instruction(input));}
 @GET @Path("/health") public Object health(){return safe(service::health);}
 @POST @Path("/diagnostics") @Transactional public Object diagnostic(OperatorSupportService.DiagnosticInput input){return safe(()->service.diagnostic(input));}
 @GET @Path("/diagnostics") @RolesAllowed({"ADMIN","MANAGER"}) @Transactional public Object diagnostics(@QueryParam("storeId")Long store,@QueryParam("limit")@DefaultValue("30")int limit){return safe(()->service.diagnostics(store,limit));}
}
