package com.intermarche.backoffice.engine.fieldops;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.inject.Inject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
@Path("/field-operations") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class SupportTraceabilityResource {
 @Inject SupportLayoutService supports;
 @Inject TraceabilityService trace;
 @Inject StockSegmentService stock;
 private Object safe(java.util.function.Supplier<Object> work){try{return work.get();}catch(BadRequestException e){throw new OperatorFailure(400,"INVALID_SUPPORT_REQUEST",e.getMessage()==null?"Vérifiez les informations du support":e.getMessage());}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 @GET @Path("/orders/{id}/deposit-target") public Object depositTarget(@PathParam("id")Long id,@QueryParam("storeId")Long store,@QueryParam("code")String code){return safe(()->supports.depositTarget(store,id,code));}
 @GET @Path("/orders/{id}/supports") public Object mission(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->supports.mission(store,id));}
 @GET @Path("/supports/{id}/layout") public Object layout(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->supports.read(store,id));}
 @PUT @Path("/supports/layout") @RolesAllowed({"ADMIN","MANAGER"}) public Object layout(SupportLayoutService.Layout r){return safe(()->supports.configure(r));}
 @POST @Path("/supports/compartments") public Object assign(SupportLayoutService.Assignment r){return safe(()->supports.assign(r));}
 @GET @Path("/supports/loads/{id}/replacements") public Object replacements(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->supports.replacements(store,id));}
 @POST @Path("/supports/full") public Object full(SupportLayoutService.Full r){return safe(()->supports.full(r));}
 @GET @Path("/products/{id}/handling-compatibility") public Object compatibility(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->trace.compatibility(store,id));}
 @GET @Path("/products/{id}/manufacturer-lots") public Object lots(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->trace.lots(store,id));}
 @GET @Path("/products/{id}/manufacturer-lot") public Object lotByCode(@PathParam("id")Long id,@QueryParam("storeId")Long store,@QueryParam("code")String code){return safe(()->trace.lotByCode(store,id,code));}
 @GET @Path("/products/{id}/product-serial") public Object serialByCode(@PathParam("id")Long id,@QueryParam("storeId")Long store,@QueryParam("code")String code){return safe(()->trace.serialByCode(store,id,code));}
 @POST @Path("/manufacturer-lots") public Object lot(TraceabilityService.Input r){return safe(()->trace.saveLot(r));}
 @POST @Path("/product-serials") public Object serial(TraceabilityService.Input r){return safe(()->trace.claimSerial(r));}
 @GET @Path("/products/{id}/traceability-policies") public Object policies(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->trace.policies(store,id));}
 @PUT @Path("/traceability-policies") @RolesAllowed({"ADMIN","MANAGER"}) public Object policy(TraceabilityService.Input r){return safe(()->trace.savePolicy(r));}
 @GET @Path("/products/{id}/stock-segments") public Object segments(@PathParam("id")Long id,@QueryParam("storeId")Long store,@QueryParam("locationId")Long location){return safe(()->stock.list(store,id,location));}
 @POST @Path("/stock-segments/reclassify") @RolesAllowed({"ADMIN","MANAGER"}) public Object reclassify(StockSegmentService.Transfer r){return safe(()->stock.transfer(r));}
}
