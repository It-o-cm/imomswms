package com.intermarche.backoffice.engine.stock;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.executing.OperatorCommandInput;
import jakarta.enterprise.context.ApplicationScoped;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.math.BigDecimal;
import java.util.*;
/** Reconciles physical item changes with the same segmented ledger, including grouped preparation. */
@ApplicationScoped @Transactional
public class PickedStockCoordinator {
 @Inject StockTraceLedger ledger;
 @Inject TemporalStockService stocks;
 @Inject com.intermarche.backoffice.engine.fieldops.TraceabilityService identities;
 @Inject com.fasterxml.jackson.databind.ObjectMapper json;
 @Inject com.intermarche.backoffice.auth.AuthenticationService authentication;
 private static final BigDecimal ZERO=BigDecimal.ZERO;
 private static class Piece {
  PickedStockState state;Product product;Long lotLineId;StockSegment origin,holding;LoadedTote legacyTote;BigDecimal quantity;
  boolean legacy(){return origin==null;}
 }
 private static BigDecimal total(PickedStockState state,List<PickedStockAllocation> allocations){return state.legacyQuantity.add(allocations.stream().filter(a->!a.shipped).map(a->a.quantity).reduce(ZERO,BigDecimal::add));}
 public void synchronize(Store store,Collection<Long> orderIds,OperatorCommandInput input,String operation){
  if(orderIds.isEmpty())return;var lines=PickedItem.getEntityManager().createQuery("select distinct line.id from PreparingOrder p join p.lots lot join lot.lines line where p.store=?1 and p.id in ?2",Long.class).setParameter(1,store).setParameter(2,orderIds).getResultList();syncLines(store,lines,input,operation);
 }
 public void syncTotes(Store store,Collection<LoadedTote> totes,String operation){if(totes.isEmpty())return;var lines=PickedItem.getEntityManager().createQuery("select distinct p.lotLine.id from PickedItem p where p.loadedTote in ?1",Long.class).setParameter(1,totes).getResultList();syncLines(store,lines,null,operation);}
 private boolean corrective(OperatorCommandInput input){return input!=null&&Set.of("RETURN_ITEM","ISOLATE","CANCEL_RETURN").contains(input.action);}
 public record ReportPick(Long lineId,Long productId,String toteCode,BigDecimal quantity,OperatorCommandInput hint){}
 public void syncLines(Store store,Collection<Long> lineIds,OperatorCommandInput input,String operation){syncLines(store,lineIds,input,operation,List.of());}
 public void syncLines(Store store,Collection<Long> lineIds,OperatorCommandInput input,String operation,List<ReportPick> reportPicks){
  if(lineIds.isEmpty())return;var em=PickedItem.getEntityManager();em.flush();
  com.intermarche.backoffice.engine.fieldops.ParcelContentGuard.requireUnchanged(store,lineIds);
  com.intermarche.backoffice.engine.fieldops.DepartureCargoGuard.requireUnchanged(store,lineIds);
  var current=PickedItem.<PickedItem>list("select p from PickedItem p join fetch p.product join fetch p.loadedTote t join fetch t.tote where p.lotLine.id in ?1 and t.tote.store=?2 and p.status=?3 and p.quantity>0 and not exists(select s.id from ShipmentLine s where s.pickedItem=p)",lineIds,store,PickedItem.PickStatus.PICKED);
  var desired=new HashMap<Long,PickedItem>();for(var item:current)desired.put(item.id,item);
  var states=PickedStockState.<PickedStockState>list("select st from PickedStockState st where st.store=?1 and st.lotLineId in ?2 and not exists(select c.id from ParcelContent c where c.pickedItem.id=st.itemId and c.parcel.materialized=true and c.parcel.state<>'CANCELLED')",store,lineIds);var byItem=new HashMap<Long,PickedStockState>();for(var state:states)byItem.put(state.itemId,state);
  var allocations=states.isEmpty()?List.<PickedStockAllocation>of():PickedStockAllocation.<PickedStockAllocation>list("select a from PickedStockAllocation a join fetch a.origin join fetch a.holding where a.state in ?1 and a.shipped=false order by a.id",states);
  var byState=new HashMap<Long,List<PickedStockAllocation>>();for(var allocation:allocations)byState.computeIfAbsent(id(allocation.state),unused->new ArrayList<>()).add(allocation);
  var pool=new ArrayList<Piece>();int part=0;
  // Retain unchanged quantities first. Released portions are available to destination items of the same commercial line.
  for(var state:states){PickedItem item=desired.get(state.itemId);if(item!=null&&!id(state.product).equals(id(item.product)))item=null;
   BigDecimal keep=item==null?ZERO:item.quantity;BigDecimal legacyKeep=state.legacyQuantity.min(keep);
   if(state.legacyQuantity.compareTo(legacyKeep)>0){var piece=new Piece();piece.state=state;piece.product=state.product;piece.lotLineId=state.lotLineId;piece.legacyTote=state.legacyTote;piece.quantity=state.legacyQuantity.subtract(legacyKeep);pool.add(piece);}
   state.legacyQuantity=legacyKeep;if(item!=null&&legacyKeep.signum()>0)state.legacyTote=item.loadedTote;keep=keep.subtract(legacyKeep);
   for(var allocation:byState.getOrDefault(state.id,List.of())){BigDecimal retained=allocation.quantity.min(keep);BigDecimal released=allocation.quantity.subtract(retained);
    if(released.signum()>0){var piece=new Piece();piece.state=state;piece.product=state.product;piece.lotLineId=state.lotLineId;piece.origin=allocation.origin;piece.holding=allocation.holding;piece.quantity=released;pool.add(piece);}
    allocation.quantity=retained;keep=keep.subtract(retained);
    if(retained.signum()>0&&item!=null){StockSegment holding=ledger.holding(allocation.holding,item.loadedTote);if(!id(holding).equals(id(allocation.holding))){if(!corrective(input))StockQualityRules.requireSegment(allocation.holding,"PICKING");ledger.move(allocation.holding,holding,retained,operation,"move-"+part++,"TOTE_TRANSFER");allocation.holding=holding;}}
   }
  }
  var consumedHints=new java.util.IdentityHashMap<ReportPick,BigDecimal>();
  for(var item:current){PickedStockState state=byItem.get(item.id);if(state==null){state=new PickedStockState();state.store=store;state.itemId=item.id;state.lotLineId=id(item.lotLine);state.product=item.product;state.persistAndFlush();byItem.put(item.id,state);}
   state.product=item.product;
   BigDecimal held=total(state,byState.getOrDefault(state.id,List.of()));BigDecimal needed=item.quantity.subtract(held);
   for(var piece:pool){if(needed.signum()<=0)break;if(piece.quantity.signum()<=0||(!Objects.equals(piece.lotLineId,id(item.lotLine))&&!(input!=null&&"REALLOCATE".equals(input.action)&&Objects.equals(piece.state.itemId,input.itemId)))||!id(piece.product).equals(id(item.product)))continue;
    BigDecimal take=needed.min(piece.quantity);if(piece.legacy()){if(!corrective(input))StockQualityRules.requireAllowed(store,item.product,null,null);state.legacyQuantity=state.legacyQuantity.add(take);state.legacyTote=item.loadedTote;}
    else{if(!corrective(input))StockQualityRules.requireSegment(piece.holding,"PICKING");StockSegment target=ledger.holding(piece.holding,item.loadedTote);ledger.move(piece.holding,target,take,operation,"repack-"+part++,"TOTE_TRANSFER");var allocation=new PickedStockAllocation();allocation.state=state;allocation.origin=piece.origin;allocation.holding=target;allocation.quantity=take;allocation.persist();byState.computeIfAbsent(state.id,unused->new ArrayList<>()).add(allocation);}
    piece.quantity=piece.quantity.subtract(take);needed=needed.subtract(take);
   }
   if(needed.signum()>0){
    var selectedHints=reportPicks.stream().filter(r->r.lineId.equals(id(item.lotLine))&&r.productId.equals(id(item.product))&&r.toteCode.equals(item.loadedTote.tote.code)).toList();
    if(selectedHints.isEmpty()){OperatorCommandInput hint=input;if(hint!=null&&hint.itemId!=null){PickedItem referenced=PickedItem.findById(hint.itemId);if(referenced==null||!id(referenced.lotLine).equals(id(item.lotLine)))hint=null;}selectedHints=List.of(new ReportPick(id(item.lotLine),id(item.product),item.loadedTote.tote.code,needed,hint));}
    for(var reportPick:selectedHints){if(needed.signum()==0)break;BigDecimal availableHint=reportPick.quantity.subtract(consumedHints.getOrDefault(reportPick,BigDecimal.ZERO));if(availableHint.signum()<=0)continue;BigDecimal amount=needed.min(availableHint);consumedHints.merge(reportPick,amount,BigDecimal::add);var hint=reportPick.hint;var trace=identity(store,item.product,hint);BaseLocation source=source(item);Long selected=hint==null?null:hint.stockSegmentId;
     for(var slice:ledger.allocate(store,item.product,source,amount,selected,trace.lot,trace.serial,"PICKING")){StockSegment target=ledger.holding(slice.segment(),item.loadedTote);ledger.move(slice.segment(),target,slice.quantity(),operation,"pick-"+part++,"PICKING_TRACE");var allocation=new PickedStockAllocation();allocation.state=state;allocation.origin=slice.segment();allocation.holding=target;allocation.quantity=slice.quantity();allocation.persist();byState.computeIfAbsent(state.id,unused->new ArrayList<>()).add(allocation);}
     needed=needed.subtract(amount);
    }
    if(needed.signum()>0)throw new BadRequestException("Les lots du rapport ne couvrent pas la quantité préparée");
   }
  }
  // A released portion not reused by a target is an explicit return/correction, not a new receipt.
  for(var piece:pool)if(piece.quantity.signum()>0&&!piece.legacy())ledger.move(piece.holding,piece.origin,piece.quantity,operation,"return-"+part++,"PICKING_RETURN");
  if(input!=null&&Set.of("CONCLUDE","VALIDATE_REPORT","DEPOSIT_TOTE").contains(input.action))for(var allocation:PickedStockAllocation.<PickedStockAllocation>list("state.store=?1 and state.lotLineId in ?2 and quantity>0 and shipped=false",store,lineIds))StockQualityRules.requireSegment(allocation.holding,"PICKING");
  if(input!=null&&Set.of("CONCLUDE","VALIDATE_REPORT","DEPOSIT_TOTE").contains(input.action))for(var state:states)if(state.legacyQuantity.signum()>0){StockQualityRules.requireAllowed(store,state.product,null,null);var policy=TraceabilityPolicy.<TraceabilityPolicy>find("store=?1 and product=?2 and context='PICKING'",store,state.product).firstResult();if(policy!=null&&(policy.lotRequired||policy.serialRequired||policy.dateRequired))throw new BadRequestException("Prélèvement historique à identifier avant validation");}
  em.flush();
 }
 public static BaseLocation source(PickedItem item){BaseLocation location=item.sourceLocation!=null?item.sourceLocation:item.lotLine.location!=null?item.lotLine.location:item.lotLine.fulfillmentLine==null?null:item.lotLine.fulfillmentLine.location;if(location==null||location.product==null||id(location.product).equals(id(item.product)))return location;
  var buildings=BaseLocation.findBuildingsByLocations(List.of(location));if(buildings.size()!=1)throw new BadRequestException("Bâtiment du produit de substitution inconnu");
  var alternatives=BaseLocation.getEntityManager().createQuery("select l from Building b join b.locations l where b=?1 and l.product=?2 and type(l) in (StoreShelfLocation,WarehouseShelfLocation)",BaseLocation.class).setParameter(1,buildings.getFirst()).setParameter(2,item.product).getResultList();if(alternatives.size()!=1)throw new BadRequestException("Choisissez l’emplacement réel du produit de substitution");item.sourceLocation=alternatives.getFirst();return item.sourceLocation;
 }
 public record Identity(ManufacturerLot lot,ProductSerial serial){}
 public Identity identity(Store store,Product product,OperatorCommandInput input){
  ManufacturerLot lot=input==null||input.manufacturerLotId==null?null:ManufacturerLot.find("id=?1 and store=?2 and product=?3",input.manufacturerLotId,store,product).firstResult();ProductSerial serial=input==null||input.serialId==null?null:ProductSerial.find("id=?1 and store=?2 and product=?3",input.serialId,store,product).firstResult();
  if(input!=null&&(input.manufacturerLotId!=null&&lot==null||input.serialId!=null&&serial==null))throw new BadRequestException("Lot ou série d’un autre contexte");
  if(input!=null&&input.scanId!=null){var scan=com.intermarche.backoffice.domain.catalog.ProductScanReceipt.<com.intermarche.backoffice.domain.catalog.ProductScanReceipt>find("store=?1 and principal=?2 and scanId=?3",store,authentication.getConnectedAccount().getUsername(),input.scanId).firstResult();if(scan==null)throw new BadRequestException("Scan absent de cette session");try{var resolved=json.readTree(scan.result);if(!"RESOLVED".equals(resolved.path("state").asText())||resolved.path("product").path("id").asLong()!=id(product))throw new BadRequestException("Le scan ne désigne pas ce produit");var fields=resolved.path("fields");if(fields.has("10")){var r=new com.intermarche.backoffice.engine.fieldops.TraceabilityService.Input();r.storeId=store.id;r.productId=id(product);r.code=fields.path("10").asText();if(fields.has("11"))r.manufacturedOn=java.time.LocalDate.parse(fields.path("11").asText());if(fields.has("15"))r.bestBefore=java.time.LocalDate.parse(fields.path("15").asText());if(fields.has("17"))r.useBy=java.time.LocalDate.parse(fields.path("17").asText());@SuppressWarnings("unchecked")var saved=(Map<String,Object>)identities.saveLot(r);ManufacturerLot scanned=ManufacturerLot.findById(((Number)saved.get("id")).longValue());if(lot!=null&&!id(lot).equals(id(scanned)))throw new BadRequestException("Le lot choisi diffère du scan");lot=scanned;}
    if(fields.has("21")){String code=fields.path("21").asText();ProductSerial scanned=ProductSerial.find("store=?1 and product=?2 and code=?3",store,product,code).firstResult();if(scanned==null){var r=new com.intermarche.backoffice.engine.fieldops.TraceabilityService.Input();r.storeId=store.id;r.productId=id(product);r.lotId=lot==null?null:id(lot);r.code=code;r.claimKey="scan:"+input.scanId;@SuppressWarnings("unchecked")var saved=(Map<String,Object>)identities.claimSerial(r);scanned=ProductSerial.findById(((Number)saved.get("id")).longValue());}if(serial!=null&&!id(serial).equals(id(scanned)))throw new BadRequestException("La série choisie diffère du scan");serial=scanned;}
   }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}}
  if(serial!=null){if(lot==null)lot=serial.lot;else if(!Objects.equals(serial.lot==null?null:id(serial.lot),id(lot)))throw new BadRequestException("Série d’un autre lot");}return new Identity(lot,serial);
 }
 public boolean materializeLegacy(PickedStockState state){
  if(state.legacyQuantity.signum()<=0)return true;PickedItem item=PickedItem.findById(state.itemId);if(item==null||item.loadedTote==null||item.status!=PickedItem.PickStatus.PICKED)return false;
  BaseLocation source;try{source=source(item);}catch(IllegalArgumentException|BadRequestException absent){return false;}if(source==null||BaseLocation.findBuildingsByLocations(List.of(source)).size()!=1)return false;
  var account=stocks.account(id(state.store),id(state.product));StockSegment origin=ledger.segment(account,source,null,null,null,"UNASSESSED");StockSegment holding=ledger.holding(origin,item.loadedTote);BigDecimal quantity=state.legacyQuantity;
  // Reconcile known current custody now; do not invent an earlier scan time or manufacturer lot.
  ledger.move(origin,holding,quantity,"legacy-custody:"+state.id,"initial","LEGACY_CUSTODY");var allocation=new PickedStockAllocation();allocation.state=state;allocation.origin=origin;allocation.holding=holding;allocation.quantity=quantity;allocation.persist();state.legacyQuantity=ZERO;state.legacyTote=item.loadedTote;return true;
 }
 public Map<Long,List<Map<String,Object>>> details(Store store,Collection<Long> orders){
  var result=new HashMap<Long,List<Map<String,Object>>>();if(orders.isEmpty())return result;
  for(var row:PickedItem.getEntityManager().createQuery("select st.itemId,a.quantity,h.id,h.lot.id,h.lot.code,h.lot.useBy,h.lot.bestBefore,h.serial.id,h.serial.code,h.quality,h.support.code,a.shipped from PickedStockAllocation a join a.state st join a.holding h left join h.lot left join h.serial left join h.support where st.store=?1 and st.lotLineId in (select line.id from PreparingOrder p join p.lots lot join lot.lines line where p.id in ?2) and a.quantity>0 order by a.id",Object[].class).setParameter(1,store).setParameter(2,orders).getResultList()){
   var v=new LinkedHashMap<String,Object>();String[] keys={"itemId","quantity","segmentId","lotId","lotCode","useBy","bestBefore","serialId","serialCode","quality","supportCode","shipped"};for(int n=1;n<keys.length;n++)v.put(keys[n],row[n]);result.computeIfAbsent((Long)row[0],unused->new ArrayList<>()).add(v);
  }
  return result;
 }
 public void ship(Store store,PickedItem item,String operation){
  // Existing historical items are explicitly marked by V8; new untracked test/import data is reconciled first.
  var state=PickedStockState.<PickedStockState>find("store=?1 and itemId=?2",store,item.id).firstResult();if(state==null){syncLines(store,List.of(id(item.lotLine)),null,operation+":prepare");state=PickedStockState.find("store=?1 and itemId=?2",store,item.id).firstResult();}
  if(state==null)throw new BadRequestException("Traçabilité du prélèvement absente");var allocations=PickedStockAllocation.<PickedStockAllocation>list("state=?1 and shipped=false and quantity>0",state);BigDecimal recorded=total(state,allocations);if(recorded.compareTo(item.quantity)!=0)throw new BadRequestException("Quantité du bac différente de sa traçabilité : faites corriger la préparation");
  var holdingIds=allocations.stream().map(a->id(a.holding)).distinct().toList();var available=new HashMap<Long,BigDecimal>();if(!holdingIds.isEmpty())for(var row:StockMovement.getEntityManager().createQuery("select m.segment.id,sum(m.quantity) from StockMovement m where m.segment.id in ?1 and m.effectiveAt<=?2 group by m.segment.id",Object[].class).setParameter(1,holdingIds).setParameter(2,DateTimeProvider.now()).getResultList())available.put((Long)row[0],(BigDecimal)row[1]);
  for(var allocation:allocations){Long holding=id(allocation.holding);BigDecimal balance=available.getOrDefault(holding,ZERO);if(balance.compareTo(allocation.quantity)<0)throw new OperatorFailure(409,"PICKED_STOCK_MISSING","Le stock physique du bac ne couvre plus la quantité préparée : contrôlez l’inventaire et complétez la préparation avant expédition");available.put(holding,balance.subtract(allocation.quantity));StockQualityRules.requireSegment(allocation.holding,"SHIPPING");ledger.entry(allocation.holding,allocation.quantity.negate(),operation,"ship-"+allocation.id,"SHIPMENT",DateTimeProvider.now());allocation.shipped=true;}
  if(state.legacyQuantity.signum()>0){StockQualityRules.requireAllowed(store,item.product,null,null);var policy=TraceabilityPolicy.<TraceabilityPolicy>find("store=?1 and product=?2 and context='SHIPPING'",store,item.product).firstResult();if(policy!=null&&(policy.lotRequired||policy.serialRequired||policy.dateRequired))throw new BadRequestException("Produits historiques sans traçabilité : contrôlez lot, série et date avant l’expédition");BaseLocation source=source(item);if(source==null)throw new BadRequestException("Provenance historique inconnue");var account=stocks.account(store.id,id(item.product));stocks.adjust(account.id,id(source),operation+":legacy",state.legacyQuantity.negate(),DateTimeProvider.now());state.legacyQuantity=ZERO;}
 }
}
