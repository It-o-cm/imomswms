package com.intermarche.backoffice.engine.fieldops;
import jakarta.inject.Inject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
@Path("/quality") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class QualityResource {
 @Inject QualityService quality;
 private Object safe(java.util.function.Supplier<Object> work){try{return work.get();}catch(BadRequestException e){throw new OperatorFailure(400,"INVALID_QUALITY_REQUEST",e.getMessage()==null?"Vérifiez la décision qualité":e.getMessage());}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 @GET @Path("/cases") public Object list(@QueryParam("storeId")Long store,@QueryParam("state")String state,@QueryParam("productId")Long product,@QueryParam("q")String q,@QueryParam("before")@DefaultValue("0")long before,@QueryParam("limit")@DefaultValue("30")int limit){return safe(()->quality.list(store,state,product,q,before,limit));}
 @GET @Path("/cases/{id}") public Object read(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->quality.read(store,id));}
 @POST @Path("/cases") @RolesAllowed({"ADMIN","MANAGER"}) public Object create(QualityService.Input input){return safe(()->quality.create(input));}
 @POST @Path("/cases/{id}/release") @RolesAllowed({"ADMIN","MANAGER"}) public Object release(@PathParam("id")Long id,QualityService.Input input){return safe(()->quality.release(id,input));}
 @GET @Path("/trace") public Object trace(@QueryParam("storeId")Long store,@QueryParam("productId")Long product,@QueryParam("lotId")Long lot,@QueryParam("serialId")Long serial,@QueryParam("serialCode")String serialCode){return safe(()->serialCode==null||serialCode.isBlank()?quality.trace(store,product,lot,serial):quality.traceSerial(store,product,lot,serialCode));}
}
