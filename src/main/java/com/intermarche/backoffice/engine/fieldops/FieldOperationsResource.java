package com.intermarche.backoffice.engine.fieldops;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.inject.Inject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
@Path("/field-operations") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class FieldOperationsResource {
 @Inject ConsumableService consumables;
 @GET @Path("/consumables/in-use") public Object consumablesInUse(@QueryParam("storeId")Long s){return safe(()->consumables.inUse(s));}
 @GET @Path("/consumables") public Object consumables(@QueryParam("storeId")Long s){return safe(()->consumables.list(s));}
 @GET @Path("/consumables/{id}/movements") public Object consumableHistory(@QueryParam("storeId")Long s,@PathParam("id")Long id){return safe(()->consumables.history(s,id));}
 @POST @Path("/consumables/{action}") public Object consumableCommand(@PathParam("action")String a,ConsumableService.Input r){return safe(()->consumables.command(r,a.toUpperCase(java.util.Locale.ROOT)));}
 @Inject SupportBatchService batches;@Inject CancellationDispositionService cancellations;@Inject SupportService supports;@Inject MissionHandoverService handovers;@Inject LineDirectiveService lines;@Inject FieldOperationsService service;@Inject FieldApprovalService approvals;@Inject FieldAttachmentService attachments;@Inject MissionStateService missions;@Inject FieldReplenishmentService replenishments;
 private Object safe(java.util.function.Supplier<Object> action){try{return action.get();}catch(BadRequestException e){throw new OperatorFailure(400,"INVALID_FIELD_REQUEST",e.getMessage()==null?"Vérifiez les informations du dossier":e.getMessage());}catch(ClientErrorException e){if(e.getResponse().getStatus()==413)throw new OperatorFailure(413,"PHOTO_TOO_LARGE","Photo limitée à 2 Mo");throw OperatorFailure.safe(e);}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 @GET @Path("/products/{id}/location-stock") public Object locationStock(@PathParam("id")Long product,@QueryParam("storeId")Long store,@QueryParam("locationId")Long location){return safe(()->service.locationStock(store,product,location));}
 @GET @Path("/incidents") public Object list(@QueryParam("storeId")Long store,@QueryParam("state")String state,@QueryParam("kind")String kind,@QueryParam("orderId")Long order,@QueryParam("productId")Long product,@QueryParam("q")String query,@QueryParam("before")@DefaultValue("0")long before,@QueryParam("limit")@DefaultValue("30")int limit){return safe(()->service.list(store,state,kind,order,product,query,before,limit));}
 @POST @Path("/incidents") public Object report(FieldOperationsService.Report input){return safe(()->service.report(input));}
 @GET @Path("/incidents/{id}") public Object get(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->service.get(store,id));}
 @POST @Path("/incidents/{id}/decisions") @RolesAllowed({"ADMIN","MANAGER"}) public Object decide(@PathParam("id")Long id,FieldOperationsService.Decision input){return safe(()->service.decide(id,input));}
 @POST @Path("/incidents/{id}/attachments") public Object attach(@PathParam("id")Long id,FieldAttachmentService.Input input){return safe(()->attachments.add(id,input));}
 @GET @Path("/incidents/{id}/attachments/{attachmentId}") @Produces({"image/png","image/jpeg"}) public jakarta.ws.rs.core.Response attachment(@PathParam("id")Long id,@PathParam("attachmentId")Long attachment,@QueryParam("storeId")Long store){try{return attachments.read(store,id,attachment);}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 @GET @Path("/incidents/{id}/approvals") public Object approvals(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->approvals.list(store,id));}
 @POST @Path("/incidents/{id}/approvals") public Object requestApproval(@PathParam("id")Long id,FieldApprovalService.Request input){return safe(()->approvals.request(id,input));}
 @POST @Path("/approvals/{id}/decision") @RolesAllowed({"ADMIN","MANAGER"}) public Object approvalDecision(@PathParam("id")Long id,FieldApprovalService.Decision input){return safe(()->approvals.decide(id,input));}
 @POST @Path("/approvals/{id}/apply") public Object applyApproval(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->approvals.apply(store,id));}

 @GET @Path("/orders/{id}/state") public Object missionState(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->missions.read(store,id));}
 @PUT @Path("/orders/{id}/state") public Object missionState(@PathParam("id")Long id,MissionStateService.Change input){return safe(()->missions.change(id,input));}

 @POST @Path("/incidents/{id}/replenishment") @RolesAllowed({"ADMIN","MANAGER"}) public Object acceptReplenishment(@PathParam("id")Long id,FieldReplenishmentService.Accept input){return safe(()->replenishments.accept(id,input));}

 @GET @Path("/mission-states") public Object missionStates(@QueryParam("storeId")Long store,@QueryParam("ids")String ids){return safe(()->missions.list(store,ids));}


 @GET @Path("/orders/{id}/lines") public Object lines(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->lines.read(store,id));}
 @PUT @Path("/orders/{id}/lines") public Object line(@PathParam("id")Long id,LineDirectiveService.Change input){return safe(()->lines.change(id,input));}
 @POST @Path("/orders/{id}/lines/{line}/ack") public Object ack(@PathParam("id")Long id,@PathParam("line")Long line,@QueryParam("storeId")Long store,@QueryParam("version")long version){return safe(()->lines.acknowledge(store,id,line,version));}

 @GET @Path("/orders/{id}/handover-candidates") public Object handoverCandidates(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->handovers.candidates(store,id));}
 @GET @Path("/handovers") public Object handovers(@QueryParam("storeId")Long store,@QueryParam("orderId")Long order){return safe(()->handovers.list(store,order));}
 @POST @Path("/orders/{id}/handovers") public Object offerHandover(@PathParam("id")Long id,MissionHandoverService.Offer input){return safe(()->handovers.offer(id,input));}
 @POST @Path("/handovers/{id}/{action}") public Object acceptHandover(@PathParam("id")Long id,@PathParam("action")String action,@QueryParam("storeId")Long store){return safe(()->handovers.decide(store,id,action.toUpperCase(java.util.Locale.ROOT)));}

 @GET @Path("/support-types") public Object supportTypes(@QueryParam("storeId")Long store){return safe(()->supports.types(store));}
 @PUT @Path("/support-types") @RolesAllowed({"ADMIN","MANAGER"}) public Object supportType(SupportService.TypeInput input){return safe(()->supports.saveType(input));}
 @GET @Path("/supports") public Object supports(@QueryParam("storeId")Long store,@QueryParam("q")String q,@QueryParam("after")@DefaultValue("0")long after,@QueryParam("limit")@DefaultValue("30")int limit,@QueryParam("kind")String kind,@QueryParam("availability")String availability,@QueryParam("typeId")Long typeId,@QueryParam("locationKind")String locationKind,@QueryParam("locationId")Long locationId){return safe(()->supports.list(store,q,after,limit,kind,availability,typeId,locationKind,locationId));}
 @PUT @Path("/supports") @RolesAllowed({"ADMIN","MANAGER"}) public Object support(SupportService.Input input){return safe(()->supports.save(input));}
 @POST @Path("/supports/batch/preview") @RolesAllowed({"ADMIN","MANAGER"}) public Object previewBatch(SupportBatchService.Input input){return safe(()->batches.preview(input));}
 @POST @Path("/supports/batch") @RolesAllowed({"ADMIN","MANAGER"}) public Object createBatch(SupportBatchService.Input input){return safe(()->batches.create(input));}
 @POST @Path("/supports/attach") public Object attachSupport(com.intermarche.backoffice.engine.executing.OperatorCommandInput input){return safe(()->supports.attach(input));}

 @GET @Path("/customer-orders/lookup") @RolesAllowed({"ADMIN","MANAGER"}) public Object lookupCustomer(@QueryParam("storeId")Long store,@QueryParam("code")String code){return safe(()->cancellations.lookup(store,code));}
 @POST @Path("/customer-orders/{id}/cancel") @RolesAllowed({"ADMIN","MANAGER"}) public Object cancelCustomer(@PathParam("id")Long id,CancellationDispositionService.Cancel input){return safe(()->cancellations.cancel(id,input));}
 @GET @Path("/orders/{id}/dispositions") public Object dispositions(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->cancellations.read(store,id));}
 @GET @Path("/dispositions/{id}/choices") public Object dispositionChoices(@PathParam("id")Long id,@QueryParam("storeId")Long store){return safe(()->cancellations.choices(store,id));}
 @POST @Path("/dispositions/{id}/complete") public Object completeDisposition(@PathParam("id")Long id,CancellationDispositionService.Complete input){return safe(()->cancellations.complete(id,input));}
}
