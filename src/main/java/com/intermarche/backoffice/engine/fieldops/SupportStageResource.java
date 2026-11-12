package com.intermarche.backoffice.engine.fieldops;
import jakarta.inject.Inject;import jakarta.ws.rs.*;import jakarta.ws.rs.core.MediaType;import jakarta.annotation.security.RolesAllowed;
@Path("/field-operations/support-handovers") @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON) @RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class SupportStageResource {
 @Inject SupportStageService stages;
 private Object safe(java.util.function.Supplier<Object> work){try{return work.get();}catch(BadRequestException e){throw new com.intermarche.backoffice.engine.operator.OperatorFailure(400,"INVALID_SUPPORT_TRANSFER",e.getMessage()==null?"Vérifiez la transmission":e.getMessage());}catch(RuntimeException e){throw com.intermarche.backoffice.engine.operator.OperatorFailure.safe(e);}}
 @GET public Object list(@QueryParam("storeId")Long store,@QueryParam("before")@DefaultValue("0")long before){if(before<0)throw new BadRequestException();return safe(()->stages.list(store,before));}
 @GET @Path("/recipients") public Object recipients(@QueryParam("storeId")Long store){return safe(()->stages.recipients(store));}
 @POST public Object offer(SupportStageService.Input input){return safe(()->stages.offer(input));}
 @POST @Path("/accept") public Object accept(SupportStageService.Input input){return safe(()->stages.decide(input,"ACCEPT"));}
 @POST @Path("/cancel") public Object cancel(SupportStageService.Input input){return safe(()->stages.decide(input,"CANCEL"));}
}
