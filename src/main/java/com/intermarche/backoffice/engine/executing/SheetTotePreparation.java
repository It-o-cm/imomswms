package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.*;
import java.util.*;

/** Volume-based lower bounds, kept separate by temperature zone. Not a packing allocation. */
@ApplicationScoped
public class SheetTotePreparation {
    public record Requirement(String zone,String type,Integer count,BigDecimal capacityLiters,boolean complete){}
    public List<Requirement> estimate(Store store,List<PickingPlanService.RouteStepDTO> steps){
        if(store==null)return List.of();
        // A cart, carton or pallet support is backed by a tote record carrying its own volume: it is not a picking tote.
        List<Tote> totes=Tote.list("store.id=?1 and id not in (select s.tote.id from PhysicalSupport s where s.type.kind<>?2)",store.id,com.intermarche.backoffice.domain.fieldops.SupportType.Kind.TOTE);
        Set<Long> ids=new HashSet<>();
        steps.stream().filter(s->s.productId!=null).forEach(s->ids.add(s.productId));
        Map<Long,Product> products=new HashMap<>();
        if(!ids.isEmpty())Product.<Product>list("id in ?1",ids).forEach(p->products.put(p.id,p));
        Map<TemperatureZone,BigDecimal> volumes=new LinkedHashMap<>();Set<TemperatureZone> incomplete=new HashSet<>();
        for(var step:steps){
            if(step.sourceToteCode!=null || step.quantity==null || step.quantity.signum()<=0)continue;
            var zone=step.temperatureZone;if(zone==null)continue;
            volumes.putIfAbsent(zone,BigDecimal.ZERO);
            Product p=products.get(step.productId);BigDecimal volume=null;
            if(p!=null){
                if(p.productType==ProductType.VOLUME)volume=step.quantity;
                else if(p.referenceVolume!=null && p.referenceVolume.signum()>0){
                    if(p.productType!=ProductType.WEIGHT)volume=p.referenceVolume.multiply(step.quantity);
                    else if(p.referenceWeight!=null && p.referenceWeight.signum()>0)volume=p.referenceVolume.multiply(step.quantity).divide(p.referenceWeight,12,RoundingMode.HALF_UP);
                }
            }
            if(volume==null)incomplete.add(zone);else volumes.merge(zone,volume,BigDecimal::add);
        }
        List<Requirement> result=new ArrayList<>();
        volumes.forEach((zone,volume)->{
            BigDecimal capacity=totes.stream().filter(t->t.isUsageAllowed(zone) && t.volume!=null && t.volume.signum()>0).map(t->t.volume).max(BigDecimal::compareTo).orElse(null);
            boolean complete=capacity!=null && !incomplete.contains(zone);
            Integer count=complete?Math.max(1,volume.divide(capacity,0,RoundingMode.CEILING).intValueExact()):null;
            result.add(new Requirement(zone.name(),zone==TemperatureZone.CHILLED || zone==TemperatureZone.FROZEN?"INSULATED":"STANDARD",count,capacity,complete));
        });
        return result;
    }
}
