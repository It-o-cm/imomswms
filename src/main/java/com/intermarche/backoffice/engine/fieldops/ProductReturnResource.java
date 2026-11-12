package com.intermarche.backoffice.engine.fieldops;
import jakarta.inject.Inject;import jakarta.ws.rs.*;import jakarta.ws.rs.core.MediaType;import jakarta.annotation.security.RolesAllowed;import com.intermarche.backoffice.engine.operator.OperatorFailure;
@Path("/product-returns") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class ProductReturnResource {
 @Inject ProductReturnService returns;
 private Object safe(java.util.function.Supplier<Object> work){try{return work.get();}catch(BadRequestException e){throw new OperatorFailure(400,"INVALID_PRODUCT_RETURN",e.getMessage()==null?"Vérifiez le retour":e.getMessage());}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 @GET public Object list(@QueryParam("storeId")Long store,@QueryParam("state")String state,@QueryParam("before")@DefaultValue("0")long before,@QueryParam("limit")@DefaultValue("30")int limit){return safe(()->returns.list(store,state,before,limit));}
 @GET @Path("/locations") public Object locations(@QueryParam("storeId")Long store,@QueryParam("productId")Long product){return safe(()->returns.locations(store,product));}
 @GET @Path("/identities") public Object identities(@QueryParam("storeId")Long store,@QueryParam("productId")Long product,@QueryParam("q")String q){return safe(()->returns.identities(store,product,q));}
 @GET @Path("/{id}") public Object read(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->returns.read(store,id));}
 @POST public Object receive(ProductReturnService.Input i){return safe(()->returns.receive(i));}
 @POST @Path("/inspect") public Object inspect(ProductReturnService.Input i){return safe(()->returns.inspect(i));}
 @POST @Path("/dispositions") @RolesAllowed({"ADMIN","MANAGER"}) public Object plan(ProductReturnService.Input i){return safe(()->returns.plan(i));}
 @POST @Path("/execute") public Object execute(ProductReturnService.Input i){return safe(()->returns.execute(i));}
}
