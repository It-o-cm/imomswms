package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.wms.handling.PreparingOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Read-only preview of draft tote loads, using physical tote capacities and product data. */
@ApplicationScoped @Transactional
public class ToteCapacityService {
    @jakarta.inject.Inject MixedPreparationService mixedPreparation;
    @jakarta.inject.Inject PickingResolutionService resolutions;
    public static class Request {
        public List<Long> orderIds;
        public List<Load> totes;
    }
    public static class Load { public String code; public List<Item> items; }
    public static class Item { public Long sourceOrderId; public Long pickedItemId;public Long productId; public BigDecimal quantity; }
    public static class Capacity {
        public String code;
        public BigDecimal capacityLiters;
        public BigDecimal usedLiters;
        public BigDecimal fillPercent;
        public BigDecimal barPercent;
        public boolean volumeComplete;
        public boolean full;
        public boolean overCapacity;
        public BigDecimal usedWeightKg,capacityWeightKg;
        public boolean weightComplete,weightOverCapacity;
    }
    record Key(Long orderId,Long itemId) {}
    public List<Capacity> preview(Request request) {
        if(request==null || request.orderIds==null || request.orderIds.isEmpty() || request.totes==null)
            throw new IllegalArgumentException("Orders and tote loads are required");
        Map<Key,Product> products=new HashMap<>();Store store=null;
        for(Long id:new LinkedHashSet<>(request.orderIds)) {
            BaseHandlingOrder order=BaseHandlingOrder.findById(id);
            if(order!=null)order=org.hibernate.Hibernate.unproxy(order,BaseHandlingOrder.class);
            if(!(order instanceof PreparingOrder preparing)) throw new IllegalArgumentException("Collecting order not found");
            if(store!=null && !store.id.equals(order.store.id)) throw new IllegalArgumentException("Orders belong to different stores");
            store=order.store;
            HandlingExecution execution=HandlingExecution.find("handlingOrder",order).firstResult();
            if(execution!=null)execution=org.hibernate.Hibernate.unproxy(execution,HandlingExecution.class);
            if(MixedPreparationService.supports(order) && (execution==null || execution.status!=HandlingExecution.ExecutionStatus.DONE)){for(var source:mixedPreparation.sources(preparing))products.put(new Key(id,source.key()),source.item()==null?source.line().product:source.item().product);}
            else if(execution instanceof PickingExecution picking) {
                for(var item:picking.itemsToPick) products.put(new Key(id,item.id),item.product);
                for(var tote:picking.loadedTotes) for(var item:tote.items) products.put(new Key(id,item.id),item.product);
            } else for(Lot lot:preparing.lots) for(LotLine line:lot.lines) products.put(new Key(id,line.id),line.product);
        }
        var supportCodes=request.totes.stream().filter(Objects::nonNull).map(l->l.code).filter(Objects::nonNull).toList();
        var supportTypes=new HashMap<String,com.intermarche.backoffice.domain.fieldops.PhysicalSupport>();
        if(!supportCodes.isEmpty())for(var support:com.intermarche.backoffice.domain.fieldops.PhysicalSupport.<com.intermarche.backoffice.domain.fieldops.PhysicalSupport>list("select s from PhysicalSupport s join fetch s.type where s.store=?1 and s.code in ?2",store,supportCodes))supportTypes.put(support.code,support);
        var policy=com.intermarche.backoffice.engine.fieldops.OperationalPolicyVersions.active(store,"SupportConstraints");
        List<Capacity> result=new ArrayList<>();Set<String> codes=new HashSet<>();
        for(Load load:request.totes) {
            if(load==null || load.code==null || !codes.add(load.code)) throw new IllegalArgumentException("Invalid or duplicate tote");
            Tote tote=Tote.find("code",load.code).firstResult();
            if(tote==null || !tote.store.id.equals(store.id)) throw new IllegalArgumentException("Tote outside order store");
            Capacity c=new Capacity();c.code=load.code;c.capacityLiters=tote.volume;c.usedLiters=BigDecimal.ZERO;c.volumeComplete=true;
            var support=supportTypes.get(load.code);c.usedWeightKg=BigDecimal.ZERO;c.weightComplete=true;c.capacityWeightKg=support==null?null:support.type.maxWeightKg;
            if(support!=null&&support.type.maxVolumeLiters!=null)c.capacityLiters=c.capacityLiters.min(support.type.maxVolumeLiters);
            String kind=support==null?"TOTE":support.type.kind.name();boolean permitted=false;for(var allowed:policy.path("allowedKinds"))if(allowed.asText().equals(kind))permitted=true;if(!permitted)throw new IllegalArgumentException("Ce type de support est désactivé par la politique du magasin");c.capacityLiters=c.capacityLiters.multiply(BigDecimal.valueOf(policy.path("maximumFillPercent").asInt(100))).divide(BigDecimal.valueOf(100));if(policy.hasNonNull("maximumWeightKg")){var maximum=policy.path("maximumWeightKg").decimalValue();c.capacityWeightKg=c.capacityWeightKg==null?maximum:c.capacityWeightKg.min(maximum);}
            if(c.capacityLiters==null || c.capacityLiters.signum()<=0) throw new IllegalArgumentException("Invalid tote capacity");
            var handlingProducts=new ArrayList<Product>();
            for(Item item:load.items==null?List.<Item>of():load.items) {
                if(item==null || item.quantity==null || item.quantity.signum()<0 || item.quantity.scale()>6 || item.quantity.precision()-item.quantity.scale()>13) throw new IllegalArgumentException("Invalid item quantity");
                Product product=products.get(new Key(item.sourceOrderId,item.pickedItemId));
                if(product==null) throw new IllegalArgumentException("Item outside requested orders");
                if(item.productId!=null)product=resolutions.replacement(store,product,item.productId);
                if(item.quantity.signum()==0) continue;
                for(var other:handlingProducts)com.intermarche.backoffice.engine.stock.ProductHandlingRules.requireCompatible(store,product,other);handlingProducts.add(product);
                if(!tote.isUsageAllowed(product.temperatureZone)) throw new IllegalArgumentException("Incompatible tote type");
                if(support!=null&&!Arrays.asList(support.type.allowedZones.split(",")).contains(product.temperatureZone.name()))throw new IllegalArgumentException("Conditionnement incompatible avec le type du support");
                if(product.productType==ProductType.WEIGHT)c.usedWeightKg=c.usedWeightKg.add(item.quantity);
                else if(product.referenceWeight!=null&&product.referenceWeight.signum()>0&&product.productType==ProductType.UNIT)c.usedWeightKg=c.usedWeightKg.add(product.referenceWeight.multiply(item.quantity));
                else c.weightComplete=false;
                // Liquid bulk quantities are already liters. Other products need a known unit volume.
                if(product.productType==ProductType.VOLUME) c.usedLiters=c.usedLiters.add(item.quantity);
                else if(product.referenceVolume!=null && product.referenceVolume.signum()>0) {
                    BigDecimal units=product.productType==ProductType.WEIGHT
                            ? (product.referenceWeight!=null && product.referenceWeight.signum()>0 ? item.quantity.divide(product.referenceWeight,12,RoundingMode.HALF_UP) : null)
                            : item.quantity;
                    if(units==null) c.volumeComplete=false;
                    else c.usedLiters=c.usedLiters.add(product.referenceVolume.multiply(units));
                } else c.volumeComplete=false;
            }
            c.fillPercent=c.usedLiters.multiply(new BigDecimal("100")).divide(c.capacityLiters,1,RoundingMode.HALF_UP);
            c.barPercent=c.fillPercent.min(new BigDecimal("100"));
            c.full=c.usedLiters.compareTo(c.capacityLiters)>=0;c.overCapacity=c.usedLiters.compareTo(c.capacityLiters)>0;
            c.weightOverCapacity=c.capacityWeightKg!=null&&c.usedWeightKg.compareTo(c.capacityWeightKg)>0;c.overCapacity=c.overCapacity||c.weightOverCapacity;
            result.add(c);
        }
        return result;
    }
}
