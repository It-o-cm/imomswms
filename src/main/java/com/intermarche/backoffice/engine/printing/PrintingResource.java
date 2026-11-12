package com.intermarche.backoffice.engine.printing;
import com.intermarche.backoffice.engine.fieldops.PrintingService;import jakarta.inject.Inject;import jakarta.ws.rs.*;import jakarta.ws.rs.core.MediaType;import jakarta.annotation.security.RolesAllowed;import com.intermarche.backoffice.engine.operator.OperatorFailure;
@Path("/printing") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @RolesAllowed({"ADMIN","MANAGER","PICKER"}) public class PrintingResource {
 @Inject PrintingService service;
 private Object safe(java.util.function.Supplier<Object> work){try{return work.get();}catch(BadRequestException e){throw new OperatorFailure(400,"INVALID_PRINT_REQUEST",e.getMessage()==null?"Vérifiez la demande d’impression":e.getMessage());}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 @GET @Path("/printers/{id}/probe") public Object probe(@QueryParam("storeId")Long store,@PathParam("id")Long id){return safe(()->service.probe(store,id));}
 @GET @Path("/configuration") public Object configuration(@QueryParam("storeId")Long store){return safe(()->service.configuration(store));}
 @POST @Path("/printers") public Object printer(PrintingService.Input i){return safe(()->service.configurePrinter(i));}
 @POST @Path("/templates") public Object template(PrintingService.Input i){return safe(()->service.createTemplate(i));}
 @GET @Path("/preview") public Object preview(@QueryParam("storeId")Long store,@QueryParam("subjectType")String type,@QueryParam("subjectId")Long subject,@QueryParam("templateId")Long template){return safe(()->service.preview(store,type,subject,template));}
 @GET @Path("/jobs") public Object list(@QueryParam("storeId")Long store,@QueryParam("before")@DefaultValue("0")long before,@QueryParam("limit")@DefaultValue("30")int limit){return safe(()->service.list(store,before,limit));}
 @GET @Path("/subjects/{type}/{id}/last-job") public Object last(@PathParam("type")String type,@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->service.lastJob(store,type,id));}
 @GET @Path("/jobs/{id}") public Object read(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->service.read(store,id));}
 @POST @Path("/jobs") public Object enqueue(PrintingService.Input i){return safe(()->service.enqueue(i));}
 @POST @Path("/jobs/cancel") public Object cancel(PrintingService.Input i){return safe(()->service.cancel(i));}
}
