package com.intermarche.backoffice.graphql.handling;
import com.intermarche.backoffice.domain.wms.handling.CancellationDispositionOrder;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.graphql.GraphQLTrait;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import java.time.LocalDateTime;
import java.util.*;
@GraphQLApi @ApplicationScoped @RunOnVirtualThread @RolesAllowed({"ADMIN","MANAGER","SERVICE_CALLER"})
public class CancellationDispositionOrderResource implements GraphQLTrait {
 @Inject SecurityIdentity identity;
 /**
  * Returns the identity of the caller, used to resolve its store context.
  *
  * @return the caller's identity
  */
 public SecurityIdentity getIdentity(){return identity;}
 /**
  * Lists the post-cancellation treatment orders planned in a period, with the associations the handling views display
  * fetched in the same query so that they stay readable once the transaction ends.
  *
  * @param start the beginning of the period
  * @param end the end of the period
  * @return the treatment orders of the caller's store, or of every store for a caller without store context
  */
 @Query @Transactional public List<CancellationDispositionOrder> cancellationDispositionOrdersByDateRange(LocalDateTime start,LocalDateTime end){if(start==null||end==null||end.isBefore(start))throw new IllegalArgumentException("Période invalide");Store store=getStoreContext();return store==null?CancellationDispositionOrder.list("select o from CancellationDispositionOrder o join fetch o.store left join fetch o.assignee left join fetch o.building left join fetch o.origin left join fetch o.customerOrder where o.requestedStart>=?1 and o.requestedStart<=?2",start,end):CancellationDispositionOrder.list("select o from CancellationDispositionOrder o join fetch o.store left join fetch o.assignee left join fetch o.building left join fetch o.origin left join fetch o.customerOrder where o.store=?1 and o.requestedStart>=?2 and o.requestedStart<=?3",store,start,end);}
 /**
  * Reads one post-cancellation treatment order of the caller's store, with the associations the handling views display.
  *
  * @param id the treatment order
  * @return the treatment order
  */
 @Query @Transactional public CancellationDispositionOrder cancellationDispositionOrder(Long id){var order=CancellationDispositionOrder.<CancellationDispositionOrder>find("select o from CancellationDispositionOrder o join fetch o.store left join fetch o.assignee left join fetch o.building left join fetch o.origin left join fetch o.customerOrder where o.id=?1",id).firstResult();Store store=getStoreContext();if(order==null||store!=null&&!org.hibernate.Hibernate.unproxy(order.store,Store.class).id.equals(store.id))throw new NoSuchElementException("Ordre de traitement introuvable");return order;}
}
