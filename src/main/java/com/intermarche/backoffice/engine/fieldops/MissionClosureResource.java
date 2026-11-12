package com.intermarche.backoffice.engine.fieldops;
import jakarta.inject.Inject;import jakarta.ws.rs.*;import jakarta.ws.rs.core.MediaType;import jakarta.annotation.security.RolesAllowed;
@Path("/field-operations/orders/{id}/closure") @Produces(MediaType.APPLICATION_JSON) @RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class MissionClosureResource {
 @Inject MissionClosureService closure;
 @GET public Object read(@PathParam("id")Long id,@QueryParam("storeId")Long store){return closure.read(store,id);}
}
