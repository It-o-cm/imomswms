package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.wms.handling.*;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.client.PickingExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.*;

/** Server-owned reconciliation of shelf remainders and traceable tote contents. */
@ApplicationScoped
public class MixedPreparationService {
 @Inject BufferOccupancyService buffers;
 @Inject ToteCapacityService capacities;
 @Inject PickingResolutionService resolutions;
 public static boolean supports(BaseHandlingOrder order){return order instanceof PackingOrder || order instanceof GroupingOrder;}
 /** Positive keys identify shelf remainders (LotLine); negative keys identify existing PickedItems. */
 public record Source(Long key,LotLine line,PickedItem item,BigDecimal quantity,Building building,String label) {
  public com.intermarche.backoffice.domain.order.Product product(){return item==null?line.product:item.product;}
  public String snapshot(){
   LoadedTote tote=item==null?null:item.loadedTote;
   return key+":"+line.quantity.stripTrailingZeros().toPlainString()+":"+quantity.stripTrailingZeros().toPlainString()+":"+(line.location==null?"?":line.location.id)+":"+":"+product().id+":"+(tote==null?"SHELF":tote.storageType+":"+tote.id+":"+(tote.buffer==null?"?":tote.buffer.id)+":"+(tote.cabinet==null?"?":tote.cabinet.id)+":"+tote.cellRow+":"+tote.cellColumn);
  }
 }
 public List<Source> sources(PreparingOrder order){
  if(order.status==BaseHandlingOrder.HandlingOrderStatus.DONE || order.status==BaseHandlingOrder.HandlingOrderStatus.CANCELLED)throw new IllegalStateException("Cet ordre est déjà terminé ou annulé.");
  List<Source> result=new ArrayList<>();Set<Long> seen=new HashSet<>();
  var cancelledLines=com.intermarche.backoffice.domain.fieldops.CancelledMissionLine.<com.intermarche.backoffice.domain.fieldops.CancelledMissionLine>list("store=?1 and line.id in ?2",order.store,order.lots.stream().flatMap(l->l.lines.stream()).map(l->l.id).toList()).stream().map(c->com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(c.line)).collect(java.util.stream.Collectors.toSet());
  for(Lot lot:order.lots)for(LotLine line:lot.lines){
   if(cancelledLines.contains(line.id)||!seen.add(line.id))continue;
   BigDecimal picked=BigDecimal.ZERO;
   List<PickedItem> items=PickedItem.list("lotLine.id = ?1 and status = ?2",line.id,PickedItem.PickStatus.PICKED);
   for(PickedItem item:items){
    if(com.intermarche.backoffice.domain.shipping.ShipmentLine.count("pickedItem",item)>0)throw new IllegalStateException("Un article déjà expédié ne peut plus être reconditionné.");
    if(item.loadedTote==null || item.loadedTote.status!=LoadedTote.Status.IN_USE)throw new IllegalStateException("Produit déjà prélevé sans bac actif : "+line.product.name);
    if(item.quantity==null || item.quantity.signum()<=0)throw new IllegalStateException("Quantité en bac invalide.");
    LoadedTote t=item.loadedTote;
    String label="Bac "+t.tote.code+" · "+(t.buffer==null?"emplacement non renseigné":t.buffer.building.buildingCode+" · "+t.buffer.locationDescription);
    if(t.cabinet!=null)label+=" · armoire "+t.cabinet.code+" · cellule "+t.cellRow+"/"+t.cellColumn;
    result.add(new Source(-item.id,line,item,item.quantity,t.buffer==null?lot.chunk.building:t.buffer.building,label));picked=picked.add(item.quantity);
   }
   BigDecimal remaining=line.quantity.subtract(picked).subtract(resolutions.unfulfilled(line));
   if(remaining.signum()<0)throw new IllegalStateException("Quantité déjà prélevée supérieure au besoin : "+line.product.name);
   if(remaining.signum()>0)result.add(new Source(line.id,line,null,remaining,lot.chunk.building,"À collecter en rayon"));
  }
  return result;
 }
 /** Legacy conclude endpoints must obey the same quantitative coverage as batch reports. */
 public Set<LoadedTote> completeTotes(PreparingOrder order){
  var sources=sources(order);Set<LoadedTote> result=new HashSet<>();Set<Long> allowed=new HashSet<>();
  for(var source:sources){if(source.item==null)throw new IllegalStateException("Préparation incomplète : "+source.line.product.name+" · reste à collecter : "+source.quantity);result.add(source.item.loadedTote);allowed.add(source.item.id);}
  if(result.isEmpty())throw new IllegalStateException("Aucun produit préparé.");
  for(var tote:result)if(tote.items.stream().anyMatch(i->i.status==PickedItem.PickStatus.PICKED && i.quantity.signum()>0 && !allowed.contains(i.id)) || BufferToteStockLocation.count("loadedTote",tote)>0 || PickingToteLocation.count("protectedTote",tote)>0)throw new IllegalStateException("Le bac "+tote.tote.code+" contient des produits hors de cette préparation.");
  return result;
 }
 @Inject com.intermarche.backoffice.engine.operator.OperationQuantities activityQuantities;
 public void conclude(PreparingOrder order,Employee employee,HandlingOrderService.OrderReportDTO report,Long defaultBuffer){
  var activityAmounts=new ArrayList<com.intermarche.backoffice.engine.operator.OperationQuantities.Amount>();
  List<Source> sources=sources(order);Map<Long,Source> byKey=new HashMap<>();sources.forEach(s->byKey.put(s.key,s));
  if(sources.isEmpty())throw new IllegalArgumentException("Cet ordre ne contient aucun produit à préparer.");
  Map<Long,BigDecimal> totals=new HashMap<>();Set<String> codes=new HashSet<>();
  if(report==null || report.totes==null)throw new IllegalArgumentException("Rapport de préparation manquant.");
  // Validate the entire report before any item is moved.
  for(var tote:report.totes){
   if(tote==null || tote.code==null || !codes.add(tote.code))throw new IllegalArgumentException("Bac manquant ou dupliqué.");
   if(tote.items==null || tote.items.isEmpty())throw new IllegalArgumentException("Bac sans produits.");
   for(var item:tote.items){
    Source source=item==null?null:byKey.get(item.pickedItemId);
    if(source==null || item.qty==null || item.qty.signum()<=0 || item.qty.stripTrailingZeros().scale()>3)throw new IllegalArgumentException("Article ou quantité invalide dans le rapport.");
    if(source.product().productType==com.intermarche.backoffice.domain.order.ProductType.UNIT && item.qty.stripTrailingZeros().scale()>0)throw new IllegalArgumentException("Un produit à la pièce exige une quantité entière.");
    if(!source.snapshot().equals(item.sourceSnapshot))throw new IllegalArgumentException("La source a changé depuis l’ouverture du parcours. Actualisez la préparation.");
    totals.merge(source.key,item.qty,BigDecimal::add);
   }
  }
  if(report.missing!=null)for(var missing:report.missing){
   Source source=missing==null?null:byKey.get(missing.pickedItemId);
   if(source==null || source.item!=null || missing.qty==null || missing.qty.signum()<=0)throw new IllegalArgumentException("Manquant invalide : indiquez le reliquat en rayon.");
   activityAmounts.add(com.intermarche.backoffice.engine.operator.OperationQuantities.amount("MISSING",source.product(),source.line.id,missing.qty));
   resolutions.record(order,source.line,employee,missing.qty,null,missing.reason);totals.merge(source.key,missing.qty,BigDecimal::add);
  }
  for(Source source:sources)if(totals.getOrDefault(source.key,BigDecimal.ZERO).compareTo(source.quantity)!=0)throw new IllegalArgumentException("Quantité incomplète ou excessive : "+source.line.product.name+" · "+source.label+" (attendu : "+source.quantity+").");
  var capacityRequest=new ToteCapacityService.Request();capacityRequest.orderIds=List.of(order.id);capacityRequest.totes=new ArrayList<>();
  for(var tote:report.totes){var load=new ToteCapacityService.Load();load.code=tote.code;load.items=new ArrayList<>();for(var item:tote.items){var ci=new ToteCapacityService.Item();ci.sourceOrderId=order.id;ci.pickedItemId=item.pickedItemId;ci.quantity=item.qty;ci.productId=item.productId;load.items.add(ci);}capacityRequest.totes.add(load);}
  if(capacities.preview(capacityRequest).stream().anyMatch(c->c.overCapacity))throw new IllegalArgumentException("La capacité d’un bac est dépassée.");
  // Consumed source items retain their original execution identity for audit, but no physical quantity.
  for(Source source:sources)if(source.item!=null){source.item.quantity=BigDecimal.ZERO;source.item.status=PickedItem.PickStatus.CANCELED;}
  Set<LoadedTote> sourceTotes=new HashSet<>();for(Source source:sources)if(source.item!=null)sourceTotes.add(source.item.loadedTote);
  for(var sourceTote:sourceTotes)if(!codes.contains(sourceTote.tote.code) && !BufferOccupancyService.hasContents(sourceTote)){
   sourceTote.status=LoadedTote.Status.DELIVERED;sourceTote.clearCell();sourceTote.buffer=null;
  }
  Set<LoadedTote> destinations=new HashSet<>();
  for(var tote:report.totes){
   Tote physical=Tote.find("code",tote.code).firstResult();
   if(physical==null || !physical.store.id.equals(order.store.id))throw new IllegalArgumentException("Bac hors du magasin.");
   for(var dto:tote.items)com.intermarche.backoffice.engine.fieldops.DepositTargetGuard.requirePrepared(order.store,order,tote.code,dto.depositScanCode,byKey.get(dto.pickedItemId).line);
   LoadedTote target=LoadedTote.find("tote = ?1 and status = ?2",physical,LoadedTote.Status.IN_USE).firstResult();
   if(target!=null){
    final LoadedTote existing=target;
    Set<Long> allowed=new HashSet<>();sources.stream().filter(s->s.item!=null && s.item.loadedTote.id.equals(existing.id)).forEach(s->allowed.add(s.item.id));
    if(allowed.isEmpty() || target.items.stream().anyMatch(i->i.status==PickedItem.PickStatus.PICKED && i.quantity.signum()>0 && !allowed.contains(i.id)) || BufferToteStockLocation.count("loadedTote",target)>0 || PickingToteLocation.count("protectedTote",target)>0)throw new IllegalArgumentException("Ce bac contient des produits hors de cette préparation. Choisissez un bac libre.");
    if(target.storageType!=tote.zone)throw new IllegalArgumentException("Le conditionnement du bac source doit être conservé.");
   } else {target=LoadedTote.useAnEmptyTote(physical);target.storageType=tote.zone;target.persist();}
   destinations.add(target);
   for(var dto:tote.items){
    Source source=byKey.get(dto.pickedItemId);com.intermarche.backoffice.engine.fieldops.LineDirectiveService.requireReady(order,"LOT_LINE",source.line.id);
    if(!physical.isUsageAllowed(source.product().temperatureZone) || source.product().temperatureZone!=tote.zone)throw new IllegalArgumentException("Conditionnement incompatible avec le produit.");
    PickedItem moved=new PickedItem();moved.lotLine=source.line;moved.product=dto.productId==null?source.product():resolutions.replacement(order.store,source.line.product,dto.productId);
    if(source.item!=null && dto.productId!=null && !source.product().id.equals(dto.productId))throw new IllegalArgumentException("Un produit déjà en bac ne peut pas être substitué comme un prélèvement en rayon.");
    activityAmounts.add(com.intermarche.backoffice.engine.operator.OperationQuantities.amount(order instanceof GroupingOrder?"GROUPED":"PACKED",moved.product,source.line.id,dto.qty));
    activityAmounts.add(com.intermarche.backoffice.engine.operator.OperationQuantities.amount(source.item==null?"PICKED":"RETRIEVED_FROM_TOTE",moved.product,source.line.id,dto.qty));
    if(source.item==null&&!moved.product.id.equals(source.line.product.id))activityAmounts.add(com.intermarche.backoffice.engine.operator.OperationQuantities.amount("SUBSTITUTED",moved.product,source.line.id,dto.qty));
    if(dto.productId!=null && !moved.product.id.equals(source.line.product.id))resolutions.record(order,source.line,employee,dto.qty,moved.product,dto.missingReason);moved.quantity=dto.qty;moved.status=PickedItem.PickStatus.PICKED;moved.loadedTote=target;moved.persist();target.items.add(moved);
   }
   Long destination=order instanceof GroupingOrder grouping?grouping.buffer==null?null:grouping.buffer.id:tote.bufferId==null?defaultBuffer:tote.bufferId;
   if(destination==null)throw new IllegalArgumentException("Choisissez le buffer de dépôt.");
   buffers.deposit(target,destination,tote.cabinetId,tote.cellRow,tote.cellColumn,order.store);
  }
  // Pending placeholders from earlier collection must not be counted again.
  for(Lot lot:order.lots)for(LotLine line:lot.lines){List<PickedItem> pending=PickedItem.list("lotLine.id = ?1 and status = ?2",line.id,PickedItem.PickStatus.PENDING);for(PickedItem item:pending){item.status=PickedItem.PickStatus.CANCELED;item.quantity=BigDecimal.ZERO;}}
  HandlingExecution execution=HandlingExecution.find("handlingOrder",order).firstResult();
  if(execution==null){
  if(order instanceof GroupingOrder){GroupingExecution grouping=new GroupingExecution();grouping.handlingOrder=order;grouping.employee=employee;grouping.loadedTotes.addAll(destinations);execution=grouping;}
  else {PickingExecution picking=new PickingExecution();picking.handlingOrder=order;picking.employee=employee;picking.loadedTotes.addAll(destinations);execution=picking;}
  } else if(execution instanceof PickingExecution picking)picking.loadedTotes.addAll(destinations);
  else if(execution instanceof GroupingExecution grouping)grouping.loadedTotes.addAll(destinations);
  execution.status=HandlingExecution.ExecutionStatus.DONE;execution.persist();order.status=BaseHandlingOrder.HandlingOrderStatus.DONE;
  activityQuantities.save(order.store,employee.badgeId,"MIXED_REPORT",execution.id,order instanceof GroupingOrder?"GROUPING_REPORT":"PACKING_REPORT",activityAmounts);
  for(Lot lot:order.lots)lot.status=order instanceof PackingOrder?Lot.LotStatus.PACKED:Lot.LotStatus.COLLECTED;
 }
}
