package com.intermarche.backoffice.graphql.handling;
import com.intermarche.backoffice.domain.wms.handling.StockProtectionOrder;
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
public class StockProtectionOrderResource implements GraphQLTrait {
    @Inject SecurityIdentity identity;
    public SecurityIdentity getIdentity(){return identity;}
    @Query @Transactional
    public List<StockProtectionOrder> stockProtectionOrdersByDateRange(LocalDateTime start,LocalDateTime end){
        if(start==null || end==null || end.isBefore(start))throw new IllegalArgumentException("Période invalide");
        Store store=getStoreContext();List<StockProtectionOrder> orders=store==null?StockProtectionOrder.list("requestedStart >= ?1 and requestedStart <= ?2",start,end):StockProtectionOrder.list("store = ?1 and requestedStart >= ?2 and requestedStart <= ?3",store,start,end);orders.forEach(o -> o.supplies.size());return orders;
    }
    @Query @Transactional
    public StockProtectionOrder stockProtectionOrder(Long id){
        var order=StockProtectionOrder.<StockProtectionOrder>findById(id);Store store=getStoreContext();
        if(order==null || store!=null && !org.hibernate.Hibernate.unproxy(order.store,Store.class).id.equals(store.id))throw new NoSuchElementException("Ordre de mise en sécurité introuvable");order.supplies.size();return order;
    }
}
