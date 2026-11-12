package com.intermarche.backoffice.engine.catalog;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
@Path("/product-information") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class ProductInformationResource {
 @Inject ProductInformationService service;
 private Object safe(java.util.function.Supplier<Object> action){try{return action.get();}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 @GET @Path("/{id}") public Object view(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->service.view(store,id));}
 @PUT @Path("/{id}/instructions") @RolesAllowed({"ADMIN","MANAGER"}) public Object instructions(@PathParam("id")Long id,ProductInformationService.Edit input){return safe(()->service.instructions(id,input));}
 @PUT @Path("/{id}/identifiers") @RolesAllowed({"ADMIN","MANAGER"}) public Object identifier(@PathParam("id")Long id,ProductInformationService.Edit input){return safe(()->service.identifier(id,input));}
 @POST @Path("/{id}/identifiers/{identifierId}/retire") @RolesAllowed({"ADMIN","MANAGER"}) public Object retire(@PathParam("id")Long id,@PathParam("identifierId")Long alias,ProductInformationService.Edit input){return safe(()->service.removeIdentifier(id,alias,input));}
 @PUT @Path("/{id}/packagings") @RolesAllowed({"ADMIN","MANAGER"}) public Object packaging(@PathParam("id")Long id,ProductInformationService.Edit input){return safe(()->service.packaging(id,input));}
 @POST @Path("/{id}/convert") public Object convert(@PathParam("id")Long id,ProductInformationService.Conversion input){return safe(()->service.convert(id,input));}
 @POST @Path("/{id}/measure") public Object measure(@PathParam("id")Long id,ProductInformationService.Measurement input){return safe(()->service.measure(id,input));}
 @POST @Path("/resolve") public Object resolve(ProductInformationService.Scan input){return safe(()->service.resolve(input));}
}
