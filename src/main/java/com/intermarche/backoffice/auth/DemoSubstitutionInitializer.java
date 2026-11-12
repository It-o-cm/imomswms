package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.domain.executing.ProductSubstitution;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.util.*;

/** Explicit demo alternatives, rather than unrelated products sharing a temperature. */
@ApplicationScoped
@Transactional
public class DemoSubstitutionInitializer {
    @Inject EntityManager em;
    @Inject DemoStockInitializer stocks;

    public void seed(Store store,List<Product> originals) {
        em.lock(store,LockModeType.PESSIMISTIC_WRITE);
        var alternatives=new ArrayList<Product>();
        for(Product original:originals) {
            DemoProductCharacteristics.populate(original,original.ean);
            String marker="DEMO-ALTERNATIVE-"+original.ean;
            Product alternative=Product.find("description",marker).firstResult();
            if(alternative==null) {
                alternative=new Product();alternative.ean=ean(original.id);
                alternative.name=original.name+" · alternative";
                alternative.brand="Sélection démo";alternative.description=marker;
                alternative.productType=original.productType;alternative.unitName=original.unitName;
                alternative.temperatureZone=original.temperatureZone;
                alternative.referenceWeight=original.referenceWeight;alternative.referenceVolume=original.referenceVolume;
                alternative.active=true;alternative.persistAndFlush();
            }
            DemoProductCharacteristics.populate(alternative,original.ean);
            for(Building building:store.buildings) {
                final Product target=alternative;
                if(building.locations.stream().anyMatch(l->l.product!=null && l.product.id.equals(target.id)))continue;
                var source=building.locations.stream().filter(l->l.product!=null && l.product.id.equals(original.id) && (l instanceof StoreShelfLocation || l instanceof WarehouseShelfLocation)).findFirst().orElse(null);
                if(source==null)continue;
                ShelfLocation location=building instanceof WarehouseBuilding?new WarehouseShelfLocation():new StoreShelfLocation();
                location.product=alternative;location.area=source.area;location.aisle=source.aisle;location.unit=source.unit;location.elevation=source.elevation;
                // A second facing in the same bay keeps both references genuinely pickable.
                int position=2;String candidate;
                do{candidate=String.valueOf(position++);final String p=candidate;if(building.locations.stream().noneMatch(l->Objects.equals(l.area,source.area)&&Objects.equals(l.aisle,source.aisle)&&Objects.equals(l.unit,source.unit)&&Objects.equals(l.elevation,source.elevation)&&Objects.equals(l.position,p)))break;}while(true);
                location.position=candidate;location.aislePosition=source.aislePosition;location.persistAndFlush();building.locations.add(location);
            }
            allow(store,original,alternative);allow(store,alternative,original);
            alternatives.add(alternative);
        }
        stocks.seed(store,alternatives);
    }
    private void allow(Store store,Product requested,Product replacement) {
        if(ProductSubstitution.count("store=?1 and requested=?2 and replacement=?3",store,requested,replacement)>0)return;
        var rule=new ProductSubstitution();rule.store=store;rule.requested=requested;rule.replacement=replacement;rule.persist();
    }
    private String ean(Long id) {
        String base="29"+String.format(Locale.ROOT,"%010d",id);int sum=0;
        for(int i=0;i<12;i++)sum+=(base.charAt(i)-'0')*(i%2==0?1:3);
        return base+(10-sum%10)%10;
    }
}
