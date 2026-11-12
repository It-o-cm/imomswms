package com.intermarche.backoffice.engine.operator;
import jakarta.annotation.security.RolesAllowed;import jakarta.inject.Inject;import jakarta.ws.rs.*;import jakarta.ws.rs.core.MediaType;
@Path("/handling-orders/operator/consultation") @Produces(MediaType.APPLICATION_JSON) @RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class OperatorConsultationResource {
 @Inject OperatorConsultationService service;
 @Inject OperatorReceiptService receipts;
 private Object safe(java.util.function.Supplier<Object> work){try{return work.get();}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 @GET @Path("/receipts/{key}") public Object receipt(@QueryParam("storeId")Long store,@PathParam("key")String key,@QueryParam("kind")String kind){return safe(()->receipts.read(store,key,kind));}
 @GET @Path("/summary") public Object summary(@QueryParam("storeId")Long store){return safe(()->service.summary(store));}
 @GET @Path("/activity") public Object activity(@QueryParam("storeId")Long store,@QueryParam("limit")@DefaultValue("30")int limit,@QueryParam("cursor")String cursor){return safe(()->service.activity(store,limit,cursor));}
 @GET @Path("/products/{id}") public Object product(@QueryParam("storeId")Long store,@PathParam("id")Long product){return safe(()->service.product(store,product));}
}
