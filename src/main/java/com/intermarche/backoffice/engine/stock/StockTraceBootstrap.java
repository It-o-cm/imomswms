package com.intermarche.backoffice.engine.stock;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.executing.PickedItem;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
/** One-time current-custody reconciliation. Historical identities and old ledger entries are never rewritten. */
@ApplicationScoped
public class StockTraceBootstrap {
 @Inject PickedStockCoordinator trace;
 @Inject jakarta.persistence.EntityManager em;
 @Transactional public void start(@Observes @Priority(3500) StartupEvent event){
  int reconciled=0,unknown=0;
  for(var store:Store.<Store>list("order by id")){
   em.createNativeQuery("select id from stores where id=?1 for update").setParameter(1,store.id).getSingleResult();
   var imported=PickedItem.<PickedItem>list("select p from PickedItem p join fetch p.product join fetch p.loadedTote t where t.tote.store=?1 and p.status=?2 and p.quantity>0 and not exists(select st.id from PickedStockState st where st.store=?1 and st.itemId=p.id) and not exists(select sh.id from ShipmentLine sh where sh.pickedItem=p)",store,PickedItem.PickStatus.PICKED);
   for(var item:imported){var state=new PickedStockState();state.store=store;state.itemId=item.id;state.lotLineId=id(item.lotLine);state.product=item.product;state.legacyTote=item.loadedTote;state.legacyQuantity=item.quantity;state.persist();}em.flush();
   for(var state:PickedStockState.<PickedStockState>list("store=?1 and legacyQuantity>0 order by id",store))if(trace.materializeLegacy(state))reconciled++;else unknown++;
  }
  if(reconciled>0||unknown>0)org.jboss.logging.Logger.getLogger(StockTraceBootstrap.class).infof("Current custody reconciled: %d historical picked lines; %d need an explicit source. Manufacturer identity remains unknown.",reconciled,unknown);
 }
}
