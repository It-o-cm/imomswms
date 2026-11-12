package com.intermarche.backoffice.engine.replenishment;
import com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.stock.*;
import com.intermarche.backoffice.engine.fieldops.StockCommands;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.inject.Inject;import jakarta.annotation.security.RolesAllowed;import jakarta.transaction.Transactional;import jakarta.ws.rs.*;import jakarta.ws.rs.core.MediaType;
import java.util.*;
/** General internal transfers, independent of commercial replenishment definitions. */
@Path("/stock-transfers") @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON) @RolesAllowed({"ADMIN","MANAGER"})
public class StockTransferResource {
 @Inject ReplenishmentResource replenishments;@Inject TemporalStockService stocks;@Inject StockCommands receipts;
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="StockTransferInput") public static class Input extends ReplenishmentResource.Plan {public String mode="MISSION";}
 @GET @Path("/locations") @Transactional public Object locations(@QueryParam("storeId")Long storeId,@QueryParam("buildingId")Long buildingId,@QueryParam("productIds")String productIds){try{Store store=replenishments.scope(storeId);List<Long> ids=Arrays.stream(productIds==null?new String[0]:productIds.split(",")).map(Long::valueOf).distinct().toList();if(ids.isEmpty()||ids.size()>100)throw new BadRequestException("Choisissez de 1 à 100 produits");var em=StockAccount.getEntityManager();var rows=em.createQuery("select l.id,l.locationCode,l.product.id,(select coalesce(sum(m.quantity),0) from StockMovement m where m.location=l and m.effectiveAt<=?4) from Store s join s.buildings b join b.locations l where s=?1 and b.id=?2 and (l.product is null or l.product.id in ?3) and type(l) in (StoreShelfLocation,WarehouseShelfLocation,BufferToteStockLocation) order by l.locationCode",Object[].class).setParameter(1,store).setParameter(2,buildingId).setParameter(3,ids).setParameter(4,DateTimeProvider.now()).getResultList();return rows.stream().map(r->{var v=new LinkedHashMap<String,Object>();v.put("id",r[0]);v.put("code",r[1]);v.put("productId",r[2]);v.put("physical",r[3]);return v;}).toList();}catch(RuntimeException e){throw OperatorFailure.safe(e);}}
 @POST @Transactional public Object create(Input input){try{
  if(input==null||!Set.of("MISSION","SINGLE_GESTURE").contains(input.mode==null?"":input.mode))throw new BadRequestException("Choisissez une mission ou un déplacement en un seul geste");
  input.operation=ReplenishmentOrder.Operation.STOCK_TRANSFER;Store store=replenishments.scope(input.storeId);StockAccount.getEntityManager().lock(store,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
  String fingerprint=StockTraceLedger.key(replenishments.mapper.valueToTree(input).toString());var previous=receipts.previous(store,input.requestKey,fingerprint);if(previous!=null)return receipts.value(previous);
  var result=replenishments.create(input);ReplenishmentOrder order=ReplenishmentOrder.findById(((Number)result.get("id")).longValue());
  if("SINGLE_GESTURE".equals(input.mode)){
   if(!com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(order.sourceBuilding).equals(com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(order.destinationBuilding)))throw new BadRequestException("Un transfert entre bâtiments exige une mission avec sortie, transit et réception");
   Employee employee=Employee.findByBadgeId(replenishments.identity.getPrincipal().getName());if(employee==null)throw new BadRequestException("Identifiez l’employé qui réalise le geste");replenishments.employeeTransit.require(employee,order,order.sourceBuilding);
   var at=DateTimeProvider.now();for(var supply:order.supplies){stocks.pick(supply.id,input.requestKey+":"+supply.id,supply.quantity,at);stocks.receive(supply.id,input.requestKey+":"+supply.id,supply.quantity,at,false);}
   order.status=BaseHandlingOrder.HandlingOrderStatus.DONE;var execution=order.createExecution(employee);execution.status=HandlingExecution.ExecutionStatus.DONE;execution.persist();StockAccount.getEntityManager().flush();result=replenishments.detail(order.id,store.id);
  }
  return receipts.record(store,input.requestKey,fingerprint,"STOCK_TRANSFER",result);
 }catch(RuntimeException e){throw OperatorFailure.safe(e);}}
}
