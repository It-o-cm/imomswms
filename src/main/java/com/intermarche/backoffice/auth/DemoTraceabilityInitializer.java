package com.intermarche.backoffice.auth;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.inventory.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.handling.InventoryOrder;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.stock.StockTraceLedger;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.math.BigDecimal;
import java.util.*;
/** Explicit demo identities are introduced by zero-net qualification, never by adding physical goods. */
@ApplicationScoped
public class DemoTraceabilityInitializer {
 @Inject StockTraceLedger ledger;
 @ConfigProperty(name="imomswms.demo-data.enabled",defaultValue="true") boolean enabled;
 @Transactional public void start(@Observes @Priority(3600) StartupEvent event){if(enabled)seed();}
 @Transactional public void seed(){Store store=Store.findByCode("LYO-042");if(store==null)return;StockAccount.getEntityManager().createNativeQuery("select id from stores where id=?1 for update").setParameter(1,store.id).getSingleResult();
  var now=DateTimeProvider.now();var rows=StockSegment.getEntityManager().createQuery("select s,sum(m.quantity) from StockSegment s join StockMovement m on m.segment=s where s.account.store=?1 and s.lot is null and s.serial is null and s.quality='UNASSESSED' and type(s.location) in (StoreShelfLocation,WarehouseShelfLocation) and m.effectiveAt<=?2 group by s having sum(m.quantity)>0 order by s.id",Object[].class).setParameter(1,store).setParameter(2,now).setMaxResults(3).getResultList();
  for(int n=0;n<rows.size();n++){StockSegment source=(StockSegment)rows.get(n)[0];String code="DEMO-TRACE-"+(n+1);if(ManufacturerLot.count("store=?1 and product=?2 and code=?3",store,source.account.product,code)>0)continue;var lot=new ManufacturerLot();lot.store=store;lot.product=source.account.product;lot.code=code;lot.manufacturedOn=now.toLocalDate().minusDays(10);lot.useBy=now.toLocalDate().plusDays(n==0?2:n==1?-1:14);lot.persistAndFlush();var target=ledger.segment(source.account,source.location,source.support,lot,null,"AVAILABLE");BigDecimal amount=((BigDecimal)rows.get(n)[1]).min(BigDecimal.valueOf(3));ledger.move(source,target,amount,"demo-traceability:"+source.id,"qualification","DEMO_LOT_QUALIFICATION");
   if(n==2){var quality=new QualityCase();quality.store=store;quality.product=source.account.product;quality.lot=lot;quality.kind="RECALL";quality.reason="Démonstration : avis fournisseur sur le lot "+code+". Les autres lots restent exploitables.";quality.createdBy="DEMO";quality.includeUnknownLots=false;quality.persist();var history=new QualityCaseHistory();history.qualityCase=quality;history.actor="DEMO";history.action="CREATED";history.reason=quality.reason;history.persist();}
  }
  if(InventoryOrder.count("store=?1 and code=?2",store,"INV-DEMO-01")==0){var locations=StoreShelfLocation.<StoreShelfLocation>find("select l from StoreShelfLocation l where l.id in (select x.id from Store s join s.buildings b join b.locations x where s=?1) and l.area is not null and l.aisle is not null and l.unit is not null order by l.id",store).range(0,0).list();if(!locations.isEmpty()){var location=locations.getFirst();var buildings=BaseLocation.findBuildingsByLocations(List.of(location));if(buildings.size()!=1)return;var order=new InventoryOrder();order.store=store;order.building=buildings.getFirst();order.referenceAt=now;order.scopeKind="BAY";order.scopeLabel=location.area+" / "+location.aisle+" / "+location.unit;order.requestedStart=now.plusHours(1);order.requestedEnd=now.plusMinutes(90);order.code="INV-DEMO-01";order.status=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;order.persist();
    for(var place:StoreShelfLocation.<StoreShelfLocation>list("id in (select x.id from Building b join b.locations x where b=?1) and area=?2 and aisle=?3 and unit=?4",order.building,location.area,location.aisle,location.unit)){
     var snapshots=StockSegment.getEntityManager().createQuery("select s,coalesce(sum(m.quantity),0) from StockSegment s left join StockMovement m on m.segment=s and m.effectiveAt<=?2 where s.account.store=?1 and s.location=?3 group by s order by s.id",Object[].class).setParameter(1,store).setParameter(2,now).setParameter(3,place).getResultList();
     for(var row:snapshots){var segment=(StockSegment)row[0];var target=new InventoryTarget();target.handlingOrder=order;target.location=place;target.product=segment.account.product;target.segment=segment;target.expectedQuantity=(BigDecimal)row[1];target.address=place.locationCode;target.persist();}
     if(snapshots.isEmpty()){var target=new InventoryTarget();target.handlingOrder=order;target.location=place;target.product=place.product;target.expectedQuantity=BigDecimal.ZERO;target.address=place.locationCode;target.persist();}
    }
  }}
 }
}
