package com.intermarche.backoffice.engine.operator;
import jakarta.inject.Inject;import jakarta.ws.rs.*;import jakarta.ws.rs.core.MediaType;import jakarta.annotation.security.RolesAllowed;
@Path("/handling-orders/operator/shifts") @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON) @RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class OperatorShiftResource {
 @Inject OperatorShiftService shifts;
 private Object safe(java.util.function.Supplier<Object> call){try{return call.get();}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 @GET public Object read(@QueryParam("storeId")Long store){return safe(()->shifts.read(store));}
 @GET @Path("/active") @RolesAllowed({"ADMIN","MANAGER"}) public Object active(@QueryParam("storeId")Long store){return safe(()->shifts.active(store));}
 @POST public Object change(OperatorShiftService.Input i){return safe(()->shifts.change(i));}
 @PUT @Path("/goal") @RolesAllowed({"ADMIN","MANAGER"}) public Object goal(OperatorShiftService.Input i){return safe(()->shifts.goal(i));}
}
