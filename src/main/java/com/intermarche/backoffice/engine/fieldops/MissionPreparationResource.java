package com.intermarche.backoffice.engine.fieldops;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.inject.Inject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
@Path("/field-operations/orders/{id}/preparation") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class MissionPreparationResource {
 @Inject MissionPreparationService service;
 @GET public Object get(@PathParam("id")Long id,@QueryParam("storeId")Long store){try{return service.read(store,id);}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 @PUT @RolesAllowed({"ADMIN","MANAGER"}) public Object put(@PathParam("id")Long id,MissionPreparationService.Change input){try{return service.change(id,input);}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
}
