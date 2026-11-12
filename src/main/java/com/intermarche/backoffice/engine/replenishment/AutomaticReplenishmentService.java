package com.intermarche.backoffice.engine.replenishment;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder;
import com.intermarche.backoffice.engine.executing.*;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import org.hibernate.Hibernate;
import java.math.*;
import java.util.*;

/** Consolidates projected shelf shortages into bounded, bay-ordered warehouse-to-store tours. */
@ApplicationScoped
public class AutomaticReplenishmentService {
    @Inject TemporalStockService stocks;
    @Inject StorePickingRoutePlanner planner;
    @Inject EntityManager em;
    private record Need(StockAccount account,Product product,ShelfLocation source,ShelfLocation destination,BigDecimal quantity,BigDecimal weight,BigDecimal volume){}
    public static Building ownedBuilding(Store store,String code,boolean warehouse) {
        Building building=Building.<Building>list("select b from Store s join s.buildings b where s.id = ?1 and b.buildingCode = ?2",store.id,code).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Bâtiment hors du magasin : "+code));
        building=Hibernate.unproxy(building,Building.class);
        if(warehouse?!(building instanceof WarehouseBuilding):!(building instanceof StoreBuilding)) throw new IllegalArgumentException(warehouse?"Le départ doit être un entrepôt":"La destination doit être un magasin");
        return building;
    }
    @Transactional
    public List<ReplenishmentOrder> evaluate(Store proxy,JsonNode rule) {
        if(!rule.path("enabled").asBoolean(true)) return List.of();
        Store store=Hibernate.unproxy(proxy,Store.class);em.lock(store,LockModeType.PESSIMISTIC_WRITE);
        Building source=ownedBuilding(store,rule.path("sourceBuildingCode").asText(),true),destination=ownedBuilding(store,rule.path("destinationBuildingCode").asText(),false);
        var now=DateTimeProvider.now();var completion=now.plusMinutes(rule.path("completionMinutes").asInt());var horizon=now.plusMinutes(Math.max(rule.path("horizonMinutes").asInt(),rule.path("completionMinutes").asInt()));
        BigDecimal minimum=rule.path("minimumStock").decimalValue(),target=rule.path("targetStock").decimalValue(),maxWeight=rule.path("maxWeightKg").decimalValue();
        Set<String> eans=new HashSet<>();rule.path("eans").forEach(n -> eans.add(n.asText()));
        List<Need> needs=new ArrayList<>();
        for(StockAccount account:StockAccount.<StockAccount>list("store = ?1 order by id",store)) {
            Product product=Hibernate.unproxy(account.product,Product.class);
            if(!product.active) continue;
            if(!eans.isEmpty() && !eans.contains(product.ean)) continue;
            List<ShelfLocation> destinations=locations(destination,product.id),sources=locations(source,product.id);
            if(destinations.isEmpty() || sources.isEmpty()) continue;
            // Any open physical job for this product/destination already owns the need, including overdue jobs.
            long pending=ReplenishmentOrder.count("select count(o) from ReplenishmentOrder o join o.supplies s where o.store = ?1 and o.destinationBuilding = ?2 and s.account = ?3 and s.status = ?4 and o.status not in ?5",store,destination,account,ExpectedSupply.SupplyStatus.EXPECTED,List.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED,BaseHandlingOrder.HandlingOrderStatus.FAILED));
            if(pending>0) continue;
            var projection=stocks.availability(account.id,destination.id,now);
            BigDecimal low=projection.periods.stream().filter(p -> !p.from.isAfter(horizon) && (p.until==null || p.until.isAfter(completion))).map(p -> p.balance).min(BigDecimal::compareTo).orElse(projection.physical);
            if(low.compareTo(minimum)>0) continue;
            BigDecimal wanted=target.subtract(low).max(BigDecimal.ZERO);
            BigDecimal available=stocks.availability(account.id,source.id,now).periods.get(0).availableToPromise;
            Map<Long,BigDecimal> physical=new HashMap<>();stocks.positions(account.id).forEach(p -> physical.put(p.locationId,p.quantity));
            Map<Long,BigDecimal> free=new HashMap<>();
            for(ShelfLocation loc:sources) {
                BigDecimal reserved=ExpectedSupply.<ExpectedSupply>list("account = ?1 and source = ?2 and status = ?3",account,loc,ExpectedSupply.SupplyStatus.EXPECTED).stream().map(s -> s.quantity.subtract(s.received)).reduce(BigDecimal.ZERO,BigDecimal::add);
                free.put(loc.id,physical.getOrDefault(loc.id,BigDecimal.ZERO).subtract(reserved).max(BigDecimal.ZERO));
            }
            BigDecimal total=free.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add).min(available);
            if(!rule.path("allowPartial").asBoolean() && total.compareTo(wanted)<0) continue;
            BigDecimal remaining=wanted.min(total);
            BigDecimal weight=product.productType==ProductType.WEIGHT?BigDecimal.ONE:product.referenceWeight;
            // A bounded load cannot include an unknown mass.
            BigDecimal volume=product.productType==ProductType.VOLUME?BigDecimal.ONE:product.referenceVolume;
            if(weight==null || weight.signum()<=0 || volume==null || volume.signum()<=0) continue;
            if(product.productType==ProductType.VOLUME) {if(product.referenceVolume==null || product.referenceVolume.signum()<=0) continue;weight=weight.divide(product.referenceVolume,12,RoundingMode.HALF_UP); }
            if(product.productType==ProductType.WEIGHT) {if(product.referenceWeight==null || product.referenceWeight.signum()<=0) continue;volume=volume.divide(product.referenceWeight,12,RoundingMode.HALF_UP); }
            destinations.sort(Comparator.comparing((ShelfLocation l) -> physical.getOrDefault(l.id,BigDecimal.ZERO)).thenComparing(l -> l.id));
            sources.sort(Comparator.comparing((ShelfLocation l) -> free.get(l.id)).reversed().thenComparing(l -> l.id));
            for(ShelfLocation loc:sources) {
                BigDecimal quantity=remaining.min(free.get(loc.id));
                if(product.productType==ProductType.UNIT) quantity=quantity.setScale(0,RoundingMode.DOWN);
                else quantity=quantity.setScale(6,RoundingMode.DOWN);
                if(quantity.signum()>0) {needs.add(new Need(account,product,loc,destinations.get(0),quantity,weight,volume));remaining=remaining.subtract(quantity);}
            }
        }
        // Use the same Java graph optimizer as the execution screen to cluster nearby bays.
        List<PickingPlanService.RouteStepDTO> steps=new ArrayList<>();Map<Long,Need> byLocation=new HashMap<>();
        for(Need need:needs) {
            var step=new PickingPlanService.RouteStepDTO();step.pickedItemId=need.source.id;step.aisle=need.source.aisle;step.unit=need.source.unit;step.area=need.source.area;step.sourceOrderId=0L;
            steps.add(step);byLocation.put(step.pickedItemId,need);
        }
        var planned=planner.plan(source,steps,null);
        if(planned==null || planned.geometry().unlocated>0 || planned.geometry().disconnected>0) return List.of();
        List<Need> ordered=planned.route().stream().flatMap(g -> g.steps.stream()).map(s -> byLocation.get(s.pickedItemId)).toList();
        int maxLines=rule.path("maxLines").asInt(),maxOrders=rule.path("maxOrdersPerCycle").asInt();
        List<ReplenishmentOrder> orders=new ArrayList<>();List<Need> batch=new ArrayList<>();BigDecimal batchWeight=BigDecimal.ZERO,batchVolume=BigDecimal.ZERO;
        for(Need need:ordered) {
            BigDecimal remaining=need.quantity;
            while(remaining.signum()>0 && orders.size()<maxOrders) {
                // Food and non-food never share a tour; the rule only decides whether food temperatures are split as well.
                if(!batch.isEmpty() && (rule.path("separateTemperatureZones").asBoolean(true) && batch.get(0).product.temperatureZone!=need.product.temperatureZone
                        || TemperatureZone.isFood(batch.get(0).product.temperatureZone)!=TemperatureZone.isFood(need.product.temperatureZone))) {
                    flushBatch(store,source,destination,rule,batch,batchWeight,orders);batch=new ArrayList<>();batchWeight=BigDecimal.ZERO;batchVolume=BigDecimal.ZERO;continue;
                }
                BigDecimal capacity=maxWeight.subtract(batchWeight).divide(need.weight,need.product.productType==ProductType.UNIT?0:6,RoundingMode.DOWN);
                capacity=capacity.min(rule.path("maxVolumeLiters").decimalValue().subtract(batchVolume).divide(need.volume,need.product.productType==ProductType.UNIT?0:6,RoundingMode.DOWN));
                BigDecimal quantity=remaining.min(capacity);
                if(batch.size()>=maxLines || quantity.signum()==0) {
                    if(batch.isEmpty()) break; // One indivisible unit is heavier than the configured load.
                    flushBatch(store,source,destination,rule,batch,batchWeight,orders);batch=new ArrayList<>();batchWeight=BigDecimal.ZERO;batchVolume=BigDecimal.ZERO;
                    continue;
                }
                batch.add(new Need(need.account,need.product,need.source,need.destination,quantity,need.weight,need.volume));batchWeight=batchWeight.add(quantity.multiply(need.weight));batchVolume=batchVolume.add(quantity.multiply(need.volume));remaining=remaining.subtract(quantity);
                if(remaining.signum()>0) {flushBatch(store,source,destination,rule,batch,batchWeight,orders);batch=new ArrayList<>();batchWeight=BigDecimal.ZERO;batchVolume=BigDecimal.ZERO;}
            }
        }
        if(!batch.isEmpty() && orders.size()<maxOrders) flushBatch(store,source,destination,rule,batch,batchWeight,orders);
        return orders;
    }
    private void flushBatch(Store store,Building source,Building destination,JsonNode rule,List<Need> batch,BigDecimal weight,List<ReplenishmentOrder> orders) {
        if(weight.compareTo(rule.path("minimumBatchWeightKg").decimalValue())<0) return;
        ReplenishmentOrder order=new ReplenishmentOrder();order.store=store;order.code=HandlingOrderSequence.getNextCode(store,"REA");order.operation=ReplenishmentOrder.Operation.SHELF_REPLENISHMENT;
        String ruleName=rule.path("name").asText("Réachalandage automatique");order.generatedByRule=ruleName.substring(0,Math.min(200,ruleName.length()));
        order.generationReason="Solde projeté net ≤ "+rule.path("minimumStock").asText()+" ; cible "+rule.path("targetStock").asText()+" ; anticipation "+rule.path("horizonMinutes").asText()+" min. Charge prévue : "+weight.stripTrailingZeros().toPlainString()+" kg. Arrivées et réservations prises en compte ; regroupement par baie.";
        order.sourceBuilding=source;order.destinationBuilding=destination;order.status=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
        order.requestedStart=DateTimeProvider.now();order.requestedEnd=order.requestedStart.plusMinutes(rule.path("completionMinutes").asInt());order.persistAndFlush();
        int i=0;for(Need need:batch) order.supplies.add(stocks.planSupply(need.account.id,order.code+":"+(++i),need.source.id,need.destination.id,need.quantity,order.requestedEnd));
        orders.add(order);em.flush();
    }
    private List<ShelfLocation> locations(Building building,Long productId) {
        return new ArrayList<>(building.locations.stream().map(l -> Hibernate.unproxy(l,ShelfLocation.class)).filter(l -> !(l instanceof PickingToteLocation) && l.product!=null && Hibernate.unproxy(l.product,Product.class).id.equals(productId)).toList());
    }
}
