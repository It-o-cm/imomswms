package com.intermarche.backoffice.engine.stock;
import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.wms.handling.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import org.hibernate.Hibernate;
import java.math.*;
import java.time.LocalDateTime;
import java.util.*;

@ApplicationScoped @Transactional
public class StockProtectionService {
    @Inject TemporalStockService stocks;
    @Inject EntityManager em;
    public static Buffer target(Store store,JsonNode rule){
        var building=com.intermarche.backoffice.engine.replenishment.AutomaticReplenishmentService.ownedBuilding(store,rule.path("buildingCode").asText(),false);
        Buffer buffer=Buffer.find("bufferCode = ?1 and building = ?2",rule.path("targetBufferCode").asText(),building).firstResult();
        if(buffer==null || !buffer.active)throw new IllegalArgumentException("Choisissez une zone de bacs active dans le bâtiment du magasin");return buffer;
    }
    private record Risk(BigDecimal desired,LocalDateTime critical){}
    private Risk risk(StockAccount account,Building building,int horizon){
        account=Hibernate.unproxy(account,StockAccount.class);var now=DateTimeProvider.now();var timeline=stocks.availability(account.id,building.id,now);
        var critical=timeline.periods.stream().filter(p -> horizon==0 || !p.from.isAfter(now.plusMinutes(horizon))).filter(p -> p.committed.signum()>0 && p.balance.signum()<=0).findFirst();
        return new Risk(critical.isPresent()?timeline.physical.subtract(account.safetyMargin).max(BigDecimal.ZERO).min(critical.get().committed):BigDecimal.ZERO,critical.map(p -> p.from).orElse(null));
    }
    private BigDecimal balance(StockAccount account,BaseLocation location){return StockMovement.<StockMovement>list("account = ?1 and location = ?2 and effectiveAt <= ?3",account,location,DateTimeProvider.now()).stream().map(m -> m.quantity).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private BigDecimal protectedQuantity(StockAccount account,Building building){return building.locations.stream().map(l -> Hibernate.unproxy(l,ShelfLocation.class)).filter(l -> l instanceof PickingToteLocation && l.product!=null && Hibernate.unproxy(l.product,Product.class).id.equals(Hibernate.unproxy(account.product,Product.class).id)).map(l -> balance(account,l)).reduce(BigDecimal.ZERO,BigDecimal::add);}
    public List<StockProtectionOrder> evaluate(Store proxy,JsonNode rule){
        if(!rule.path("enabled").asBoolean())return List.of();
        Store store=Hibernate.unproxy(proxy,Store.class);em.lock(store,LockModeType.PESSIMISTIC_WRITE);
        Buffer buffer=target(store,rule);Building building=Hibernate.unproxy(buffer.building,Building.class);
        String key;try{key=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((rule.path("id").asText(rule.path("name").asText("StockProtection"))+":"+building.id+":"+buffer.id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
        Set<String> eans=new HashSet<>();rule.path("eans").forEach(n -> eans.add(n.asText()));
        List<StockProtectionOrder> created=new ArrayList<>();StockProtectionOrder current=null;
        for(StockAccount account:StockAccount.<StockAccount>list("store = ?1 order by id",store)){
            Product product=Hibernate.unproxy(account.product,Product.class);if(!eans.isEmpty() && !eans.contains(product.ean))continue;
            Risk risk=risk(account,building,rule.path("horizonMinutes").asInt());
            BigDecimal desired=risk.desired.subtract(protectedQuantity(account,building)).max(BigDecimal.ZERO);
            List<StockProtectionOrder> active=StockProtectionOrder.list("store = ?1 and sourceBuilding = ?2 and status in ?3",store,building,List.of(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED,BaseHandlingOrder.HandlingOrderStatus.PROCESSING));
            // Reconcile unstarted withdrawals if an order is cancelled or a delivery improves availability.
            for(StockProtectionOrder open:active)for(ExpectedSupply supply:open.supplies){
                if(!Hibernate.unproxy(supply.account,StockAccount.class).id.equals(account.id) || supply.status!=ExpectedSupply.SupplyStatus.EXPECTED)continue;
                BigDecimal remaining=supply.quantity.subtract(supply.received);
                if(desired.compareTo(remaining)<0 && Objects.equals(open.protectionRuleKey,key) && open.status==BaseHandlingOrder.HandlingOrderStatus.SCHEDULED){
                    stocks.reviseSupply(supply.id,supply.received.add(desired),supply.availableAt,desired.signum()==0);remaining=desired;
                }
                desired=desired.subtract(remaining).max(BigDecimal.ZERO);
            }
            for(StockProtectionOrder open:active)if(open.supplies.stream().noneMatch(s -> s.status==ExpectedSupply.SupplyStatus.EXPECTED))open.status=open.supplies.stream().anyMatch(s -> s.received.signum()>0)?BaseHandlingOrder.HandlingOrderStatus.DONE:BaseHandlingOrder.HandlingOrderStatus.CANCELLED;
            if(desired.signum()==0)continue;
            var sources=building.locations.stream().map(l -> Hibernate.unproxy(l,ShelfLocation.class)).filter(l -> l instanceof StoreShelfLocation && l.product!=null && Hibernate.unproxy(l.product,Product.class).id.equals(product.id)).sorted(Comparator.comparing(l -> l.locationCode)).toList();
            for(ShelfLocation source:sources){
                BigDecimal quantity=balance(account,source).min(desired);if(quantity.signum()<=0)continue;
                // Leave the configured safety margin on the shelves; never invent physical stock.
                if(current==null || current.supplies.size()>=rule.path("maxLinesPerOrder").asInt()){
                    current=new StockProtectionOrder();current.store=store;current.sourceBuilding=building;current.destinationBuilding=building;current.protectionBuffer=buffer;current.protectionRuleKey=key;
                    current.protectionHorizonMinutes=rule.path("horizonMinutes").asInt();current.operation=ReplenishmentOrder.Operation.STOCK_PROTECTION;current.code=HandlingOrderSequence.getNextCode(store,"SEC");current.status=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
                    current.requestedStart=DateTimeProvider.now();current.requestedEnd=current.requestedStart.plusMinutes(rule.path("interventionMinutes").asInt());
                    current.generatedByRule=rule.path("name").asText("Mise en sécurité des stocks");current.generationReason="URGENT : disponibilité future compromise. Retirer les produits des rayons vers des bacs de picking en laissant la marge de sécurité. Échéance critique : "+risk.critical;
                    current.persistAndFlush();created.add(current);
                }
                PickingToteLocation pending=pendingLocation(building,product);
                current.supplies.add(stocks.planSupply(account.id,current.code+":"+source.id,source.id,pending.id,quantity,current.requestedEnd));desired=desired.subtract(quantity);if(desired.signum()==0)break;
            }
        }
        em.flush();return created;
    }
    private PickingToteLocation pendingLocation(Building building,Product product){
        var found=building.locations.stream().map(l -> Hibernate.unproxy(l,ShelfLocation.class)).filter(l -> l instanceof PickingToteLocation p && p.protectedTote==null && l.product!=null && Hibernate.unproxy(l.product,Product.class).id.equals(product.id)).findFirst();
        if(found.isPresent())return (PickingToteLocation)found.get();
        PickingToteLocation place=new PickingToteLocation();place.product=product;place.area="SECURED";place.aisle="SEC";place.unit="PENDING";place.elevation="1";place.position="1";place.locationCode="SEC-"+building.id+"-"+product.ean;place.persistAndFlush();building.locations.add(place);return place;
    }
    /** Même calcul de risque que la génération : réduire un reliquat, sans annuler un retrait confirmé. */
    public void reconcile(StockProtectionOrder order){
        Store store=Hibernate.unproxy(order.store,Store.class);em.lock(store,LockModeType.PESSIMISTIC_WRITE);
        Building building=Hibernate.unproxy(order.sourceBuilding,Building.class);
        var accounts=new HashSet<Long>();
        for(var supply:order.supplies)if(supply.status==ExpectedSupply.SupplyStatus.EXPECTED && accounts.add(Hibernate.unproxy(supply.account,StockAccount.class).id)){
            StockAccount account=Hibernate.unproxy(supply.account,StockAccount.class);
            BigDecimal desired=risk(account,building,order.protectionHorizonMinutes).desired.subtract(protectedQuantity(account,building)).max(BigDecimal.ZERO);
            for(StockProtectionOrder other:StockProtectionOrder.<StockProtectionOrder>list("store=?1 and sourceBuilding=?2 and status in ?3",store,building,List.of(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED,BaseHandlingOrder.HandlingOrderStatus.PROCESSING)))if(!other.id.equals(order.id))
                for(var line:other.supplies)if(line.status==ExpectedSupply.SupplyStatus.EXPECTED && Hibernate.unproxy(line.account,StockAccount.class).id.equals(account.id))desired=desired.subtract(line.quantity.subtract(line.received)).max(BigDecimal.ZERO);
            for(var line:order.supplies)if(line.status==ExpectedSupply.SupplyStatus.EXPECTED && Hibernate.unproxy(line.account,StockAccount.class).id.equals(account.id)){
                BigDecimal remaining=line.quantity.subtract(line.received),retained=remaining.min(desired);desired=desired.subtract(retained);
                if(retained.compareTo(remaining)<0)stocks.reviseSupply(line.id,line.received.add(retained),line.availableAt,retained.signum()==0 && line.received.signum()==0);
            }
        }
        if(order.supplies.stream().noneMatch(s->s.status==ExpectedSupply.SupplyStatus.EXPECTED)){
            order.status=order.supplies.stream().anyMatch(s->s.received.signum()>0)?BaseHandlingOrder.HandlingOrderStatus.DONE:BaseHandlingOrder.HandlingOrderStatus.CANCELLED;
            for(var execution:com.intermarche.backoffice.domain.handling.HandlingExecution.<com.intermarche.backoffice.domain.handling.HandlingExecution>list("handlingOrder",order))execution.status=order.status==BaseHandlingOrder.HandlingOrderStatus.DONE?com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.DONE:com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.CANCELLED;
        }
    }
    private BigDecimal liters(Product p,BigDecimal qty){
        if(p.productType==ProductType.VOLUME)return qty;
        if(p.referenceVolume==null || p.referenceVolume.signum()<=0)throw new IllegalArgumentException("Renseignez le volume du produit avant sa mise en bac");
        if(p.productType==ProductType.WEIGHT){if(p.referenceWeight==null || p.referenceWeight.signum()<=0)throw new IllegalArgumentException("Poids de référence manquant pour calculer la capacité du bac");return qty.multiply(p.referenceVolume).divide(p.referenceWeight,12,RoundingMode.HALF_UP);}
        return qty.multiply(p.referenceVolume);
    }
    public List<Map<String,Object>> totes(StockProtectionOrder order){
        List<Map<String,Object>> result=new ArrayList<>();
        for(Tote tote:Tote.<Tote>list("store = ?1 order by code",order.store)){
            LoadedTote load=LoadedTote.find("tote = ?1 and status = ?2",tote,LoadedTote.Status.IN_USE).firstResult();
            if(load!=null && PickingToteLocation.count("protectedTote=?1 and traceOnly=false",load)==0)continue;
            if(load!=null && (load.buffer==null || !Hibernate.unproxy(load.buffer,Buffer.class).id.equals(Hibernate.unproxy(order.protectionBuffer,Buffer.class).id)))continue;
            BigDecimal used=load==null?BigDecimal.ZERO:usedLiters(load);
            Map<String,Object> item=new LinkedHashMap<>();item.put("code",tote.code);item.put("type",tote.toteType);item.put("freeLiters",tote.volume.subtract(used).max(BigDecimal.ZERO));item.put("temperatureZone",load==null?null:load.storageType);final LoadedTote existing=load;item.put("allowedZones",Arrays.stream(TemperatureZone.values()).filter(zone -> tote.isUsageAllowed(zone) && (existing==null || existing.storageType==zone)).map(Enum::name).toList());result.add(item);
        }
        return result;
    }
    private BigDecimal usedLiters(LoadedTote load){
        BigDecimal used=BigDecimal.ZERO;
        for(PickingToteLocation place:PickingToteLocation.<PickingToteLocation>list("protectedTote=?1 and traceOnly=false",load)){
            Product product=Hibernate.unproxy(place.product,Product.class);StockAccount account=StockAccount.find("store = ?1 and product = ?2",Hibernate.unproxy(load.tote,Tote.class).store,product).firstResult();
            if(account!=null)used=used.add(liters(product,balance(account,place)));
        }return used;
    }
    public void confirm(StockProtectionOrder order,ExpectedSupply supply,String event,BigDecimal qty,String toteCode){
        StockProtectionPlacement previous=StockProtectionPlacement.find("supply = ?1 and eventKey = ?2",supply,event).firstResult();
        if(previous!=null){if(qty.compareTo(previous.quantity)!=0 || !Hibernate.unproxy(Hibernate.unproxy(Hibernate.unproxy(previous.destination,PickingToteLocation.class).protectedTote,LoadedTote.class).tote,Tote.class).code.equals(toteCode))throw new IllegalArgumentException("Cette confirmation a déjà été utilisée avec une autre quantité ou un autre bac");return;}
        if(supply.status!=ExpectedSupply.SupplyStatus.EXPECTED || supply.received.add(qty).compareTo(supply.quantity)>0)throw new IllegalArgumentException("Quantité supérieure au reste à mettre en sécurité");
        Product product=Hibernate.unproxy(supply.account.product,Product.class);if(product.productType==ProductType.UNIT && qty.stripTrailingZeros().scale()>0)throw new IllegalArgumentException("La quantité doit être entière pour un produit à la pièce");Building building=Hibernate.unproxy(order.sourceBuilding,Building.class);
        var current=risk(supply.account,building,order.protectionHorizonMinutes);BigDecimal stillNeeded=current.desired.subtract(protectedQuantity(supply.account,building)).max(BigDecimal.ZERO);
        if(qty.compareTo(stillNeeded)>0)throw new IllegalArgumentException("Le besoin de protection a diminué : il reste "+stillNeeded.stripTrailingZeros().toPlainString()+" à sécuriser. Actualisez l’ordre.");
        Tote tote=Tote.findByCode(toteCode);if(tote==null || !Hibernate.unproxy(tote.store,Store.class).id.equals(Hibernate.unproxy(order.store,Store.class).id))throw new IllegalArgumentException("Choisissez un bac du magasin");
        if(!tote.isUsageAllowed(product.temperatureZone))throw new IllegalArgumentException("Bac incompatible avec la température du produit");
        LoadedTote load=LoadedTote.find("tote = ?1 and status = ?2",tote,LoadedTote.Status.IN_USE).firstResult();
        if(load!=null && (PickingToteLocation.count("protectedTote=?1 and traceOnly=false",load)==0 || load.storageType!=product.temperatureZone || load.buffer==null || !Hibernate.unproxy(load.buffer,Buffer.class).id.equals(Hibernate.unproxy(order.protectionBuffer,Buffer.class).id)))throw new IllegalArgumentException("Bac déjà utilisé pour une autre opération ou température");
        if(liters(product,qty).add(load==null?BigDecimal.ZERO:usedLiters(load)).compareTo(tote.volume)>0)throw new IllegalArgumentException("Le bac n’a pas assez de place : réduisez la quantité ou choisissez un autre bac");
        if(load==null){load=LoadedTote.useAnEmptyTote(tote);load.storageType=product.temperatureZone;load.buffer=order.protectionBuffer;load.persistAndFlush();}
        PickingToteLocation destination=PickingToteLocation.find("protectedTote = ?1 and product = ?2",load,product).firstResult();
        if(destination==null){destination=new PickingToteLocation();destination.product=product;destination.protectedTote=load;destination.area="SECURED";destination.aisle="SEC";destination.unit=tote.code.substring(0,Math.min(10,tote.code.length()));destination.elevation="1";destination.position="1";destination.locationCode="BAC-"+tote.code+"-"+product.ean;destination.persistAndFlush();building.locations.add(destination);em.flush();}
        stocks.moveToProtection(Hibernate.unproxy(supply.account,StockAccount.class).id,Hibernate.unproxy(supply.source,BaseLocation.class).id,destination.id,"secure:"+event+":"+supply.id,qty,DateTimeProvider.now());
        supply.received=supply.received.add(qty);if(supply.received.compareTo(supply.quantity)==0)supply.status=ExpectedSupply.SupplyStatus.RECEIVED;
        StockProtectionPlacement placement=new StockProtectionPlacement();placement.supply=supply;placement.destination=destination;placement.eventKey=event;placement.quantity=qty;placement.persist();
    }
}
