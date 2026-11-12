package com.intermarche.backoffice.engine.executing;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

/** Lectures de reprise et actions acquittées, communes aux interfaces d'exécution. */
@Path("/handling-orders/operator")
@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class OperatorExecutionResource {
    @Inject OperatorExecutionService service;
    @Inject com.intermarche.backoffice.engine.operator.OperatorMissionPageService pages;
    @Inject com.intermarche.backoffice.engine.operator.OperatorCommandTransaction commands;
    @Inject com.intermarche.backoffice.engine.operator.OperatorContext context;
    private <T> T guarded(java.util.function.Supplier<T> work){try{context.account();return work.get();}catch(RuntimeException e){throw com.intermarche.backoffice.engine.operator.OperatorFailure.safe(e);}}
    @GET @Path("/context") @Transactional
    public Object context(@QueryParam("storeId") Long storeId){return guarded(()->context.view(storeId));}
    @GET @Path("/orders") @Transactional
    public Object orders(@QueryParam("storeId") Long storeId){return guarded(()->service.orders(storeId));}
    @GET @Path("/orders/page") public Object page(@QueryParam("storeId")Long store,@QueryParam("before")@DefaultValue("0")long before,@QueryParam("limit")@DefaultValue("30")int limit,@QueryParam("state")@DefaultValue("ACTIVE")String state,@QueryParam("assignment")@DefaultValue("MINE")String assignment,@QueryParam("search")String search){return guarded(()->pages.page(store,before,limit,state,assignment,search));}
    @GET @Path("/orders/{id}") @Transactional
    public Object order(@PathParam("id") Long id,@QueryParam("storeId") Long storeId){return guarded(()->service.order(storeId,id));}
    @GET @Path("/executions/{id}") @Transactional
    public Object execution(@PathParam("id") Long id,@QueryParam("storeId") Long storeId){return guarded(()->service.execution(storeId,id));}
    @GET @Path("/orders/{id}/product-locations") @Transactional
    public Object productLocations(@PathParam("id") Long id,@QueryParam("storeId") Long storeId,@QueryParam("requestedProductId") Long requestedProductId,@QueryParam("productId") Long productId,@QueryParam("plannedCode") String plannedCode,@QueryParam("buildingCode") String buildingCode) {return guarded(()->service.productLocations(storeId,id,requestedProductId,productId,plannedCode,buildingCode));}
    @POST @Path("/commands")
    public Response apply(OperatorCommandInput input){return guarded(()->Response.ok(commands.apply(input),MediaType.APPLICATION_JSON).build());}
    @POST @Path("/reports/inspect") @Transactional
    public Object inspect(OperatorCommandInput input){return guarded(()->service.inspectLegacy(input));}
    @GET @Path("/commands/{key}") @Transactional
    public Response result(@PathParam("key") String key,@QueryParam("storeId") Long storeId){return guarded(()->Response.ok(service.receipt(storeId,key),MediaType.APPLICATION_JSON).build());}
}
