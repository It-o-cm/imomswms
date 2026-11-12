package com.intermarche.backoffice.engine.fieldops;
import jakarta.inject.Inject;import jakarta.ws.rs.*;import jakarta.ws.rs.core.MediaType;import jakarta.annotation.security.RolesAllowed;import com.intermarche.backoffice.engine.operator.OperatorFailure;
@Path("/parcels") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class ParcelResource {
 @Inject ParcelService parcels;
 private Object safe(java.util.function.Supplier<Object> work){try{return work.get();}catch(BadRequestException e){throw new OperatorFailure(400,"INVALID_PARCEL",e.getMessage()==null?"Vérifiez le colis":e.getMessage());}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 @GET public Object list(@QueryParam("storeId")Long store,@QueryParam("orderId")Long order,@QueryParam("before")@DefaultValue("0")long before,@QueryParam("limit")@DefaultValue("30")int limit){return safe(()->parcels.list(store,order,before,limit));}
 @GET @Path("/candidates") public Object candidates(@QueryParam("storeId")Long store,@QueryParam("q")String query,@QueryParam("after")@DefaultValue("0")long after,@QueryParam("limit")@DefaultValue("24")int limit){return safe(()->parcels.candidates(store,query,after,limit));}
 @GET @Path("/available-content") public Object available(@QueryParam("storeId")Long store,@QueryParam("orderId")Long order){return safe(()->parcels.available(store,order));}
 @GET @Path("/policy") public Object policy(@QueryParam("storeId")Long store){return safe(()->parcels.readPolicy(store));}
 @POST @Path("/policy") @RolesAllowed({"ADMIN","MANAGER"}) public Object policy(ParcelService.PolicyInput i){return safe(()->parcels.savePolicy(i));}
 @GET @Path("/{id}") public Object read(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->parcels.read(store,id));}
 @GET @Path("/declaration") public Object declaration(@QueryParam("storeId")Long store,@QueryParam("orderId")Long order){return safe(()->parcels.declaration(store,order));}
 @POST @Path("/batch") public Object batch(ParcelService.Input i){return safe(()->parcels.createBatch(i));}
 @POST public Object create(ParcelService.Input i){return safe(()->parcels.create(i));}
 @POST @Path("/cancel") public Object cancel(ParcelService.Input i){return safe(()->parcels.cancelEmpty(i));}
 @POST @Path("/content") public Object save(ParcelService.Input i){return safe(()->parcels.save(i));}
 @POST @Path("/close") public Object close(ParcelService.Input i){return safe(()->parcels.close(i));}
 @GET @Path("/{id}/reopen-target") @RolesAllowed({"ADMIN","MANAGER"}) public Object reopenTarget(@PathParam("id")Long id,@QueryParam("storeId")Long store,@QueryParam("code")String code){return safe(()->parcels.reopenTarget(store,id,code));}
 @POST @Path("/reopen") @RolesAllowed({"ADMIN","MANAGER"}) public Object reopen(ParcelService.Input i){return safe(()->parcels.reopen(i));}
}
