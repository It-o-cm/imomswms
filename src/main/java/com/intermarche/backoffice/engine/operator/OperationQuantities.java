package com.intermarche.backoffice.engine.operator;
import com.intermarche.backoffice.domain.operator.ActivityQuantity;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.engine.executing.*;
import com.intermarche.backoffice.engine.catalog.ProductInformationService;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.util.*;
/** Explicit measures of work. Different measures must never be added to a single productivity total. */
@ApplicationScoped
public class OperationQuantities {
 public record Amount(String metric,Long productId,Long lineId,String unit,BigDecimal quantity){}
 public static Amount amount(String metric,Product p,Long line,BigDecimal qty){return new Amount(metric,com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(p),line,ProductInformationService.unit(p),qty);}
 public void save(Store store,String actor,String source,Long receipt,String action,List<Amount> amounts){
  int ordinal=0;for(var a:amounts){if(a.quantity==null||a.unit==null)continue;var row=new ActivityQuantity();row.store=store;row.actor=actor;row.source=source;row.receiptId=receipt;row.ordinal=ordinal++;row.action=action;row.metric=a.metric;row.productId=a.productId;row.lineId=a.lineId;row.unit=a.unit.toUpperCase(Locale.ROOT);row.quantity=a.quantity;row.persist();}
 }
 /** Historical commands have no client key. A receipt identifies each committed gesture;
  * a failed transaction rolls back both the gesture and its measures. */
 public void legacy(com.intermarche.backoffice.domain.handling.HandlingExecution execution,String action,List<Amount> amounts){
  if(amounts.isEmpty())return;
  var receipt=new StockOperationReceipt();receipt.store=execution.handlingOrder.store;receipt.actor=execution.employee.badgeId;
  receipt.requestKey=UUID.randomUUID().toString();receipt.fingerprint="legacy-execution:"+execution.id;receipt.operation=action;receipt.result="{}";receipt.quantityProjectionVersion=1;receipt.persist();
  save(receipt.store,receipt.actor,"OPERATION",receipt.id,action,amounts);
 }
 public boolean hasLegacyWork(com.intermarche.backoffice.domain.handling.HandlingExecution execution){
  if(StockOperationReceipt.count("store=?1 and fingerprint=?2",execution.handlingOrder.store,"legacy-execution:"+execution.id)>0)return true;
  // Compatibility with receipts written before the execution correlation existed.
  // A proved gesture on one of these lines since reservation must not be erased.
  if(execution instanceof com.intermarche.backoffice.client.PickingExecution p && execution.createdAt!=null){
   var lines=p.itemsToPick.stream().filter(i->i.lotLine!=null).map(i->com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(i.lotLine)).distinct().toList();
   if(!lines.isEmpty())return ActivityQuantity.count("store=?1 and source='OPERATION' and lineId in ?2 and createdAt>=?3 and action in ?4",execution.handlingOrder.store,lines,execution.createdAt,List.of("PICK","MOVE_ITEM","RETURN_ITEM","MISSING","COLLECTION_REPORT","CONCLUDE","DEPOSIT_TOTE"))>0;
  }return false;
 }
 public List<Amount> completion(com.intermarche.backoffice.domain.handling.HandlingExecution e){
  var rows=new ArrayList<Amount>();var o=org.hibernate.Hibernate.unproxy(e.handlingOrder,com.intermarche.backoffice.domain.wms.BaseHandlingOrder.class);
  if(MixedPreparationService.supports(o)){
   var totes=e instanceof com.intermarche.backoffice.client.PickingExecution p?p.loadedTotes:e instanceof GroupingExecution g?g.loadedTotes:Set.<LoadedTote>of();
   for(var t:totes)for(var i:t.items)if(i.status==PickedItem.PickStatus.PICKED&&i.quantity.signum()>0)rows.add(amount(o instanceof com.intermarche.backoffice.domain.wms.handling.GroupingOrder?"GROUPED":"PACKED",i.product,i.lotLine.id,i.quantity));
  }return rows;
 }
 public List<Amount> before(OperatorCommandInput c){
  var rows=new ArrayList<Amount>();

  if(Set.of("MOVE_ITEM","RETURN_ITEM","MISSING").contains(c.action)&&c.itemId!=null){PickedItem item=PickedItem.findById(c.itemId);if(item!=null)rows.add(amount(switch(c.action){case "MOVE_ITEM"->"TRANSFERRED";case "RETURN_ITEM"->"RETURNED_TO_SHELF";default->"MISSING";},"MISSING".equals(c.action)&&item.lotLine!=null?item.lotLine.product:item.product,item.lotLine==null?null:item.lotLine.id,item.quantity));}
  if(Set.of("DEPOSIT_TOTE","RETRIEVE_TOTE").contains(c.action)&&c.toteCode!=null){for(PickedItem i:PickedItem.<PickedItem>list("select i from PickedItem i join fetch i.product join fetch i.lotLine where i.loadedTote.tote.code=?1 and i.status=?2 and i.quantity>0 and i.loadedTote.status=?3 and not exists(select sh.id from ShipmentLine sh where sh.pickedItem=i)",c.toteCode,PickedItem.PickStatus.PICKED,LoadedTote.Status.IN_USE))rows.add(amount(c.action.equals("DEPOSIT_TOTE")?"DEPOSITED":"RETRIEVED",i.product,i.lotLine.id,i.quantity));}
  if("CORRECT_SHORTAGE".equals(c.action)&&c.shortageId!=null){PickingShortage shortage=PickingShortage.findById(c.shortageId);if(shortage!=null)rows.add(amount("MISSING",shortage.requestedProduct,shortage.lotLine.id,c.quantity==null?null:c.quantity.negate()));}
  return rows;
 }
 public List<Amount> after(OperatorCommandInput c,List<Amount> rows){
  rows=new ArrayList<>(rows);
  if("CONCLUDE".equals(c.action)&&c.executionId!=null){var e=com.intermarche.backoffice.domain.handling.HandlingExecution.<com.intermarche.backoffice.domain.handling.HandlingExecution>findById(c.executionId);if(e!=null)rows.addAll(completion(org.hibernate.Hibernate.unproxy(e,com.intermarche.backoffice.domain.handling.HandlingExecution.class)));}
  if("PICK".equals(c.action)&&c.itemId!=null){PickedItem i=PickedItem.findById(c.itemId);if(i!=null){Product p=c.productId==null?i.product:Product.findById(c.productId);rows.add(amount("PICKED",p,i.lotLine.id,c.quantity));if(!p.id.equals(i.lotLine.product.id))rows.add(amount("SUBSTITUTED",p,i.lotLine.id,c.quantity));}}

  return rows;
 }
}
