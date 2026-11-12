package com.intermarche.backoffice.engine.stock;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.operator.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.math.*;
import java.time.*;
import java.util.*;
/** Internal ledger operations. Callers hold the store writer lock and own the physical command receipt. */
@ApplicationScoped @Transactional
public class StockTraceLedger {
 @Inject TemporalStockService stocks;
 @Inject InventoryScopeGuard freezes;
 @Inject com.intermarche.backoffice.engine.fieldops.SupportCatalog supports;
 public record Slice(StockSegment segment,BigDecimal quantity){}
 public static String key(Object... values){try{return CommandCanonical.hash(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Arrays.asList(values)));}catch(Exception e){throw new IllegalArgumentException(e);}}
 public StockSegment segment(StockAccount account,BaseLocation location,com.intermarche.backoffice.domain.fieldops.PhysicalSupport support,ManufacturerLot lot,ProductSerial serial,String quality){
  if(support==null&&lot==null&&serial==null&&"UNASSESSED".equals(quality))return StockSegments.legacy(account,location);
  String key=key(id(location),support==null?null:id(support),lot==null?null:id(lot),serial==null?null:id(serial),quality);
  StockSegment s=StockSegment.find("account=?1 and segmentKey=?2",account,key).firstResult();if(s==null){s=new StockSegment();s.account=account;s.location=location;s.support=support;s.lot=lot;s.serial=serial;s.quality=quality;s.segmentKey=key;s.persistAndFlush();}return s;
 }
 public StockSegment holding(StockSegment source,LoadedTote tote){
  var account=source.account;List<? extends Building> buildings=BaseLocation.findBuildingsByLocations(List.of(source.location));if(buildings.size()!=1)throw new BadRequestException("Bâtiment de provenance non déterminé");Building building=tote.buffer==null?buildings.getFirst():tote.buffer.building;
  PickingToteLocation location=PickingToteLocation.find("protectedTote=?1 and product=?2 and traceOnly=true and id in (select l.id from Building b join b.locations l where b=?3)",tote,account.product,building).firstResult();
  if(location==null){
   location=new PickingToteLocation();location.traceOnly=true;location.product=account.product;location.protectedTote=tote;location.area="PREPARATION";location.aisle="BAC";location.unit="BAC";location.elevation="1";location.position="1";location.locationCode="BAC-"+tote.id+"-"+id(account.product)+"-"+id(building);location.persist();building.locations.add(location);
  }
  supports.register(tote.tote);
  var support=com.intermarche.backoffice.domain.fieldops.PhysicalSupport.<com.intermarche.backoffice.domain.fieldops.PhysicalSupport>find("tote",tote.tote).firstResult();var target=segment(account,location,support,source.lot,source.serial,source.quality);if(source.receivedAt!=null&&(target.receivedAt==null||target.receivedAt.isAfter(source.receivedAt)))target.receivedAt=source.receivedAt;return target;
 }
 public void entry(StockSegment segment,BigDecimal quantity,String operation,String suffix,String reason,LocalDateTime at){
  String event="trace:"+key(operation,suffix);StockMovement previous=StockMovement.find("account=?1 and eventKey=?2",segment.account,event).firstResult();
  if(previous!=null){if(!id(previous.segment).equals(segment.id)||previous.quantity.compareTo(quantity)!=0||!previous.reason.equals(reason))throw new OperatorFailure(409,"TRACE_REPLAY_CONFLICT","Le mouvement de traçabilité diffère de la confirmation initiale");return;}
  freezes.location(segment.account.store,segment.location);
  var movement=new StockMovement();movement.account=segment.account;movement.location=segment.location;movement.segment=segment;movement.eventKey=event;movement.operationKey=operation.length()>160?"trace:"+key(operation):operation;movement.reason=reason;movement.quantity=quantity;movement.effectiveAt=at;movement.persist();segment.account.alertDirty=true;
 }
 public void move(StockSegment from,StockSegment to,BigDecimal quantity,String operation,String part,String reason){if(!id(from.account).equals(id(to.account)))throw new BadRequestException("Les mouvements conservent le produit et son compte");if(quantity.signum()<=0)throw new BadRequestException("Quantité positive requise");if(from.id.equals(to.id))return;LocalDateTime at=DateTimeProvider.now();entry(from,quantity.negate(),operation,part+":out",reason,at);entry(to,quantity,operation,part+":in",reason,at);}
 public static boolean serialOccupied(ProductSerial serial,BaseLocation except){
  if(SupplyStockAllocation.count("origin.serial=?1 and quantity>received",serial)>0)return true;
  return !StockMovement.getEntityManager().createQuery("select m.segment.id from StockMovement m where m.segment.serial=?1 and m.effectiveAt<=?2 and (?3 is null or m.location<>?3) group by m.segment.id having sum(m.quantity)>0",Long.class).setParameter(1,serial).setParameter(2,DateTimeProvider.now()).setParameter(3,except).getResultList().isEmpty();
 }
 public List<Slice> allocate(Store store,Product product,BaseLocation location,BigDecimal quantity,Long selected,ManufacturerLot lot,ProductSerial serial,String context){
  if(location==null)throw new BadRequestException("Précisez l’emplacement physique du prélèvement");if(org.hibernate.Hibernate.unproxy(location) instanceof ParcelStockLocation || org.hibernate.Hibernate.unproxy(location) instanceof PickingToteLocation held&&held.traceOnly)throw new OperatorFailure(409,"ALREADY_ALLOCATED","Ces marchandises sont déjà affectées à une préparation ; utilisez son transfert de bac ou sa réaffectation.");if(location.product!=null&&!id(location.product).equals(id(product)))throw new BadRequestException("Cet emplacement n’est pas celui du produit réellement prélevé");var account=stocks.account(store.id,id(product));var buildings=BaseLocation.findBuildingsByLocations(List.of(location));if(buildings.size()!=1||!Objects.equals(id(Building.findStoreByBuildingId(id(buildings.getFirst()))),id(store)))throw new BadRequestException("Emplacement hors du magasin");
  if(serial!=null&&quantity.compareTo(BigDecimal.ONE)!=0)throw new BadRequestException("Une série représente une seule unité");
  if(serial!=null&&serialOccupied(serial,location))throw new OperatorFailure(409,"SERIAL_ALREADY_STOCKED","Cette série est déjà présente dans un autre emplacement, support ou transit");

  var policy=TraceabilityPolicy.<TraceabilityPolicy>find("store=?1 and product=?2 and context=?3",store,product,context).firstResult();
  var holds=QualityCase.<QualityCase>list("store=?1 and product=?2 and state='ACTIVE'",store,product);
  var balances=new HashMap<Long,BigDecimal>();for(var row:StockMovement.getEntityManager().createQuery("select m.segment.id,sum(m.quantity) from StockMovement m where m.account=?1 and m.location=?2 and m.effectiveAt<=?3 group by m.segment.id",Object[].class).setParameter(1,account).setParameter(2,location).setParameter(3,DateTimeProvider.now()).getResultList())balances.put((Long)row[0],(BigDecimal)row[1]);
  // An explicit segment supplies its identity; caller need not retype its manufacturer code.
  var rows=StockSegment.<StockSegment>list("select s from StockSegment s left join fetch s.lot left join fetch s.serial where s.account=?1 and s.location=?2",account,location);
  if(selected!=null){var chosen=rows.stream().filter(s->s.id.equals(selected)).findFirst().orElseThrow(()->new BadRequestException("Segment absent de cet emplacement"));if(lot!=null&&!Objects.equals(chosen.lot==null?null:id(chosen.lot),id(lot))||serial!=null&&!Objects.equals(chosen.serial==null?null:id(chosen.serial),id(serial)))throw new BadRequestException("Lot ou série incompatible avec le segment sélectionné");rows=new ArrayList<>(List.of(chosen));}
  else rows=new ArrayList<>(rows.stream().filter(s->(lot==null||s.lot!=null&&id(s.lot).equals(id(lot)))&&(serial==null||s.serial!=null&&id(s.serial).equals(id(serial)))).toList());
  var comparator=StockEligibility.comparator(policy==null?"FEFO":policy.allocationMethod);rows.sort((a,b)->comparator.compare(new StockEligibility.Candidate(a.id,a.receivedAt,a.lot==null?null:a.lot.useBy,a.lot==null?null:a.lot.bestBefore),new StockEligibility.Candidate(b.id,b.receivedAt,b.lot==null?null:b.lot.useBy,b.lot==null?null:b.lot.bestBefore)));
  BigDecimal remaining=quantity;var result=new ArrayList<Slice>();RuntimeException refusal=null;
  for(var candidate:rows){BigDecimal available=balances.getOrDefault(candidate.id,BigDecimal.ZERO);if(available.signum()<=0)continue;try{StockQualityRules.requireSegment(candidate,policy,holds);}catch(ClientErrorException|OperatorFailure denied){refusal=denied;continue;}if(candidate.serial!=null&&remaining.compareTo(BigDecimal.ONE)<0)continue;BigDecimal take=remaining.min(available);if(candidate.serial!=null)take=BigDecimal.ONE;result.add(new Slice(candidate,take));remaining=remaining.subtract(take);if(remaining.signum()==0)break;}
  if(remaining.signum()>0){if(selected!=null)throw new OperatorFailure(409,"SEGMENT_INSUFFICIENT","Le segment a changé ou ne couvre pas la quantité : actualisez le choix");if(refusal!=null)throw refusal;
   if(policy!=null&&(policy.lotRequired||policy.serialRequired||policy.dateRequired)&&lot==null&&serial==null)throw new OperatorFailure(409,"TRACE_REQUIRED","Choisissez ou scannez le lot et les dates exigés");
   StockQualityRules.requireAllowed(store,product,lot,serial);
   if(serial!=null){var existing=StockMovement.getEntityManager().createQuery("select coalesce(sum(m.quantity),0) from StockMovement m where m.segment.serial=?1",BigDecimal.class).setParameter(1,serial).getSingleResult();if(existing.signum()>0)throw new OperatorFailure(409,"SERIAL_ALREADY_STOCKED","Cette série est déjà localisée dans un autre segment");}
   var unknown=segment(account,location,null,lot,serial,"UNASSESSED");StockQualityRules.requireSegment(unknown,policy,holds);if(serial!=null&&quantity.compareTo(BigDecimal.ONE)!=0)throw new BadRequestException("Une série représente une seule unité");result.add(new Slice(unknown,remaining));
  }return result;
 }
}
