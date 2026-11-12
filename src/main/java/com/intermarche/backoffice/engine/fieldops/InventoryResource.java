package com.intermarche.backoffice.engine.fieldops;
import jakarta.inject.Inject;import jakarta.annotation.security.RolesAllowed;import jakarta.ws.rs.*;import jakarta.ws.rs.core.MediaType;
@Path("/inventories") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class InventoryResource {
 @Inject InventoryService inventory;
 @Inject InventoryCountingService counting;
 private Object safe(java.util.function.Supplier<Object> work){try{return work.get();}catch(BadRequestException e){throw new com.intermarche.backoffice.engine.operator.OperatorFailure(400,"INVALID_INVENTORY_REQUEST",e.getMessage()==null?"Vérifiez le périmètre et le créneau":e.getMessage());}catch(RuntimeException e){throw com.intermarche.backoffice.engine.operator.OperatorFailure.safe(e);}}
 @GET public Object list(@QueryParam("storeId")Long store,@QueryParam("state")String state,@QueryParam("before")@DefaultValue("0")long before,@QueryParam("limit")@DefaultValue("30")int limit){return safe(()->inventory.list(store,state,before,limit));}
 @GET @Path("/{id}") public Object read(@QueryParam("storeId")Long store,@PathParam("id")Long id){return safe(()->inventory.read(store,id));}
 @GET @Path("/scopes") public Object scopes(@QueryParam("storeId")Long store,@QueryParam("buildingId")Long building){return safe(()->inventory.scopes(store,building));}
 @GET @Path("/{id}/sessions") public Object sessions(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->counting.sessions(store,id));}
 @GET @Path("/sessions/{id}") public Object session(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->counting.read(store,id));}
 @POST @Path("/sessions") public Object start(InventoryCountingService.SessionInput input){return safe(()->counting.start(input));}
 @POST @Path("/counts") public Object count(InventoryCountingService.CountInput input){return safe(()->counting.count(input));}
 @POST @Path("/sessions/submit") public Object submit(InventoryCountingService.SessionInput input){return safe(()->counting.submit(input));}
 @POST @Path("/sessions/recount") public Object recount(InventoryCountingService.SessionInput input){return safe(()->counting.recount(input));}
 @GET @Path("/sessions/{id}/review") @RolesAllowed({"ADMIN","MANAGER"}) public Object review(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->counting.review(store,id));}
 @POST @Path("/sessions/approve") @RolesAllowed({"ADMIN","MANAGER"}) public Object approve(InventoryCountingService.ApprovalInput input){return safe(()->counting.approve(input));}
 @POST @Path("/sessions/cancel") @RolesAllowed({"ADMIN","MANAGER"}) public Object cancel(InventoryCountingService.SessionInput input){return safe(()->counting.cancel(input));}
 @POST @Path("/sessions/apply") @RolesAllowed({"ADMIN","MANAGER"}) public Object apply(InventoryCountingService.SessionInput input){return safe(()->counting.apply(input));}
 @GET @Path("/control-policy") public Object policy(@QueryParam("storeId")Long store){return safe(()->counting.policy(store));}
 @PUT @Path("/control-policy") @RolesAllowed({"ADMIN","MANAGER"}) public Object policy(InventoryCountingService.PolicyInput input){return safe(()->counting.savePolicy(input));}
 @POST @RolesAllowed({"ADMIN","MANAGER"}) public Object create(InventoryService.Input input){return safe(()->inventory.create(input));}
}
