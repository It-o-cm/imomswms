package com.intermarche.backoffice.graphql.handling;
import com.intermarche.backoffice.domain.wms.handling.InventoryOrder;
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
public class InventoryOrderResource implements GraphQLTrait {
 @Inject SecurityIdentity identity;public SecurityIdentity getIdentity(){return identity;}
 @Query @Transactional public List<InventoryOrder> inventoryOrdersByDateRange(LocalDateTime start,LocalDateTime end){if(start==null||end==null||end.isBefore(start))throw new IllegalArgumentException("Période invalide");Store store=getStoreContext();return store==null?InventoryOrder.list("select o from InventoryOrder o join fetch o.building left join fetch o.assignee join fetch o.store where o.requestedStart>=?1 and o.requestedStart<=?2",start,end):InventoryOrder.list("select o from InventoryOrder o join fetch o.building left join fetch o.assignee join fetch o.store where o.store=?1 and o.requestedStart>=?2 and o.requestedStart<=?3",store,start,end);}
 @Query @Transactional public InventoryOrder inventoryOrder(Long id){var order=InventoryOrder.<InventoryOrder>find("select o from InventoryOrder o join fetch o.building left join fetch o.assignee join fetch o.store where o.id=?1",id).firstResult();Store store=getStoreContext();if(order==null||store!=null&&!org.hibernate.Hibernate.unproxy(order.store,Store.class).id.equals(store.id))throw new NoSuchElementException("Inventaire introuvable");return order;}
}
