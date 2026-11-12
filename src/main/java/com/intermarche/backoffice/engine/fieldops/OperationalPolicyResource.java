package com.intermarche.backoffice.engine.fieldops;
import jakarta.inject.Inject;import jakarta.ws.rs.*;import jakarta.ws.rs.core.MediaType;import jakarta.annotation.security.RolesAllowed;import com.intermarche.backoffice.engine.operator.OperatorFailure;
@Path("/operational-policies") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @RolesAllowed({"ADMIN","MANAGER"}) public class OperationalPolicyResource {
 @Inject OperationalPolicyService policies;
 private Object safe(java.util.function.Supplier<Object> action){try{return action.get();}catch(BadRequestException e){throw new OperatorFailure(400,"INVALID_OPERATIONAL_POLICY",e.getMessage()==null?"Vérifiez les paramètres et le scénario de test":e.getMessage());}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 @GET @Path("/catalog") public Object catalog(@QueryParam("storeId")Long store){return safe(()->policies.catalog(store));}
 @GET @Path("/current") public Object current(@QueryParam("storeId")Long store,@QueryParam("type")String type,@QueryParam("productId")Long product,@QueryParam("context")String context){return safe(()->policies.current(store,type,product,context));}
 @POST @Path("/simulate") public Object simulate(OperationalPolicyService.Input input){return safe(()->policies.simulate(input));}
 @POST @Path("/activate") public Object activate(OperationalPolicyService.Input input){return safe(()->policies.activate(input));}
}
