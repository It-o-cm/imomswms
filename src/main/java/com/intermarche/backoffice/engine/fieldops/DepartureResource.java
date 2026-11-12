package com.intermarche.backoffice.engine.fieldops;
import jakarta.inject.Inject;import jakarta.ws.rs.*;import jakarta.ws.rs.core.MediaType;import jakarta.annotation.security.RolesAllowed;import com.intermarche.backoffice.engine.operator.OperatorFailure;
@Path("/departures") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class DepartureResource {
 @Inject DepartureService departures;
 private Object safe(java.util.function.Supplier<Object> work){try{return work.get();}catch(BadRequestException e){throw new OperatorFailure(400,"INVALID_DEPARTURE",e.getMessage()==null?"Vérifiez le départ":e.getMessage());}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 @GET public Object list(@QueryParam("storeId")Long store,@QueryParam("state")String state,@QueryParam("before")@DefaultValue("0")long before,@QueryParam("limit")@DefaultValue("30")int limit){return safe(()->departures.list(store,state,before,limit));}
 @GET @Path("/choices") public Object choices(@QueryParam("storeId")Long store,@QueryParam("orderId")Long order){return safe(()->departures.choices(store,order));}
 @GET @Path("/employees") public Object employees(@QueryParam("storeId")Long store,@QueryParam("q")String query){return safe(()->departures.employees(store,query));}
 @GET @Path("/{id}") public Object read(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->departures.read(store,id));}
 @POST public Object create(DepartureService.Input i){return safe(()->departures.create(i));}
 @POST @Path("/load") public Object load(DepartureService.Input i){return safe(()->departures.load(i));}
 @POST @Path("/unload") public Object unload(DepartureService.Input i){return safe(()->departures.unload(i));}
 @POST @Path("/handover") public Object handover(DepartureService.Input i){return safe(()->departures.handover(i));}
 @POST @Path("/cancel") public Object cancel(DepartureService.Input i){return safe(()->departures.cancel(i));}
 @POST @Path("/custody") public Object transfer(DepartureService.Input i){return safe(()->departures.requestCustody(i));}
 @POST @Path("/custody/accept") public Object accept(DepartureService.Input i){return safe(()->departures.acceptCustody(i,true));}
 @POST @Path("/custody/cancel") public Object cancelCustody(DepartureService.Input i){return safe(()->departures.acceptCustody(i,false));}
}
