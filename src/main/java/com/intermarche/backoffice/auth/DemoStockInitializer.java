package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/** Incremental demo inventory, using the same ledger and validation as production operations. */
@ApplicationScoped
@Transactional
public class DemoStockInitializer {
    private static final Logger LOG=Logger.getLogger(DemoStockInitializer.class);
    @Inject TemporalStockService stocks;
    @Inject EntityManager em;

    public void seed(Store store,List<Product> products) {
        em.lock(store,LockModeType.PESSIMISTIC_WRITE);
        LocalDateTime now=DateTimeProvider.now();
        int initialized=0;
        for (int i=0;i<products.size();i++) {
            Product product=products.get(i);
            StockAccount existing=StockAccount.find("store = ?1 and product = ?2",store,product).firstResult();
            // An account can already have been opened manually: never alter its configuration or activity.
            if (existing!=null) continue;
            var place=store.buildings.stream().sorted(Comparator.comparing(b -> b.buildingCode))
                    .flatMap(b -> b.locations.stream().filter(l -> l.product!=null && l.product.id.equals(product.id))
                            .sorted(Comparator.comparing(l -> l.locationCode)))
                    .findFirst();
            if(place.isEmpty()) {
                LOG.warnf("Demo stock skipped: product %s has no location in store %s",product.ean,store.code);
                continue;
            }
            ShelfLocation location=place.get();
            boolean weighed=product.productType==ProductType.WEIGHT;
            BigDecimal opening=weighed ? new BigDecimal("18.750").add(BigDecimal.valueOf(i%9)) : BigDecimal.valueOf(36+(i%8)*12L);
            if ("3017620422003".equals(product.ean)) opening=new BigDecimal("37");
            StockAccount account=stocks.account(store.id,product.id);
            stocks.setMargin(account.id,weighed ? new BigDecimal("1.500") : new BigDecimal("3"));
            stocks.adjust(account.id,location.id,"DEMO-OPENING-"+product.ean,opening,now);
            if ("3017620422003".equals(product.ean)) seedNutella(account,location,now);
            initialized++;
        }
        LOG.infof("Demo stocks initialized: %d products (existing accounts preserved)",initialized);
    }

    /** Document scenario after POS -4 and the additional orders: 33 physical, 92 due before the second arrival. */
    private void seedNutella(StockAccount account,ShelfLocation location,LocalDateTime now) {
        stocks.adjust(account.id,location.id,"DEMO-NUTELLA-POS-4",new BigDecimal("-4"),now);
        stocks.planSupply(account.id,"DEMO-NUTELLA-ARRIVAL-60",null,location.id,new BigDecimal("60"),now.plusHours(3));
        stocks.planSupply(account.id,"DEMO-NUTELLA-ARRIVAL-80",null,location.id,new BigDecimal("80"),now.plusHours(8));
        Building building=BaseLocation.findBuildingsByLocations(List.of(location)).get(0);
        stocks.reserve(account.id,"DEMO-NUTELLA-EARLY",building.id,new BigDecimal("17"),now.plusHours(1),false);
        stocks.reserve(account.id,"DEMO-NUTELLA-NOON",building.id,new BigDecimal("75"),now.plusHours(6),false);
        stocks.reserve(account.id,"DEMO-NUTELLA-LATE",building.id,new BigDecimal("3"),now.plusHours(9),false);
    }
}
