package com.intermarche.backoffice.graphql.handling;

import com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder;
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
import java.util.List;
import java.util.NoSuchElementException;

@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed({"ADMIN","MANAGER","SERVICE_CALLER"})
public class ReplenishmentOrderResource implements GraphQLTrait {
    @Inject SecurityIdentity identity;
    public SecurityIdentity getIdentity() { return identity; }
    @Query @Transactional
    public List<ReplenishmentOrder> replenishmentOrdersByDateRange(LocalDateTime start, LocalDateTime end) {
        if (start==null || end==null || end.isBefore(start)) throw new IllegalArgumentException("Invalid date range");
        Store scope=getStoreContext();
        List<ReplenishmentOrder> orders=scope==null ? ReplenishmentOrder.list("requestedStart >= ?1 and requestedStart <= ?2",start,end)
            : ReplenishmentOrder.list("requestedStart >= ?1 and requestedStart <= ?2 and store = ?3",start,end,scope);
        orders=orders.stream().filter(o -> !(o instanceof com.intermarche.backoffice.domain.wms.handling.StockProtectionOrder)).toList();
        orders.forEach(order -> order.supplies.size());
        return orders;
    }
    @Query @Transactional
    public ReplenishmentOrder replenishmentOrder(Long id) {
        Store scope=getStoreContext();
        ReplenishmentOrder order=ReplenishmentOrder.findById(id);
        if(order==null || (scope!=null && !scope.id.equals(order.store.id))) throw new NoSuchElementException("Replenishment order not found");
        order.supplies.size();
        return order;
    }
}
