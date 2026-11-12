package com.intermarche.backoffice.engine.alerts;
import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.stock.StockAccount;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.Hibernate;
import java.time.LocalDateTime;
import java.util.*;
/** Persistent edge detection: one event for each transition, including transitions caused by time alone. */
@ApplicationScoped
public class StockAvailabilityAlerts {
    @Inject TemporalStockService stocks;
    @Inject EntityManager em;
    @ConfigProperty(name="storepilot.stock.alert-horizon-hours",defaultValue="24") int horizonHours;
    @Transactional public List<Long> dueAccounts(){
        return em.createQuery("select a.id from StockAccount a where a.alertDirty=true or a.alertCheckedAt is null or a.alertNextCheckAt<=:now order by a.alertCheckedAt nulls first,a.id",Long.class).setParameter("now",DateTimeProvider.now()).setMaxResults(200).getResultList();
    }
    @Transactional public void evaluate(Long accountId){
        StockAccount a=StockAccount.findById(accountId);if(a==null)return;
        Store store=Hibernate.unproxy(a.store,Store.class);em.lock(store,LockModeType.PESSIMISTIC_WRITE);
        LocalDateTime now=DateTimeProvider.now();var timeline=stocks.availability(accountId,null,now);
        LocalDateTime unavailable=timeline.periods.stream().filter(p->p.balance.signum()<=0).map(p->p.from).findFirst().orElse(null);
        String state=timeline.periods.getFirst().balance.signum()<=0?"UNAVAILABLE":unavailable!=null && !unavailable.isAfter(now.plusHours(horizonHours))?"TENSION":"AVAILABLE";
        if(!state.equals(a.alertStatus) && !"AVAILABLE".equals(state)){
            Product p=Hibernate.unproxy(a.product,Product.class);
            String label="UNAVAILABLE".equals(state)?"Produit indisponible":"Produit en tension";
            String description=p.name+" ("+p.ean+") — magasin "+store.code+". "+("UNAVAILABLE".equals(state)?"Le solde disponible actuel est épuisé après engagements et marge.":"Épuisement prévu le "+unavailable+" dans les "+horizonHours+" prochaines heures.");
            Event.publishInCurrentTx(label,description,"UNAVAILABLE".equals(state)?Event.UrgencyLevel.CRITICAL:Event.UrgencyLevel.HIGH,store,a,p.ean);
        }
        LocalDateTime next=now.plusDays(1);
        for(var period:timeline.periods){
            if(period.from.isAfter(now) && period.from.isBefore(next))next=period.from;
            var entry=period.from.minusHours(horizonHours);
            if(period.balance.signum()<=0 && entry.isAfter(now) && entry.isBefore(next))next=entry;
        }
        a.alertStatus=state;a.alertCheckedAt=now;a.alertNextCheckAt=next;a.alertDirty=false;
    }
}
