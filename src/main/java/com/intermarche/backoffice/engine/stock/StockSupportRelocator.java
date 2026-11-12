package com.intermarche.backoffice.engine.stock;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.stock.*;import com.intermarche.backoffice.domain.store.*;import com.intermarche.backoffice.domain.executing.LoadedTote;import com.intermarche.backoffice.domain.util.DateTimeProvider;
import jakarta.enterprise.context.ApplicationScoped;import jakarta.inject.Inject;import java.util.*;import java.math.BigDecimal;
/** Moving a complete support keeps its contents and customer allocations together. */
@ApplicationScoped
public class StockSupportRelocator {
 @Inject StockTraceLedger ledger;
 public void afterDeposit(LoadedTote tote,Store store){
  if(tote.buffer==null)return;Buffer buffer=org.hibernate.Hibernate.unproxy(tote.buffer,Buffer.class);Building building=org.hibernate.Hibernate.unproxy(buffer.building,Building.class);var em=StockMovement.getEntityManager();var rows=new ArrayList<Object[]>();
  for(String type:List.of("PickingToteLocation","BufferToteStockLocation")){String link=type.equals("PickingToteLocation")?"protectedTote":"loadedTote";rows.addAll(em.createQuery("select s,sum(m.quantity) from StockMovement m join m.segment s where m.location.id in (select l.id from "+type+" l where l."+link+"=?1) and m.effectiveAt<=?2 group by s having sum(m.quantity)<>0 order by s.id",Object[].class).setParameter(1,tote).setParameter(2,DateTimeProvider.now()).getResultList());}
  String operation="support-move:"+UUID.randomUUID();var relocated=new HashMap<Long,StockSegment>();
  for(var row:rows){StockSegment source=(StockSegment)row[0];BigDecimal quantity=(BigDecimal)row[1];if(quantity.signum()<0)throw new IllegalArgumentException("Solde négatif dans le support : contrôlez son inventaire avant déplacement");BaseLocation old=org.hibernate.Hibernate.unproxy(source.location,BaseLocation.class);var owners=BaseLocation.findBuildingsByLocations(List.of(old));if(owners.size()!=1)throw new IllegalArgumentException("Bâtiment source du support indéterminé");
   if(id(owners.getFirst()).equals(building.id)){if(old instanceof BufferToteStockLocation location)location.buffer=buffer;continue;}
   StockSegment target;
   if(old instanceof PickingToteLocation picking&&picking.traceOnly)target=ledger.holding(source,tote);
   else{
    String code="SUPPORT-"+tote.id+"-"+id(source.account.product)+"-"+building.id;ShelfLocation location=ShelfLocation.find("locationCode",code).firstResult();if(location==null){if(old instanceof PickingToteLocation){var p=new PickingToteLocation();p.protectedTote=tote;location=p;}else{var p=new BufferToteStockLocation();p.loadedTote=tote;p.buffer=buffer;location=p;}location.product=source.account.product;location.area="BUFFER";location.aisle="BAC";location.unit="BAC";location.elevation="1";location.position="1";location.locationCode=code;location.persist();building.locations.add(location);}else if(location instanceof BufferToteStockLocation b)b.buffer=buffer;
    target=ledger.segment(source.account,location,source.support,source.lot,source.serial,source.quality);if(source.receivedAt!=null&&(target.receivedAt==null||target.receivedAt.isAfter(source.receivedAt)))target.receivedAt=source.receivedAt;
   }
   ledger.move(source,target,quantity,operation,"segment-"+source.id,"SUPPORT_TRANSFER");relocated.put(source.id,target);
  }
  if(!relocated.isEmpty())for(var allocation:PickedStockAllocation.<PickedStockAllocation>list("holding.id in ?1 and shipped=false",relocated.keySet()))allocation.holding=relocated.get(id(allocation.holding));
 }
}
