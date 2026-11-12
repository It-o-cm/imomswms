package com.intermarche.backoffice.engine.stock;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.inventory.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.hibernate.Hibernate;
import java.util.*;
/** One bulk lock snapshot per transaction, shared by all movements of a batch. */
@ApplicationScoped
public class InventoryScopeGuard {
 @Inject TransactionSynchronizationRegistry transactions;
 private record Lease(Long order,String code,Long location,Long cabinet,Integer row,Integer column){}
 private String key(Store store){return getClass().getName()+":"+id(store);}
 public void invalidate(Store store){transactions.putResource(key(store),null);}
 @SuppressWarnings("unchecked") private List<Lease> leases(Store store){Object cached=transactions.getResource(key(store));if(cached!=null)return (List<Lease>)cached;var rows=InventoryScopeLock.getEntityManager().createQuery("select l.session.handlingOrder.id,l.session.handlingOrder.code,l.location.id,l.cabinet.id,l.cellRow,l.cellColumn from InventoryScopeLock l left join l.location left join l.cabinet where l.session.handlingOrder.store=?1 and l.session.state in ?2 and l.session.handlingOrder.status not in ?3",Object[].class).setParameter(1,store).setParameter(2,List.of("COUNTING","RECOUNT_REQUIRED","SUBMITTED","DISPUTED","APPROVED")).setParameter(3,List.of(com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus.CANCELLED,com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus.FAILED,com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus.DONE)).getResultList().stream().map(r->new Lease((Long)r[0],(String)r[1],(Long)r[2],(Long)r[3],(Integer)r[4],(Integer)r[5])).toList();transactions.putResource(key(store),rows);return rows;}
 private void blocked(Lease lease){throw new OperatorFailure(409,"INVENTORY_SCOPE_FROZEN","Emplacement temporairement gelé par l’inventaire "+lease.code+". Le responsable doit terminer ou annuler ce comptage avant ce mouvement.");}
 public void location(Store store,BaseLocation raw){if(raw==null)return;var leases=leases(store);if(leases.isEmpty())return;Long location=id(raw);for(var lease:leases)if(location.equals(lease.location))blocked(lease);BaseLocation value=Hibernate.unproxy(raw,BaseLocation.class);if(value instanceof PickingToteLocation p&&p.protectedTote!=null)tote(store,p.protectedTote);else if(value instanceof BufferToteStockLocation b)tote(store,b.loadedTote);}
 public void cell(Store store,BufferCabinet cabinet,Integer row,Integer column){if(cabinet==null)return;for(var lease:leases(store))if(Objects.equals(lease.cabinet,id(cabinet))&&Objects.equals(lease.row,row)&&Objects.equals(lease.column,column))blocked(lease);}
 public void tote(Store store,LoadedTote tote){if(tote==null)return;cell(store,tote.cabinet,tote.cellRow,tote.cellColumn);}
 public void acquire(InventoryCountSession session,Collection<Long> locations,BufferCabinet cabinet,Integer row,Integer column){
  if(!"TARGETED_FREEZE".equals(session.movementMode))return;Store store=session.handlingOrder.store;
  for(var lease:leases(store))if(!Objects.equals(lease.order,id(session.handlingOrder))&&(lease.location!=null&&locations.contains(lease.location)||cabinet!=null&&Objects.equals(lease.cabinet,id(cabinet))&&Objects.equals(lease.row,row)&&Objects.equals(lease.column,column)))blocked(lease);
  for(Long location:locations){var lease=new InventoryScopeLock();lease.session=session;lease.location=BaseLocation.getEntityManager().getReference(BaseLocation.class,location);lease.persist();}
  if(cabinet!=null){var lease=new InventoryScopeLock();lease.session=session;lease.cabinet=cabinet;lease.cellRow=row;lease.cellColumn=column;lease.persist();}invalidate(store);
 }
}
